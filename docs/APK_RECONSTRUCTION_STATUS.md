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
