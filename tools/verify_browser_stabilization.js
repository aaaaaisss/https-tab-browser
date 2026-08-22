const fs = require('fs');

const viewModel = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserViewModel.kt', 'utf8');
const screen = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt', 'utf8');
const controls = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserControls.kt', 'utf8');
const sheets = fs.readFileSync('app/src/main/java/com/example/httpsbrowser/ui/BrowserSheets.kt', 'utf8');

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
requireText(controls, 'reverseLayout = true', 'bottom-up suggestion layout');
requireText(screen, '@OptIn(ExperimentalLayoutApi::class)', 'IME layout API opt-in');
requireText(screen, 'val imeVisible = WindowInsets.isImeVisible', 'IME dismissal observer');
requireText(screen, 'onSubmit = { input -> navigate(input) }', 'IME navigation wiring');
requireText(screen, 'onOpenBookmark = { bookmark -> navigate(bookmark.url) }', 'bookmark URL-bar navigation');
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

console.log('Browser stabilization settings, address, suggestions, and home-back behavior: OK');
