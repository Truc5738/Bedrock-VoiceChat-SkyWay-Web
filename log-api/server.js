import http from "node:http";

const PORT = Number(process.env.PORT || 10000);
const AXIOM_TOKEN = process.env.AXIOM_API_TOKEN || "";
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
  if (req.method === "GET" && path === "/health") return json(res, 200, { ok: true, service: "bedrock-voicechat-log-api", axiomConfigured: Boolean(AXIOM_TOKEN), timestamp: new Date().toISOString() }, origin);
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
