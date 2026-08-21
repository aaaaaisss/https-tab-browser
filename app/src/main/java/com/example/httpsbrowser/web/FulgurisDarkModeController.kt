/*
 * The contents of this file are subject to the Common Public Attribution License
 * Version 1.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * https://opensource.org/license/cpal-1-0
 *
 * The Original Code is Fulguris.
 * The Original Developer is Stéphane Lenclud.
 * The Initial Developer of the Original Code is Stéphane Lenclud.
 * Portions of the Original Code are Copyright © 2020-2021 Stéphane Lenclud
 * and Copyright 2014 A.C.R. Development.
 *
 * This file is derived from the dark-mode decision path in Fulguris
 * `app/src/main/java/fulguris/view/WebPageTab.kt`:
 * https://github.com/Slion/Fulguris/blob/main/app/src/main/java/fulguris/view/WebPageTab.kt
 *
 * Modifications for ねこぶらうざ, 2026-08-21:
 * - Extracted the WebView-only logic from Fulguris' Activity/Hilt/tab architecture.
 * - Preserved WebViewFeature gates and native dark-theme preference ordering.
 * - Intentionally omitted the legacy ColorMatrix inversion fallback so that images
 *   and videos are never globally inverted on older devices.
 */
package com.example.httpsbrowser.web

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Fulguris-derived native dark-mode controller.
 *
 * WebView's color-scheme contract is the primary path.  Page-level JavaScript/CSS
 * transformers are intentionally not used, so video, image, login and payment pages
 * retain Chromium's normal rendering pipeline.
 */
internal object FulgurisDarkModeController {
    fun apply(view: WebView, forceDarkPages: Boolean): AppliedDarkMode {
        val settings = view.settings
        val appUsesDarkTheme = !view.context.isLightTheme()
        val allowNativeDarkening = appUsesDarkTheme || forceDarkPages
        val hasAlgorithmicDarkening = WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
        val hasForceDark = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
        val hasForceDarkStrategy = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)

        // Fulguris enables algorithmic darkening only for an explicit per-tab force request.
        // When the app itself is dark, standards-compliant websites instead receive
        // prefers-color-scheme: dark from the Android theme without forced recoloring.
        if (hasAlgorithmicDarkening) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, forceDarkPages)
        }

        if (hasForceDark) {
            if (allowNativeDarkening) {
                if (hasForceDarkStrategy) {
                    WebSettingsCompat.setForceDarkStrategy(
                        settings,
                        if (forceDarkPages) {
                            WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                        } else {
                            WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                        }
                    )
                }
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
            } else {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
        }

        // Fulguris declares forceDarkAllowed on its WebView XML.  This registry-created
        // WebView has no XML attributes, so set the equivalent View flag explicitly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.setForceDarkAllowed(true)
        }

        return AppliedDarkMode(
            appUsesDarkTheme = appUsesDarkTheme,
            forceDarkPages = forceDarkPages,
            algorithmicDarkening = hasAlgorithmicDarkening,
            forceDark = hasForceDark,
            forceDarkStrategy = hasForceDarkStrategy
        )
    }

    private fun Context.isLightTheme(): Boolean {
        val value = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.isLightTheme, value, true)) {
            value.data != 0
        } else {
            true
        }
    }

    data class AppliedDarkMode(
        val appUsesDarkTheme: Boolean,
        val forceDarkPages: Boolean,
        val algorithmicDarkening: Boolean,
        val forceDark: Boolean,
        val forceDarkStrategy: Boolean
    )
}
