# 動画・PiP再調査: Fulgurisとの差分

## 結論

前回の実装は、Fulgurisのロード状態再arm、重複`onPageFinished`抑止、custom viewの単一所有という一部の安全策を適合したものであり、**Fulgurisの動画表示経路をそのまま移植したものではない**。特にviewport既定値、full-screen custom viewの親View、PiP開始経路が異なる。この差が、YouTubeとGoogle動画タブに残る横幅・左端・重なり表示、全画面切替時の一時停止、ホームジェスチャーからPiPへ入れない問題の主要候補である。

## 確定した差分

| 項目 | Fulguris | 現行ねこぶらうざ | 影響 |
|---|---|---|---|
| モバイルviewport | 通常は`useWideViewPort = false`。desktop mode時だけtrue | 常に`useWideViewPort = true` | YouTube/Google動画タブの横幅・左端・初期縮尺がFulgurisと異なる |
| 全画面custom viewの親 | `window.decorView`へ黒い`FrameLayout`を追加し、その中へcustom viewを追加。通常タブはINVISIBLE | Compose内で通常WebViewと別の`AndroidView`を重ねる。全画面状態により通常WebViewのサイズも変わる | Chromiumのvideo surfaceが親変更・再測定と競合し、停止または黒白レイヤーになる可能性 |
| PiP開始 | `onUserLeaveHint`でAPI 26以上は明示的に`enterPictureInPictureMode`。API 31以上ではauto-enterも併用 | API 31以上ではauto-enterだけへ依存し、手動PiP操作がない | 端末・ジェスチャー実装によりホーム操作でPiPが始まらない |
| システムバー | Activity側でfullscreen状態を制御 | Compose状態変更とActivityのinsets制御が混在 | 一度目の下端スワイプがsystem bar表示に消費され、ホームジェスチャーが成立しない可能性 |

## 再構築の最小単位

1. WebViewの通常viewportをFulgurisと同様に`useWideViewPort = false`へ戻す。desktop modeは実装していないため、通常表示でwide viewportを有効にする根拠はない。
2. full-screen custom viewをComposeから外し、`MainActivity`がcontent root上のnative `FrameLayout`として一度だけ所有する。通常のWebViewをCompose側で再測定・親変更しない。
3. 全画面時だけActivityの小さな手動PiPボタンを表示する。Android 8以上で`enterPictureInPictureMode`を直接実行する。
4. `onUserLeaveHint`でもAPI 26以上で明示的にPiP開始する。API 31以上のauto-enterは補助として残すが、唯一の開始経路にしない。
5. immersive操作はstatus bar中心へ限定し、ユーザーのホームジェスチャーを第一優先にする。

## 非採用

FulgurisのActivity全体、Hilt、RxJava、XMLタブUI、`VideoView.stopPlayback()`、外部プレーヤー、YouTube player response JSON改変は採用しない。`VideoView.stopPlayback()`はWebViewのHTML5 videoには適用対象ではなく、全画面終了時の不要な停止につながるため移植しない。

## 参照

- `analysis/fulguris_darkmode_sources/WebPageClient.kt` 173–197
- `analysis/fulguris_size_comparison/Fulguris_WebBrowserActivity.kt` 3663–3757、4697–4823
- `app/src/main/java/com/example/httpsbrowser/MainActivity.kt`
- `app/src/main/java/com/example/httpsbrowser/ui/BrowserScreen.kt`
- `app/src/main/java/com/example/httpsbrowser/web/BrowserWebView.kt`
