(function () {
  /* ===================== CONFIGURATION ===================== */
  // Cloud server URL - change this after deploying to Render.com
  var CLOUD_SERVER_URL = 'wss://karawebos.onrender.com';
  var CLOUD_HTTP_URL = 'https://karawebos.onrender.com';
  var SERVER_PORT = 3000;

  var serverMode = null; // 'cloud' or 'lan'
  var serverIP = null;
  var serverWSUrl = null;

  var pairingCodeElement = document.getElementById('pairing-code');
  var connectionStatusElement = document.getElementById('connection-status');

  var pairingCode = generatePairingCode();
  var socket = null;
  var reconnectTimer = null;
  var reconnectAttempts = 0;
  var isConnected = false;
  var isConnecting = false;
  var socketToken = 0;

  pairingCodeElement.textContent = pairingCode;

  function generatePairingCode() {
    return String(Math.floor(100000 + Math.random() * 900000));
  }

  function setStatus(text) {
    connectionStatusElement.textContent = text;
  }

  function safeParseJson(raw) {
    try {
      return JSON.parse(raw);
    } catch (error) {
      return null;
    }
  }

  /* ===================== CLOUD SERVER CHECK ===================== */
  function pingCloud(callback) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', CLOUD_HTTP_URL + '/ping', true);
    xhr.timeout = 5000;
    xhr.onload = function () {
      if (xhr.status === 200) {
        try {
          var data = JSON.parse(xhr.responseText);
          if (data.service === 'karaoke') {
            callback(true);
            return;
          }
        } catch (e) { }
      }
      callback(false);
    };
    xhr.onerror = function () { callback(false); };
    xhr.ontimeout = function () { callback(false); };
    xhr.send();
  }

  /* ===================== LAN DISCOVERY ===================== */
  function getLocalSubnet(callback) {
    try {
      var pc = new RTCPeerConnection({ iceServers: [] });
      pc.createDataChannel('');
      pc.createOffer().then(function (offer) {
        return pc.setLocalDescription(offer);
      });
      pc.onicecandidate = function (e) {
        if (!e || !e.candidate || !e.candidate.candidate) return;
        var match = e.candidate.candidate.match(/([0-9]{1,3}(\.[0-9]{1,3}){3})/);
        if (match) {
          var ip = match[1];
          if (ip !== '0.0.0.0' && !ip.startsWith('127.')) {
            pc.close();
            var parts = ip.split('.');
            callback(parts[0] + '.' + parts[1] + '.' + parts[2]);
            return;
          }
        }
      };
      setTimeout(function () {
        pc.close();
        callback('192.168.1');
      }, 2000);
    } catch (e) {
      callback('192.168.1');
    }
  }

  function pingServer(ip, callback) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', 'http://' + ip + ':' + SERVER_PORT + '/ping', true);
    xhr.timeout = 1500;
    xhr.onload = function () {
      if (xhr.status === 200) {
        try {
          var data = JSON.parse(xhr.responseText);
          if (data.service === 'karaoke') {
            callback(true);
            return;
          }
        } catch (e) { }
      }
      callback(false);
    };
    xhr.onerror = function () { callback(false); };
    xhr.ontimeout = function () { callback(false); };
    xhr.send();
  }

  function scanSubnet(onFound, onFail) {
    setStatus('Đang tìm server trên mạng LAN...');
    getLocalSubnet(function (subnet) {
      var found = false;
      var pending = 254;
      for (var i = 1; i <= 254; i++) {
        (function (n) {
          var ip = subnet + '.' + n;
          pingServer(ip, function (ok) {
            if (ok && !found) {
              found = true;
              try { localStorage.setItem('karaoke_server_ip', ip); } catch (e) { }
              onFound(ip);
            }
            pending--;
            if (pending === 0 && !found) {
              onFail();
            }
          });
        })(i);
      }
    });
  }

  /* ===================== SMART DISCOVERY ===================== */
  // Priority: 1) Cached LAN IP  2) LAN scan  3) Cloud server (fallback)
  // LAN first because Android Remote only supports LAN - both must be on same server
  function discoverServer(onFound, onFail) {
    // Step 1: Try cached LAN IP first (fastest)
    var cached = null;
    try { cached = localStorage.getItem('karaoke_server_ip'); } catch (e) { }

    if (cached) {
      setStatus('Kết nối LAN ' + cached + '...');
      pingServer(cached, function (ok) {
        if (ok) {
          serverMode = 'lan';
          onFound(cached);
          return;
        }
        localStorage.removeItem('karaoke_server_ip');
        // Cached IP failed, try full LAN scan
        tryLanThenCloud(onFound, onFail);
      });
    } else {
      tryLanThenCloud(onFound, onFail);
    }
  }

  function tryLanThenCloud(onFound, onFail) {
    // Step 2: Full LAN scan
    setStatus('Đang tìm server trên mạng LAN...');
    scanSubnet(function (ip) {
      serverMode = 'lan';
      onFound(ip);
    }, function () {
      // Step 3: LAN not found, try cloud as fallback
      setStatus('LAN không tìm thấy, thử Cloud...');
      pingCloud(function (cloudOk) {
        if (cloudOk) {
          serverMode = 'cloud';
          onFound(null);
        } else {
          onFail();
        }
      });
    });
  }

  /* ===================== WEBSOCKET ===================== */
  function handleCommand(payload) {
    if (!payload || typeof payload !== 'object') {
      return;
    }

    switch (payload.action) {
      case 'play':
        window.KaraokePlayer.play(payload.videoId);
        break;
      case 'pause':
        window.KaraokePlayer.pause();
        break;
      case 'stop':
        window.KaraokePlayer.stop();
        break;
      case 'volume_up':
        window.KaraokePlayer.volumeUp();
        break;
      case 'volume_down':
        window.KaraokePlayer.volumeDown();
        break;
      default:
        break;
    }
  }

  function clearReconnectTimer() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  }

  function scheduleReconnect() {
    if (isConnected || isConnecting) {
      return;
    }

    clearReconnectTimer();
    reconnectAttempts += 1;

    // Every 10 reconnects, re-discover in case server changed
    if (reconnectAttempts % 10 === 0) {
      setStatus('Tìm lại server...');
      discoverServer(function (ip) {
        serverIP = ip;
        if (serverMode === 'cloud') {
          serverWSUrl = CLOUD_SERVER_URL;
          window.KaraokePlayer.setServerIP('cloud');
        } else {
          serverWSUrl = 'ws://' + ip + ':' + SERVER_PORT;
          window.KaraokePlayer.setServerIP(ip);
        }
        connectWS();
      }, function () {
        var delay = Math.min(15000, 1000 * reconnectAttempts);
        setStatus('Không tìm thấy server, thử lại...');
        reconnectTimer = setTimeout(function () {
          reconnectTimer = null;
          scheduleReconnect();
        }, delay);
      });
      return;
    }

    var delay = Math.min(15000, 1000 * reconnectAttempts);
    setStatus('Mất kết nối, thử lại sau ' + Math.floor(delay / 1000) + 's...');

    reconnectTimer = setTimeout(function () {
      reconnectTimer = null;
      connectWS();
    }, delay);
  }

  function sendJoinRoom() {
    var joinPayload = {
      action: 'join',
      room: pairingCode,
      role: 'tv'
    };
    socket.send(JSON.stringify(joinPayload));
  }

  function connectWS() {
    if (!serverWSUrl) return;

    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
      return;
    }

    isConnecting = true;
    isConnected = false;

    var currentToken = ++socketToken;
    var displayName = (serverMode === 'cloud') ? 'Cloud' : serverIP;

    try {
      setStatus('Đang kết nối ' + displayName + '...');
      socket = new WebSocket(serverWSUrl);
    } catch (error) {
      isConnecting = false;
      scheduleReconnect();
      return;
    }

    socket.onopen = function () {
      if (currentToken !== socketToken) {
        return;
      }

      clearReconnectTimer();
      reconnectAttempts = 0;
      isConnecting = false;
      isConnected = true;
      setStatus('Đã kết nối ' + displayName + '. Room: ' + pairingCode);
      sendJoinRoom();
    };

    socket.onmessage = function (event) {
      var payload = safeParseJson(event.data);
      handleCommand(payload);
    };

    socket.onerror = function () {
      if (currentToken !== socketToken) {
        return;
      }
      setStatus('Lỗi kết nối ' + displayName);
    };

    socket.onclose = function () {
      if (currentToken !== socketToken) {
        return;
      }

      isConnecting = false;
      isConnected = false;
      window.KaraokePlayer.showWaiting();
      scheduleReconnect();
    };
  }

  /* ===================== START ===================== */
  discoverServer(function (ip) {
    serverIP = ip;
    if (serverMode === 'cloud') {
      serverWSUrl = CLOUD_SERVER_URL;
      window.KaraokePlayer.setServerIP('cloud');
      setStatus('Cloud Server ✓');
    } else {
      serverWSUrl = 'ws://' + ip + ':' + SERVER_PORT;
      window.KaraokePlayer.setServerIP(ip);
      setStatus('LAN Server: ' + ip);
    }
    connectWS();
  }, function () {
    setStatus('Không tìm thấy server. Kiểm tra WiFi.');
    setTimeout(function retry() {
      discoverServer(function (ip) {
        serverIP = ip;
        if (serverMode === 'cloud') {
          serverWSUrl = CLOUD_SERVER_URL;
          window.KaraokePlayer.setServerIP('cloud');
        } else {
          serverWSUrl = 'ws://' + ip + ':' + SERVER_PORT;
          window.KaraokePlayer.setServerIP(ip);
        }
        connectWS();
      }, function () {
        setTimeout(retry, 5000);
      });
    }, 5000);
  });
})();

