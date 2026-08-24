# Brave・VivaldiのYouTube PiP調査メモ

## 検索で確認した公開情報

- Braveには、アドレスバー右側のPiPボタンからYouTube動画をPiPへ入れる案内が公開されている。ただし対象ページは主にBraveのデスクトップ向けUIを説明しており、Android System WebViewアプリへ移植できる実装ではない。
  - https://brave.com/whats-new/picture-in-picture/
- Brave AndroidではYouTube PiPがChromium側の変更により動かなくなったという公開issueがある。Brave自身もYouTubeサイトの制限・Chromiumのfullscreen/PiP実装の影響を受けており、独自のWebView用回避策を公開しているわけではない。
  - https://github.com/brave/brave-browser/issues/44933
- Vivaldiの公開資料で確認できるPiPは主にデスクトップ版ChromiumのWeb PiP APIまたは独自のPop-out Video UIであり、Android版System WebViewへ取り込める専用実装ではない。
  - https://vivaldi.com/blog/picture-in-picture-mode-how-to/
  - https://help.vivaldi.com/desktop/media/pop-out-video/
- ChromiumにはMedia SessionとPiP関連のAction/Browser UIがあるが、System WebViewアプリからはChromium内部のMediaSession・WebContents・PiP delegateへ接続できない。
  - https://developer.chrome.com/blog/automatic-picture-in-picture-media-playback

## 暫定結論

Brave・Vivaldiともソースや挙動は参照できるが、YouTubeのサイト制限を突破する「WebViewに移植可能なPiP対策」は確認できない。Brave Browserが使うChromium内部のMediaSession、WebContents、PiP delegateやShieldsが必要であり、Android System WebViewからは到達できない。

ねこぶらうざで実施可能な上限は、OS Activity PiPを全画面custom viewへ正しく接続し、明示PiPボタン、onUserLeaveHint、auto-enterを併用すること、また動画surfaceをCompose再構成で再親子化しないことである。YouTubeがブラウザにPiP可能なMediaSession/video surfaceを渡さない場合の強制的な継続再生は、外部プレーヤー・ダウンローダー・YouTube payload改変なしにはできない。

## 本文確認で得た重要な事実

BraveのAndroid issue #44933では、m.youtube.comのPiP不作動をBrave自身が再現し、ChromiumまたはGoogle側で削除・変更された可能性を記載している。Braveの担当者が示した一時的回避策は、`m.youtube.com`ではなくdesktop modeの`youtube.com`を使うことであった。さらにissueの技術的議論では、YouTubeの`base.js`がvideo要素へ`disablePictureInPicture`属性を付加している可能性が指摘されている。これはBrave固有のPiP engineではなく、YouTube文書側のWeb PiP制限が原因であることを強く示す。

Braveの公開案内はAndroidでアドレスバー右側にPiPボタンを置く機能を説明している。これは今回追加済みの「全画面native containerにPiPボタンを置き、OS Activity PiPを明示開始する」方針と目的は同じだが、BraveはChromiumブラウザアプリとしてWebContents・MediaSession・browser UIに接続できる。System WebViewアプリは同じ内部APIを利用できない。

Chrome Developersの自動PiP資料は、media sessionの`enterpictureinpicture` action、audible、audio focus、再生状態、site permissionなど複数のWeb側条件を明示している。現行Android System WebViewアプリからは、YouTubeにこのmedia session actionを登録させるChromium内部のbrowser policyを変更できない。したがってBrave/Vivaldiの「仕組みそのもの」を移植するのではなく、YouTubeの`disablePictureInPicture`を選択的に解除してWeb PiP APIを利用可能にするdocument-start scriptを、ユーザーの明示設定として検討する余地がある。

ただし、YouTubeのDOM・player scriptの改変は動画再生、広告遮断、SPA遷移を壊しやすい。導入するなら、player response JSON改変やネットワーク応答改変を行わず、`disablePictureInPicture`属性・対応プロパティの解除だけを最小限にし、YouTube専用かつ既定OFFの実験的設定として隔離する必要がある。

## 参考URL

- https://github.com/brave/brave-browser/issues/44933
- https://brave.com/whats-new/picture-in-picture/
- https://developer.chrome.com/blog/automatic-picture-in-picture-media-playback
- https://developer.mozilla.org/en-US/docs/Web/API/HTMLVideoElement/disablePictureInPicture

## Vivaldi本文確認の結論

VivaldiのAndroid公式ヘルプは、動画を全画面にしてホームへ戻る標準的なAndroid PiP手順を案内している。これは、ねこぶらうざがActivity PiP経路で実現しようとしている動作と同じ分類であり、Vivaldi固有のWebViewアプリ用解法は示していない。

より重要なのは、Vivaldi自身がAndroid版ではSystem WebViewを使わず、Vivaldi改変済みのChromiumソースからAndroidブラウザを構築したと説明している点である。またVivaldiは、ブラウザがChromium、独自C++ backend、閉鎖的なAndroid UIという層で成り立つことを明記している。従って、VivaldiのPiP・YouTube表示・広告遮断を「WebViewアプリの部品」として移植することはできない。採用可能なのは、Android PiPのUX原則とChromiumの公開仕様だけである。

### 参考URL

- https://help.vivaldi.com/android/android-browse/pop-out-video-on-android/
- https://vivaldi.com/blog/how-we-built-vivaldi-for-android/
- https://vivaldi.com/blog/technology/why-isnt-vivaldi-browser-open-source/
