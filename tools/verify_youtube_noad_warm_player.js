const fs = require('fs');

const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const forbidden = [
  'WebViewCompat.addDocumentStartJavaScript(entry.webView, YOUTUBE_NO_AD_WARM_PLAYER_SCRIPT, originRules)',
  'youtube_no_ad_warm_player_ready',
];
for (const text of forbidden) {
  if (source.includes(text)) throw new Error(`YouTube player request prepatch must remain disabled: ${text}`);
}
for (const required of [
  'YOUTUBE_SABR_PATCH_ONLY_SCRIPT',
  '実際のSABR backoff制御応答だけを見て待機値を短縮する',
  'var target=50+Math.floor(Math.random()*100)',
]) {
  if (!source.includes(required)) throw new Error(`SABR-only playback protection missing: ${required}`);
}
console.log('YouTube uses SABR-only playback protection without proactive player-request mutation: OK');
