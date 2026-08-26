const fs = require('fs');

const source = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const required = [
  'YOUTUBE_NO_AD_WARM_PLAYER_SCRIPT',
  'WebViewCompat.addDocumentStartJavaScript(entry.webView, YOUTUBE_NO_AD_WARM_PLAYER_SCRIPT, originRules)',
  'youtube_no_ad_warm_player_ready',
  'window.__nekoBrowserNoAdWarmPlayer=true',
  'isInlinePlaybackNoAd',
  'if (!entry.aggressiveAdBlockingEnabled)',
  'YOUTUBE_SABR_PATCH_ONLY_SCRIPT',
  'var target=25+Math.floor(Math.random()*50)',
  'return passThrough();',
  'YOUTUBE_PLAYBACK_METRICS_SCRIPT',
  'youtube_playback_metric',
  "video.addEventListener('playing'",
];
for (const text of required) {
  if (!source.includes(text)) throw new Error(`Known-good #140 YouTube start path missing: ${text}`);
}
for (const forbidden of [
  'enterPictureInPictureMode',
  'loadUrl(\'https://www.youtube.com\')',
]) {
  if (source.includes(forbidden)) throw new Error(`Warm-player script must not restart the playback session: ${forbidden}`);
}
console.log('YouTube #140 warm-player and safe SABR fallback protections: OK');
