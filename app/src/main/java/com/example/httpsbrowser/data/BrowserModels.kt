package com.example.httpsbrowser.data

import java.util.UUID

enum class AddressDisplayMode { URL, SEARCH }

enum class SettingsPage { ROOT, BOOKMARKS, HISTORY, AD_BLOCK, DATA }

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "ホーム",
    val displayText: String = "",
    val displayMode: AddressDisplayMode = AddressDisplayMode.URL,
    val lastRequestedUrl: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isHome: Boolean = true
)

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val query: String? = null,
    val visitedAt: Long = System.currentTimeMillis()
)

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class BrowserSettings(
    val forceDarkPages: Boolean = true,
    val adBlockingEnabled: Boolean = true,
    val javascriptEnabled: Boolean = true
)

data class BrowserUiState(
    val tabs: List<BrowserTab> = listOf(BrowserTab()),
    val selectedTabId: String? = tabs.firstOrNull()?.id,
    val addressInput: String = "",
    val suggestions: List<Suggestion> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val settings: BrowserSettings = BrowserSettings(),
    val isAddressFocused: Boolean = false,
    val isSuggestionPanelVisible: Boolean = false,
    val isTabSheetVisible: Boolean = false,
    val isSettingsSheetVisible: Boolean = false,
    val settingsPage: SettingsPage = SettingsPage.ROOT,
    val isFullscreen: Boolean = false
) {
    val selectedTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.firstOrNull()
}

data class Suggestion(
    val primary: String,
    val secondary: String,
    val url: String,
    val type: SuggestionType
)

enum class SuggestionType { OPEN_TAB, BOOKMARK, HISTORY, GOOGLE_SEARCH }

data class PreparedNavigation(
    val url: String,
    val displayText: String,
    val displayMode: AddressDisplayMode
)
