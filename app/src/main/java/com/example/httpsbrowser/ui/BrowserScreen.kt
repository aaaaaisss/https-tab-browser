package com.example.httpsbrowser.ui

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.httpsbrowser.data.AdBlockListRepository
import com.example.httpsbrowser.data.AdBlockUpdateWorker
import com.example.httpsbrowser.data.SettingsPage
import com.example.httpsbrowser.web.BrowserWebCallbacks
import com.example.httpsbrowser.web.BrowserWebViewRegistry
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient

private data class PendingWebPermission(
    val origin: String,
    val webResources: Set<String>,
    val appPermissions: Array<String>,
    val reply: (Boolean) -> Unit
)

private data class FullscreenContent(
    val view: View,
    val callback: WebChromeClient.CustomViewCallback
)

@Composable
fun BrowserScreen(viewModel: BrowserViewModel, externalUrl: String? = null) {
    val context = LocalContext.current
    val activity = context as? Activity
    val blocker = remember { com.example.httpsbrowser.data.UrlRuleBlocker() }
    val registry = remember { BrowserWebViewRegistry(context.applicationContext, blocker) }
    val listRepository = remember { AdBlockListRepository(context.applicationContext, blocker) }
    val state = viewModel.uiState
    val selectedTab = state.selectedTab
    var progress by remember(selectedTab?.id) { mutableIntStateOf(0) }
    var scrollFraction by remember(selectedTab?.id) { mutableFloatStateOf(0f) }
    var rendererVersion by remember { mutableIntStateOf(0) }
    var pendingPermission by remember { mutableStateOf<PendingWebPermission?>(null) }
    var fullscreenContent by remember { mutableStateOf<FullscreenContent?>(null) }
    var longPressedLink by remember { mutableStateOf<String?>(null) }
    var externalAppUrl by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var addBookmarkDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val pending = pendingPermission ?: return@rememberLauncherForActivityResult
        val granted = pending.appPermissions.all { result[it] == true ||
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        pending.reply(granted)
        pendingPermission = null
    }

    LaunchedEffect(Unit) {
        listRepository.ensureStandardLists()
        listRepository.loadAndCompile()
        AdBlockUpdateWorker.schedule(context.applicationContext)
    }
    LaunchedEffect(externalUrl) {
        externalUrl?.let { viewModel.prepareNavigation(it) }
    }
    DisposableEffect(selectedTab?.id) {
        selectedTab?.takeIf { !it.isHome }?.let { registry.resume(it.id) }
        onDispose { selectedTab?.takeIf { !it.isHome }?.let { registry.pause(it.id) } }
    }
    DisposableEffect(Unit) { onDispose { registry.destroyAll() } }
    LaunchedEffect(selectedTab?.id, selectedTab?.lastRequestedUrl, selectedTab?.isHome) {
        selectedTab?.takeIf { !it.isHome && it.lastRequestedUrl.isNotBlank() }?.let { registry.load(it.id, it.lastRequestedUrl) }
    }

    fun finishFullscreen(notifyPage: Boolean) {
        val content = fullscreenContent ?: return
        fullscreenContent = null
        (content.view.parent as? ViewGroup)?.removeView(content.view)
        viewModel.setFullscreen(false)
        if (notifyPage) content.callback.onCustomViewHidden()
        activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        viewModel.setFullscreen(true)
        fullscreenContent = FullscreenContent(view, callback)
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler {
        when {
            state.isFullscreen -> finishFullscreen(true)
            state.isAddressFocused -> viewModel.stopAddressEditing()
            state.isTabSheetVisible -> viewModel.toggleTabSheet()
            state.isSettingsSheetVisible -> viewModel.backFromSettingsPage()
            selectedTab?.isHome == false && selectedTab != null && registry.canGoBack(selectedTab.id) -> registry.goBack(selectedTab.id)
            selectedTab?.isHome == false -> viewModel.openHome()
            else -> Unit // 履歴が尽きても戻る操作でアプリを終了しない。
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (state.isFullscreen) {
            fullscreenContent?.let { content ->
                AndroidView(
                    factory = { FrameLayout(it).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
                    update = { host ->
                        if (content.view.parent !== host) {
                            (content.view.parent as? ViewGroup)?.removeView(content.view)
                            host.removeAllViews()
                            host.addView(content.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (selectedTab != null) {
            // 表示領域と操作バーを重ねずに分離する。操作バーのタップは WebView へ透過しない。
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (selectedTab.isHome) {
                        HomeScreen(
                            bookmarks = state.bookmarks,
                            onOpenBookmark = { bookmark -> viewModel.prepareNavigation(bookmark.url) },
                            onAddBookmark = { addBookmarkDialog = true }
                        )
                    } else {
                        androidx.compose.runtime.key("${selectedTab.id}-$rendererVersion") {
                            AndroidView(
                                factory = {
                                    registry.obtain(selectedTab, state.settings, callbacksFor(
                                        viewModel = viewModel,
                                        registry = registry,
                                        tabId = selectedTab.id,
                                        onProgress = { progress = it },
                                        onScrollPosition = { scrollFraction = it },
                                        onFullscreen = ::enterFullscreen,
                                        onHideFullscreen = { finishFullscreen(false) },
                                        onPermission = { origin, resources, reply ->
                                            pendingPermission = PendingWebPermission(origin, resources, requiredAndroidPermissions(resources), reply)
                                        },
                                        onLongPress = { longPressedLink = it },
                                        showNotice = { notice = it },
                                        onExternalApp = { externalAppUrl = it },
                                        onRendererGone = { rendererVersion++ }
                                    ))
                                },
                                update = {
                                    registry.obtain(selectedTab, state.settings, callbacksFor(
                                        viewModel = viewModel,
                                        registry = registry,
                                        tabId = selectedTab.id,
                                        onProgress = { progress = it },
                                        onScrollPosition = { scrollFraction = it },
                                        onFullscreen = ::enterFullscreen,
                                        onHideFullscreen = { finishFullscreen(false) },
                                        onPermission = { origin, resources, reply ->
                                            pendingPermission = PendingWebPermission(origin, resources, requiredAndroidPermissions(resources), reply)
                                        },
                                        onLongPress = { longPressedLink = it },
                                        showNotice = { notice = it },
                                        onExternalApp = { externalAppUrl = it },
                                        onRendererGone = { rendererVersion++ }
                                    ))
                                },
                                modifier = Modifier.fillMaxSize().pointerInput(state.isAddressFocused) {
                                    detectTapGestures(onTap = { if (state.isAddressFocused) viewModel.stopAddressEditing() })
                                }
                            )
                        }
                        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                            RightEdgeScrollRail(
                                currentFraction = scrollFraction,
                                onScrollToFraction = { fraction -> registry.scrollToFraction(selectedTab.id, fraction) }
                            )
                        }
                    }
                }
                Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                    if (state.isAddressFocused) SuggestionPanel(state.suggestions) { suggestion ->
                        viewModel.stopAddressEditing()
                        viewModel.openSuggestion(suggestion)
                    }
                    AddressBar(
                        value = state.addressInput,
                        progress = progress,
                        isEditing = state.isAddressFocused,
                        onValueChange = viewModel::setAddressInput,
                        onSubmit = { viewModel.stopAddressEditing(); viewModel.prepareNavigation() },
                        onTranslate = { if (!selectedTab.isHome) registry.translateToJapanese(selectedTab.id) },
                        onRefresh = { if (!selectedTab.isHome) registry.reload(selectedTab.id) },
                        onEditingStarted = viewModel::startAddressEditing
                    )
                    NavigationRow(
                        canGoBack = selectedTab.canGoBack && !selectedTab.isHome,
                        canGoForward = selectedTab.canGoForward && !selectedTab.isHome,
                        onTabs = { viewModel.stopAddressEditing(); viewModel.toggleTabSheet() },
                        onBack = { viewModel.stopAddressEditing(); if (!selectedTab.isHome) registry.goBack(selectedTab.id) },
                        onSearch = viewModel::startAddressEditing,
                        onForward = { viewModel.stopAddressEditing(); if (!selectedTab.isHome) registry.goForward(selectedTab.id) },
                        onBookmark = { viewModel.stopAddressEditing(); addBookmarkDialog = true },
                        onHistory = { viewModel.stopAddressEditing(); viewModel.openSettings(SettingsPage.HISTORY) },
                        onDownloads = { viewModel.stopAddressEditing(); runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } },
                        onShare = { viewModel.stopAddressEditing(); if (!selectedTab.isHome) shareUrl(context, selectedTab.url) },
                        onSettings = { viewModel.stopAddressEditing(); viewModel.openSettings() }
                    )
                    TabBar(
                        tabs = state.tabs,
                        selectedTabId = state.selectedTabId,
                        onSelect = { id -> viewModel.stopAddressEditing(); viewModel.selectTab(id) },
                        onClose = { id -> viewModel.stopAddressEditing(); registry.remove(id); viewModel.closeTab(id) },
                        onAdd = { viewModel.stopAddressEditing(); viewModel.addTab() }
                    )
                }
            }
        }

        if (state.isTabSheetVisible) {
            BrowserSheets.TabSheet(
                tabs = state.tabs,
                selectedTabId = state.selectedTabId,
                onSelect = viewModel::selectTab,
                onClose = { id -> registry.remove(id); viewModel.closeTab(id) },
                onNewTab = { viewModel.addTab() },
                onDismiss = viewModel::toggleTabSheet
            )
        }
        if (state.isSettingsSheetVisible) {
            BrowserSheets.SettingsSheet(
                state = state,
                listRepository = listRepository,
                onSettings = viewModel::updateSettings,
                onOpenUrl = { url -> viewModel.prepareNavigation(url) },
                onOpenPage = viewModel::showSettingsPage,
                onBack = viewModel::backFromSettingsPage,
                onDismiss = viewModel::closeSettings,
                onSaveBookmark = viewModel::addBookmark,
                onUpdateBookmark = viewModel::updateBookmark,
                onDeleteBookmark = viewModel::removeBookmark,
                onClear = { viewModel.clearBrowsingData { registry.clearAllBrowsingData() }; viewModel.closeSettings() },
                onDownloads = { runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } },
                onNotice = { notice = it }
            )
        }
    }

    if (addBookmarkDialog) {
        val tab = selectedTab
        BrowserSheets.BookmarkEditorDialog(
            title = "ブックマークを追加",
            initialTitle = tab?.title?.takeIf { it != "ホーム" }.orEmpty(),
            initialUrl = tab?.url.orEmpty(),
            onConfirm = { title, url ->
                if (viewModel.addBookmark(title, url)) addBookmarkDialog = false else notice = "HTTPS URL または検索語を入力してください。"
            },
            onDismiss = { addBookmarkDialog = false }
        )
    }

    pendingPermission?.let { pending ->
        AlertDialog(
            onDismissRequest = { pending.reply(false); pendingPermission = null },
            title = { Text("サイト権限の確認") },
            text = { Text("${pending.origin} が ${pending.webResources.joinToString()} へのアクセスを求めています。許可しますか？") },
            confirmButton = {
                Button(onClick = {
                    if (pending.appPermissions.isEmpty()) { pending.reply(false); pendingPermission = null }
                    else permissionLauncher.launch(pending.appPermissions)
                }) { Text("許可") }
            },
            dismissButton = { TextButton(onClick = { pending.reply(false); pendingPermission = null }) { Text("拒否") } }
        )
    }

    externalAppUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { externalAppUrl = null },
            title = { Text("外部アプリをブロックしました") },
            text = { Text("このリンクはアプリを開こうとしました。対応アプリで開く場合だけ、下のボタンを押してください。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)) }
                    externalAppUrl = null
                }) { Text("アプリで開く") }
            },
            dismissButton = { TextButton(onClick = { externalAppUrl = null }) { Text("このブラウザに留まる") } }
        )
    }

    longPressedLink?.let { url ->
        AlertDialog(
            onDismissRequest = { longPressedLink = null },
            title = { Text("リンク") },
            text = { Text(url) },
            confirmButton = { TextButton(onClick = { viewModel.addTab(url); longPressedLink = null }) { Text("新しいタブで開く") } },
            dismissButton = { TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", url))
                longPressedLink = null
            }) { Text("URL をコピー") } }
        )
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("閉じる") } },
            title = { Text("HTTPS Tab Browser") },
            text = { Text(message) }
        )
    }
}

private fun callbacksFor(
    viewModel: BrowserViewModel,
    registry: BrowserWebViewRegistry,
    tabId: String,
    onProgress: (Int) -> Unit,
    onScrollPosition: (Float) -> Unit,
    onFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit,
    onPermission: (String, Set<String>, (Boolean) -> Unit) -> Unit,
    onLongPress: (String) -> Unit,
    showNotice: (String) -> Unit,
    onExternalApp: (String) -> Unit,
    onRendererGone: () -> Unit
) = object : BrowserWebCallbacks {
    override fun onPageStarted(tabId: String, url: String) = viewModel.onPageStarted(tabId, url)
    override fun onPageFinished(tabId: String, url: String, title: String?) = viewModel.onPageFinished(tabId, url, title)
    override fun onTitle(tabId: String, title: String) = viewModel.onTitleChanged(tabId, title)
    override fun onHistoryState(tabId: String, canGoBack: Boolean, canGoForward: Boolean) = viewModel.onHistoryStateChanged(tabId, canGoBack, canGoForward)
    override fun onProgress(tabId: String, progress: Int) = onProgress(progress)
    override fun onScrollPosition(tabId: String, fraction: Float) = onScrollPosition(fraction)
    override fun onHttpsUpgrade(url: String) = registry.load(tabId, url)
    override fun onBlockedNavigation(url: String) = onNotice("HTTPS 接続のみ許可されています。\n$url")
    override fun onSslError(url: String) = onNotice("証明書エラーのため安全に接続できませんでした。\n$url")
    override fun onRendererGone(tabId: String) = onRendererGone()
    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = onFullscreen(view, callback)
    override fun onHideFullscreen() = onHideFullscreen()
    override fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit) = onPermission(origin, resources, reply)
    override fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit) = onPermission(origin, setOf("位置情報"), reply)
    override fun onPopupRequested(): String? = viewModel.addTab().id
    override fun onLinkLongPressed(url: String) = onLongPress(url)
    override fun onDownloadStarted(fileName: String) = showNotice("ダウンロードを開始しました: $fileName")
    override fun onExternalAppRequested(url: String) = onExternalApp(url)
    override fun onNotice(message: String) = showNotice(message)
}

private fun requiredAndroidPermissions(resources: Set<String>): Array<String> = buildSet {
    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) add(Manifest.permission.RECORD_AUDIO)
    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) add(Manifest.permission.CAMERA)
    if ("位置情報" in resources) add(Manifest.permission.ACCESS_FINE_LOCATION)
}.toTypedArray()

private fun shareUrl(context: Context, url: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }, "ページを共有"))
}
