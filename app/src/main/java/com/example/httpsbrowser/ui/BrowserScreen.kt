package com.example.httpsbrowser.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.httpsbrowser.data.AdBlockListRepository
import com.example.httpsbrowser.data.Suggestion
import com.example.httpsbrowser.data.UrlRuleBlocker
import com.example.httpsbrowser.web.BrowserWebCallbacks
import com.example.httpsbrowser.web.BrowserWebViewRegistry
import kotlinx.coroutines.launch
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
fun BrowserScreen(viewModel: BrowserViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val blocker = remember { UrlRuleBlocker() }
    val registry = remember { BrowserWebViewRegistry(context.applicationContext, blocker) }
    val listRepository = remember { AdBlockListRepository(context.applicationContext, blocker) }
    val state = viewModel.uiState
    val selectedTab = state.selectedTab
    var progress by remember(selectedTab?.id) { mutableIntStateOf(0) }
    var rendererVersion by remember { mutableIntStateOf(0) }
    var pendingPermission by remember { mutableStateOf<PendingWebPermission?>(null) }
    var fullscreenContent by remember { mutableStateOf<FullscreenContent?>(null) }
    var longPressedLink by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scrollAmount = with(LocalDensity.current) { 480.dp.roundToPx() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val pending = pendingPermission ?: return@rememberLauncherForActivityResult
        val granted = pending.appPermissions.all { result[it] == true ||
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        pending.reply(granted)
        pendingPermission = null
    }

    LaunchedEffect(Unit) { listRepository.loadAndCompile() }
    DisposableEffect(selectedTab?.id) {
        selectedTab?.let { registry.resume(it.id) }
        onDispose { selectedTab?.let { registry.pause(it.id) } }
    }
    DisposableEffect(Unit) { onDispose { registry.destroyAll() } }

    fun finishFullscreen(notifyPage: Boolean) {
        val content = fullscreenContent ?: return
        fullscreenContent = null // コールバックによる再入を防ぐため、先に状態を閉じる。
        (content.view.parent as? ViewGroup)?.removeView(content.view)
        viewModel.setFullscreen(false)
        if (notifyPage) content.callback.onCustomViewHidden()
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun exitFullscreen() = finishFullscreen(notifyPage = true)

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
            state.isFullscreen -> exitFullscreen()
            state.isTabSheetVisible -> viewModel.toggleTabSheet()
            state.isSettingsSheetVisible -> viewModel.toggleSettingsSheet()
            selectedTab?.canGoBack == true -> selectedTab?.let { registry.goBack(it.id) }
            else -> activity?.moveTaskToBack(true)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (selectedTab != null) {
            androidx.compose.runtime.key("${selectedTab.id}-$rendererVersion") {
                AndroidView(
                    factory = {
                        registry.obtain(selectedTab, state.settings, callbacksFor(
                            viewModel = viewModel,
                            registry = registry,
                            tabId = selectedTab.id,
                            onProgress = { progress = it },
                            onFullscreen = ::enterFullscreen,
                            onHideFullscreen = { finishFullscreen(notifyPage = false) },
                            onPermission = { origin, resources, reply ->
                                val permissions = requiredAndroidPermissions(resources)
                                pendingPermission = PendingWebPermission(origin, resources, permissions, reply)
                            },
                            onLongPress = { longPressedLink = it },
                            onNotice = { notice = it },
                            onRendererGone = { rendererVersion++ }
                        ))
                    },
                    update = { webView ->
                        registry.obtain(selectedTab, state.settings, callbacksFor(
                            viewModel = viewModel,
                            registry = registry,
                            tabId = selectedTab.id,
                            onProgress = { progress = it },
                            onFullscreen = ::enterFullscreen,
                            onHideFullscreen = { finishFullscreen(notifyPage = false) },
                            onPermission = { origin, resources, reply ->
                                pendingPermission = PendingWebPermission(origin, resources, requiredAndroidPermissions(resources), reply)
                            },
                            onLongPress = { longPressedLink = it },
                            onNotice = { notice = it },
                            onRendererGone = { rendererVersion++ }
                        ))
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        fullscreenContent?.let { content ->
            AndroidView(
                factory = { FrameLayout(it).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
                update = { host ->
                    if (content.view.parent !== host) {
                        (content.view.parent as? ViewGroup)?.removeView(content.view)
                        host.removeAllViews()
                        host.addView(content.view, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!state.isFullscreen && selectedTab != null) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 198.dp)
            ) { ShortcutDock(
                isBookmarked = viewModel.isBookmarked(selectedTab.url),
                onHistory = { viewModel.toggleSettingsSheet() },
                onBookmarks = { viewModel.toggleSettingsSheet() },
                onDownloads = { runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } },
                onShare = { shareUrl(context, selectedTab.url) },
                onBookmark = { viewModel.toggleBookmark() }
            ) }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 198.dp)
            ) { ScrollButtons(
                onTop = { registry.scrollToTop(selectedTab.id) },
                onUp = { registry.scrollBy(selectedTab.id, -scrollAmount) },
                onDown = { registry.scrollBy(selectedTab.id, scrollAmount) },
                onBottom = { registry.scrollToBottom(selectedTab.id) }
            ) }

            androidx.compose.foundation.layout.Column(Modifier.align(Alignment.BottomCenter)) {
                SuggestionPanel(state.suggestions) { suggestion ->
                    viewModel.openSuggestion(suggestion).also { registry.load(selectedTab.id, it.url) }
                }
                AddressBar(
                    value = state.addressInput,
                    progress = progress,
                    onValueChange = viewModel::setAddressInput,
                    onSubmit = { viewModel.prepareNavigation()?.let { registry.load(selectedTab.id, it.url) } },
                    onRefresh = { registry.reload(selectedTab.id) }
                )
                NavigationRow(
                    canGoBack = selectedTab.canGoBack,
                    canGoForward = selectedTab.canGoForward,
                    onTabs = viewModel::toggleTabSheet,
                    onBack = { registry.goBack(selectedTab.id) },
                    onSearch = { viewModel.prepareNavigation()?.let { registry.load(selectedTab.id, it.url) } },
                    onForward = { registry.goForward(selectedTab.id) },
                    onSettings = viewModel::toggleSettingsSheet
                )
                TabBar(
                    tabs = state.tabs,
                    selectedTabId = state.selectedTabId,
                    onSelect = viewModel::selectTab,
                    onClose = { id -> registry.remove(id); viewModel.closeTab(id) },
                    onAdd = { viewModel.addTab() }
                )
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
                onClear = { viewModel.clearBrowsingData { registry.clearAllBrowsingData() } },
                onOpenUrl = { url -> selectedTab?.let { tab -> viewModel.prepareNavigation(url)?.let { prepared -> registry.load(tab.id, prepared.url) } } },
                onDismiss = viewModel::toggleSettingsSheet,
                onNotice = { notice = it }
            )
        }
    }

    pendingPermission?.let { pending ->
        AlertDialog(
            onDismissRequest = { pending.reply(false); pendingPermission = null },
            title = { Text("サイト権限の確認") },
            text = { Text("${pending.origin} が ${pending.webResources.joinToString()} へのアクセスを求めています。許可しますか？") },
            confirmButton = {
                Button(onClick = {
                    if (pending.appPermissions.isEmpty()) {
                        pending.reply(false) // 未対応リソースは明示的に拒否する。
                        pendingPermission = null
                    } else permissionLauncher.launch(pending.appPermissions)
                }) { Text("許可") }
            },
            dismissButton = { TextButton(onClick = { pending.reply(false); pendingPermission = null }) { Text("拒否") } }
        )
    }

    longPressedLink?.let { url ->
        AlertDialog(
            onDismissRequest = { longPressedLink = null },
            title = { Text("リンク") },
            text = { Text(url) },
            confirmButton = { TextButton(onClick = { viewModel.addTab(url); longPressedLink = null }) { Text("新しいタブで開く") } },
            dismissButton = { TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("URL", url))
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
    onFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit,
    onPermission: (String, Set<String>, (Boolean) -> Unit) -> Unit,
    onLongPress: (String) -> Unit,
    onNotice: (String) -> Unit,
    onRendererGone: () -> Unit
) = object : BrowserWebCallbacks {
    override fun onPageStarted(tabId: String, url: String) = viewModel.onPageStarted(tabId, url)
    override fun onPageFinished(tabId: String, url: String, title: String?) = viewModel.onPageFinished(tabId, url, title)
    override fun onTitle(tabId: String, title: String) = viewModel.onTitleChanged(tabId, title)
    override fun onHistoryState(tabId: String, canGoBack: Boolean, canGoForward: Boolean) = viewModel.onHistoryStateChanged(tabId, canGoBack, canGoForward)
    override fun onProgress(tabId: String, progress: Int) = onProgress(progress)
    override fun onHttpsUpgrade(url: String) = registry.load(tabId, url)
    override fun onBlockedNavigation(url: String) = onNotice("HTTPS 接続のみ許可されています。\n$url")
    override fun onSslError(url: String) = onNotice("証明書エラーのため安全に接続できませんでした。\n$url")
    override fun onRendererGone(tabId: String) { onNotice("ページ描画を再起動します。"); onRendererGone() }
    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = onFullscreen(view, callback)
    override fun onHideFullscreen() = onHideFullscreen()
    override fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit) = onPermission(origin, resources, reply)
    override fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit) = onPermission(origin, setOf("位置情報"), reply)
    override fun onPopupRequested(): String? = viewModel.addTab().id
    override fun onLinkLongPressed(url: String) = onLongPress(url)
    override fun onDownloadStarted(fileName: String) = onNotice("ダウンロードを開始しました: $fileName")
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
