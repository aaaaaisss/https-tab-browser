const fs = require('fs');
const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const marker = 'val YOUTUBE_NO_AD_WARM_PLAYER_SCRIPT = """';
const start = source.indexOf(marker);
if (start < 0) throw new Error('Warm player script marker not found');
const end = source.indexOf('""".trimIndent()', start);
if (end < 0) throw new Error('Warm player script terminator not found');
const script = source.slice(start + marker.length, end);
new Function(script);
for (const forbidden of ['cancelPlayback', 'loadVideoById', 'ytInitialPlayerResponse', 'location.reload']) {
  if (script.includes(forbidden)) throw new Error(`Warm player script must not use ${forbidden}`);
}

function createWindow() {
  const window = {};
  window.JSON = JSON;
  window.Object = Object;
  return window;
}

const window = createWindow();
new Function('window', 'JSON', 'Object', 'Proxy', 'Reflect', script)(window, JSON, Object, Proxy, Reflect);
const original = JSON.stringify({
  playbackContext: { contentPlaybackContext: { currentUrl: '/watch?v=test' } },
});
const patched = window.JSON.stringify({
  playbackContext: { contentPlaybackContext: { currentUrl: '/watch?v=test' } },
});
if (!patched.includes('"isInlinePlaybackNoAd":true')) throw new Error('JSON.stringify player body was not patched');
const unchanged = window.JSON.stringify({ foo: 'bar' });
if (unchanged !== '{"foo":"bar"}') throw new Error('Unrelated JSON was changed');
const carrier = { body: original };
window.Object.assign(carrier, { method: 'POST' });
if (!carrier.body.includes('"isInlinePlaybackNoAd":true')) throw new Error('Object.assign body was not patched');
console.log('YouTube warm player no-ad request behavior: OK');
