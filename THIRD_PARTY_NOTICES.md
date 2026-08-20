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
