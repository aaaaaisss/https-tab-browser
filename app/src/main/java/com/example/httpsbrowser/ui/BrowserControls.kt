package com.example.httpsbrowser.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun AddressBar(
    value: String,
    progress: Int,
    isEditing: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    focusRequester: FocusRequester,
    onTranslate: () -> Unit,
    onRefresh: () -> Unit,
    onEditingStarted: () -> Unit,
    onEditingStopped: () -> Unit
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(value) {
        if (textFieldValue.text != value) textFieldValue = TextFieldValue(value, TextRange(value.length))
    }

    Column(Modifier.fillMaxWidth().background(BottomBarBlack).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = { updated -> textFieldValue = updated; onValueChange(updated.text) },
                modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(Color(0xFF474747))
                    .focusRequester(focusRequester).onFocusChanged { focus ->
                        if (focus.isFocused) {
                            textFieldValue = textFieldValue.copy(selection = TextRange(textFieldValue.text.length), composition = null)
                            if (!isEditing) onEditingStarted()
                        }
                    },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontSize = 14.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(Color.White),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(textFieldValue.text) }),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Google 検索または HTTPS URL を入力", modifier = Modifier.size(18.dp), tint = Color.White)
                        Box(modifier = Modifier.weight(1f).padding(horizontal = 7.dp)) {
                            if (textFieldValue.text.isBlank()) Text("Google 検索または HTTPS URL", color = Color(0xFFD0D0D0), fontSize = 13.sp, maxLines = 1)
                            innerTextField()
                        }
                        if (textFieldValue.text.isNotBlank()) {
                            IconButton(onClick = {
                                if (!isEditing) onEditingStarted()
                                textFieldValue = TextFieldValue("", TextRange(0))
                                onValueChange("")
                                if (!imeVisible) {
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "入力を消去", modifier = Modifier.size(18.dp), tint = Color.White)
                            }
                        }
                    }
                }
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
            modifier = Modifier.height((suggestions.size * 48).coerceAtMost(288).dp),
            reverseLayout = true
        ) {
            items(suggestions, key = { "${it.type}:${it.url}" }) { suggestion ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onClick(suggestion) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (suggestion.type) {
                            SuggestionType.HISTORY -> Icons.Default.History
                            SuggestionType.BOOKMARK -> Icons.Default.Bookmark
                            SuggestionType.SEARCH -> Icons.Default.Search
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(suggestion.title.ifBlank { suggestion.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (suggestion.title.isNotBlank() && suggestion.url.isNotBlank()) {
                            Text(suggestion.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
