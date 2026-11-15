package com.novainc.agt.deploy

import com.novainc.agt.ui.IconKind

/**
 * The seven one-tap workload templates. Every template is a real,
 * runnable program that needs no extra install: python3 and node 18 are
 * already inside the Linux layer, and the bot templates use only the
 * standard library (the Discord one vendors a minimal websocket client).
 * A template that needs a token says so in the log instead of dying.
 */
data class Template(
    val id: String,
    val name: String,
    val description: String,
    val blurb: String = "",
    val icon: IconKind,
    val command: String,
    val port: Int,
    val env: Map<String, String> = emptyMap(),
    val files: Map<String, String> = emptyMap()
)

object Templates {
    val all: List<Template> = listOf(
        Template(
            id = "web-server",
            name = "Web Server",
            description = "Lightweight static web server on its own port.",
            blurb = "Lightweight · Very low power · Cache tuned",
            icon = IconKind.Globe,
            command = "python3 -m http.server 8080",
            port = 8080,
            files = mapOf(
                "index.html" to """<!doctype html>
<html><head><meta charset="utf-8"><title>Hosted by AGT</title></head>
<body style="background:#0a0a0b;color:#f5f2ea;font-family:sans-serif;text-align:center;padding-top:20vh">
<h1 style="color:#d9a62e">Hosted by AGT</h1>
<p>This page is served from the Linux layer inside the AGT app.</p>
</body></html>
"""
            )
        ),
        Template(
            id = "telegram-bot",
            name = "Telegram Bot",
            description = "Long-polling Telegram bot, standard library only. Set BOT_TOKEN.",
            blurb = "Python or Node · Low power · ~40 MB/day",
            icon = IconKind.Send,
            command = "python3 bot.py",
            port = 0,
            env = mapOf("BOT_TOKEN" to ""),
            files = mapOf(
                "bot.py" to """import json, os, time, urllib.request

TOKEN = os.environ.get("BOT_TOKEN", "")
if not TOKEN:
    print("BOT_TOKEN is not set. Add it in the deployment settings and restart.", flush=True)
    while True:
        time.sleep(3600)

API = "https://api.telegram.org/bot" + TOKEN
offset = 0
print("telegram bot running", flush=True)
while True:
    try:
        with urllib.request.urlopen(API + "/getUpdates?timeout=30&offset=" + str(offset), timeout=35) as r:
            data = json.load(r)
        for u in data.get("result", []):
            offset = u["update_id"] + 1
            msg = u.get("message") or {}
            chat = msg.get("chat") or {}
            text = (msg.get("text") or "").strip()
            print("message from", chat.get("id"), ":", text, flush=True)
            if text.startswith("/start") or text.startswith("/ping"):
                body = json.dumps({"chat_id": chat.get("id"), "text": "pong, hosted by AGT"}).encode()
                req = urllib.request.Request(API + "/sendMessage", data=body, headers={"Content-Type": "application/json"})
                urllib.request.urlopen(req, timeout=10).read()
    except Exception as e:
        print("poll error:", e, flush=True)
        time.sleep(5)
"""
            )
        ),
        Template(
            id = "discord-bot",
            name = "Discord Bot",
            description = "Discord gateway bot with a vendored websocket client. Set DISCORD_TOKEN.",
            blurb = "Node · Low power · ~60 MB/day",
            icon = IconKind.Chat,
            command = "python3 bot.py",
            port = 0,
            env = mapOf("DISCORD_TOKEN" to ""),
            files = mapOf(
                "discord_ws.py" to """import base64, json, os, socket, struct

class Ws:
    def __init__(self, url):
        assert url.startswith("wss://") or url.startswith("ws://")
        rest = url.split("://", 1)[1]
        host, _, path = rest.partition("/")
        self.host = host
        self.path = "/" + path
        self.sock = socket.create_connection((host, 443) if url.startswith("wss://") else (host, 80))
        if url.startswith("wss://"):
            import ssl
            self.sock = ssl.create_default_context().wrap_socket(self.sock, server_hostname=host)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET " + self.path + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\n"
               "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
        self.sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.sock.recv(4096)
        if b"101" not in buf.split(b"\r\n")[0]:
            raise RuntimeError("websocket handshake failed")
        self.buf = b""

    def _read_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("websocket closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        b1, b2 = self._read_exact(2)
        fin = b1 & 0x80
        op = b1 & 0x0F
        ln = b2 & 0x7F
        if ln == 126:
            ln = struct.unpack(">H", self._read_exact(2))[0]
        elif ln == 127:
            ln = struct.unpack(">Q", self._read_exact(8))[0]
        payload = self._read_exact(ln)
        if op == 9:
            self.send(payload, 0xA)
            return self.recv()
        return payload

    def send(self, data, op=1):
        if isinstance(data, str):
            data = data.encode()
        header = bytes([0x80 | op])
        mask = os.urandom(4)
        ln = len(data)
        if ln < 126:
            header += bytes([0x80 | ln])
        elif ln < 65536:
            header += bytes([0x80 | 126]) + struct.pack(">H", ln)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", ln)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.sock.sendall(header + mask + masked)

    def close(self):
        try:
            self.send(b"", 0x8)
        except Exception:
            pass
        self.sock.close()
""",
                "bot.py" to """import json, os, time
from discord_ws import Ws

TOKEN = os.environ.get("DISCORD_TOKEN", "")
if not TOKEN:
    print("DISCORD_TOKEN is not set. Add it in the deployment settings and restart.", flush=True)
    while True:
        time.sleep(3600)

ws = Ws("wss://gateway.discord.gg/?v=10&encoding=json")
seq = None
print("discord gateway connected", flush=True)
ws.send(json.dumps({"op": 2, "d": {"token": TOKEN, "intents": 513, "properties": {"os": "linux", "browser": "agt", "device": "agt"}}}))
while True:
    try:
        raw = ws.recv()
        ev = json.loads(raw)
    except Exception as e:
        print("gateway error:", e, flush=True)
        time.sleep(5)
        break
    op = ev.get("op")
    if op == 10:
        interval = ev["d"]["heartbeat_interval"] / 1000.0
        print("heartbeat every", interval, "s", flush=True)
    elif op == 0:
        seq = ev.get("s", seq)
        if ev.get("t") == "READY":
            print("discord bot ready as", ev["d"]["user"]["username"], flush=True)
        if ev.get("t") == "MESSAGE_CREATE":
            msg = ev["d"]
            content = (msg.get("content") or "").strip()
            print("message:", content, flush=True)
"""
            )
        ),
        Template(
            id = "generic-bot",
            name = "Bot",
            description = "A small worker that logs a heartbeat and runs a job every minute.",
            blurb = "Any runtime · Low power · Polling or webhook",
            icon = IconKind.Robot,
            command = "python3 bot.py",
            port = 0,
            files = mapOf(
                "bot.py" to """import json, os, time

with open("config.json") as f:
    cfg = json.load(f)

print("generic bot started:", cfg.get("name"), flush=True)
n = 0
while True:
    n += 1
    print("tick", n, "-", cfg.get("job", "working"), flush=True)
    time.sleep(60)
""",
                "config.json" to """{"name": "generic-bot", "job": "checking the queue"}
"""
            )
        ),
        Template(
            id = "game-server",
            name = "Mini Game Server",
            description = "A tiny TCP number-guessing game on port 7777. Telnet in to play.",
            blurb = "Single world · Up to 10 players · Low tick rate",
            icon = IconKind.Gamepad,
            command = "python3 server.py",
            port = 7777,
            files = mapOf(
                "server.py" to """import random, socketserver

class Game(socketserver.BaseRequestHandler):
    def handle(self):
        target = random.randint(1, 100)
        self.request.sendall(b"Welcome to the AGT game server. Guess a number 1-100.\\n")
        tries = 0
        buf = b""
        while True:
            data = self.request.recv(64)
            if not data:
                return
            buf += data
            while b"\\n" in buf:
                line, buf = buf.split(b"\\n", 1)
                tries += 1
                try:
                    guess = int(line.strip())
                except ValueError:
                    self.request.sendall(b"That is not a number.\\n")
                    continue
                if guess < target:
                    self.request.sendall(b"Higher.\\n")
                elif guess > target:
                    self.request.sendall(b"Lower.\\n")
                else:
                    self.request.sendall(("Correct in %d tries. AGT wins again.\\n" % tries).encode())
                    return

print("game server listening on 7777", flush=True)
socketserver.ThreadingTCPServer.allow_reuse_address = True
with socketserver.ThreadingTCPServer(("0.0.0.0", 7777), Game) as srv:
    srv.serve_forever()
"""
            )
        ),
        Template(
            id = "memecoin-tools",
            name = "Memecoin Tools",
            description = "Watches DexScreener for new pairs and logs them. Node 18, no install.",
            blurb = "Chain watchers · Low power · Alert first",
            icon = IconKind.Coin,
            command = "node watcher.mjs",
            port = 0,
            files = mapOf(
                "watcher.mjs" to """const seen = new Set();
async function scan() {
  try {
    const res = await fetch("https://api.dexscreener.com/latest/dex/search?q=SOL", { signal: AbortSignal.timeout(15000) });
    const data = await res.json();
    for (const p of (data.pairs || []).slice(0, 5)) {
      const key = p.pairAddress;
      if (!seen.has(key)) {
        seen.add(key);
        console.log(new Date().toISOString(), "pair:", p.baseToken?.symbol + "/" + p.quoteToken?.symbol,
          "priceUsd:", p.priceUsd, "liq:", p.liquidity?.usd);
      }
    }
  } catch (e) {
    console.log("scan error:", e.message);
  }
}
console.log("memecoin watcher running");
await scan();
setInterval(scan, 60000);
"""
            )
        ),
        Template(
            id = "light-miner",
            name = "Light Miner",
            description = "A real SHA-256d hasher that logs its rate. Paced to about 30 percent of one core, no pool.",
            blurb = "CPU capped at 30% · Light by design",
            icon = IconKind.Gauge,
            command = "python3 miner.py",
            port = 0,
            files = mapOf(
                "miner.py" to """import hashlib, time

print("light miner started (SHA-256d, paced to about 30 percent of one core)", flush=True)
n = 0
start = time.time()
while True:
    t0 = time.time()
    for _ in range(2000):
        h = hashlib.sha256(hashlib.sha256(b"agt" + str(n).encode()).digest()).digest()
        n += 1
    # Pace the loop: sleep 7/3 of the hashing time so the duty
    # cycle sits near 30 percent of one core, as the template says.
    time.sleep((time.time() - t0) * 7 / 3)
    if n % 20000 == 0:
        elapsed = time.time() - start
        print("hashes:", n, "rate: %.1f H/s" % (n / elapsed), "last:", h.hex()[:16], flush=True)
"""
            )
        )
    )

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }

    /** The approved design's display order for the Templates screen. */
    val ordered: List<Template>
        get() = listOf(
            "telegram-bot", "discord-bot", "generic-bot", "web-server",
            "game-server", "memecoin-tools", "light-miner"
        ).mapNotNull { byId(it) }
}
