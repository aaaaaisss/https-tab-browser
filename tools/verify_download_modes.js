const fs = require('fs');

const downloads = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/data/BrowserDownloads.kt', 'utf8');
const web = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const screen = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt', 'utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');

function requireText(source, text, label) {
  if (!source.includes(text)) throw new Error(`${label}: missing ${text}`);
}

for (const required of [
  'enum class BrowserDownloadMode { NORMAL, HIGH }',
  'DownloadManager.Request(Uri.parse(request.url))',
  'setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.fileName)',
  'setRequestProperty("Range", range)',
  'private const val PARALLEL_CONNECTIONS = 4',
  'HttpURLConnection.HTTP_PARTIAL',
  'BrowserDownloadDispatcher.enqueueNormal(applicationContext, request)',
  'MediaStore.Downloads.IS_PENDING',
  'ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC'
]) requireText(downloads, required, 'download dispatch safety');

requireText(web, 'entry.callbacks.onDownloadRequested(', 'WebView download handoff');
requireText(web, 'referer = entry.webView.url?.takeIf(::isHttps)', 'HTTPS Referer handoff');
requireText(screen, 'Text("通常")', 'normal download option');
requireText(screen, 'Text("高速")', 'high download option');
requireText(screen, 'BrowserDownloadMode.HIGH', 'high dispatch choice');
requireText(manifest, 'android.permission.FOREGROUND_SERVICE_DATA_SYNC', 'data sync permission');
requireText(manifest, 'android:foregroundServiceType="dataSync"', 'data sync service type');

console.log('Download link handoff and normal/high download mode safeguards: OK');
