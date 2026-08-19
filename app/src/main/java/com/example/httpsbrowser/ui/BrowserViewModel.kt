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
import com.example.httpsbrowser.data.SettingsPage
import com.example.httpsbrowser.data.Suggestion
import com.example.httpsbrowser.data.SuggestionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BrowserRepository(application)
    private var persistJob: Job? = null
    private var suggestionJob: Job? = null

    var uiState by mutableStateOf(BrowserUiState())
        private set

    init {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { repository.load() }
            val normalizedTabs = stored.tabs.ifEmpty { listOf(homeTab()) }.map { tab ->
                val isLegacyStartTab = tab.url == GOOGLE_HOME && tab.title == "新しいタブ"
                if (tab.url.isBlank() || tab.isHome || isLegacyStartTab) tab.copy(
                    url = "", lastRequestedUrl = "", displayText = "", title = tab.title.ifBlank { "ホーム" }, isHome = true
                ) else tab.copy(isHome = false)
            }
            val selected = stored.selectedTabId?.takeIf { id -> normalizedTabs.any { it.id == id } }
                ?: normalizedTabs.first().id
            uiState = BrowserUiState(
                tabs = normalizedTabs,
                selectedTabId = selected,
                addressInput = normalizedTabs.firstOrNull { it.id == selected }?.displayText.orEmpty(),
                history = stored.history,
                bookmarks = stored.bookmarks,
                settings = stored.settings
            )
        }
    }

    fun selectTab(id: String) {
        val tab = uiState.tabs.firstOrNull { it.id == id } ?: return
        uiState = uiState.copy(
            selectedTabId = id,
            addressInput = if (tab.isHome) "" else tab.displayText.ifBlank { tab.url },
            isAddressFocused = false,
            suggestions = emptyList(),
            isTabSheetVisible = false
        )
        persistSoon()
    }

    fun addTab(url: String = ""): BrowserTab {
        val prepared = if (url.isBlank()) null else buildNavigation(url)
        val tab = if (prepared == null) homeTab() else BrowserTab(
            url = prepared.url,
            title = prepared.displayText.ifBlank { "新しいタブ" },
            displayText = prepared.displayText,
            displayMode = prepared.displayMode,
            lastRequestedUrl = prepared.url,
            isHome = false
        )
        uiState = uiState.copy(
            tabs = uiState.tabs + tab,
            selectedTabId = tab.id,
            addressInput = tab.displayText,
            isAddressFocused = false,
            suggestions = emptyList()
        )
        persistSoon()
        return tab
    }

    fun closeTab(id: String): BrowserTab? {
        val current = uiState.tabs
        val closingIndex = current.indexOfFirst { it.id == id }
        if (closingIndex < 0) return null
        val remaining = current.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val newTab = homeTab()
            uiState = uiState.copy(tabs = listOf(newTab), selectedTabId = newTab.id, addressInput = "")
        } else {
            val next = remaining.getOrElse((closingIndex - 1).coerceAtLeast(0)) { remaining.last() }
            uiState = uiState.copy(
                tabs = remaining,
                selectedTabId = next.id,
                addressInput = if (next.isHome) "" else next.displayText.ifBlank { next.url },
                isAddressFocused = false,
                suggestions = emptyList()
            )
        }
        persistSoon()
        return current[closingIndex]
    }

    fun startAddressEditing() {
        val tab = uiState.selectedTab ?: return
        uiState = uiState.copy(
            addressInput = if (tab.isHome) "" else tab.displayText.ifBlank { tab.url },
            isAddressFocused = true,
            suggestions = emptyList()
        )
    }

    fun stopAddressEditing() {
        val tab = uiState.selectedTab ?: return
        uiState = uiState.copy(
            addressInput = if (tab.isHome) "" else tab.displayText.ifBlank { tab.url },
            isAddressFocused = false,
            suggestions = emptyList()
        )
    }

    fun setAddressInput(value: String) {
        val query = value.trim()
        // まず端末内の履歴・ブックマーク候補を即時表示し、通信待ちで候補欄が消えないようにする。
        uiState = uiState.copy(addressInput = value, isAddressFocused = true, suggestions = createSuggestions(value))
        suggestionJob?.cancel()
        if (query.length < 2) return
        suggestionJob = viewModelScope.launch {
            delay(180)
            val googleQueries = withContext(Dispatchers.IO) { fetchGoogleSuggestions(query) }
            // 古い入力に対する応答や、編集終了後の応答では画面を上書きしない。
            if (uiState.isAddressFocused && uiState.addressInput == value) {
                uiState = uiState.copy(suggestions = createSuggestions(value, googleQueries))
            }
        }
    }

    fun prepareNavigation(input: String = uiState.addressInput): PreparedNavigation? {
        val prepared = buildNavigation(input) ?: return null
        updateSelected { it.copy(
            url = prepared.url,
            lastRequestedUrl = prepared.url,
            displayText = prepared.displayText,
            displayMode = prepared.displayMode,
            isHome = false
        ) }
        uiState = uiState.copy(addressInput = prepared.displayText, isAddressFocused = false, suggestions = emptyList())
        persistSoon()
        return prepared
    }

    fun openHome() {
        updateSelected { tab ->
            tab.copy(url = "", lastRequestedUrl = "", title = "ホーム", displayText = "", displayMode = AddressDisplayMode.URL, isHome = true, canGoBack = false, canGoForward = false)
        }
        uiState = uiState.copy(addressInput = "", isAddressFocused = false, suggestions = emptyList())
        persistSoon()
    }

    fun onPageStarted(tabId: String, url: String) {
        if (!isHttps(url)) return
        updateTab(tabId) { it.copy(lastRequestedUrl = url, isHome = false) }
    }

    fun onPageFinished(tabId: String, url: String, title: String?) {
        if (!isHttps(url)) return
        updateTab(tabId) { tab ->
            val googleQuery = googleSearchQuery(url)
            tab.copy(
                url = url,
                title = title?.takeIf(String::isNotBlank) ?: tab.title,
                displayText = googleQuery ?: url,
                displayMode = if (googleQuery != null) AddressDisplayMode.SEARCH else AddressDisplayMode.URL,
                isHome = false
            )
        }
        val tab = uiState.tabs.firstOrNull { it.id == tabId } ?: return
        val entry = HistoryEntry(title = tab.title, url = url, query = tab.displayText.takeIf { tab.displayMode == AddressDisplayMode.SEARCH })
        uiState = uiState.copy(history = listOf(entry) + uiState.history.filterNot { it.url == url }.take(499))
        if (uiState.selectedTabId == tabId && !uiState.isAddressFocused) uiState = uiState.copy(addressInput = tab.displayText)
        persistSoon()
    }

    fun onTitleChanged(tabId: String, title: String) {
        updateTab(tabId) { it.copy(title = title.ifBlank { it.title }) }
        persistSoon()
    }

    fun onHistoryStateChanged(tabId: String, canGoBack: Boolean, canGoForward: Boolean) {
        updateTab(tabId) { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun openSuggestion(suggestion: Suggestion): PreparedNavigation? = when (suggestion.type) {
        SuggestionType.GOOGLE_SEARCH -> prepareNavigation(suggestion.url)
        else -> prepareNavigation(suggestion.url)
    }

    fun toggleTabSheet() { uiState = uiState.copy(isTabSheetVisible = !uiState.isTabSheetVisible) }

    fun openSettings(page: SettingsPage = SettingsPage.ROOT) {
        uiState = uiState.copy(isSettingsSheetVisible = true, settingsPage = page, isAddressFocused = false, suggestions = emptyList())
    }

    fun closeSettings() { uiState = uiState.copy(isSettingsSheetVisible = false, settingsPage = SettingsPage.ROOT) }
    fun showSettingsPage(page: SettingsPage) { uiState = uiState.copy(settingsPage = page) }
    fun backFromSettingsPage() {
        uiState = if (uiState.settingsPage == SettingsPage.ROOT) uiState.copy(isSettingsSheetVisible = false)
        else uiState.copy(settingsPage = SettingsPage.ROOT)
    }

    fun setFullscreen(value: Boolean) { uiState = uiState.copy(isFullscreen = value) }

    fun updateSettings(transform: (BrowserSettings) -> BrowserSettings) {
        uiState = uiState.copy(settings = transform(uiState.settings))
        persistSoon()
    }

    fun addBookmark(title: String, url: String): Boolean {
        val prepared = buildNavigation(url) ?: return false
        val existing = uiState.bookmarks.firstOrNull { it.url == prepared.url }
        val bookmark = Bookmark(id = existing?.id ?: java.util.UUID.randomUUID().toString(), title = title.trim().ifBlank { prepared.displayText.ifBlank { prepared.url } }, url = prepared.url, createdAt = existing?.createdAt ?: System.currentTimeMillis())
        uiState = uiState.copy(bookmarks = listOf(bookmark) + uiState.bookmarks.filterNot { it.id == bookmark.id })
        persistSoon()
        return true
    }

    fun updateBookmark(id: String, title: String, url: String): Boolean {
        val prepared = buildNavigation(url) ?: return false
        val old = uiState.bookmarks.firstOrNull { it.id == id } ?: return false
        val updated = old.copy(title = title.trim().ifBlank { prepared.displayText.ifBlank { prepared.url } }, url = prepared.url)
        uiState = uiState.copy(bookmarks = uiState.bookmarks.map { if (it.id == id) updated else it })
        persistSoon()
        return true
    }

    fun removeBookmark(id: String) {
        uiState = uiState.copy(bookmarks = uiState.bookmarks.filterNot { it.id == id })
        persistSoon()
    }

    fun isBookmarked(url: String): Boolean = url.isNotBlank() && uiState.bookmarks.any { it.url == url }

    fun clearBrowsingData(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.clearBrowsingData(keepBookmarks = true) }
            val newTab = homeTab()
            uiState = uiState.copy(tabs = listOf(newTab), selectedTabId = newTab.id, addressInput = "", history = emptyList(), isAddressFocused = false, suggestions = emptyList())
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

    private fun createSuggestions(input: String, googleQueries: List<String> = emptyList()): List<Suggestion> {
        val needle = input.trim().lowercase()
        if (needle.isBlank()) return emptyList()
        val results = linkedMapOf<String, Suggestion>()
        uiState.tabs.filter { !it.isHome && (it.title.contains(needle, true) || it.url.contains(needle, true)) }.forEach {
            results.putIfAbsent(it.url, Suggestion(it.title, it.url, it.url, SuggestionType.OPEN_TAB))
        }
        uiState.bookmarks.filter { it.title.contains(needle, true) || it.url.contains(needle, true) }.forEach {
            results.putIfAbsent(it.url, Suggestion(it.title, it.url, it.url, SuggestionType.BOOKMARK))
        }
        uiState.history.filter { it.title.contains(needle, true) || it.url.contains(needle, true) || it.query?.contains(needle, true) == true }.forEach {
            results.putIfAbsent(it.url, Suggestion(it.query ?: it.title, it.url, it.url, SuggestionType.HISTORY))
        }
        googleQueries.forEach { query ->
            results.putIfAbsent("google:$query", Suggestion("Google 検索: $query", "Google の候補", query, SuggestionType.GOOGLE_SEARCH))
        }
        results.putIfAbsent("google:$input", Suggestion("Google 検索: $input", "Google", input, SuggestionType.GOOGLE_SEARCH))
        return results.values.take(8)
    }

    private fun fetchGoogleSuggestions(query: String): List<String> = runCatching {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = (URI("https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded")
            .toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 3_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) HTTPS-Tab-Browser/1.0")
        }
        connection.inputStream.bufferedReader().use { reader ->
            val array = JSONArray(reader.readText())
            val suggestions = array.optJSONArray(1) ?: JSONArray()
            List(suggestions.length()) { index -> suggestions.optString(index).trim() }
                .filter(String::isNotBlank)
                .distinct()
                .take(6)
        }
    }.getOrDefault(emptyList())

    private fun buildNavigation(input: String): PreparedNavigation? {
        val value = input.trim()
        if (value.isBlank()) return null
        return if (looksLikeUrl(value)) {
            val url = secureUrl(value) ?: return null
            PreparedNavigation(url, url, AddressDisplayMode.URL)
        } else {
            val encoded = URLEncoder.encode(value, Charsets.UTF_8.name())
            PreparedNavigation("https://www.google.com/search?q=$encoded", value, AddressDisplayMode.SEARCH)
        }
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
        if (uri.host?.endsWith("google.com") == true && uri.path == "/search") uri.getQueryParameter("q")?.takeIf(String::isNotBlank) else null
    }.getOrNull()

    private fun homeTab() = BrowserTab()

    private companion object {
        const val GOOGLE_HOME = "https://www.google.com/"
    }
}
