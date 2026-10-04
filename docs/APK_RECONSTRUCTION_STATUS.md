# APK reconstruction status

Source APK: `https-tab-browser-minimal-adblock.apk`
SHA-256: `80508eec05d486b0d310d975c89ae91f892c9181c14cd000e4fe20382a781226`

Reference branch: `apk-sync-20261004`
Base: `main` at `a1b4f90b45b98ec7126573a95d6cb8f8569da6e4`

## Confirmed from APK and restored

- `web/MinimalAdBlockClient.kt`
- `web/AdBlockInjector.kt`
- `BrowserSettings.aggressiveAdBlockingEnabled`
- DataStore persistence for aggressive ad blocking
- Settings UI toggle for aggressive ad blocking
- WebView integration for minimal URL blocking
- WebView document-end aggressive CSS/YouTube response sanitization
- Existing YouTube/Brave filtering path retained

## APK evidence still to reconstruct

- Full APK-era `BrowserWebView.kt` delta. APK debug/source metadata indicates a substantially larger source file than the current repository version.
- Additional YouTube request/response handling and script handlers visible in APK symbols/strings.
- Remaining APK-era UI/data behavior and constants.
- Exact implementation details where only compiled/debug metadata is available.

## Important limitation

This file tracks **confirmed restoration separately from inferred reconstruction**. New code should not be described as an exact source recovery unless the APK evidence supports that claim.

## Workflow safety

The repository workflow currently triggers on pushes to `main`. Work is therefore being kept on this branch and Actions are not manually dispatched.

## Git-history correlation discovered

Commit `362d91a5d622763f43fa055bbfff661ee58ef7cb` (`Port robust Android PiP video handling`) contains the same `BrowserWebViewRegistry` architecture and PiP/video code family visible in the APK. The current `main` is based on that line of development. This gives a reliable historical reference for the large WebView source rather than treating all of it as APK-only code.

The APK nevertheless contains additional symbols not found in the repository history, notably:
- `youtubeAdSanitizerScriptHandler`
- `youtubeNoAdWarmPlayerScriptHandler`
- `youtubeSabrPatchOnlyScriptHandler`
- `youtubeCosmeticAggressiveApplied`
- `MAX_AGGRESSIVE_YOUTUBE_SELECTORS`
- `MAX_QUEUED_BACK_REQUESTS`
- `MAX_SAFE_REDIRECT_BYTES`

These remain pending because their exact compiled implementation should be reconstructed from APK evidence rather than guessed.

## Latest restoration pass

- Recovered the APK's YouTube document-start **no-ad warm player** script.
- Recovered the APK's YouTube **SABR patch-only** script, including both Fetch and XMLHttpRequest response paths and the Premium exclusion.
- Recovered a document-start YouTube ad-response sanitizer corresponding to the APK's `youtubeAdSanitizerScriptHandler`.
- Added lifecycle registration/removal for the three script handlers.
- Added the APK-visible `youtubeCosmeticAggressiveApplied` Entry state field.

These scripts are wired only when the repository's new `aggressiveAdBlockingEnabled` setting is enabled. The APK contains the same handler names and diagnostic event names, but the exact Kotlin registration order is not fully recoverable from strings alone.

## Latest APK evidence pass 2

- Replaced the reconstructed YouTube sanitizer with the more complete APK-observed implementation:
  - player/playerResponse field cleanup
  - player response text cleanup
  - Shorts ad-entry pruning
  - `reel_watch_sequence` handling
  - Fetch response rewriting
  - XMLHttpRequest `responseText` rewriting
- Restored the APK-observed aggressive YouTube cosmetic selector set in `AdBlockInjector`.
- APK evidence also confirms `isSafeYoutubeAdSelector` and safe-redirect constants/helpers exist. Their exact numeric thresholds and Kotlin control flow remain uncommitted because the current environment lacks a DEX decompiler and the string table alone does not establish those values.

## Latest APK evidence pass 3

- Direct DEX inspection recovered exact safe-data limits: `MAX_SAFE_REDIRECT_DATA_URL_CHARS = 262144`, `MAX_SAFE_REDIRECT_BYTES = 131072`.
- `isSafeRedirectMimeType` branches by `stylesheet`, `image`, and `script`; stylesheet is `text/css`. Image MIME strings recovered: `image/gif`, `image/jpeg`, `image/png`, `image/webp`. Script-safe strings recovered from the APK: `application/javascript`, `application/json`, `application/octet-stream`, `application/wasm`, `text/javascript`, `text/plain`.
- `isSafeYoutubeAdSelector` was directly decoded far enough to recover the 500-character cap and exact ad/layout token lists. Exact final boolean composition is still being treated conservatively until the remaining Dalvik branch/register semantics are decoded.
- The safe redirect helper has been reconstructed as an isolated method only. It is **not wired into request interception yet**, because the APK's exact MIME/resource-type call-site contract is not fully decoded. This avoids introducing an unsafe or incorrect fallback.

- Corrected the safe redirect helper placement so its constants live in the existing `BrowserWebView.kt` companion object; no duplicate companion object remains.


## Latest APK evidence pass 4

- Direct DEX decoding confirmed `isSafeYoutubeAdSelector` returns true only when the normalized selector is nonblank, at most 500 characters, contains at least one ad token (`ad`, `promoted`, `sponsor`, `masthead`, `merchandise`, `paid`, `brand`) **and** at least one layout token (`#player`, `video`, `iframe`, `ytd-app`, `ytm-app`, `ytd-page-manager`, `html`, `body`).\n- Recovered two aggressive YouTube selectors that were missing from the reconstructed CSS: `.ytp-suggested-action > .ytp-suggested-action-badge` and `yt-overlay-product-sticker`. These are now restored.\n- Safe redirect limits and MIME handling remain isolated and unwired until the APK request-interception call site is decoded.\n

## Latest APK evidence pass 5

- Decoded the APK `SecureClient.shouldInterceptRequest` method shape: when the entry's ad blocking flag is enabled, it calls an APK-era URL-only `shouldBlockMinimalAd(String)` helper and returns an empty ad response when that helper says true; main-frame lifecycle rearming happens separately.
- Decoded the APK `MinimalAdBlockClient.shouldBlockMinimalAd` signature as URL-only (`String -> Boolean`), unlike the current reconstruction's three-argument helper. The exact internal collection/control flow is still being decoded before replacing the current call site.
- This is a structural difference worth preserving in the status log, but it does not yet require a strategy change: the current branch can still be reconciled incrementally without discarding the existing Brave path.


## Latest APK evidence pass 6

- APK string evidence narrowed `MinimalAdBlockClient`: confirmed hosts are `doubleclick.net`, `googlesyndication.com`, `googleadservices.com`, `googletagservices.com`, and `ads.youtube.com`. Confirmed path strings are `/pagead`, `/api/stats/ads`, `/_get_ads`, and `/youtubei/v1/player/ad_break`. Strings previously added speculatively (`adservice.google.com`, `/ads/`, `/adserver`, `/advertising`, `/prebid/`, `/gampad/`) were removed from the reconstructed minimal blocker.
- The reconstructed helper and call site now use the APK-observed URL-only signature `shouldBlockMinimalAd(String)`. The broader Brave filtering path remains unchanged.


## Latest APK evidence pass 7

- Direct DEX static-value decoding recovered exact APK constants: `MAX_QUEUED_BACK_REQUESTS = 2000` and `MAX_AGGRESSIVE_YOUTUBE_SELECTORS = 500`.
- Both constants are now restored to `BrowserWebView.kt`. The queued-back-request constant is currently recorded but not behaviorally wired because its request-queue call site has not yet been decoded; the aggressive-selector cap matches the already recovered 500-character selector safety limit.


## Latest APK evidence pass 8

- APK `BrowserWebViewRegistry` still contains dedicated dark-document-start infrastructure (`prepareDarkDocumentStartScript`, deep-dark CSS, already-dark detection, dark reveal guard). The current repository intentionally uses its newer Fulguris-native dark-mode path instead, so this APK-era implementation was not blindly reintroduced.
- APK evidence also confirms the diagnostic/lifecycle names `youtube_pip_unlock_ready/unsupported`, `youtube_viewport_metrics`, and the three aggressive YouTube script readiness events. Existing repository code already contains corresponding PiP/viewport and YouTube script paths, so no duplicate implementation was added.


## Latest APK evidence pass 9

- Rechecked the current branch against APK-observed lifecycle structure. `ensureYoutubePictureInPictureScript` and the three aggressive document-start handlers are already registered/removed at the appropriate WebView lifecycle points, so no duplicate handlers were introduced.
- The APK-era safe redirect helper remains intentionally isolated. It has exact recovered size/MIME guards but is not connected to interception until the remaining call-site semantics are decoded.
- The current Fulguris-native dark-mode implementation remains preferred over reintroducing the older APK document-start darkening layer.


## Latest APK evidence pass 10

- Parsed the APK's `BrowserWebViewRegistry` class table directly from `classes6.dex`. Confirmed the APK class itself declares the recovered browser-limit fields `MAX_AGGRESSIVE_YOUTUBE_SELECTORS`, `MAX_SAFE_REDIRECT_BYTES`, `MAX_SAFE_REDIRECT_DATA_URL_CHARS`, and `MAX_STATIC_COSMETIC_SELECTORS`, plus the dark-mode, YouTube, safe-redirect, playback-protection, and viewport script resources already tracked above.
- The APK class table also confirms `VIDEO_VIEWPORT_METRICS_SCRIPT` and `YOUTUBE_PAGE_DARK_CSS` are first-class static resources, reinforcing that viewport reporting and a YouTube-specific dark layer were intentional APK features. The current branch already has viewport reporting and deliberately keeps Fulguris-native dark mode as the active path.


## Latest APK evidence pass 11

- APK string/debug evidence directly confirms the aggressive ad-blocking setting is real: `aggressiveAdBlockingEnabled`, `aggressive_ad_block`, and the corresponding getter/setter symbols are present in the APK.
- The APK also contains a dedicated `youtubeCosmeticAggressiveApplied` state alongside `youtubeCosmeticAppliedUrl`. The branch now uses that dedicated state so the recovered YouTube cosmetic CSS is applied only when aggressive ad blocking is enabled, and the aggressive state is reset on navigation/clear.
