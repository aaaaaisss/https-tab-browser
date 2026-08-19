package com.example.httpsbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.Suggestion
import com.example.httpsbrowser.data.SuggestionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBar(
    value: String,
    progress: Int,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit
) {
    var textFieldValue by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(TextFieldValue(value)) }
    androidx.compose.runtime.LaunchedEffect(value) {
        if (textFieldValue.text != value) textFieldValue = TextFieldValue(value, TextRange(value.length))
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { updated -> textFieldValue = updated; onValueChange(updated.text) },
                modifier = Modifier.weight(1f).onFocusChanged { focus ->
                    if (focus.isFocused && textFieldValue.text.isNotEmpty()) {
                        textFieldValue = textFieldValue.copy(selection = TextRange(0, textFieldValue.text.length))
                    }
                },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Google 検索または HTTPS URL を入力") },
                trailingIcon = {
                    if (value.isNotBlank()) IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "入力を消去")
                    }
                },
                placeholder = { Text("Google 検索または HTTPS URL") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() })
            )
            IconButton(onClick = onRefresh, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "再読み込み")
            }
        }
        if (progress in 1..99) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun SuggestionPanel(suggestions: List<Suggestion>, onClick: (Suggestion) -> Unit) {
    if (suggestions.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        LazyColumn(Modifier.height((suggestions.size * 56).coerceAtMost(280).dp)) {
            items(suggestions, key = { "${it.type}:${it.url}" }) { suggestion ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onClick(suggestion) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (suggestion.type) {
                            SuggestionType.OPEN_TAB -> Icons.Default.Tab
                            SuggestionType.BOOKMARK -> Icons.Default.Bookmark
                            SuggestionType.HISTORY -> Icons.Default.History
                            SuggestionType.GOOGLE_SEARCH -> Icons.Default.Search
                        },
                        contentDescription = null
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(suggestion.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(suggestion.secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun NavigationRow(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onTabs: () -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onForward: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavButton(Icons.Default.Tab, "タブ一覧", onTabs)
        NavButton(Icons.Default.ArrowBack, "戻る", onBack, enabled = canGoBack)
        NavButton(Icons.Default.Search, "Google 検索", onSearch, emphasized = true)
        NavButton(Icons.Default.ArrowForward, "進む", onForward, enabled = canGoForward)
        NavButton(Icons.Default.Settings, "設定", onSettings)
    }
}

@Composable
private fun NavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = false
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(if (emphasized) 52.dp else 48.dp),
        colors = if (emphasized) IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) else IconButtonDefaults.iconButtonColors()
    ) { Icon(icon, contentDescription = description) }
}

@Composable
fun TabBar(tabs: List<BrowserTab>, selectedTabId: String?, onSelect: (String) -> Unit, onClose: (String) -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 6.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { tab ->
                val selected = tab.id == selectedTabId
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
                        .clickable { onSelect(tab.id) }
                        .padding(start = 12.dp, end = 3.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tab.title, modifier = Modifier.width(104.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "${tab.title} を閉じる", modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(50.dp)) { Icon(Icons.Default.Add, contentDescription = "新しいタブ") }
    }
}

@Composable
fun ShortcutDock(
    isBookmarked: Boolean,
    onHistory: () -> Unit,
    onBookmarks: () -> Unit,
    onDownloads: () -> Unit,
    onShare: () -> Unit,
    onBookmark: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ShortcutButton(if (isBookmarked) Icons.Default.Star else Icons.Default.Bookmark, "ブックマーク", onBookmark)
        ShortcutButton(Icons.Default.History, "履歴", onHistory)
        ShortcutButton(Icons.Default.Download, "ダウンロード", onDownloads)
        ShortcutButton(Icons.Default.Share, "共有", onShare)
        ShortcutButton(Icons.Default.Bookmark, "ブックマーク一覧", onBookmarks)
    }
}

@Composable
private fun ShortcutButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), CircleShape),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    ) { Icon(icon, contentDescription = label) }
}

@Composable
fun ScrollButtons(onTop: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onBottom: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ShortcutButton(Icons.Default.ArrowUpward, "ページ先頭", onTop)
        ShortcutButton(Icons.Default.ArrowUpward, "上へスクロール", onUp)
        ShortcutButton(Icons.Default.ArrowDownward, "下へスクロール", onDown)
        ShortcutButton(Icons.Default.ArrowDownward, "ページ末尾", onBottom)
    }
}
