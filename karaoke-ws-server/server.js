const http = require('http');
const url = require('url');
const fs = require('fs');
const path = require('path');
const dgram = require('dgram');
const os = require('os');
const WebSocket = require('ws');

const PORT = process.env.PORT || 3000;
const UDP_PORT = 3001;
const DISCOVERY_MSG = 'KARAOKE_SERVER';
const IS_CLOUD = !!process.env.PORT || !!process.env.RENDER;

/* ===================== GET LOCAL IP ===================== */
function getLocalIP() {
  const interfaces = os.networkInterfaces();
  const candidates = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        candidates.push({ name, address: iface.address });
      }
    }
  }
  // Prefer 192.168.x.x or 10.x.x.x (real LAN), skip 192.168.56.x (VirtualBox) etc.
  const preferred = candidates.find(c =>
    (c.address.startsWith('192.168.') && !c.address.startsWith('192.168.56.')) ||
    c.address.startsWith('10.') ||
    c.address.startsWith('172.')
  );
  if (preferred) return preferred.address;
  if (candidates.length > 0) return candidates[0].address;
  return '127.0.0.1';
}

/* ===================== UDP DISCOVERY ===================== */
// Broadcasts server presence so Android & TV can auto-discover
// Skip UDP on cloud deployments (no LAN to discover)
if (!IS_CLOUD) {
  const udpServer = dgram.createSocket({ type: 'udp4', reuseAddr: true });

  udpServer.on('message', (msg, rinfo) => {
    const text = msg.toString().trim();
    if (text === 'KARAOKE_DISCOVER') {
      const localIP = getLocalIP();
      const response = Buffer.from(`KARAOKE_SERVER:${localIP}:${PORT}`);
      udpServer.send(response, rinfo.port, rinfo.address);
    }
  });

  udpServer.bind(UDP_PORT, '0.0.0.0', () => {
    udpServer.setBroadcast(true);
    console.log(`UDP discovery listening on port ${UDP_PORT}`);

    // Also broadcast every 2 seconds so TV can passively listen
    setInterval(() => {
      const localIP = getLocalIP();
      const beacon = Buffer.from(`KARAOKE_SERVER:${localIP}:${PORT}`);
      udpServer.send(beacon, 0, beacon.length, UDP_PORT, '255.255.255.255', () => {});
    }, 2000);
  });
} else {
  console.log('Cloud mode: UDP discovery disabled');
}

/* ===================== HTTP SERVER ===================== */
// Serves a YouTube player page so the embed runs on http://
// This avoids Error 153 (missing referrer) when loaded from file://

function getPlayerHTML(videoId, reqHost) {
  // Dynamically determine origin from the request Host header
  const origin = reqHost ? `http://${reqHost}` : `http://${getLocalIP()}:${PORT}`;
  const encodedOrigin = encodeURIComponent(origin);
  return `<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<style>*{margin:0;padding:0;overflow:hidden}html,body{width:100%;height:100%;background:#000}
iframe{width:100%;height:100%;border:0}</style>
</head><body>
<iframe id="ytframe"
  src="https://www.youtube.com/embed/${videoId}?autoplay=1&controls=0&modestbranding=1&rel=0&iv_load_policy=3&disablekb=1&playsinline=1&enablejsapi=1&origin=${encodedOrigin}&widget_referrer=${encodedOrigin}"
  allow="autoplay; encrypted-media; fullscreen"
  allowfullscreen>
</iframe>
<script>
  // Listen for commands from parent window (webOS app)
  window.addEventListener('message', function(e) {
    try {
      var data = (typeof e.data === 'string') ? JSON.parse(e.data) : e.data;
      var frame = document.getElementById('ytframe');
      if (!frame || !frame.contentWindow) return;
      if (data.command === 'pause') {
        frame.contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}), '*');
      }
      if (data.command === 'resume') {
        frame.contentWindow.postMessage(JSON.stringify({event:'command',func:'playVideo',args:[]}), '*');
      }
      if (data.command === 'stop') {
        frame.contentWindow.postMessage(JSON.stringify({event:'command',func:'stopVideo',args:[]}), '*');
      }
      if (data.command === 'volume_up') {
        frame.contentWindow.postMessage(JSON.stringify({event:'command',func:'setVolume',args:[Math.min(100, (window._ytVol || 50) + 10)]}), '*');
        window._ytVol = Math.min(100, (window._ytVol || 50) + 10);
      }
      if (data.command === 'volume_down') {
        frame.contentWindow.postMessage(JSON.stringify({event:'command',func:'setVolume',args:[Math.max(0, (window._ytVol || 50) - 10)]}), '*');
        window._ytVol = Math.max(0, (window._ytVol || 50) - 10);
      }
    } catch(err) {}
  });
</script>
</body></html>`;
}

const httpServer = http.createServer((req, res) => {
  const parsed = url.parse(req.url, true);

  // Discovery endpoint - apps use this to verify server
  if (parsed.pathname === '/ping') {
    res.writeHead(200, {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*'
    });
    res.end(JSON.stringify({ service: 'karaoke', ip: getLocalIP(), port: PORT }));
    return;
  }

  // App update: version check
  if (parsed.pathname === '/version') {
    const versionFile = path.join(__dirname, 'update', 'version.json');
    if (fs.existsSync(versionFile)) {
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*'
      });
      res.end(fs.readFileSync(versionFile, 'utf-8'));
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('no version info');
    }
    return;
  }

  // App update: APK download
  if (parsed.pathname === '/apk') {
    const apkFile = path.join(__dirname, 'update', 'app.apk');
    if (fs.existsSync(apkFile)) {
      const stat = fs.statSync(apkFile);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="KaraokeRemote.apk"',
        'Access-Control-Allow-Origin': '*'
      });
      fs.createReadStream(apkFile).pipe(res);
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('APK not found');
    }
    return;
  }

  if (parsed.pathname === '/player') {
    const videoId = (parsed.query.v || '').replace(/[^a-zA-Z0-9_-]/g, '');
    if (!videoId) {
      res.writeHead(400, { 'Content-Type': 'text/plain' });
      res.end('Missing video id');
      return;
    }
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Access-Control-Allow-Origin': '*'
    });
    res.end(getPlayerHTML(videoId, req.headers.host));
    return;
  }

  // Health check
  if (parsed.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
    return;
  }

  // Store screenshots mockup
  if (parsed.pathname === '/screenshots') {
    const screenshotFile = path.join(path.dirname(__dirname), 'store_screenshots.html');
    if (fs.existsSync(screenshotFile)) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(fs.readFileSync(screenshotFile));
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Mockup not found');
    }
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found');
});

httpServer.listen(PORT, '0.0.0.0', () => {
  if (IS_CLOUD) {
    console.log(`Karaoke Cloud server running on port ${PORT}`);
  } else {
    console.log(`Karaoke HTTP+WS server running on http://${getLocalIP()}:${PORT}`);
    console.log(`UDP discovery on port ${UDP_PORT}`);
  }
});

/* ===================== WEBSOCKET SERVER ===================== */
const wss = new WebSocket.Server({ server: httpServer });

const clients = new Map();

function sendJson(socket, payload) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
  }
}

function cleanup(socket) {
  const info = clients.get(socket);
  clients.delete(socket);

  if (!info || !info.room || !info.role) {
    return;
  }

  const peerRole = info.role === 'controller' ? 'tv' : 'controller';
  const peer = findTarget(peerRole, info.room);
  if (peer) {
    sendJson(peer, { action: 'peer_disconnected', room: info.room, role: info.role });
  }
}

function findTarget(role, room) {
  for (const [socket, info] of clients.entries()) {
    if (info.room === room && info.role === role && socket.readyState === WebSocket.OPEN) {
      return socket;
    }
  }
  return null;
}

function findSameRoleSockets(room, role, exceptSocket) {
  const matched = [];
  for (const [socket, info] of clients.entries()) {
    if (socket === exceptSocket) continue;
    if (info.room === room && info.role === role && socket.readyState === WebSocket.OPEN) {
      matched.push(socket);
    }
  }
  return matched;
}

wss.on('connection', (socket) => {
  clients.set(socket, { room: null, role: null });

  socket.on('message', (raw) => {
    let payload;
    try {
      payload = JSON.parse(raw.toString());
    } catch {
      sendJson(socket, { action: 'error', message: 'invalid_json' });
      return;
    }

    if (payload.action === 'join') {
      const room = String(payload.room || '');
      const role = String(payload.role || '');

      if (!room || !role) {
        sendJson(socket, { action: 'error', message: 'missing_join_fields' });
        return;
      }

      clients.set(socket, { room, role });
      sendJson(socket, { action: 'joined', room, role });

      const duplicated = findSameRoleSockets(room, role, socket);
      for (const oldSocket of duplicated) {
        sendJson(oldSocket, { action: 'error', message: 'duplicate_session' });
        oldSocket.close(4000, 'duplicate_session');
      }

      const peerRole = role === 'controller' ? 'tv' : 'controller';
      const peer = findTarget(peerRole, room);
      if (peer) {
        sendJson(socket, { action: 'peer_connected', room, role: peerRole });
        sendJson(peer, { action: 'peer_connected', room, role });
      }

      return;
    }

    const sender = clients.get(socket);
    if (!sender || !sender.room || !sender.role) {
      sendJson(socket, { action: 'error', message: 'not_joined' });
      return;
    }

    if (['play', 'pause', 'stop', 'volume_up', 'volume_down'].includes(payload.action)) {
      const targetRole = sender.role === 'controller' ? 'tv' : 'controller';
      const target = findTarget(targetRole, sender.room);
      if (!target) {
        sendJson(socket, { action: 'error', message: 'target_not_connected' });
        return;
      }

      sendJson(target, payload);
      sendJson(socket, { action: 'ack', sourceAction: payload.action });
      return;
    }

    sendJson(socket, { action: 'error', message: 'unknown_action' });
  });

  socket.on('close', () => cleanup(socket));
  socket.on('error', () => cleanup(socket));
});
