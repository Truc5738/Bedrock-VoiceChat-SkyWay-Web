const http = require("http");
const crypto = require("crypto");
const WebSocket = require("ws");

const PORT = Number(process.env.PORT || 10000);
const MAX_ROOM_SIZE = 8;
const MAX_FRAME_BYTES = 16 * 1024;
const rooms = new Map();
function publicRooms() {
  return [...rooms.entries()].map(([id, room]) => ({ id, name: room.name, members: room.size, locked: Boolean(room.passwordHash) }));
}
function send(ws, value) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value));
}
function leave(ws) {
  if (!ws.room) return;
  const roomId = ws.room;
  const room = rooms.get(roomId);
  if (room) {
    room.delete(ws);
    for (const peer of room) send(peer, { type: "peer_left" });
    if (room.size === 0) rooms.delete(roomId);
    else for (const peer of room) send(peer, { type: "rooms", rooms: publicRooms() });
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
      if (room) for (const peer of room) if (peer !== ws && peer.readyState === WebSocket.OPEN) peer.send(raw, { binary: true });
      return;
    }
    let msg;
    try { msg = JSON.parse(raw.toString("utf8")); } catch { return send(ws, { type: "error", message: "JSON không hợp lệ" }); }
    if (!msg || typeof msg.type !== "string") return;
    if (msg.type === "list_rooms") return send(ws, { type: "rooms", rooms: publicRooms() });
    if (msg.type === "create") {
      leave(ws);
      const name = String(msg.name || "Phòng thoại").trim().slice(0, 32) || "Phòng thoại";
      const password = String(msg.password || "").trim();
      if (password.length > 64) return send(ws, { type: "error", message: "Mật khẩu tối đa 64 ký tự" });
      let id;
      do { id = crypto.randomBytes(9).toString("hex"); } while (rooms.has(id));
      const room = new Set([ws]);
      room.name = name;
      room.passwordHash = password ? crypto.createHash("sha256").update(password).digest("hex") : "";
      rooms.set(id, room);
      ws.room = id;
      send(ws, { type: "rooms", rooms: publicRooms() });
      return send(ws, { type: "created", roomId: id, name, members: 1 });
    }
    if (msg.type === "join") {
      const id = String(msg.roomId || "").trim();
      const room = rooms.get(id);
      if (!room) return send(ws, { type: "error", message: "Phòng không còn hoạt động. Hãy làm mới danh sách." });
      const password = String(msg.password || "").trim();
      const suppliedHash = password ? crypto.createHash("sha256").update(password).digest("hex") : "";
      if (room.passwordHash && suppliedHash !== room.passwordHash) return send(ws, { type: "error", message: "Mật khẩu phòng không đúng" });
      if (room.size >= MAX_ROOM_SIZE) return send(ws, { type: "error", message: "Phòng đã đầy" });
      leave(ws);
      room.add(ws);
      ws.room = id;
      for (const peer of room) if (peer !== ws) send(peer, { type: "peer_joined" });
      send(ws, { type: "rooms", rooms: publicRooms() });
      return send(ws, { type: "joined", roomId: id, name: room.name, members: room.size });
    }
    if (msg.type === "leave") { leave(ws); return send(ws, { type: "left" }); }
    send(ws, { type: "error", message: "Lệnh không được hỗ trợ" });
  });
  ws.on("close", () => leave(ws));
  ws.on("error", () => leave(ws));
});
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) { if (!ws.isAlive) { ws.terminate(); continue; } ws.isAlive = false; ws.ping(); }
}, 30000);
server.listen(PORT, "0.0.0.0", () => console.log("Voice room relay listening on " + PORT));
process.on("SIGTERM", () => { clearInterval(heartbeat); for (const ws of wss.clients) ws.close(); server.close(); });
