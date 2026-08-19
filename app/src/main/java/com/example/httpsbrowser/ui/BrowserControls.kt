package com.example.httpsbrowser.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.httpsbrowser.data.Bookmark
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.Suggestion
import com.example.httpsbrowser.data.SuggestionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.roundToInt

private val BottomBarBlack = Color(0xFF05070A)
private val BottomBarButton = Color(0xFF1C2531)
private val BottomBarButtonEmphasis = Color(0xFF2C5C92)
private val BottomBarText = Color(0xFFF2F6FC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBar(
    value: String,
    progress: Int,
    isEditing: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onTranslate: () -> Unit,
    onRefresh: () -> Unit,
    onEditingStarted: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    LaunchedEffect(value) {
        if (textFieldValue.text != value) textFieldValue = TextFieldValue(value, TextRange(value.length))
    }
    LaunchedEffect(isEditing) {
        if (isEditing) {
            textFieldValue = TextFieldValue(value, TextRange(0, value.length))
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus(force = true)
        }
    }

    Column(
        Modifier.fillMaxWidth().background(BottomBarBlack).padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { updated -> textFieldValue = updated; onValueChange(updated.text) },
                modifier = Modifier.weight(1f).height(48.dp).focusRequester(focusRequester).onFocusChanged { focus ->
                    if (focus.isFocused && !isEditing) onEditingStarted()
                },
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF505050),
                    unfocusedContainerColor = Color(0xFF404040),
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedPlaceholderColor = Color(0xFFD0D0D0),
                    unfocusedPlaceholderColor = Color(0xFFD0D0D0),
                    focusedLeadingIconColor = Color.White,
                    unfocusedLeadingIconColor = Color.White,
                    focusedTrailingIconColor = Color.White,
                    unfocusedTrailingIconColor = Color.White
                ),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Google 検索または HTTPS URL を入力") },
                trailingIcon = {
                    if (textFieldValue.text.isNotBlank()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "入力を消去")
                    }
                },
                placeholder = { Text("Google 検索または HTTPS URL") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() })
            )
            IconButton(
                onClick = onTranslate,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = BottomBarButton, contentColor = BottomBarText)
            ) { Icon(Icons.Default.Translate, contentDescription = "このページを日本語へ翻訳") }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = BottomBarButton, contentColor = BottomBarText)
            ) { Icon(Icons.Default.Refresh, contentDescription = "再読み込み") }
        }
        if (progress in 1..99) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
fun SuggestionPanel(suggestions: List<Suggestion>, onClick: (Suggestion) -> Unit) {
    if (suggestions.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        LazyColumn(
            modifier = Modifier.height((suggestions.size * 48).coerceAtMost(216).dp),
            reverseLayout = true
        ) {
            items(suggestions, key = { "${it.type}:${it.url}" }) { suggestion ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onClick(suggestion) }.padding(horizontal = 14.dp, vertical = 7.dp),
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
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(suggestion.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (suggestion.secondary.isNotBlank()) {
                            Text(suggestion.secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
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
    onBookmark: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().background(BottomBarBlack).padding(horizontal = 8.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavButton(Icons.Default.Tab, "タブ一覧", onTabs)
        NavButton(Icons.Default.ArrowBack, "戻る", onBack, enabled = canGoBack)
        NavButton(Icons.Default.Search, "アドレスバーを編集", onSearch, emphasized = true)
        NavButton(Icons.Default.ArrowForward, "進む", onForward, enabled = canGoForward)
        Box {
            NavButton(Icons.Default.Menu, "メニュー", { menuExpanded = true })
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("ブックマーク") }, leadingIcon = { Icon(Icons.Default.Bookmark, null) }, onClick = { menuExpanded = false; onBookmark() })
                DropdownMenuItem(text = { Text("履歴") }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { menuExpanded = false; onHistory() })
                DropdownMenuItem(text = { Text("ダウンロード") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menuExpanded = false; onDownloads() })
                DropdownMenuItem(text = { Text("共有") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menuExpanded = false; onShare() })
                DropdownMenuItem(text = { Text("設定") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { menuExpanded = false; onSettings() })
            }
        }
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
        modifier = Modifier.size(if (emphasized) 44.dp else 40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (emphasized) BottomBarButtonEmphasis else BottomBarButton,
            contentColor = BottomBarText,
            disabledContainerColor = Color(0xFF131820),
            disabledContentColor = Color(0xFF657080)
        )
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(if (emphasized) 24.dp else 21.dp)) }
}

@Composable
fun TabBar(tabs: List<BrowserTab>, selectedTabId: String?, onSelect: (String) -> Unit, onClose: (String) -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(BottomBarBlack).padding(start = 5.dp, top = 2.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            tabs.forEach { tab ->
                val selected = tab.id == selectedTabId
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Color(0xFF18375B) else Color(0xFF1A2029))
                        .then(if (selected) Modifier.border(2.dp, Color(0xFF66B5FF), RoundedCornerShape(12.dp)) else Modifier.border(1.dp, Color(0xFF394554), RoundedCornerShape(12.dp)))
                        .clickable { onSelect(tab.id) }
                        .padding(start = 8.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tab.title.ifBlank { "ホーム" },
                        color = BottomBarText,
                        modifier = Modifier.width(46.dp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(25.dp), colors = IconButtonDefaults.iconButtonColors(contentColor = BottomBarText)) {
                        Icon(Icons.Default.Close, contentDescription = "${tab.title} を閉じる", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(34.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = BottomBarButton, contentColor = BottomBarText)) {
            Icon(Icons.Default.Add, contentDescription = "新しいタブ")
        }
    }
}

@Composable
fun RightEdgeScrollRail(
    currentFraction: Float,
    onScrollToFraction: (Float) -> Unit
) {
    val density = LocalDensity.current
    val trackHeight = 148.dp
    val normalThumbHeight = 20.dp
    val activeThumbHeight = 32.dp
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(currentFraction) }
    val thumbHeight by animateDpAsState(
        targetValue = if (isDragging) activeThumbHeight else normalThumbHeight,
        label = "scrollThumbHeight"
    )
    val railWidth by animateDpAsState(
        targetValue = if (isDragging) 22.dp else 10.dp,
        label = "scrollRailWidth"
    )
    val usableTrackPx = with(density) { (trackHeight - thumbHeight).toPx() }

    LaunchedEffect(currentFraction, isDragging) {
        if (!isDragging) dragFraction = currentFraction.coerceIn(0f, 1f)
    }
    val dragState = rememberDraggableState { delta ->
        dragFraction = (dragFraction + delta / usableTrackPx).coerceIn(0f, 1f)
        onScrollToFraction(dragFraction)
    }
    Box(
        modifier = Modifier
            .width(railWidth)
            .height(trackHeight)
            .alpha(if (isDragging) 0.92f else 0.38f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDragging) Color(0x44212A35) else Color.Transparent)
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = { isDragging = true },
                onDragStopped = { isDragging = false }
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (usableTrackPx * dragFraction).roundToInt()) }
                .height(thumbHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isDragging) Color(0xFF89C4FF) else Color(0xAA89C4FF))
        )
    }
}

@Composable
fun BookmarkFavicon(url: String, title: String, modifier: Modifier = Modifier) {
    var favicon by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        favicon = withContext(Dispatchers.IO) { loadFavicon(url) }
    }
    Box(
        modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (favicon != null) {
            Image(
                bitmap = favicon!!,
                contentDescription = "$title のサイトアイコン",
                modifier = Modifier.fillMaxSize().padding(5.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Icon(
                Icons.Default.Bookmark,
                contentDescription = "$title のサイトアイコン",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun loadFavicon(pageUrl: String): ImageBitmap? = runCatching {
    val pageUri = URI(pageUrl)
    if (!pageUri.scheme.equals("https", ignoreCase = true) || pageUri.host.isNullOrBlank()) return null
    val faviconUri = URI("https", null, pageUri.host, pageUri.port, "/favicon.ico", null, null)
    val connection = (faviconUri.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 3_000
        readTimeout = 3_000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) HTTPS-Tab-Browser/1.0")
    }
    connection.inputStream.use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
}.getOrNull()

@Composable
fun HomeScreen(bookmarks: List<Bookmark>, onOpenBookmark: (Bookmark) -> Unit, onAddBookmark: () -> Unit) {
    val cells = bookmarks.take(24).map { HomeCell.BookmarkCell(it) } + HomeCell.AddCell
    val rows = cells.chunked(4)
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.End
        ) {
            rows.reversed().forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.reversed().forEach { cell ->
                        when (cell) {
                            is HomeCell.BookmarkCell -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(64.dp).clickable { onOpenBookmark(cell.bookmark) }
                            ) {
                                BookmarkFavicon(
                                    url = cell.bookmark.url,
                                    title = cell.bookmark.title.ifBlank { cell.bookmark.url },
                                    modifier = Modifier.size(46.dp)
                                )
                                Text(
                                    cell.bookmark.title.ifBlank { cell.bookmark.url },
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            HomeCell.AddCell -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(64.dp).clickable { onAddBookmark() }
                            ) {
                                Box(
                                    modifier = Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF3D3D3D)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Default.Add, contentDescription = "ブックマークを追加", tint = Color.White) }
                                Text("追加", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface HomeCell {
    data class BookmarkCell(val bookmark: Bookmark) : HomeCell
    data object AddCell : HomeCell
}
