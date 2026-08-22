const fs = require('fs');
const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const marker = 'val YOUTUBE_NO_AD_PLAYER_REQUEST_SCRIPT = """';
const start = source.indexOf(marker);
if (start < 0) throw new Error('No-ad player script marker not found');
const end = source.indexOf('""".trimIndent()', start);
if (end < 0) throw new Error('No-ad player script terminator not found');
const script = source.slice(start + marker.length, end);
new Function(script);

const testObject = { assign: Object.assign };
const window = {};
new Function('window', 'Object', script)(window, testObject);
const request = testObject.assign({}, {
  body: '{"context":{"client":{"clientName":"MWEB"}},"contentPlaybackContext":{}}'
});
if (!request.body.includes('"isInlinePlaybackNoAd":true')) {
  throw new Error('No-ad flag was not added to player request body');
}
const once = testObject.assign({}, request);
if ((once.body.match(/"isInlinePlaybackNoAd":true/g) || []).length !== 1) {
  throw new Error('No-ad flag was duplicated');
}
console.log('YouTube no-ad player request injection: OK');
