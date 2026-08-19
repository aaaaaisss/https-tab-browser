package com.example.httpsbrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
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
import com.example.httpsbrowser.data.BrowserSettings
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.BrowserUiState
import com.example.httpsbrowser.data.BlockListSource
import kotlinx.coroutines.launch

object BrowserSheets {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TabSheet(
        tabs: List<BrowserTab>,
        selectedTabId: String?,
        onSelect: (String) -> Unit,
        onClose: (String) -> Unit,
        onNewTab: () -> Unit,
        onDismiss: () -> Unit
    ) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("タブ一覧", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onNewTab) { Icon(Icons.Default.Add, null); Text("新しいタブ") }
            }
            LazyColumn(Modifier.padding(bottom = 32.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(tab.id) },
                        headlineContent = { Text(tab.title.ifBlank { "新しいタブ" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(tab.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row {
                                if (tab.id == selectedTabId) Text("選択中", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                                IconButton(onClick = { onClose(tab.id) }) { Icon(Icons.Default.Close, "タブを閉じる") }
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
        onClear: () -> Unit,
        onOpenUrl: (String) -> Unit,
        onDismiss: () -> Unit,
        onNotice: (String) -> Unit
    ) {
        val scope = rememberCoroutineScope()
        var listUrl by remember { mutableStateOf("") }
        var listName by remember { mutableStateOf("") }
        var sources by remember { mutableStateOf<List<BlockListSource>>(emptyList()) }
        var clearConfirmation by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { sources = listRepository.loadAndCompile() }

        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(Modifier.padding(bottom = 36.dp)) {
                item { SheetTitle("設定と閲覧データ") }
                item {
                    SettingSwitch("ページを強制的に暗色化", state.settings.forceDarkPages) {
                        onSettings { settings -> settings.copy(forceDarkPages = it) }
                    }
                }
                item {
                    SettingSwitch("広告 URL ルールをブロック", state.settings.adBlockingEnabled) {
                        onSettings { settings -> settings.copy(adBlockingEnabled = it) }
                    }
                }
                item {
                    SettingSwitch("JavaScript を有効化", state.settings.javascriptEnabled) {
                        onSettings { settings -> settings.copy(javascriptEnabled = it) }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item { SheetTitle("広告ブロックリスト（HTTPS URL のみ）") }
                item {
                    OutlinedTextField(
                        value = listName,
                        onValueChange = { listName = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        label = { Text("リスト名") }
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        OutlinedTextField(
                            value = listUrl,
                            onValueChange = { listUrl = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("https://…") }
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            scope.launch {
                                listRepository.addOrUpdate(listName, listUrl)
                                    .onSuccess { source ->
                                        sources = listRepository.loadAndCompile()
                                        listName = ""; listUrl = ""
                                        onNotice("リストを更新しました: ${source.name}")
                                    }
                                    .onFailure { onNotice(it.message ?: "リストを登録できませんでした。") }
                            }
                        }) { Text("追加") }
                    }
                }
                items(sources, key = { it.id }) { source ->
                    ListItem(
                        headlineContent = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(source.sourceUrl, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row {
                                Switch(checked = source.enabled, onCheckedChange = { enabled ->
                                    scope.launch { listRepository.setEnabled(source.id, enabled); sources = listRepository.loadAndCompile() }
                                })
                                IconButton(onClick = {
                                    scope.launch { listRepository.remove(source.id); sources = listRepository.loadAndCompile() }
                                }) { Icon(Icons.Default.Delete, "リストを削除") }
                            }
                        }
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item { SheetTitle("ブックマーク") }
                if (state.bookmarks.isEmpty()) item { EmptyRow("ブックマークはまだありません。") }
                items(state.bookmarks, key = { it.id }) { bookmark ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenUrl(bookmark.url) },
                        headlineContent = { Text(bookmark.title.ifBlank { bookmark.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(bookmark.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Icon(Icons.Default.OpenInNew, null) }
                    )
                }
                item { SheetTitle("閲覧履歴") }
                if (state.history.isEmpty()) item { EmptyRow("閲覧履歴はまだありません。") }
                items(state.history.take(100), key = { it.id }) { entry ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenUrl(entry.url) },
                        headlineContent = { Text(entry.query ?: entry.title.ifBlank { entry.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(entry.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Icon(Icons.Default.OpenInNew, null) }
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item {
                    TextButton(onClick = { clearConfirmation = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("履歴・タブ・キャッシュを消去")
                    }
                }
            }
        }

        if (clearConfirmation) {
            AlertDialog(
                onDismissRequest = { clearConfirmation = false },
                title = { Text("閲覧データを消去しますか？") },
                text = { Text("履歴、開いているタブ、WebView のキャッシュを削除します。ブックマークは残ります。") },
                confirmButton = { TextButton(onClick = { onClear(); clearConfirmation = false; onDismiss() }) { Text("消去") } },
                dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("キャンセル") } }
            )
        }
    }

    @Composable private fun SheetTitle(text: String) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
    }

    @Composable private fun EmptyRow(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }

    @Composable private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
        ListItem(
            headlineContent = { Text(label) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChecked) }
        )
    }
}
