const fs = require('fs');

const viewModel = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserViewModel.kt', 'utf8');
const screen = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt', 'utf8');
const controls = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserControls.kt', 'utf8');
const sheets = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserSheets.kt', 'utf8');
const models = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/data/BrowserModels.kt', 'utf8');
const repository = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/data/BrowserRepository.kt', 'utf8');
const webView = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt', 'utf8');
const mainActivity = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/MainActivity.kt', 'utf8');

function requireText(source, text, label) {
  if (!source.includes(text)) throw new Error(`${label}: missing ${text}`);
}
function forbidText(source, text, label) {
  if (source.includes(text)) throw new Error(`${label}: obsolete ${text} remains`);
}

requireText(viewModel, 'beginAddressEditing(if (tab.isHome) "" else tab.displayText.ifBlank { tab.url })', 'Google query focus suggestions');
requireText(viewModel, 'fun setAddressInput(value: String) = beginAddressEditing(value)', 'unified address editing');
requireText(viewModel, 'return results.values.take(MAX_SUGGESTIONS)', 'suggestion count limit');
requireText(viewModel, 'if (uiState.selectedTab?.isHome == false) openHome()', 'home back fallback');
requireText(controls, 'onSubmit: (String) -> Unit', 'IME latest input contract');
requireText(controls, 'onSubmit(textFieldValue.text)', 'IME latest input call');
requireText(controls, 'onEditingStopped: () -> Unit', 'address editing callback contract');
requireText(screen, 'onEditingStopped = { addressBarEditing = false; viewModel.stopAddressEditing() }', 'screen restores controls on address editing end');
requireText(screen, 'onBackgroundTap = ::endAddressEditing', 'home background ends address editing');
requireText(controls, 'onBackgroundTap: () -> Unit', 'home background tap contract');
requireText(controls, 'reverseLayout = true', 'bottom-up suggestion layout');
requireText(screen, 'onSubmit = { input -> navigate(input) }', 'IME navigation wiring');
requireText(screen, 'onOpenBookmark = { bookmark -> navigate(bookmark.url) }', 'bookmark URL-bar navigation');

// Focus and IME ownership: Android focus/keyboard effects stay in Compose UI, not ViewModel focus state.
forbidText(viewModel, 'isAddressFocused', 'legacy ViewModel focus state');
requireText(controls, 'val keyboardController = LocalSoftwareKeyboardController.current', 'X button keyboard controller');
requireText(controls, 'val imeVisible = WindowInsets.isImeVisible', 'X button IME visibility check');
requireText(controls, 'if (!imeVisible) {', 'X opens keyboard only when closed');
requireText(controls, 'focusRequester.requestFocus()', 'X restores address-bar focus');
requireText(controls, 'keyboardController?.show()', 'X opens keyboard when needed');
requireText(controls, 'textFieldValue = TextFieldValue("", TextRange(0))', 'X clears all address text');
forbidText(controls, 'LaunchedEffect(isEditing)', 'focus-driven IME side effect loop');

const systemBackHome = screen.indexOf('selectedTab?.returnToHomeOnBack == true -> returnSelectedTabToHome()');
const systemBackHistory = screen.indexOf('selectedTab?.isHome == false && selectedTab != null && registry.canGoBack(selectedTab.id) -> registry.goBack(selectedTab.id)');
if (systemBackHome < 0 || systemBackHistory < 0 || systemBackHistory > systemBackHome) {
  throw new Error('Chromium history back must run before bookmark home fallback');
}
const navigationBackHistory = screen.indexOf('if (registry.canGoBack(selectedTab.id)) registry.goBack(selectedTab.id)');
const navigationBackHome = screen.indexOf('else if (selectedTab.returnToHomeOnBack) returnSelectedTabToHome()');
if (navigationBackHistory < 0 || navigationBackHome < 0 || navigationBackHistory > navigationBackHome) {
  throw new Error('navigation-row must use Chromium history before home fallback');
}
requireText(sheets, 'label = "広告ブロック"', 'adblock label');
requireText(sheets, 'label = "暗色化"', 'dark mode label');
requireText(sheets, 'Text(if (highSelected) "normal" else "✓ normal")', 'normal mode selection');
requireText(sheets, 'Text(if (highSelected) "✓ high" else "high")', 'high mode selection');
forbidText(sheets, '広告 URL ルールをブロック', 'renamed adblock setting');
forbidText(sheets, '攻めた広告遮断モード', 'renamed high adblock mode');
forbidText(sheets, '動画サイトにも暗色化を適用', 'renamed high dark mode');

requireText(viewModel, 'uiState = uiState.copy(addressInput = "", isAddressEditing = false, isSuggestionPanelVisible = false, suggestions = emptyList())', 'home clears address and suggestion state');
const homeStateUpdate = screen.indexOf('viewModel.returnSelectedTabToHome()');
const homeHistoryReset = screen.indexOf('registry.resetForHome(id)');
if (homeStateUpdate < 0 || homeHistoryReset < 0 || homeStateUpdate > homeHistoryReset) {
  throw new Error('home state must clear before native history reset');
}
requireText(screen, 'private fun shouldShowRightEdgeScrollRail(url: String): Boolean', 'Google popup scroll protection helper');
requireText(screen, 'host.startsWith("google.") || host.contains(".google.")', 'Google web surface detection');
requireText(screen, 'if (!state.isFullscreen && shouldShowRightEdgeScrollRail(selectedTab.url))', 'edge rail suppression on Google web surfaces');
requireText(screen, 'statusBarsPadding().navigationBarsPadding()', 'safe system-bar layout for page and bottom controls');
requireText(screen, 'reserveRightTouchRail = shouldShowRightEdgeScrollRail(selectedTab.url)', 'native touch rail follows page type');
requireText(screen, 'placeAboveCompose = isGoogleWebSurface(selectedTab.url)', 'Google popup uses native front layer');
requireText(mainActivity, 'normalWebContentReservesRightTouchRail', 'page-specific native rail reservation');
requireText(mainActivity, 'else 0', 'Google web surfaces forward the full page width');
requireText(mainActivity, 'clipChildren = true', 'native host clips popup rendering to page bounds');
requireText(mainActivity, 'normalWebContentHost.bringToFront()', 'Google native front layer');

requireText(models, 'skipDarkeningAlreadyDarkPages: Boolean = false', 'already-dark exclusion default');
requireText(models, 'darkModeExcludedHosts: List<String> = emptyList()', 'manual dark exclusion model');
requireText(repository, 'skip_darkening_already_dark_pages', 'already-dark DataStore key');
requireText(repository, 'dark_mode_excluded_hosts', 'manual dark exclusion DataStore key');
requireText(sheets, '"元から暗いページでは追加暗色化しない"', 'already-dark setting UI');
requireText(sheets, 'SettingsPage.DARK_EXCLUSIONS -> DarkExclusionsPage', 'manual dark exclusion page routing');
requireText(sheets, '"暗色化の例外"', 'manual dark exclusion setting UI');
requireText(webView, 'DARK_DETECTOR_WITHOUT_OVERRIDE_SCRIPT', 'unmodified-style dark detector');
requireText(webView, 'prepareDarkDocumentStartScript(entry, url)', 'document-start dark CSS preparation');
requireText(webView, 'beginDarkRevealGuard(view, it, url)', 'black reveal guard on page start');
requireText(webView, 'beginDarkRevealGuard(entry.webView, entry, url)', 'black reveal guard before navigation');
requireText(webView, 'releaseDarkRevealGuard(view, entry, url)', 'reveal guard release');
requireText(webView, 'isDarkModeExcluded(settings, url)', 'manual dark exclusion runtime matching');
requireText(webView, 'fun refreshContentFiltering()', 'post-compile adblock reapplication');
requireText(webView, 'adblock_reapplied_after_engine_ready', 'engine-ready adblock diagnostic');
requireText(screen, 'registry.refreshContentFiltering()', 'post-compile existing tab protection');
requireText(webView, 'entry.documentIsAlreadyDark && entry.settings.skipDarkeningAlreadyDarkPages', 'existing-dark runtime suppression');
requireText(webView, 'entry.homeResetInProgress = true', 'home reset callback guard');
requireText(webView, 'page_finished_ignored_during_home_reset', 'stale callback diagnostics');
requireText(webView, 'isVideoPlaybackDocumentUrl(url) || isDarkModeExcluded(entry.settings, url)', 'video and manual exclusion path');
forbidText(screen, 'このページの描画プロセスが終了しました。タブを再作成しています。', 'renderer restart modal');

console.log('Browser stabilization, address editing, X/IME behavior, home reset, Google popup scrolling, safe bounds, renderer notice removal, and dark-page exclusion: OK');