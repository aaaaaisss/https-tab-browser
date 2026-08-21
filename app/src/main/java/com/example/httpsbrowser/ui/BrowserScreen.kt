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
import com.example.httpsbrowser.MainActivity
import com.example.httpsbrowser.data.AdBlockListRepository
import com.example.httpsbrowser.data.AdBlockUpdateWorker
import com.example.httpsbrowser.data.SettingsPage
import com.example.httpsbrowser.web.BrowserWebCallbacks
import com.example.httpsbrowser.web.BrowserWebViewRegistry
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import java.io.File
import java.io.FileInputStream

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
    val blocker = remember { com.example.httpsbrowser.data.BraveAdBlockEngine(context.applicationContext) }
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
    var editingHomeBookmark by remember { mutableStateOf<com.example.httpsbrowser.data.Bookmark?>(null) }
    var homeBookmarkEditMode by remember { mutableStateOf(false) }
    var homeBookmarkSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingPageArchive by remember { mutableStateOf<File?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val pending = pendingPermission ?: return@rememberLauncherForActivityResult
        val granted = pending.appPermissions.all { result[it] == true ||
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        pending.reply(granted)
        pendingPermission = null
    }

    /** 新規URL入力だけをWebViewへ命令し、戻る・進む・ページ内遷移ではWebView履歴を再読込しない。 */
    fun navigate(input: String = state.addressInput) {
        val prepared = viewModel.prepareNavigation(input) ?: return
        selectedTab?.let { tab -> registry.load(tab.id, prepared.url) }
    }

    val pageArchiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        val archive = pendingPageArchive
        pendingPageArchive = null
        if (destination != null && archive != null) {
            runCatching {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    FileInputStream(archive).use { input -> input.copyTo(output) }
                } ?: error("保存先を開けませんでした。")
            }.onSuccess {
                notice = "ページを保存しました。"
            }.onFailure {
                notice = "ページを保存できませんでした: ${it.message ?: "保存先を確認してください。"}"
            }
        }
        archive?.delete()
    }

    LaunchedEffect(Unit) {
        // Fulgurisの帰属と対応ソースは設定内「オープンソースライセンス」で常時閲覧可能にする。
        listRepository.ensureStandardLists()
        // Kotlin/JNIの巨大文字列コピーを避けたファイル直読コンパイルで、標準リストを有効化する。
        listRepository.loadAndCompile()
        AdBlockUpdateWorker.schedule(context.applicationContext)
    }
    LaunchedEffect(externalUrl) {
        externalUrl?.let(::navigate)
    }
    LaunchedEffect(state.bookmarks) {
        homeBookmarkSelection = homeBookmarkSelection.intersect(state.bookmarks.map { it.id }.toSet())
    }
    DisposableEffect(selectedTab?.id) {
        selectedTab?.takeIf { !it.isHome }?.let { registry.resume(it.id) }
        // タブ切替だけで WebView を pause すると、ユーザーが開始した音声・動画も停止する。
        // レンダラはアプリ終了時またはタブを閉じた時に確実に破棄する。
        onDispose { }
    }
    DisposableEffect(Unit) { onDispose { registry.close() } }

    /**
     * Fulgurisの`onHideCustomView`と同じ所有権の順序で、custom viewを一度だけ解放する。
     * 先に状態を空にするため、callbackに伴う再入onHideCustomViewでは何も二重に外さない。
     */
    fun finishFullscreen(notifyPage: Boolean) {
        val content = fullscreenContent ?: return
        fullscreenContent = null
        // custom viewが消えた瞬間にPiP自動移行も無効化し、通常ページでPiPにならないようにする。
        (activity as? MainActivity)?.setFullscreenVideoForPictureInPicture(null)
        runCatching { content.view.keepScreenOn = false }
        (content.view.parent as? ViewGroup)?.removeView(content.view)
        viewModel.setFullscreen(false)
        if (notifyPage) runCatching { content.callback.onCustomViewHidden() }
        activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun handleWebViewHideFullscreen() {
        if ((activity as? MainActivity)?.shouldRetainFullscreenCustomView() == true) {
            // PiP遷移ではWebViewがonHideCustomViewを先に通知することがある。
            // この時点でcustom viewを外すと通常の下部バーがPiPに入り、再生も止まる。
            com.example.httpsbrowser.CrashDiagnostics.record("pip_custom_view_retained", "reason=webview_hide_during_pip")
            return
        }
        finishFullscreen(false)
    }

    /**
     * Fulgurisの`onShowCustomView`から、Compose UIに必要な最小の状態遷移だけを移植する。
     * WebChromeClientが重複してcustom viewを送った場合、新しいviewを重ねず即時に拒否する。
     */
    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenContent != null) {
            com.example.httpsbrowser.CrashDiagnostics.record("fullscreen_duplicate_show", "ignored=true")
            runCatching { callback.onCustomViewHidden() }
            return
        }
        runCatching { view.keepScreenOn = true }
        fullscreenContent = FullscreenContent(view, callback)
        viewModel.setFullscreen(true)
        // PiPのsource rect、auto-enter、遷移中のonHideCustomView保持はActivityに一元化する。
        (activity as? MainActivity)?.setFullscreenVideoForPictureInPicture(view)
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
            homeBookmarkEditMode -> {
                homeBookmarkEditMode = false
                homeBookmarkSelection = emptySet()
            }
            selectedTab?.isHome == false && selectedTab != null && registry.canGoBack(selectedTab.id) -> registry.goBack(selectedTab.id)
            // 独自ホームから開いたブックマーク等は、WebView履歴が尽きた次の戻るでホームへ戻す。
            selectedTab?.returnToHomeOnBack == true -> viewModel.returnSelectedTabToHome()
            // 通常ページの履歴が尽きた時は、Android戻るとして終了する。
            else -> activity?.finish()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Chromium WebViewのfullscreenは元のWebViewを残したままcustom viewを上に重ねる。
        // 通常Viewを外すとAwContentsの描画先が切り替わり、再生停止や黒画面の原因になる。
        if (selectedTab != null) {
            // 表示領域と操作バーを重ねずに分離する。操作バーのタップは WebView へ透過しない。
            Column(Modifier.fillMaxSize()) {
                Box(if (state.isFullscreen) Modifier.fillMaxSize() else Modifier.weight(1f).fillMaxWidth()) {
                    if (selectedTab.isHome) {
                        HomeScreen(
                            bookmarks = state.bookmarks,
                            editMode = homeBookmarkEditMode,
                            selectedIds = homeBookmarkSelection,
                            onOpenBookmark = { bookmark -> navigate(bookmark.url) },
                            onAddBookmark = { addBookmarkDialog = true },
                            onEnterEditMode = { id ->
                                homeBookmarkEditMode = true
                                homeBookmarkSelection = setOf(id)
                            },
                            onToggleSelection = { id ->
                                homeBookmarkSelection = if (id in homeBookmarkSelection) homeBookmarkSelection - id else homeBookmarkSelection + id
                            },
                            onExitEditMode = {
                                homeBookmarkEditMode = false
                                homeBookmarkSelection = emptySet()
                            }
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
                                        onHideFullscreen = ::handleWebViewHideFullscreen,
                                        onPermission = { origin, resources, reply ->
                                            pendingPermission = PendingWebPermission(origin, resources, requiredAndroidPermissions(resources), reply)
                                        },
                                        onLongPress = { longPressedLink = it },
                                        showNotice = { notice = it },
                                        onExternalApp = { externalAppUrl = it },
                                        onRendererGone = {
                                            // 同じクラッシュURLを自動再読込せず、操作可能なホームへ復帰する。
                                            viewModel.openHome()
                                            notice = "このページの描画プロセスが停止しました。ホームへ戻りました。"
                                        },
                                        onPageArchiveReady = { sourcePath, fileName ->
                                            pendingPageArchive = File(sourcePath)
                                            pageArchiveLauncher.launch(fileName)
                                        }
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
                                        onHideFullscreen = ::handleWebViewHideFullscreen,
                                        onPermission = { origin, resources, reply ->
                                            pendingPermission = PendingWebPermission(origin, resources, requiredAndroidPermissions(resources), reply)
                                        },
                                        onLongPress = { longPressedLink = it },
                                        showNotice = { notice = it },
                                        onExternalApp = { externalAppUrl = it },
                                        onRendererGone = {
                                            // 同じクラッシュURLを自動再読込せず、操作可能なホームへ復帰する。
                                            viewModel.openHome()
                                            notice = "このページの描画プロセスが停止しました。ホームへ戻りました。"
                                        },
                                        onPageArchiveReady = { sourcePath, fileName ->
                                            pendingPageArchive = File(sourcePath)
                                            pageArchiveLauncher.launch(fileName)
                                        }
                                    ))
                                },
                                modifier = Modifier.fillMaxSize().pointerInput(state.isAddressFocused) {
                                    detectTapGestures(onTap = { if (state.isAddressFocused) viewModel.stopAddressEditing() })
                                }
                            )
                        }
                        if (!state.isFullscreen) {
                            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                                RightEdgeScrollRail(
                                    currentFraction = scrollFraction,
                                    onScrollToFraction = { fraction -> registry.scrollToFraction(selectedTab.id, fraction) }
                                )
                            }
                        }
                    }
                }
                if (!state.isFullscreen) {
                    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                    if (state.isSuggestionPanelVisible) SuggestionPanel(state.suggestions) { suggestion ->
                        navigate(suggestion.url)
                    }
                    AddressBar(
                        value = state.addressInput,
                        progress = progress,
                        isEditing = state.isAddressFocused,
                        onValueChange = viewModel::setAddressInput,
                        // 新規入力時だけURLを読み込む。WebViewの戻る・進むではloadUrlを呼ばない。
                        onSubmit = { navigate() },
                        onTranslate = { if (!selectedTab.isHome) registry.translateToJapanese(selectedTab.id) },
                        onRefresh = { if (!selectedTab.isHome) registry.reload(selectedTab.id) },
                        onEditingStarted = viewModel::startAddressEditing
                    )
                    NavigationRow(
                        canGoBack = (selectedTab.canGoBack || selectedTab.returnToHomeOnBack) && !selectedTab.isHome,
                        canGoForward = selectedTab.canGoForward && !selectedTab.isHome,
                        onTabs = { viewModel.stopAddressEditing(); viewModel.toggleTabSheet() },
                        onBack = {
                            viewModel.stopAddressEditing()
                            if (!selectedTab.isHome) {
                                if (registry.canGoBack(selectedTab.id)) registry.goBack(selectedTab.id)
                                else viewModel.returnSelectedTabToHome()
                            }
                        },
                        onSearch = viewModel::startAddressEditing,
                        onForward = { viewModel.stopAddressEditing(); if (!selectedTab.isHome) registry.goForward(selectedTab.id) },
                        onBookmark = { viewModel.stopAddressEditing(); addBookmarkDialog = true },
                        onHistory = { viewModel.stopAddressEditing(); viewModel.openSettings(SettingsPage.HISTORY) },
                        onDownloads = { viewModel.stopAddressEditing(); runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } },
                        onSavePage = {
                            viewModel.stopAddressEditing()
                            if (!selectedTab.isHome) registry.savePageArchive(selectedTab.id, selectedTab.title)
                        },
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
        }

        // normal WebViewの上にのみfullscreen custom viewを置く。PiP中も同じ描画階層を維持する。
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
        }

        if (!state.isFullscreen && selectedTab?.isHome == true && homeBookmarkEditMode) {
            BookmarkEditActionBar(
                selectedCount = homeBookmarkSelection.size,
                onEdit = {
                    editingHomeBookmark = state.bookmarks.firstOrNull { it.id in homeBookmarkSelection }
                },
                onDelete = {
                    viewModel.removeBookmarks(homeBookmarkSelection)
                    homeBookmarkEditMode = false
                    homeBookmarkSelection = emptySet()
                },
                onMoveToBottomRight = { viewModel.moveBookmarks(homeBookmarkSelection, false) },
                onMoveToTopLeft = { viewModel.moveBookmarks(homeBookmarkSelection, true) },
                onDone = {
                    homeBookmarkEditMode = false
                    homeBookmarkSelection = emptySet()
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 138.dp)
            )
        }

        if (state.isTabSheetVisible) {
            BrowserSheets.TabSheet(
                tabs = state.tabs,
                selectedTabId = state.selectedTabId,
                onSelect = viewModel::selectTab,
                onClose = { id -> registry.remove(id); viewModel.closeTab(id) },
                onNewTab = { isPrivate -> viewModel.addTab(isPrivate = isPrivate) },
                onPrivateModeChanged = { enabled ->
                    if (enabled) viewModel.switchToPrivateTab() else viewModel.switchToNormalTab()
                },
                onDismiss = viewModel::toggleTabSheet
            )
        }
        if (state.isSettingsSheetVisible) {
            BrowserSheets.SettingsSheet(
                state = state,
                listRepository = listRepository,
                onSettings = viewModel::updateSettings,
                onOpenUrl = { url ->
                    navigate(url)
                    viewModel.closeSettings()
                },
                onOpenPage = viewModel::showSettingsPage,
                onBack = viewModel::backFromSettingsPage,
                onDismiss = viewModel::closeSettings,
                onSaveBookmark = viewModel::addBookmark,
                onUpdateBookmark = viewModel::updateBookmark,
                onDeleteBookmark = viewModel::removeBookmark,
                onDeleteHistory = viewModel::removeHistory,
                onClear = { viewModel.clearBrowsingData { registry.clearAllBrowsingData() }; viewModel.closeSettings() },
                onDownloads = { runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } },
                onShareDiagnostics = { runCatching { com.example.httpsbrowser.CrashDiagnostics.share(context) }.onFailure { notice = "診断情報を共有できませんでした。" } },
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

    editingHomeBookmark?.let { bookmark ->
        BrowserSheets.BookmarkEditorDialog(
            title = "ブックマークを編集",
            initialTitle = bookmark.title,
            initialUrl = bookmark.url,
            onConfirm = { title, url ->
                if (viewModel.updateBookmark(bookmark.id, title, url)) editingHomeBookmark = null
                else notice = "HTTPS URL または検索語を入力してください。"
            },
            onDismiss = { editingHomeBookmark = null }
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
            title = { Text("ねこぶらうざ") },
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
    onRendererGone: () -> Unit,
    onPageArchiveReady: (String, String) -> Unit
) = object : BrowserWebCallbacks {
    override fun onPageStarted(tabId: String, url: String) = viewModel.onPageStarted(tabId, url)
    override fun onPageFinished(tabId: String, url: String, title: String?) = viewModel.onPageFinished(tabId, url, title)
    override fun onTitle(tabId: String, title: String) = viewModel.onTitleChanged(tabId, title)
    override fun onHistoryState(tabId: String, canGoBack: Boolean, canGoForward: Boolean) = viewModel.onHistoryStateChanged(tabId, canGoBack, canGoForward)
    override fun onProgress(tabId: String, progress: Int) = onProgress(progress)
    override fun onScrollPosition(tabId: String, fraction: Float) = onScrollPosition(fraction)
    override fun onHttpsUpgrade(url: String) = registry.load(tabId, url)
    override fun onBlockedNavigation(url: String) = showNotice("HTTPS 接続のみ許可されています。\n$url")
    override fun onSslError(url: String) = showNotice("証明書エラーのため安全に接続できませんでした。\n$url")
    override fun onRendererGone(tabId: String) = onRendererGone()
    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = onFullscreen(view, callback)
    override fun onHideFullscreen() = onHideFullscreen()
    override fun onWebPermissionRequest(origin: String, resources: Set<String>, reply: (Boolean) -> Unit) = onPermission(origin, resources, reply)
    override fun onGeolocationPermission(origin: String, reply: (Boolean) -> Unit) = onPermission(origin, setOf("位置情報"), reply)
    override fun onPopupRequested(): String? = viewModel.addTab(isPrivate = viewModel.isPrivateTab(tabId)).id
    override fun onLinkLongPressed(url: String) = onLongPress(url)
    override fun onDownloadStarted(fileName: String, destination: String) =
        showNotice("ダウンロードを開始しました: $fileName（保存先: $destination）")
    override fun onPageArchiveReady(sourcePath: String, fileName: String) = onPageArchiveReady(sourcePath, fileName)
    override fun onExternalAppRequested(url: String) = onExternalApp(url)
    override fun onPageInteraction() = viewModel.stopAddressEditing()
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
