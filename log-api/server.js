import http from "node:http";
import { timingSafeEqual } from "node:crypto";
import { SkyWayAuthToken, nowInSec, uuidV4 } from "@skyway-sdk/token";

const PORT = Number(process.env.PORT || 10000);
const AXIOM_TOKEN = process.env.AXIOM_API_TOKEN || "";
const SKYWAY_APP_ID = process.env.SKYWAY_APP_ID || "";
const SKYWAY_SECRET_KEY = process.env.SKYWAY_SECRET_KEY || "";
const YOUTUBE_API_KEY = process.env.YOUTUBE_API_KEY || "";
const SERVER_CONTROL_PASSWORD = process.env.SERVER_CONTROL_PASSWORD || "";
const AXIOM_DATASET = process.env.AXIOM_DATASET || "bedrock-voicechat-logs";
const AXIOM_DOMAIN = process.env.AXIOM_DOMAIN || "us-east-1.aws.edge.axiom.co";
const ALLOWED_ORIGINS = (process.env.ALLOWED_ORIGINS || "https://truc5738.github.io").split(",").map(v => v.trim()).filter(Boolean);
const MAX_BODY = 64 * 1024;
const MAX_EVENTS = 30;
const sessions = new Map();
const counters = new Map();

function json(res, status, body, origin) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    ...(origin ? { "Access-Control-Allow-Origin": origin, "Vary": "Origin" } : {})
  });
  res.end(JSON.stringify(body));
}

function cors(req, res) {
  const origin = req.headers.origin || "";
  if (origin && ALLOWED_ORIGINS.includes(origin)) {
    res.setHeader("Access-Control-Allow-Origin", origin);
    res.setHeader("Vary", "Origin");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  }
  return origin && ALLOWED_ORIGINS.includes(origin) ? origin : "";
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", chunk => {
      body += chunk;
      if (Buffer.byteLength(body) > MAX_BODY) {
        reject(Object.assign(new Error("body_too_large"), { status: 413 }));
        req.destroy();
      }
    });
    req.on("end", () => {
      try { resolve(JSON.parse(body || "{}")); }
      catch { reject(Object.assign(new Error("invalid_json"), { status: 400 })); }
    });
    req.on("error", reject);
  });
}

function rateLimited(req) {
  const key = (req.headers["x-forwarded-for"] || req.socket.remoteAddress || "unknown").toString().split(",")[0].trim();
  const now = Date.now();
  let entry = counters.get(key);
  if (!entry || now - entry.start > 60_000) entry = { start: now, count: 0 };
  entry.count++;
  counters.set(key, entry);
  if (counters.size > 2000) {
    for (const [k, v] of counters) if (now - v.start > 120_000) counters.delete(k);
  }
  return entry.count > 90;
}

async function sendToAxiom(events) {
  if (!AXIOM_TOKEN) return { skipped: true, reason: "AXIOM_API_TOKEN_not_configured" };
  const url = `https://${AXIOM_DOMAIN}/v1/ingest/${encodeURIComponent(AXIOM_DATASET)}`;
  const response = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Bearer ${AXIOM_TOKEN}`, "Content-Type": "application/json" },
    body: JSON.stringify(events)
  });
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 300);
    throw new Error(`Axiom ingest failed (${response.status}): ${detail}`);
  }
  return { sent: events.length };
}

const server = http.createServer(async (req, res) => {
  const origin = cors(req, res);
  if (req.method === "OPTIONS") {
    res.writeHead(origin ? 204 : 403);
    return res.end();
  }
  const path = new URL(req.url || "/", "http://localhost").pathname;
  if (req.method === "GET" && path === "/health") return json(res, 200, { ok: true, service: "bedrock-voicechat-log-api", axiomConfigured: Boolean(AXIOM_TOKEN), skywayConfigured: Boolean(SKYWAY_APP_ID && SKYWAY_SECRET_KEY), timestamp: new Date().toISOString() }, origin);
  if (req.method === "GET" && path === "/music/search") {
    if (!origin) return json(res, 403, { error: "origin_not_allowed" });
    if (rateLimited(req)) return json(res, 429, { error: "rate_limited" }, origin);
    if (!YOUTUBE_API_KEY) return json(res, 503, { error: "youtube_not_configured", message: "YOUTUBE_API_KEY chưa được cấu hình trên Render." }, origin);
    const query = (new URL(req.url || "/", "http://localhost").searchParams.get("q") || "").trim().slice(0, 120);
    if (query.length < 1) return json(res, 400, { error: "query_required" }, origin);
    try {
      const apiUrl = new URL("https://www.googleapis.com/youtube/v3/search");
      apiUrl.search = new URLSearchParams({ part: "snippet", type: "video", videoEmbeddable: "true", maxResults: "10", safeSearch: "moderate", q: query, key: YOUTUBE_API_KEY }).toString();
      const response = await fetch(apiUrl, { signal: AbortSignal.timeout(10000) });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        console.error("YouTube search failed:", response.status, data?.error?.status || "api_error");
        return json(res, response.status === 403 ? 502 : 502, { error: "youtube_api_failed", message: "YouTube API từ chối yêu cầu. Kiểm tra API key, quota và API đã bật." }, origin);
      }
      const items = (Array.isArray(data.items) ? data.items : []).map(item => ({
        videoId: item.id?.videoId || "",
        title: item.snippet?.title || "Không có tiêu đề",
        channel: item.snippet?.channelTitle || "",
        publishedAt: item.snippet?.publishedAt || "",
        thumbnail: item.snippet?.thumbnails?.medium?.url || item.snippet?.thumbnails?.default?.url || ""
      })).filter(item => item.videoId);
      return json(res, 200, { ok: true, query, results: items }, origin);
    } catch (error) {
      console.error("YouTube search error:", error?.name || "Error");
      return json(res, 502, { error: "youtube_unavailable", message: "Không kết nối được YouTube API. Thử lại sau." }, origin);
    }
  }
  if (req.method === "GET" && path === "/skyway-token") {
    if (!origin || !SKYWAY_APP_ID || !SKYWAY_SECRET_KEY) return json(res, 503, { error: "skyway_not_configured" }, origin);
    if (rateLimited(req)) return json(res, 429, { error: "rate_limited" }, origin);
    try {
      const issuedAt = nowInSec();
      const authToken = new SkyWayAuthToken({
        jti: uuidV4(),
        iat: issuedAt,
        exp: issuedAt + 60 * 60,
        version: 3,
        scope: {
          appId: SKYWAY_APP_ID,
          turn: { enabled: true },
          analytics: { enabled: true },
          rooms: [{
            id: "*",
            name: "*",
            methods: ["create", "close", "updateMetadata"],
            sfu: { enabled: true, maxSubscribersLimit: 99 },
            member: { id: "*", name: "*", methods: ["publish", "subscribe", "updateMetadata"] }
          }]
        }
      });
      const token = authToken.encode(SKYWAY_SECRET_KEY);
      return json(res, 200, { token, expiresAt: issuedAt + 60 * 60 }, origin);
    } catch (error) {
      console.error("SkyWay token issue error:", error.message);
      return json(res, 500, { error: "token_issue_failed" }, origin);
    }
  }
  if (req.method === "POST" && path === "/control/stop") {
    if (!origin) return json(res, 403, { error: "origin_not_allowed" });
    if (rateLimited(req)) return json(res, 429, { error: "rate_limited" }, origin);
    if (!SERVER_CONTROL_PASSWORD) return json(res, 503, { error: "server_control_not_configured", message: "SERVER_CONTROL_PASSWORD chưa được cấu hình trên Render." }, origin);
    try {
      const payload = await readBody(req);
      const supplied = Buffer.from(typeof payload.password === "string" ? payload.password : "", "utf8");
      const expected = Buffer.from(SERVER_CONTROL_PASSWORD, "utf8");
      const valid = supplied.length === expected.length && timingSafeEqual(supplied, expected);
      supplied.fill(0);
      if (!valid) return json(res, 401, { error: "invalid_password", message: "Mật khẩu không đúng." }, origin);
      return json(res, 200, { ok: true, action: "connection_stop_authorized", message: "Xác thực thành công. Có thể ngắt kết nối của trang này." }, origin);
    } catch (error) {
      return json(res, error.status || 400, { error: error.status === 413 ? "body_too_large" : "bad_request" }, origin);
    }
  }
  if (req.method !== "POST" || path !== "/log") return json(res, 404, { error: "not_found" }, origin);
  if (!origin) return json(res, 403, { error: "origin_not_allowed" });
  if (rateLimited(req)) return json(res, 429, { error: "rate_limited" }, origin);

  try {
    const payload = await readBody(req);
    const sessionId = typeof payload.sessionId === "string" ? payload.sessionId.slice(0, 80) : "unknown";
    const kind = typeof payload.kind === "string" ? payload.kind.slice(0, 40) : "log";
    if (kind === "heartbeat") {
      sessions.set(sessionId, Date.now());
      return json(res, 202, { ok: true, session: "active" }, origin);
    }
    if (kind === "stop") {
      sessions.delete(sessionId);
      const event = { _time: new Date().toISOString(), app: "bedrock-voicechat-skyway-web", kind: "session_stop", sessionId, message: "Browser session stopped" };
      try { await sendToAxiom([event]); } catch (e) { console.error(e.message); }
      return json(res, 202, { ok: true, session: "stopped" }, origin);
    }
    const incoming = Array.isArray(payload.events) ? payload.events.slice(0, MAX_EVENTS) : [];
    if (!incoming.length) return json(res, 400, { error: "events_required" }, origin);
    const events = incoming.map(item => ({
      _time: new Date().toISOString(),
      app: "bedrock-voicechat-skyway-web",
      sessionId,
      level: ["info", "warn", "error", "status"].includes(item.level) ? item.level : "info",
      message: String(item.message || "log").slice(0, 500),
      detail: String(item.detail || "").slice(0, 500),
      page: String(item.page || "").slice(0, 200),
      userAgent: String(item.userAgent || "").slice(0, 250)
    }));
    sessions.set(sessionId, Date.now());
    try {
      const result = await sendToAxiom(events);
      return json(res, 202, { ok: true, accepted: events.length, ...result }, origin);
    } catch (error) {
      console.error("Axiom ingest error:", error.message);
      return json(res, 502, { error: "log_sink_unavailable" }, origin);
    }
  } catch (error) {
    return json(res, error.status || 400, { error: error.status === 413 ? "body_too_large" : error.message || "bad_request" }, origin);
  }
});

setInterval(() => {
  const cutoff = Date.now() - 90_000;
  for (const [id, lastSeen] of sessions) if (lastSeen < cutoff) sessions.delete(id);
}, 30_000).unref();

server.listen(PORT, "0.0.0.0", () => console.log(`log API listening on ${PORT}; Axiom configured: ${Boolean(AXIOM_TOKEN)}`));
