package com.example.httpsbrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.httpsbrowser.data.AdBlockListRepository
import com.example.httpsbrowser.data.BlockListSource
import com.example.httpsbrowser.data.Bookmark
import com.example.httpsbrowser.data.BrowserSettings
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.BrowserUiState
import com.example.httpsbrowser.data.SettingsPage
import kotlinx.coroutines.launch

object BrowserSheets {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TabSheet(
        tabs: List<BrowserTab>,
        selectedTabId: String?,
        onSelect: (String) -> Unit,
        onClose: (String) -> Unit,
        onNewTab: (Boolean) -> Unit,
        onPrivateModeChanged: (Boolean) -> Unit,
        onDismiss: () -> Unit
    ) {
        val selectedTab = tabs.firstOrNull { it.id == selectedTabId }
        var privateMode by remember(selectedTabId, selectedTab?.isPrivate) { mutableStateOf(selectedTab?.isPrivate == true) }
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("タブ一覧", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onNewTab(privateMode) }) { Icon(Icons.Default.Add, null); Text("新しいタブ") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text("シークレットタブ")
                    Text("履歴・タブ復元に残さない", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = privateMode, onCheckedChange = { enabled ->
                    privateMode = enabled
                    onPrivateModeChanged(enabled)
                })
            }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(tab.id) },
                        headlineContent = { Text(tab.title.ifBlank { "ホーム" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (tab.isPrivate) "シークレット・履歴を保存しない"
                                else if (tab.isHome) "独自ホーム" else tab.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingContent = {
                            Row {
                                if (tab.id == selectedTabId) Text("選択中", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                                IconButton(onClick = { onClose(tab.id) }) { Icon(Icons.Default.Delete, "タブを閉じる") }
                            }
                        }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsSheet(
        state: BrowserUiState,
        listRepository: AdBlockListRepository,
        onSettings: ((BrowserSettings) -> BrowserSettings) -> Unit,
        onOpenUrl: (String) -> Unit,
        onOpenPage: (SettingsPage) -> Unit,
        onBack: () -> Unit,
        onDismiss: () -> Unit,
        onSaveBookmark: (String, String) -> Boolean,
        onUpdateBookmark: (String, String, String) -> Boolean,
        onDeleteBookmark: (String) -> Unit,
        onDeleteHistory: (String) -> Unit,
        onClear: () -> Unit,
        onDownloads: () -> Unit,
        onNotice: (String) -> Unit
    ) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (state.settingsPage) {
                SettingsPage.ROOT -> SettingsRoot(state, onSettings, onOpenPage, onDownloads, onDismiss)
                SettingsPage.BOOKMARKS -> BookmarkPage(state.bookmarks, onOpenUrl, onSaveBookmark, onUpdateBookmark, onDeleteBookmark, onBack, onNotice)
                SettingsPage.HISTORY -> HistoryPage(state.history, onOpenUrl, onDeleteHistory, onBack)
                SettingsPage.AD_BLOCK -> AdBlockPage(listRepository, onBack, onNotice)
                SettingsPage.DATA -> DataPage(onClear, onBack)
            }
        }
    }

    @Composable
    private fun SettingsRoot(
        state: BrowserUiState,
        onSettings: ((BrowserSettings) -> BrowserSettings) -> Unit,
        onOpenPage: (SettingsPage) -> Unit,
        onDownloads: () -> Unit,
        onDismiss: () -> Unit
    ) {
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            item { SheetTitle("設定") }
            item { SettingSwitch("ページを強制的に暗色化", state.settings.forceDarkPages) { onSettings { setting -> setting.copy(forceDarkPages = it) } } }
            item { SettingSwitch("広告 URL ルールをブロック", state.settings.adBlockingEnabled) { onSettings { setting -> setting.copy(adBlockingEnabled = it) } } }
            item { SettingSwitch("JavaScript を有効化", state.settings.javascriptEnabled) { onSettings { setting -> setting.copy(javascriptEnabled = it) } } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SheetTitle("管理") }
            item { NavigationItem("ブックマーク", Icons.Default.Bookmark) { onOpenPage(SettingsPage.BOOKMARKS) } }
            item { NavigationItem("閲覧履歴", Icons.Default.History) { onOpenPage(SettingsPage.HISTORY) } }
            item { NavigationItem("ダウンロード", Icons.Default.Download, onDownloads) }
            item { NavigationItem("広告ブロック", Icons.Default.Security) { onOpenPage(SettingsPage.AD_BLOCK) } }
            item { NavigationItem("閲覧データの消去", Icons.Default.Delete) { onOpenPage(SettingsPage.DATA) } }
            item { TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Text("閉じる") } }
        }
    }

    @Composable
    private fun BookmarkPage(
        bookmarks: List<Bookmark>,
        onOpenUrl: (String) -> Unit,
        onSaveBookmark: (String, String) -> Boolean,
        onUpdateBookmark: (String, String, String) -> Boolean,
        onDeleteBookmark: (String) -> Unit,
        onBack: () -> Unit,
        onNotice: (String) -> Unit
    ) {
        var creating by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<Bookmark?>(null) }
        PageHeader("ブックマーク", onBack, actionLabel = "追加") { creating = true }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (bookmarks.isEmpty()) item { EmptyRow("ブックマークはまだありません。") }
            items(bookmarks, key = { it.id }) { bookmark ->
                ListItem(
                    modifier = Modifier.clickable { onOpenUrl(bookmark.url) },
                    headlineContent = { Text(bookmark.title.ifBlank { bookmark.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(bookmark.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        BookmarkFavicon(
                            url = bookmark.url,
                            title = bookmark.title.ifBlank { bookmark.url },
                            modifier = Modifier.size(40.dp).padding(3.dp)
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = bookmark }) { Icon(Icons.Default.Edit, "ブックマークを編集") }
                            IconButton(onClick = { onDeleteBookmark(bookmark.id) }) { Icon(Icons.Default.Delete, "ブックマークを削除") }
                        }
                    }
                )
            }
        }
        if (creating) BookmarkEditorDialog(
            title = "ブックマークを追加", initialTitle = "", initialUrl = "",
            onConfirm = { title, url ->
                if (onSaveBookmark(title, url)) creating = false else onNotice("HTTPS URL または検索語を入力してください。")
            }, onDismiss = { creating = false }
        )
        editing?.let { bookmark ->
            BookmarkEditorDialog(
                title = "ブックマークを編集", initialTitle = bookmark.title, initialUrl = bookmark.url,
                onConfirm = { title, url ->
                    if (onUpdateBookmark(bookmark.id, title, url)) editing = null else onNotice("HTTPS URL または検索語を入力してください。")
                }, onDismiss = { editing = null }
            )
        }
    }

    @Composable
    private fun HistoryPage(
        history: List<com.example.httpsbrowser.data.HistoryEntry>,
        onOpenUrl: (String) -> Unit,
        onDelete: (String) -> Unit,
        onBack: () -> Unit
    ) {
        PageHeader("閲覧履歴", onBack)
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (history.isEmpty()) item { EmptyRow("閲覧履歴はまだありません。") }
            items(history.take(200), key = { it.id }) { entry ->
                ListItem(
                    modifier = Modifier.clickable { onOpenUrl(entry.url) },
                    headlineContent = { Text(entry.query ?: entry.title.ifBlank { entry.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(entry.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            Icon(Icons.Default.OpenInNew, null)
                            IconButton(onClick = { onDelete(entry.id) }) { Icon(Icons.Default.Delete, "この履歴を削除") }
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun AdBlockPage(listRepository: AdBlockListRepository, onBack: () -> Unit, onNotice: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        var sources by remember { mutableStateOf<List<BlockListSource>>(emptyList()) }
        var status by remember { mutableStateOf(listRepository.blockStatus()) }
        var creating by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<BlockListSource?>(null) }
        LaunchedEffect(Unit) {
            sources = listRepository.loadAndCompile()
            status = listRepository.blockStatus()
        }
        PageHeader("広告ブロック", onBack, actionLabel = "追加") { creating = true }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text("今日の遮断件数: ${status.blockedToday} 件", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "高互換フィルタエンジン: ${if (status.engineReady) "準備済み" else "準備中または利用不可"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "読み込んだ URL 規則: ${status.networkRuleCount} 件 / cosmetic 規則: ${status.cosmeticRuleCount} 件",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("HTTPS URL のリストだけを登録できます。例外・オプション・resource type はフィルタエンジンが評価します。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (sources.isEmpty()) item { EmptyRow("広告ブロックリストはまだありません。") }
            items(sources, key = { it.id }) { source ->
                ListItem(
                    headlineContent = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(source.sourceUrl, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            Switch(checked = source.enabled, onCheckedChange = { enabled ->
                                scope.launch {
                                    listRepository.setEnabled(source.id, enabled)
                                    sources = listRepository.loadAndCompile()
                                    status = listRepository.blockStatus()
                                }
                            })
                            IconButton(onClick = { editing = source }) { Icon(Icons.Default.Edit, "リストを編集") }
                            IconButton(onClick = {
                                scope.launch {
                                    listRepository.remove(source.id)
                                    sources = listRepository.loadAndCompile()
                                    status = listRepository.blockStatus()
                                }
                            }) { Icon(Icons.Default.Delete, "リストを削除") }
                        }
                    }
                )
            }
        }
        if (creating) AdBlockEditorDialog("広告ブロックリストを追加", "", "", onDismiss = { creating = false }) { name, url ->
            scope.launch {
                listRepository.addOrUpdate(name, url).onSuccess { source ->
                    sources = listRepository.loadAndCompile(); status = listRepository.blockStatus(); creating = false; onNotice("リストを追加しました: ${source.name}")
                }.onFailure { onNotice(it.message ?: "リストを登録できませんでした。") }
            }
        }
        editing?.let { source ->
            AdBlockEditorDialog("広告ブロックリストを編集", source.name, source.sourceUrl, onDismiss = { editing = null }) { name, url ->
                scope.launch {
                    listRepository.update(source.id, name, url).onSuccess { updated ->
                        sources = listRepository.loadAndCompile(); status = listRepository.blockStatus(); editing = null; onNotice("リストを更新しました: ${updated.name}")
                    }.onFailure { onNotice(it.message ?: "リストを更新できませんでした。") }
                }
            }
        }
    }

    @Composable
    private fun DataPage(onClear: () -> Unit, onBack: () -> Unit) {
        var confirmation by remember { mutableStateOf(false) }
        PageHeader("閲覧データ", onBack)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("履歴、開いているタブ、WebView のキャッシュを消去します。ブックマークは残ります。")
            TextButton(onClick = { confirmation = true }, modifier = Modifier.padding(top = 12.dp)) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("閲覧データを消去")
            }
        }
        if (confirmation) AlertDialog(
            onDismissRequest = { confirmation = false },
            title = { Text("閲覧データを消去しますか？") },
            text = { Text("履歴、タブ、キャッシュを削除します。") },
            confirmButton = { TextButton(onClick = { onClear(); confirmation = false }) { Text("消去") } },
            dismissButton = { TextButton(onClick = { confirmation = false }) { Text("キャンセル") } }
        )
    }

    @Composable
    fun BookmarkEditorDialog(
        title: String,
        initialTitle: String,
        initialUrl: String,
        onConfirm: (String, String) -> Unit,
        onDismiss: () -> Unit
    ) {
        var bookmarkTitle by remember(title, initialTitle) { mutableStateOf(initialTitle) }
        var bookmarkUrl by remember(title, initialUrl) { mutableStateOf(initialUrl) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(value = bookmarkTitle, onValueChange = { bookmarkTitle = it }, singleLine = true, label = { Text("名前") })
                    OutlinedTextField(value = bookmarkUrl, onValueChange = { bookmarkUrl = it }, singleLine = true, label = { Text("URL または検索語") }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { onConfirm(bookmarkTitle, bookmarkUrl) }) { Text("決定") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
        )
    }

    @Composable
    private fun AdBlockEditorDialog(title: String, initialName: String, initialUrl: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
        var name by remember(title, initialName) { mutableStateOf(initialName) }
        var url by remember(title, initialUrl) { mutableStateOf(initialUrl) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("リスト名") })
                    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, label = { Text("https://…") }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { onConfirm(name, url) }) { Text("保存") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
        )
    }

    @Composable
    private fun PageHeader(title: String, onBack: () -> Unit, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "設定に戻る") }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
            }
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Icon(Icons.Default.Add, null); Text(actionLabel) }
        }
    }

    @Composable
    private fun NavigationItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
        ListItem(modifier = Modifier.clickable { onClick() }, headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Default.OpenInNew, null) })
    }

    @Composable private fun SheetTitle(text: String) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
    }

    @Composable private fun EmptyRow(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }

    @Composable private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
        ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(checked = checked, onCheckedChange = onChecked) })
    }
}
