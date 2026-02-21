(function () {
  var waitingScreen = document.getElementById('waiting-screen');
  var playerScreen = document.getElementById('player-screen');
  var container = document.getElementById('player-container');
  var currentIframe = null;

  // Cloud server URL (must match socket.js)
  var CLOUD_HTTP_URL = 'https://karawebos.onrender.com';

  // Dynamic server IP - set by discovery in socket.js
  var serverIP = null;

  function getPlayerHost() {
    if (serverIP === 'cloud') {
      return CLOUD_HTTP_URL;
    }
    return 'http://' + (serverIP || '127.0.0.1') + ':3000';
  }

  function setServerIP(ip) {
    serverIP = ip;
  }

  function showWaitingScreen() {
    waitingScreen.style.display = 'flex';
    playerScreen.style.display = 'none';
    if (currentIframe) {
      try { currentIframe.src = 'about:blank'; } catch (e) { }
      currentIframe.parentNode.removeChild(currentIframe);
      currentIframe = null;
    }
  }

  function showPlayerScreen() {
    waitingScreen.style.display = 'none';
    playerScreen.style.display = 'block';
  }

  function playVideoId(videoId) {
    if (!videoId) return;

    showPlayerScreen();

    // Remove old iframe
    container.innerHTML = '';

    // Create iframe pointing to our HTTP-hosted player page
    var iframe = document.createElement('iframe');
    iframe.style.cssText = 'width:100%;height:100%;border:0;background:#000;';
    iframe.setAttribute('allow', 'autoplay; encrypted-media; fullscreen');
    iframe.src = getPlayerHost() + '/player?v=' + encodeURIComponent(videoId);

    currentIframe = iframe;
    container.appendChild(iframe);
  }

  function sendCommand(cmd) {
    if (currentIframe && currentIframe.contentWindow) {
      currentIframe.contentWindow.postMessage(JSON.stringify({ command: cmd }), '*');
    }
  }

  window.KaraokePlayer = {
    play: playVideoId,
    pause: function () { sendCommand('pause'); },
    stop: showWaitingScreen,
    volumeUp: function () { sendCommand('volume_up'); },
    volumeDown: function () { sendCommand('volume_down'); },
    showWaiting: showWaitingScreen,
    setServerIP: setServerIP
  };

  showWaitingScreen();
})();
