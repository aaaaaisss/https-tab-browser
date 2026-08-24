# Brave adblock-rust・YouTube PiPの移植境界

## 結論

ねこぶらうざはBraveの`adblock-rust`を**実際にJNIで稼働させている**。Rust側では`Engine`、`FilterSet`、`Request`、公式resourcesのscriptlet resourceを使い、Kotlin側がABP/AdGuard規則を独自解釈していない。

一方、Brave Androidアプリ本体の遮断・PiPはChromiumのネットワークサービス、`WebContents`、`MediaSession`、`FullscreenManager`、タブモデルへ深く接続している。Android System WebViewを使う通常アプリにはこれらの非公開Chromium内部APIが公開されないため、Braveアプリ本体をそのまま移植することはできない。

## ねこぶらうざで直接使っているadblock-rust機能

| 機能 | 実装状態 | 根拠 |
|---|---|---|
| ABP/AdGuard network rule評価 | 実装済み | Rust `Engine::check_network_request(Request)`をJNI公開し、WebView `shouldInterceptRequest`から呼び出す。 |
| hostname-specific cosmetic rule | 実装済み | `Engine::url_cosmetic_resources`をJNI公開し、ページへCSS注入する。 |
| generic cosmetic rule | 実装済み | DOMのclass/idを収集し、`Engine::hidden_class_id_selectors`を呼び出してCSS注入する。 |
| scriptlet resource | 実装済み | Brave公式resources.jsonを資産として展開し、標準2フィルタのみ`ParseOptions`のscriptlet権限を与える。 |
| Brave network stackでの完全遮断 | 非実装・不可能 | System WebViewのネットワーク層へChromium内部のBrave Shieldsサービスを差し込めない。WebView公開APIの`shouldInterceptRequest`で代替している。 |

## YouTubeでBraveと遮断率が一致しない理由

YouTube再生に必要なmedia、iframe、player/API要求には、フィルタ規則上広告/計測と似たURL・resource typeが混在する。通常モードでは再生維持のため一部要求を保護する。攻めた広告遮断モードでは、この保護を直接YouTubeで外してnetwork/CSS規則を最大限通す。Google動画タブは黒画面回帰を避けるため全通過を維持する。

## Brave YouTube PiPの公開実装との差

Braveの`BraveYouTubePictureInPictureController`は、`WebContents`を保存し、その`MediaSession`をPiP開始時にresume、PiP終了時にsuspendする。さらに`FullscreenManager`・`BrowserControlsManager`・tab modelを使ってChromium内の永続全画面とツールバー状態を管理する。これらはBraveがChromiumへ加えた内部APIで、WebViewアプリのActivity/WebViewから利用できない。

ねこぶらうざで移植可能な部分は、Activity PiP、full-screen custom viewのnative所有、sourceRectHint更新、auto-enter、全画面中の明示PiP操作、YouTube video要素の`disablePictureInPicture`属性解除、PiP中の非動画UIの隠蔽であり、いずれも実装済みまたは今回追加する範囲である。

## 参照

1. Brave public source, `BraveYouTubePictureInPictureController.java`: https://github.com/brave/brave-core/blob/e9b023edcc8249d6bc63d07e7fc4ab2594cfbd9e/android/java/org/chromium/chrome/browser/media/BraveYouTubePictureInPictureController.java
2. Brave public source, `BraveFullscreenVideoPictureInPictureController.java`: https://github.com/brave/brave-core/blob/e9b023edcc8249d6bc63d07e7fc4ab2594cfbd9e/android/java/org/chromium/chrome/browser/media/BraveFullscreenVideoPictureInPictureController.java
3. Brave public source, adblock-rust wrapper: https://github.com/brave/brave-core/blob/e9b023edcc8249d6bc63d07e7fc4ab2594cfbd9e/components/brave_shields/core/common/adblock/rs/src/lib.rs
4. Android Developers, `WebViewClient.shouldInterceptRequest`: https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest)
