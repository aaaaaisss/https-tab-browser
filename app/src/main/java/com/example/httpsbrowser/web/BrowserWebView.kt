            val shouldCheck = !protectedPlaybackResource || isYoutubeAdOrTrackingNetwork(url)
            val minimalBlocked = entry.settings.aggressiveAdBlockingEnabled &&
                MinimalAdBlockClient.shouldBlockMinimalAd(url)
            if (minimalBlocked || (entry.adBlockingEnabled && shouldCheck && blocker.shouldBlock(
                    url = url,
                    documentUrl = documentUrl,
                    resourceType = resourceType
                ))
            ) {
                return WebResourceResponse(
                    "text/plain", "utf-8", 204, "No Content",
                    mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0))
                )
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            CrashDiagnostics.recordWebViewNavigation(url)
            val entry = entries[tabId]
            entry?.cosmeticAppliedUrl = null
            entry?.genericCosmeticAppliedUrl = null
            entry?.youtubeCosmeticAppliedUrl = null
            entry?.activeDocumentUrl = url
            view.setBackgroundColor(android.graphics.Color.BLACK)
            // ページ内CSSは注入しない。Fulguris由来のネイティブ設定を再適用して、
            // SPA遷移後もWebViewの色テーマ契約だけを維持する。
            entry?.let { configure(view, it.settings) }
            entry?.callbacks?.onPageStarted(tabId, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {