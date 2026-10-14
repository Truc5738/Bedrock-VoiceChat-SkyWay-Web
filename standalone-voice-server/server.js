const http = require("http");
const crypto = require("crypto");
const WebSocket = require("ws");

const PORT = Number(process.env.PORT || 10000);
const MAX_ROOM_SIZE = 8;
const MAX_FRAME_BYTES = 16 * 1024;
const rooms = new Map();

function roomCode() {
  return String(crypto.randomInt(0, 1000000)).padStart(6, "0");
}
function send(ws, value) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value));
}
function leave(ws) {
  if (!ws.room) return;
  const room = rooms.get(ws.room);
  if (room) {
    room.delete(ws);
    for (const peer of room) send(peer, { type: "peer_left" });
    if (room.size === 0) rooms.delete(ws.room);
  }
  ws.room = null;
}
const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    return res.end(JSON.stringify({ ok: true, service: "minecraft-pe-voice-room" }));
  }
  res.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
  res.end("Minecraft PE Voice Room relay is running.\n");
});
const wss = new WebSocket.Server({ server, maxPayload: MAX_FRAME_BYTES, perMessageDeflate: false });

wss.on("connection", (ws) => {
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });
  ws.on("message", (raw, isBinary) => {
    if (isBinary) {
      if (!ws.room || raw.length > MAX_FRAME_BYTES) return;
      const room = rooms.get(ws.room);
      if (!room) return;
      for (const peer of room) {
        if (peer !== ws && peer.readyState === WebSocket.OPEN) peer.send(raw, { binary: true });
      }
      return;
    }
    let msg;
    try { msg = JSON.parse(raw.toString("utf8")); } catch { return send(ws, { type: "error", message: "JSON không hợp lệ" }); }
    if (!msg || typeof msg.type !== "string") return;
    if (msg.type === "create") {
      leave(ws);
      let code;
      do { code = roomCode(); } while (rooms.has(code));
      rooms.set(code, new Set([ws]));
      ws.room = code;
      return send(ws, { type: "created", code, members: 1 });
    }
    if (msg.type === "join") {
      const code = String(msg.code || "").trim();
      if (!/^\d{6}$/.test(code)) return send(ws, { type: "error", message: "Mã phòng phải có 6 chữ số" });
      const room = rooms.get(code);
      if (!room) return send(ws, { type: "error", message: "Không tìm thấy phòng hoặc phòng đã đóng" });
      if (room.size >= MAX_ROOM_SIZE) return send(ws, { type: "error", message: "Phòng đã đầy" });
      leave(ws);
      room.add(ws);
      ws.room = code;
      for (const peer of room) if (peer !== ws) send(peer, { type: "peer_joined" });
      return send(ws, { type: "joined", code, members: room.size });
    }
    if (msg.type === "leave") {
      leave(ws);
      return send(ws, { type: "left" });
    }
    send(ws, { type: "error", message: "Lệnh không được hỗ trợ" });
  });
  ws.on("close", () => leave(ws));
  ws.on("error", () => leave(ws));
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);
server.listen(PORT, "0.0.0.0", () => console.log("Voice room relay listening on " + PORT));
process.on("SIGTERM", () => { clearInterval(heartbeat); for (const ws of wss.clients) ws.close(); server.close(); });
