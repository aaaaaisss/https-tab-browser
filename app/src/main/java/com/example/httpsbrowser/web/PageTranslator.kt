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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 外部の翻訳ページを開かず、端末に取得した ML Kit モデルで WebView の本文を日本語へ置換する。
 * 翻訳モデルの初回取得のみネットワークを使用し、本文を外部の翻訳 Web サイトへ送らない。
 */
class PageTranslator {
    private val languageIdentifier = LanguageIdentification.getClient()
    private val requestSequence = AtomicInteger(0)

    fun translatePage(webView: WebView, onStatus: (String) -> Unit) {
        val requestId = requestSequence.incrementAndGet()
        val expectedUrl = webView.url.orEmpty()
        if (expectedUrl.isBlank() || expectedUrl == "about:blank") {
            onStatus("ページの読み込みが完了してから翻訳してください。")
            return
        }

        // 呼び出すたびに現ページのテキストノードを収集する。SPA の画面切替後でも前ページの
        // ノードを再利用しないため、別サイト・2回目以降の翻訳にも安全に対応できる。
        webView.evaluateJavascript(EXTRACT_SNAPSHOT_SCRIPT) { raw ->
            if (!isCurrentRequest(requestId, webView, expectedUrl)) return@evaluateJavascript
            val snapshot = extractSnapshot(raw)
            if (snapshot == null || snapshot.texts.isEmpty()) {
                onStatus("翻訳できる本文が見つかりませんでした。")
                return@evaluateJavascript
            }
            if (snapshot.url != expectedUrl) {
                onStatus("ページが切り替わりました。現在のページで、もう一度翻訳してください。")
                return@evaluateJavascript
            }

            val sample = snapshot.texts.take(30).joinToString(" ").take(4_000)
            languageIdentifier.identifyLanguage(sample)
                .addOnSuccessListener { languageTag ->
                    if (!isCurrentRequest(requestId, webView, expectedUrl)) return@addOnSuccessListener
                    val sourceLanguage = TranslateLanguage.fromLanguageTag(languageTag)
                    if (sourceLanguage == null) {
                        onStatus("ページの言語を判定できませんでした。")
                        return@addOnSuccessListener
                    }
                    if (sourceLanguage == TranslateLanguage.JAPANESE) {
                        onStatus("このページはすでに日本語です。")
                        return@addOnSuccessListener
                    }
                    val translator = Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(sourceLanguage)
                            .setTargetLanguage(TranslateLanguage.JAPANESE)
                            .build()
                    )
                    onStatus("日本語翻訳モデルを準備しています…")
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            if (!isCurrentRequest(requestId, webView, expectedUrl)) {
                                translator.close()
                                return@addOnSuccessListener
                            }
                            onStatus("ページを翻訳しています…")
                            translateTexts(webView, translator, snapshot, expectedUrl, requestId, onStatus)
                        }
                        .addOnFailureListener {
                            translator.close()
                            if (isCurrentRequest(requestId, webView, expectedUrl)) {
                                onStatus("翻訳モデルを取得できませんでした。ネットワーク接続を確認してください。")
                            }
                        }
                }
                .addOnFailureListener {
                    if (isCurrentRequest(requestId, webView, expectedUrl)) onStatus("ページの言語を判定できませんでした。")
                }
        }
    }

    fun close() {
        languageIdentifier.close()
    }

    private fun translateTexts(
        webView: WebView,
        translator: Translator,
        snapshot: PageSnapshot,
        expectedUrl: String,
        requestId: Int,
        onStatus: (String) -> Unit
    ) {
        // 短いテキスト単位で並列翻訳し、メニューや SPA の遅延読み込みにも十分な範囲を対象にする。
        val targetTexts = snapshot.texts.take(MAX_TEXT_NODES).map { it.take(MAX_NODE_CHARS) }
        val tasks = targetTexts.map(translator::translate)
        Tasks.whenAllComplete(tasks)
            .addOnSuccessListener {
                if (!isCurrentRequest(requestId, webView, expectedUrl)) {
                    translator.close()
                    return@addOnSuccessListener
                }
                val translated = tasks.mapIndexed { index, task ->
                    if (task.isSuccessful) task.result else targetTexts[index]
                }
                val payload = JSONArray(translated).toString()
                val sourceUrl = JSONObject.quote(expectedUrl)
                webView.post {
                    if (!isCurrentRequest(requestId, webView, expectedUrl)) {
                        translator.close()
                        return@post
                    }
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
                        translator.close()
                        if (!isCurrentRequest(requestId, webView, expectedUrl)) return@evaluateJavascript
                        when {
                            result?.contains("applied:") == true -> onStatus("ページを日本語へ翻訳しました。")
                            result?.contains("navigated") == true -> onStatus("ページが切り替わりました。現在のページで、もう一度翻訳してください。")
                            else -> onStatus("ページへ翻訳結果を適用できませんでした。もう一度お試しください。")
                        }
                    }
                }
            }
            .addOnFailureListener {
                translator.close()
                if (isCurrentRequest(requestId, webView, expectedUrl)) onStatus("ページ翻訳に失敗しました。")
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
        const val MAX_TEXT_NODES = 120
        const val MAX_NODE_CHARS = 600

        val EXTRACT_SNAPSHOT_SCRIPT = """
            (function() {
              function collectNodes() {
                var root = document.body || document.documentElement;
                if (!root) return [];
                var nodes = [];
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                var node;
                while ((node = walker.nextNode()) && nodes.length < 120) {
                  var parent = node.parentElement;
                  var text = (node.nodeValue || '').trim();
                  if (!parent || text.length < 2) continue;
                  if (parent.closest('script,style,noscript,svg,canvas,video,iframe,code,pre,textarea,select,option')) continue;
                  var style = window.getComputedStyle(parent);
                  if (style.display === 'none' || style.visibility === 'hidden') continue;
                  nodes.push(node);
                }
                return nodes;
              }
              var nodes = collectNodes();
              window.__httpsBrowserTranslateNodes = nodes;
              window.__httpsBrowserTranslatePageUrl = location.href;
              window.__httpsBrowserApplyTranslations = function(values, sourceUrl) {
                if (!Array.isArray(values)) return 'empty';
                if (location.href !== sourceUrl || window.__httpsBrowserTranslatePageUrl !== sourceUrl) return 'navigated';
                var current = window.__httpsBrowserTranslateNodes || [];
                var applied = 0;
                for (var i = 0; i < values.length && i < current.length; i++) {
                  var target = current[i];
                  if (target && target.isConnected && typeof values[i] === 'string' && values[i].length > 0) {
                    target.nodeValue = values[i];
                    applied++;
                  }
                }
                return applied > 0 ? 'applied:' + applied : 'empty';
              };
              return JSON.stringify({ url: location.href, texts: nodes.map(function(node) { return node.nodeValue || ''; }) });
            })();
        """.trimIndent()
    }
}
