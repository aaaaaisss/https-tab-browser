package com.example.httpsbrowser.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.httpsbrowser.data.AddressDisplayMode
import com.example.httpsbrowser.data.Bookmark
import com.example.httpsbrowser.data.BrowserRepository
import com.example.httpsbrowser.data.BrowserSettings
import com.example.httpsbrowser.data.BrowserTab
import com.example.httpsbrowser.data.BrowserUiState
import com.example.httpsbrowser.data.HistoryEntry
import com.example.httpsbrowser.data.PreparedNavigation
import com.example.httpsbrowser.data.Suggestion
import com.example.httpsbrowser.data.SuggestionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BrowserRepository(application)
    private var persistJob: Job? = null

    var uiState by mutableStateOf(BrowserUiState())
        private set

    init {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { repository.load() }
            val selected = stored.selectedTabId?.takeIf { id -> stored.tabs.any { it.id == id } }
                ?: stored.tabs.first().id
            uiState = BrowserUiState(
                tabs = stored.tabs,
                selectedTabId = selected,
                addressInput = stored.tabs.firstOrNull { it.id == selected }?.displayText
                    .orEmpty()
                    .ifBlank { stored.tabs.firstOrNull { it.id == selected }?.url.orEmpty() },
                history = stored.history,
                bookmarks = stored.bookmarks,
                settings = stored.settings
            )
        }
    }

    fun selectTab(id: String) {
        val tab = uiState.tabs.firstOrNull { it.id == id } ?: return
        uiState = uiState.copy(selectedTabId = id, addressInput = tab.displayText.ifBlank { tab.url }, isTabSheetVisible = false)
        persistSoon()
    }

    fun addTab(url: String = GOOGLE_HOME): BrowserTab {
        val secureUrl = secureUrl(url) ?: GOOGLE_HOME
        val tab = BrowserTab(url = secureUrl, displayText = secureUrl, lastRequestedUrl = secureUrl)
        uiState = uiState.copy(tabs = uiState.tabs + tab, selectedTabId = tab.id, addressInput = tab.displayText)
        persistSoon()
        return tab
    }

    /** 最後のタブを閉じた場合も Google の空タブを残す。 */
    fun closeTab(id: String): BrowserTab? {
        val current = uiState.tabs
        val closingIndex = current.indexOfFirst { it.id == id }
        if (closingIndex < 0) return null
        val remaining = current.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val newTab = BrowserTab(url = GOOGLE_HOME, displayText = GOOGLE_HOME)
            uiState = uiState.copy(tabs = listOf(newTab), selectedTabId = newTab.id, addressInput = newTab.displayText)
        } else {
            val next = remaining.getOrElse((closingIndex - 1).coerceAtLeast(0)) { remaining.last() }
            uiState = uiState.copy(tabs = remaining, selectedTabId = next.id, addressInput = next.displayText.ifBlank { next.url })
        }
        persistSoon()
        return current[closingIndex]
    }

    fun setAddressInput(value: String) {
        uiState = uiState.copy(addressInput = value, suggestions = createSuggestions(value))
    }

    fun clearSuggestions() {
        uiState = uiState.copy(suggestions = emptyList())
    }

    fun prepareNavigation(input: String = uiState.addressInput): PreparedNavigation? {
        val value = input.trim()
        if (value.isBlank()) return null
        val prepared = if (looksLikeUrl(value)) {
            val url = secureUrl(value) ?: return null
            PreparedNavigation(url, url, AddressDisplayMode.URL)
        } else {
            val encoded = URLEncoder.encode(value, Charsets.UTF_8.name())
            PreparedNavigation(
                url = "https://www.google.com/search?q=$encoded",
                displayText = value,
                displayMode = AddressDisplayMode.SEARCH
            )
        }
        updateSelected { it.copy(
            lastRequestedUrl = prepared.url,
            displayText = prepared.displayText,
            displayMode = prepared.displayMode
        ) }
        uiState = uiState.copy(addressInput = prepared.displayText, suggestions = emptyList())
        persistSoon()
        return prepared
    }

    fun onPageStarted(tabId: String, url: String) {
        if (!isHttps(url)) return
        updateTab(tabId) { it.copy(lastRequestedUrl = url) }
    }

    fun onPageFinished(tabId: String, url: String, title: String?) {
        if (!isHttps(url)) return
        updateTab(tabId) { tab ->
            val googleQuery = googleSearchQuery(url)
            tab.copy(
                url = url,
                title = title?.takeIf(String::isNotBlank) ?: tab.title,
                displayText = googleQuery ?: url,
                displayMode = if (googleQuery != null) AddressDisplayMode.SEARCH else AddressDisplayMode.URL
            )
        }
        val tab = uiState.tabs.firstOrNull { it.id == tabId } ?: return
        val entry = HistoryEntry(title = tab.title, url = url, query = tab.displayText.takeIf { tab.displayMode == AddressDisplayMode.SEARCH })
        uiState = uiState.copy(history = listOf(entry) + uiState.history.filterNot { it.url == url }.take(499))
        if (uiState.selectedTabId == tabId) uiState = uiState.copy(addressInput = tab.displayText)
        persistSoon()
    }

    fun onTitleChanged(tabId: String, title: String) {
        updateTab(tabId) { it.copy(title = title.ifBlank { it.title }) }
        persistSoon()
    }

    fun onHistoryStateChanged(tabId: String, canGoBack: Boolean, canGoForward: Boolean) {
        updateTab(tabId) { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun openSuggestion(suggestion: Suggestion): PreparedNavigation {
        return when (suggestion.type) {
            SuggestionType.GOOGLE_SEARCH -> prepareNavigation(suggestion.primary)!!
            else -> {
                val prepared = PreparedNavigation(suggestion.url, suggestion.primary, AddressDisplayMode.URL)
                updateSelected { it.copy(lastRequestedUrl = prepared.url, displayText = prepared.displayText, displayMode = prepared.displayMode) }
                uiState = uiState.copy(addressInput = prepared.displayText, suggestions = emptyList())
                persistSoon()
                prepared
            }
        }
    }

    fun toggleTabSheet() { uiState = uiState.copy(isTabSheetVisible = !uiState.isTabSheetVisible) }
    fun toggleSettingsSheet() { uiState = uiState.copy(isSettingsSheetVisible = !uiState.isSettingsSheetVisible) }
    fun setFullscreen(value: Boolean) { uiState = uiState.copy(isFullscreen = value) }

    fun updateSettings(transform: (BrowserSettings) -> BrowserSettings) {
        uiState = uiState.copy(settings = transform(uiState.settings))
        persistSoon()
    }

    fun toggleBookmark() {
        val tab = uiState.selectedTab ?: return
        val existing = uiState.bookmarks.firstOrNull { it.url == tab.url }
        uiState = if (existing == null) {
            uiState.copy(bookmarks = listOf(Bookmark(title = tab.title, url = tab.url)) + uiState.bookmarks)
        } else {
            uiState.copy(bookmarks = uiState.bookmarks.filterNot { it.id == existing.id })
        }
        persistSoon()
    }

    fun isBookmarked(url: String): Boolean = uiState.bookmarks.any { it.url == url }

    fun clearBrowsingData(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.clearBrowsingData(keepBookmarks = true) }
            val newTab = BrowserTab(url = GOOGLE_HOME, displayText = GOOGLE_HOME)
            uiState = uiState.copy(tabs = listOf(newTab), selectedTabId = newTab.id, addressInput = GOOGLE_HOME, history = emptyList())
            onDone()
        }
    }

    private fun updateSelected(transform: (BrowserTab) -> BrowserTab) {
        uiState.selectedTab?.let { selected -> updateTab(selected.id, transform) }
    }

    private fun updateTab(id: String, transform: (BrowserTab) -> BrowserTab) {
        uiState = uiState.copy(tabs = uiState.tabs.map { if (it.id == id) transform(it) else it })
    }

    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            repository.save(uiState)
        }
    }

    private fun createSuggestions(input: String): List<Suggestion> {
        val needle = input.trim().lowercase()
        if (needle.isBlank()) return emptyList()
        val results = linkedMapOf<String, Suggestion>()
        uiState.tabs.filter { it.title.contains(needle, true) || it.url.contains(needle, true) }.forEach {
            results.putIfAbsent(it.url, Suggestion(it.title, it.url, it.url, SuggestionType.OPEN_TAB))
        }
        uiState.bookmarks.filter { it.title.contains(needle, true) || it.url.contains(needle, true) }.forEach {
            results.putIfAbsent(it.url, Suggestion(it.title, it.url, it.url, SuggestionType.BOOKMARK))
        }
        uiState.history.filter { it.title.contains(needle, true) || it.url.contains(needle, true) || it.query?.contains(needle, true) == true }.forEach {
            results.putIfAbsent(it.url, Suggestion(it.query ?: it.title, it.url, it.url, SuggestionType.HISTORY))
        }
        results["google:$input"] = Suggestion("Google 検索: $input", "Google", input, SuggestionType.GOOGLE_SEARCH)
        return results.values.take(8)
    }

    private fun looksLikeUrl(value: String): Boolean = value.startsWith("http://", true) ||
        value.startsWith("https://", true) ||
        (value.none(Char::isWhitespace) && value.contains('.') && !value.startsWith("."))

    private fun secureUrl(raw: String): String? = runCatching {
        val withoutHttp = raw.trim().removePrefix("http://").removePrefix("HTTP://")
        val candidate = if (withoutHttp.startsWith("https://", true)) withoutHttp else "https://$withoutHttp"
        val uri = URI(candidate)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
        uri.toString()
    }.getOrNull()

    private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)
    private fun googleSearchQuery(url: String): String? = runCatching {
        val uri = Uri.parse(url)
        if (uri.host?.endsWith("google.com") == true && uri.path == "/search") {
            uri.getQueryParameter("q")?.takeIf(String::isNotBlank)
        } else null
    }.getOrNull()

    companion object {
        const val GOOGLE_HOME = "https://www.google.com/"
    }
}
