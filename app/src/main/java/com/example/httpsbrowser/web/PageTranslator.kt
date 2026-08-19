package com.example.httpsbrowser.web

import android.webkit.WebView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONTokener

/**
 * 外部の翻訳ページを開かず、端末に取得した ML Kit モデルで WebView の可視本文を日本語へ置換する。
 * 翻訳モデルの初回取得のみネットワークを使用し、本文は外部の翻訳 Web サイトへ送らない。
 */
class PageTranslator {
    private val languageIdentifier = LanguageIdentification.getClient()

    fun translatePage(webView: WebView, onStatus: (String) -> Unit) {
        webView.evaluateJavascript(EXTRACT_TEXT_NODES_SCRIPT) { raw ->
            val texts = extractTexts(raw)
            if (texts.isEmpty()) {
                onStatus("翻訳できる本文が見つかりませんでした。")
                return@evaluateJavascript
            }
            val sample = texts.take(24).joinToString(" ").take(4_000)
            languageIdentifier.identifyLanguage(sample)
                .addOnSuccessListener { languageTag ->
                    val sourceLanguage = TranslateLanguage.fromLanguageTag(languageTag)
                        ?: TranslateLanguage.ENGLISH
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
                            onStatus("ページを翻訳しています…")
                            translateTexts(webView, translator, texts, onStatus)
                        }
                        .addOnFailureListener {
                            translator.close()
                            onStatus("翻訳モデルを取得できませんでした。ネットワーク接続を確認してください。")
                        }
                }
                .addOnFailureListener {
                    onStatus("ページの言語を判定できませんでした。")
                }
        }
    }

    fun close() {
        languageIdentifier.close()
    }

    private fun translateTexts(
        webView: WebView,
        translator: Translator,
        texts: List<String>,
        onStatus: (String) -> Unit
    ) {
        // 画面内に現れる本文を優先し、翻訳待ちで画面操作が重くならない上限を設ける。
        val targetTexts = texts.take(MAX_TEXT_NODES)
        val tasks = targetTexts.map { text -> translator.translate(text.take(MAX_NODE_CHARS)) }
        Tasks.whenAllComplete(tasks)
            .addOnSuccessListener {
                val translated = tasks.mapIndexed { index, task ->
                    if (task.isSuccessful) task.result else targetTexts[index]
                }
                val payload = JSONArray(translated).toString()
                webView.post {
                    webView.evaluateJavascript(
                        """
                        (function() {
                          try {
                            if (!window.__httpsBrowserApplyTranslations) return 'missing';
                            return window.__httpsBrowserApplyTranslations($payload) ? 'applied' : 'empty';
                          } catch (error) { return 'error'; }
                        })();
                        """.trimIndent()
                    ) { result ->
                        translator.close()
                        if (result?.contains("applied") == true) onStatus("ページを日本語へ翻訳しました。")
                        else onStatus("ページへ翻訳結果を適用できませんでした。もう一度お試しください。")
                    }
                }
            }
            .addOnFailureListener {
                translator.close()
                onStatus("ページ翻訳に失敗しました。")
            }
    }

    private fun extractTexts(rawResult: String?): List<String> = runCatching {
        val serialized = JSONTokener(rawResult ?: "\"[]\"").nextValue() as? String ?: return emptyList()
        val array = JSONArray(serialized)
        List(array.length()) { index -> array.optString(index).trim() }
            .filter { it.length >= 2 }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_TEXT_NODES = 80
        const val MAX_NODE_CHARS = 750

        val EXTRACT_TEXT_NODES_SCRIPT = """
            (function() {
              var nodes = [];
              var walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
              var node;
              while ((node = walker.nextNode()) && nodes.length < 80) {
                var parent = node.parentElement;
                var text = (node.nodeValue || '').trim();
                if (!parent || text.length < 2) continue;
                if (parent.closest('script,style,noscript,svg,canvas,video,iframe,code,pre,textarea,select,option')) continue;
                var style = window.getComputedStyle(parent);
                if (style.display === 'none' || style.visibility === 'hidden') continue;
                nodes.push(node);
              }
              window.__httpsBrowserTranslateNodes = nodes;
              window.__httpsBrowserApplyTranslations = function(values) {
                var applied = 0;
                if (!Array.isArray(values)) return false;
                for (var i = 0; i < values.length && i < nodes.length; i++) {
                  if (typeof values[i] === 'string' && values[i].length > 0) {
                    nodes[i].nodeValue = values[i];
                    applied++;
                  }
                }
                return applied > 0;
              };
              return JSON.stringify(nodes.map(function(item) { return item.nodeValue || ''; }));
            })();
        """.trimIndent()
    }
}
