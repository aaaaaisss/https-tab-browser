const fs = require('fs');

const main = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/MainActivity.kt', 'utf8');
const browser = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/BrowserActivity.kt', 'utf8');
const screen = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt', 'utf8');
const viewModel = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserViewModel.kt', 'utf8');
const repository = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/data/BrowserRepository.kt', 'utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');

for (const [name, source, required] of [
  ['PiP host action', main, 'builder.setActions(listOf(createOpenBrowserRemoteAction()))'],
  ['isolated browser activity launch', main, 'Intent(this, BrowserActivity::class.java)'],
  ['separate document task', main, 'Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK'],
  ['isolated browser Activity', browser, 'BrowserViewModelFactory(application, restorePersistentSession = false)'],
  ['no PiP WebView handoff', browser, 'session=isolated\\npersistentTabs=false'],
  ['shared BrowserScreen host contract', screen, 'activity as? BrowserScreenHost'],
  ['ephemeral session persistence guard', viewModel, 'if (!restorePersistentSession) return'],
  ['isolated settings share', viewModel, 'repository.saveSettings(uiState.settings)'],
  ['isolated bookmarks share', viewModel, 'repository.saveBookmarks(uiState.bookmarks)'],
  ['isolated clear-data guard', viewModel, 'if (restorePersistentSession) {'],
  ['settings-only repository save', repository, 'suspend fun saveSettings(settings: BrowserSettings)'],
  ['bookmarks-only repository save', repository, 'suspend fun saveBookmarks(bookmarks: List<Bookmark>)'],
  ['internal BrowserActivity manifest declaration', manifest, 'android:name=".BrowserActivity"'],
]) {
  if (!source.includes(required)) throw new Error(`${name} missing: ${required}`);
}
if (!/android:name="\.BrowserActivity"[\s\S]*?android:exported="false"/.test(manifest)) {
  throw new Error('BrowserActivity must remain non-exported');
}
if (browser.includes('enterPictureInPictureMode') || browser.includes('setActions(')) {
  throw new Error('BrowserActivity must not create a competing PiP surface');
}
console.log('PiP playback host and isolated continuous-browsing Activity: OK');
