package com.example.httpsbrowser.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.browserDataStore by preferencesDataStore(name = "browser_state")

class BrowserRepository(private val context: Context) {
    private object Keys {
        val tabs = stringPreferencesKey("tabs")
        val selectedTab = stringPreferencesKey("selected_tab")
        val history = stringPreferencesKey("history")
        val bookmarks = stringPreferencesKey("bookmarks")
        val forceDark = booleanPreferencesKey("force_dark")
        val adBlock = booleanPreferencesKey("ad_block")
        val javascript = booleanPreferencesKey("javascript")
    }

    suspend fun load(): BrowserPersistedState {
        val preferences = context.browserDataStore.data.first()
        return BrowserPersistedState(
            tabs = decodeTabs(preferences[Keys.tabs]).ifEmpty { listOf(BrowserTab()) },
            selectedTabId = preferences[Keys.selectedTab],
            history = decodeHistory(preferences[Keys.history]),
            bookmarks = decodeBookmarks(preferences[Keys.bookmarks]),
            settings = BrowserSettings(
                forceDarkPages = preferences[Keys.forceDark] ?: false,
                adBlockingEnabled = preferences[Keys.adBlock] ?: true,
                javascriptEnabled = preferences[Keys.javascript] ?: true
            )
        )
    }

    suspend fun save(state: BrowserUiState) {
        context.browserDataStore.edit { preferences ->
            preferences[Keys.tabs] = encodeTabs(state.tabs).toString()
            state.selectedTab?.id?.let { preferences[Keys.selectedTab] = it }
            preferences[Keys.history] = encodeHistory(state.history.take(500)).toString()
            preferences[Keys.bookmarks] = encodeBookmarks(state.bookmarks).toString()
            preferences[Keys.forceDark] = state.settings.forceDarkPages
            preferences[Keys.adBlock] = state.settings.adBlockingEnabled
            preferences[Keys.javascript] = state.settings.javascriptEnabled
        }
    }

    suspend fun clearBrowsingData(keepBookmarks: Boolean) {
        context.browserDataStore.edit { preferences ->
            preferences.remove(Keys.history)
            preferences.remove(Keys.tabs)
            preferences.remove(Keys.selectedTab)
            if (!keepBookmarks) preferences.remove(Keys.bookmarks)
        }
    }

    private fun decodeTabs(raw: String?): List<BrowserTab> = decodeArray(raw) { item ->
        BrowserTab(
            id = item.optString("id", java.util.UUID.randomUUID().toString()),
            url = item.optString("url", "https://www.google.com/"),
            title = item.optString("title", "新しいタブ"),
            displayText = item.optString("displayText", ""),
            displayMode = runCatching { AddressDisplayMode.valueOf(item.optString("displayMode")) }
                .getOrDefault(AddressDisplayMode.URL),
            lastRequestedUrl = item.optString("lastRequestedUrl", item.optString("url")),
            canGoBack = item.optBoolean("canGoBack"),
            canGoForward = item.optBoolean("canGoForward")
        )
    }

    private fun decodeHistory(raw: String?): List<HistoryEntry> = decodeArray(raw) { item ->
        HistoryEntry(
            id = item.optString("id", java.util.UUID.randomUUID().toString()),
            title = item.optString("title", ""),
            url = item.optString("url", ""),
            query = item.optString("query").ifBlank { null },
            visitedAt = item.optLong("visitedAt", System.currentTimeMillis())
        )
    }

    private fun decodeBookmarks(raw: String?): List<Bookmark> = decodeArray(raw) { item ->
        Bookmark(
            id = item.optString("id", java.util.UUID.randomUUID().toString()),
            title = item.optString("title", ""),
            url = item.optString("url", ""),
            createdAt = item.optLong("createdAt", System.currentTimeMillis())
        )
    }

    private fun <T> decodeArray(raw: String?, map: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index -> map(array.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private fun encodeTabs(tabs: List<BrowserTab>) = JSONArray().apply {
        tabs.forEach { tab -> put(JSONObject().apply {
            put("id", tab.id); put("url", tab.url); put("title", tab.title)
            put("displayText", tab.displayText); put("displayMode", tab.displayMode.name)
            put("lastRequestedUrl", tab.lastRequestedUrl); put("canGoBack", tab.canGoBack)
            put("canGoForward", tab.canGoForward)
        }) }
    }

    private fun encodeHistory(history: List<HistoryEntry>) = JSONArray().apply {
        history.forEach { entry -> put(JSONObject().apply {
            put("id", entry.id); put("title", entry.title); put("url", entry.url)
            put("query", entry.query); put("visitedAt", entry.visitedAt)
        }) }
    }

    private fun encodeBookmarks(bookmarks: List<Bookmark>) = JSONArray().apply {
        bookmarks.forEach { bookmark -> put(JSONObject().apply {
            put("id", bookmark.id); put("title", bookmark.title); put("url", bookmark.url)
            put("createdAt", bookmark.createdAt)
        }) }
    }
}

data class BrowserPersistedState(
    val tabs: List<BrowserTab>,
    val selectedTabId: String?,
    val history: List<HistoryEntry>,
    val bookmarks: List<Bookmark>,
    val settings: BrowserSettings
)
