package com.example.httpsbrowser.web

import android.webkit.WebView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 外部の翻訳サイトや API へ本文を送らず、端末上の ML Kit モデルだけで WebView の本文を日本語へ置換する。
 * JavaScript は翻訳エンジンではなく、現在ページの可視テキストを安全に収集・適用する用途だけに使う。
 */
class PageTranslator {
    private val languageIdentifier = LanguageIdentification.getClient()
    private val requestSequence = AtomicInteger(0)
    private val translators = object : LinkedHashMap<String, Translator>(MAX_CACHED_TRANSLATORS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Translator>?): Boolean {
            val remove = size > MAX_CACHED_TRANSLATORS
            if (remove) eldest?.value?.close()
            return remove
        }
    }

    fun translatePage(webView: WebView, onStatus: (String) -> Unit) {
        val requestId = requestSequence.incrementAndGet()
        val expectedUrl = webView.url.orEmpty()
        if (expectedUrl.isBlank() || expectedUrl == "about:blank") {
            onStatus("ページの読み込みが完了してから翻訳してください。")
            return
        }
        collectAndTranslate(webView, expectedUrl, requestId, pass = 0, knownLanguage = null, onStatus)
    }

    fun close() {
        requestSequence.incrementAndGet()
        synchronized(translators) {
            translators.values.forEach(Translator::close)
            translators.clear()
        }
        languageIdentifier.close()
    }

    private fun collectAndTranslate(
        webView: WebView,
        expectedUrl: String,
        requestId: Int,
        pass: Int,
        knownLanguage: String?,
        onStatus: (String) -> Unit
    ) {
        webView.evaluateJavascript(EXTRACT_SNAPSHOT_SCRIPT) { raw ->
            if (!isCurrentRequest(requestId, webView, expectedUrl)) return@evaluateJavascript
            val snapshot = extractSnapshot(raw)
            if (snapshot == null || snapshot.url != expectedUrl) {
                if (pass == 0) onStatus("ページが切り替わりました。現在のページで、もう一度翻訳してください。")
                return@evaluateJavascript
            }
            if (snapshot.texts.isEmpty()) {
                if (pass == 0) onStatus("翻訳できる本文が見つかりませんでした。")
                else onStatus("ページを日本語へ翻訳しました。")
                return@evaluateJavascript
            }

            val detectedLanguage = knownLanguage
            if (detectedLanguage != null) {
                translateSnapshot(webView, snapshot, expectedUrl, requestId, pass, detectedLanguage, onStatus)
                return@evaluateJavascript
            }

            val sample = snapshot.texts.take(30).joinToString(" ").take(MAX_LANGUAGE_SAMPLE_CHARS)
            languageIdentifier.identifyLanguage(sample)
                .addOnSuccessListener { languageTag ->
                    if (!isCurrentRequest(requestId, webView, expectedUrl)) return@addOnSuccessListener
                    val sourceLanguage = TranslateLanguage.fromLanguageTag(languageTag)
                    if (sourceLanguage == null) {
                        onStatus("ページの言語を判定できませんでした。")
                    } else if (sourceLanguage == TranslateLanguage.JAPANESE) {
                        onStatus("このページはすでに日本語です。")
                    } else {
                        translateSnapshot(webView, snapshot, expectedUrl, requestId, pass, sourceLanguage, onStatus)
                    }
                }
                .addOnFailureListener {
                    if (isCurrentRequest(requestId, webView, expectedUrl)) onStatus("ページの言語を判定できませんでした。")
                }
        }
    }

    private fun translateSnapshot(
        webView: WebView,
        snapshot: PageSnapshot,
        expectedUrl: String,
        requestId: Int,
        pass: Int,
        sourceLanguage: String,
        onStatus: (String) -> Unit
    ) {
        val translator = translatorFor(sourceLanguage)
        if (pass == 0) onStatus("日本語翻訳モデルを確認しています…")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (!isCurrentRequest(requestId, webView, expectedUrl)) return@addOnSuccessListener
                if (pass == 0) onStatus("ページを翻訳しています…")
                translateBatches(
                    webView = webView,
                    translator = translator,
                    targetTexts = snapshot.texts.take(MAX_TEXT_NODES).map { it.take(MAX_NODE_CHARS) },
                    expectedUrl = expectedUrl,
                    requestId = requestId,
                    pass = pass,
                    sourceLanguage = sourceLanguage,
                    onStatus = onStatus
                )
            }
            .addOnFailureListener {
                if (isCurrentRequest(requestId, webView, expectedUrl)) {
                    onStatus("翻訳モデルを取得できませんでした。初回のみネットワーク接続が必要です。")
                }
            }
    }

    private fun translateBatches(
        webView: WebView,
        translator: Translator,
        targetTexts: List<String>,
        expectedUrl: String,
        requestId: Int,
        pass: Int,
        sourceLanguage: String,
        onStatus: (String) -> Unit,
        start: Int = 0,
        translated: MutableList<String> = targetTexts.toMutableList()
    ) {
        if (!isCurrentRequest(requestId, webView, expectedUrl)) return
        if (start >= targetTexts.size) {
            applyTranslations(webView, translated, expectedUrl, requestId, pass, sourceLanguage, onStatus)
            return
        }
        val end = minOf(start + TRANSLATION_BATCH_SIZE, targetTexts.size)
        val tasks = (start until end).map { index -> index to translator.translate(targetTexts[index]) }
        Tasks.whenAllComplete(tasks.map { it.second })
            .addOnSuccessListener {
                if (!isCurrentRequest(requestId, webView, expectedUrl)) return@addOnSuccessListener
                tasks.forEach { (index, task) -> if (task.isSuccessful) translated[index] = task.result }
                translateBatches(
                    webView, translator, targetTexts, expectedUrl, requestId, pass, sourceLanguage, onStatus,
                    start = end, translated = translated
                )
            }
            .addOnFailureListener {
                if (isCurrentRequest(requestId, webView, expectedUrl)) onStatus("ページ翻訳に失敗しました。")
            }
    }

    private fun applyTranslations(
        webView: WebView,
        translated: List<String>,
        expectedUrl: String,
        requestId: Int,
        pass: Int,
        sourceLanguage: String,
        onStatus: (String) -> Unit
    ) {
        val payload = JSONArray(translated).toString()
        val sourceUrl = JSONObject.quote(expectedUrl)
        webView.post {
            if (!isCurrentRequest(requestId, webView, expectedUrl)) return@post
            webView.evaluateJavascript(
                """
                (function() {
                  try {
                    if (!window.__httpsBrowserApplyTranslations) return 'missing';
                    return window.__httpsBrowserApplyTranslations($payload, $sourceUrl);
                  } catch (error) { return 'error'; }
                })();
                """.trimIndent()
            ) { result ->
                if (!isCurrentRequest(requestId, webView, expectedUrl)) return@evaluateJavascript
                when {
                    result?.contains("applied:") == true && pass < DYNAMIC_RESCAN_COUNT -> {
                        onStatus("本文を翻訳しました。追加表示を確認しています…")
                        webView.postDelayed(
                            { collectAndTranslate(webView, expectedUrl, requestId, pass + 1, sourceLanguage, onStatus) },
                            DYNAMIC_RESCAN_DELAY_MS
                        )
                    }
                    result?.contains("applied:") == true -> onStatus("ページを日本語へ翻訳しました。")
                    result?.contains("navigated") == true -> onStatus("ページが切り替わりました。現在のページで、もう一度翻訳してください。")
                    else -> onStatus("ページへ翻訳結果を適用できませんでした。もう一度お試しください。")
                }
            }
        }
    }

    private fun translatorFor(sourceLanguage: String): Translator = synchronized(translators) {
        translators.getOrPut(sourceLanguage) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(TranslateLanguage.JAPANESE)
                    .build()
            )
        }
    }

    private fun isCurrentRequest(requestId: Int, webView: WebView, expectedUrl: String): Boolean =
        requestSequence.get() == requestId && webView.url == expectedUrl

    private fun extractSnapshot(rawResult: String?): PageSnapshot? = runCatching {
        val serialized = JSONTokener(rawResult ?: "\"\"").nextValue() as? String ?: return null
        val objectValue = JSONObject(serialized)
        val texts = objectValue.optJSONArray("texts") ?: return null
        PageSnapshot(
            url = objectValue.optString("url"),
            texts = List(texts.length()) { index -> texts.optString(index).trim() }.filter { it.length >= 2 }
        )
    }.getOrNull()

    private data class PageSnapshot(val url: String, val texts: List<String>)

    private companion object {
        const val MAX_TEXT_NODES = 180
        const val MAX_NODE_CHARS = 600
        const val MAX_LANGUAGE_SAMPLE_CHARS = 4_000
        const val TRANSLATION_BATCH_SIZE = 16
        const val MAX_CACHED_TRANSLATORS = 2
        const val DYNAMIC_RESCAN_COUNT = 1
        const val DYNAMIC_RESCAN_DELAY_MS = 1_000L

        val EXTRACT_SNAPSHOT_SCRIPT = """
            (function() {
              var root = document.body || document.documentElement;
              if (!root) return JSON.stringify({ url: location.href, texts: [] });
              var state = window.__httpsBrowserTranslationState;
              if (!state || state.url !== location.href) {
                state = { url: location.href, translated: new WeakSet() };
                window.__httpsBrowserTranslationState = state;
              }
              var nodes = [];
              var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
              var node;
              while ((node = walker.nextNode()) && nodes.length < 180) {
                var parent = node.parentElement;
                var text = (node.nodeValue || '').trim();
                if (!parent || text.length < 2 || state.translated.has(node)) continue;
                if (parent.closest('script,style,noscript,svg,canvas,video,iframe,code,pre,textarea,select,option')) continue;
                var style = window.getComputedStyle(parent);
                if (style.display === 'none' || style.visibility === 'hidden') continue;
                nodes.push(node);
              }
              window.__httpsBrowserTranslateNodes = nodes;
              window.__httpsBrowserTranslatePageUrl = location.href;
              window.__httpsBrowserApplyTranslations = function(values, sourceUrl) {
                if (!Array.isArray(values)) return 'empty';
                if (location.href !== sourceUrl || window.__httpsBrowserTranslatePageUrl !== sourceUrl) return 'navigated';
                var current = window.__httpsBrowserTranslateNodes || [];
                var activeState = window.__httpsBrowserTranslationState;
                var applied = 0;
                for (var i = 0; i < values.length && i < current.length; i++) {
                  var target = current[i];
                  if (target && target.isConnected && typeof values[i] === 'string' && values[i].length > 0) {
                    target.nodeValue = values[i];
                    activeState.translated.add(target);
                    applied++;
                  }
                }
                return applied > 0 ? 'applied:' + applied : 'empty';
              };
              return JSON.stringify({ url: location.href, texts: nodes.map(function(item) { return item.nodeValue || ''; }) });
            })();
        """.trimIndent()
    }
}
