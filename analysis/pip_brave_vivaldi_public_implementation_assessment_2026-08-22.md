# Brave・Vivaldi参照によるPiP改善評価

## 調査目的

ねこぶらうざのWebView全画面動画PiPを、BraveおよびVivaldiの公開情報とAndroid公式仕様に照らして評価した。YouTube固有の制限を回避する未公開コードのコピーではなく、公開されているActivity PiP遷移・全画面custom view保持・UI除外の仕組みだけを対象とする。

## 公開情報から確認できた事実

| 参照先 | 確認内容 | ねこぶらうざへの含意 |
|---|---|---|
| Vivaldi Androidヘルプ | 再生中の動画を全画面にし、端末のホーム操作でPiPへ移す手順を案内している。 | 全画面動画のcustom viewを保持してActivity PiPへ移行する経路が基本である。 |
| Brave公式案内 | YouTube動画でアドレスバー付近のPiP操作を提供すると案内している。 | 明示PiP入口と広告遮断を組み合わせる方針は妥当。ただしYouTubeの課金制限を直接解除する実装は公開案内からは得られない。 |
| Android公式PiP仕様 | API 31以降では`setAutoEnterEnabled(true)`と遷移前の最新`PictureInPictureParams`、正確な`sourceRectHint`が推奨される。PiP中は動画以外のUIを隠し、再生を停止しないことが重要。 | Activity native fullscreen container、layout listenerによるsourceRectHint、auto-enter、明示PiP入口は必要条件であり、既存実装の中心方針は正しい。 |

## 現行実装の評価

現在の`MainActivity`は、以下を実装済みである。

1. `android:supportsPictureInPicture="true"` と画面構成変更処理。
2. WebChromeClientの全画面custom viewをActivity rootのnative `FrameLayout`へ保持。
3. 動画Viewの`getGlobalVisibleRect()`とlayout listenerからの`sourceRectHint`更新。
4. API 31以上の`setAutoEnterEnabled(true)`および`setSeamlessResizeEnabled(true)`。
5. 全画面中の明示PiPボタンと`onUserLeaveHint()`によるAPI 26以上の明示PiP開始。
6. PiP遷移中の`onHideCustomView()`に対して動画Viewを破棄しない保持処理。

このため、Brave/Vivaldiの公開情報だけから追加できる根本的なPiP経路はほぼ導入済みである。残る改善は、PiP開始前に通常WebView hostとCompose操作UIを隠し、PiP windowに動画以外の合成レイヤーを残さないこと、PiP状態遷移中にfull-screen custom viewを確実に保持することに限られる。

## 今回実装する安全なPiP改善

* `onPictureInPictureModeChanged(true)`で通常WebView hostを隠し、PiPに非動画UIが混ざる経路を閉じる。
* PiP終了時はfull-screen custom viewが残っている時だけ通常UIを復帰し、動画Viewの所有権を動かさない。
* `onUserLeaveHint()`の二重呼び出しは既存の`pictureInPictureTransitionRequested`で抑制する。

YouTubeがWebViewからのPiPをサイト側ポリシーやアカウント状態で拒否する場合、ブラウザ側が合法かつ安定的に強制解除できる公開APIは確認できなかった。そのため、`disablePictureInPicture`属性のdocument-start解除、標準Activity PiP、広告遮断の三層を維持し、追加の回避コードは実装しない。

## 参照

1. Vivaldi Help, "Pop-out Video on Android": https://help.vivaldi.com/android/android-browse/pop-out-video-on-android/
2. Brave, "Quickly enter picture-in-picture mode in YouTube": https://brave.com/whats-new/picture-in-picture/
3. Android Developers, "Use picture-in-picture (PiP)": https://developer.android.com/develop/ui/views/picture-in-picture
4. Chrome for Developers, "Picture-in-Picture (PiP)": https://developer.chrome.com/blog/picture-in-picture
