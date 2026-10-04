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
