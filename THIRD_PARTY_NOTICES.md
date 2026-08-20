# Third-Party Notices

## Brave adblock-rust

The native content-filtering engine is built from the [`adblock`](https://crates.io/crates/adblock) crate maintained by Brave Software. It is licensed under the **Mozilla Public License 2.0**. The project source is available at <https://github.com/brave/adblock-rust>.

## AdGuard filter lists

The application downloads and bundles snapshots of the following user-selected AdGuard Android optimized filters from their official HTTPS endpoints:

- EasyList (Optimized): <https://filters.adtidy.org/android/filters/101_optimized.txt>
- AdGuard Japanese filter (Optimized): <https://filters.adtidy.org/android/filters/7_optimized.txt>

Filter content remains subject to its upstream licenses and terms. Source URLs are displayed in the application’s ad-block settings page.

## Brave adblock-resources

The application bundles Brave's official scriptlet resource manifest solely to resolve scriptlets referenced by the two built-in AdGuard Android filter lists.

- Source repository: <https://github.com/brave/adblock-resources>
- Fixed source commit: `9a0cc4312e155cb5b16b701afc0ab9285dc30f24`
- Bundled file: `app/src/main/assets/adblock_resources/brave_resources.json`
- SHA-256: `dca2802415565b15ceb7288811685d47ddf4bc6b0c4324357ac66e33c1de4948`
- License: **Mozilla Public License 2.0**

User-added remote filter lists do not receive scriptlet execution permission.

## Dark Reader dynamic theme library

The application bundles a fixed, local copy of the Dark Reader JavaScript API solely to generate a dynamic dark theme for non-video HTTPS pages. It never fetches or executes remote code at runtime.

- Source repository: <https://github.com/darkreader/darkreader>
- Fixed release: `4.9.128`
- Bundled file: `app/src/main/assets/darkreader/darkreader-4.9.128.js`
- SHA-256: `52cdb6603e5eb6bb9b53ebd59efdec0d36f71bd2196d695eb466ad7adfb97b83` (the upstream CRLF line endings are normalized to LF; JavaScript tokens are unchanged)
- License: **MIT**

YouTube, YouTube embedded frames, Google video-search pages, and sign-in/payment pages are excluded from this dynamic theme injection.

## Design references (no source code copied or bundled)

The following open-source projects are consulted for architecture and compatibility decisions only. No source files, assets, or binary code from this section are copied into the application.

- **Fulguris**: <https://github.com/Slion/Fulguris> — consulted for its public WebView rendering-mode and official AndroidX dark-mode integration approach. In particular, the application follows the design principle of leaving normal WebView composition available when no temporary rendering effect is required, instead of permanently forcing an off-screen hardware layer. Fulguris is licensed under **MPL-2.0**.
- **AndroidX WebKit**: <https://github.com/androidx/androidx/tree/androidx-main/webkit> — used through the declared `androidx.webkit` dependency for feature-gated document-start scripts and dark-mode APIs. It is licensed under **Apache-2.0**.
- **AndroidX Activity / PiP Hint Tracker**: <https://android.googlesource.com/platform/frameworks/support/+/androidx-main/activity/activity/src/main/java/androidx/activity/PipHintTracker.kt> — the application already uses the `androidx.activity` dependency. Its public PiP source-rectangle tracking design is consulted to keep the fullscreen WebView custom view's `sourceRectHint` current during layout changes. The implementation here is independently written and does not copy that source file. AndroidX is licensed under **Apache-2.0**.

The Google video-search repair deliberately does not add an external player, proxy, downloader, or remote script. It preserves the platform WebView's native media pipeline, removes app-injected visual filtering from the sensitive video-search document, and retains only the existing Brave/AdGuard network filtering path.
