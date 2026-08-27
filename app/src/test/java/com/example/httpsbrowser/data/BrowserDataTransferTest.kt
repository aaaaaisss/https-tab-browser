package com.example.httpsbrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDataTransferTest {
    private val settings = BrowserSettings(
        forceDarkPages = true,
        forceDarkVideoPages = false,
        skipDarkeningAlreadyDarkPages = true,
        darkModeExcludedHosts = listOf("example.com"),
        adBlockingEnabled = true,
        aggressiveAdBlockingEnabled = false,
        javascriptEnabled = true
    )

    @Test
    fun `HTML transfer round trip keeps approved data only`() {
        val html = BrowserDataTransfer.exportHtml(
            bookmarks = listOf(Bookmark(title = "Example", url = "https://example.com/path")),
            settings = settings,
            sources = listOf(
                BlockListSource(name = "Built in", sourceUrl = "https://built-in.example/list.txt", builtIn = true),
                BlockListSource(name = "Custom", sourceUrl = "https://filters.example/custom.txt", enabled = false)
            )
        )

        assertTrue(html.contains("<script id=\"https-tab-browser-transfer\" type=\"application/json\">"))
        assertFalse(html.contains("built-in.example"))
        val parsed = BrowserDataTransfer.import(html).getOrThrow()
        assertEquals(listOf("https://example.com/path"), parsed.bookmarks.map(Bookmark::url))
        assertEquals(settings, parsed.settings)
        assertEquals(listOf(TransferFilterSource("Custom", "https://filters.example/custom.txt", false)), parsed.customFilterSources)
    }

    @Test
    fun `legacy JSON transfer remains readable`() {
        val json = BrowserDataTransfer.exportJson(
            bookmarks = emptyList(),
            settings = settings,
            sources = emptyList()
        )

        assertEquals(settings, BrowserDataTransfer.import(json).getOrThrow().settings)
    }

    @Test
    fun `transfer rejects untrusted format and non HTTPS URL`() {
        assertTrue(BrowserDataTransfer.import("<html><body>not a transfer</body></html>").isFailure)
        val invalidUrl = """{
            "format":"https-tab-browser-transfer",
            "schemaVersion":1,
            "bookmarks":[{"title":"Unsafe","url":"http://example.com"}],
            "settings":{"forceDarkPages":true,"forceDarkVideoPages":false,"skipDarkeningAlreadyDarkPages":false,"darkModeExcludedHosts":[],"adBlockingEnabled":true,"aggressiveAdBlockingEnabled":false,"javascriptEnabled":true},
            "customFilterSources":[]
        }"""
        assertTrue(BrowserDataTransfer.import(invalidUrl).isFailure)
    }
}
