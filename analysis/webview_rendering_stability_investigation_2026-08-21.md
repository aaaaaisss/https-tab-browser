# WebView描画安定性の調査

## 事実の整理

FulgurisはWebViewの描画レイヤーを「None」「Software」「Hardware」から選べる構成にしており、通常の初期値はHardwareである。これはWebGLを有効にする選択肢であり、動画再生を直接制御する専用機構ではない。Android公式資料でも、`LAYER_TYPE_NONE`はオフスクリーンバッファを使わない通常描画、`LAYER_TYPE_HARDWARE`は明示的なハードウェアテクスチャ、`LAYER_TYPE_SOFTWARE`はCPU描画と説明されている。[1]

現行ねこぶらうざは`LAYER_TYPE_NONE`を設定している。Activity自体がハードウェアアクセラレーション有効であれば、これはAndroidの通常GPU合成を使う。FulgurisのHardwareと同一ではないが、動画表示を改善するために常時Hardwareへ固定する根拠はない。明示Hardware layerは動画メモリを余分に消費し、レンダラ終了を増やす可能性がある。Software layerはWebGLを無効にできる反面、動画surfaceの合成には適さない。[1]

`loadWithOverviewMode`は、ページ読込時にコンテンツを画面幅へ収めるための初期縮尺設定であり、WebGL・動画デコーダ・YouTube iframeの描画可否を制御しない。[2] Fulgurisはユーザー設定として提供しており、通常viewportは`useWideViewPort=false`、desktop mode時だけtrueにしている。現行アプリも通常表示では同じ`useWideViewPort=false`と`loadWithOverviewMode=false`を採用済みである。

従って、WebGLやoverview modeは「表示幅や端末固有の合成差を試験する設定」としては有用だが、Google動画タブの音声だけ残る黒画面の主因ではない。主因候補は、動画ページへの暗色化の誤適用、WebViewのrenderer終了、またはWebViewへの追加処理である。

## 描画プロセスが終了しました、の意味

これはアプリの通常UIがページを閉じた文言ではない。`WebViewClient.onRenderProcessGone`が呼ばれ、Chromium WebViewのrenderer processがクラッシュまたはシステムから終了させられた状態である。[3] 現行コードは無限再生成を避けるためWebViewを破棄し、UI callbackが独自ホームを開く構成だった。つまり「ホームへ戻りました」はアプリ側の安全策であり、WebView renderer終了そのものの既定挙動ではない。

Fulgurisは同じ通知を受けると、タブのURL・状態をfreezable modelへ退避してWebViewを破棄し、現在のタブを再作成してSnackbarを表示する。そのためホームへ飛ばず、同じタブに留まる。ねこぶらうざもこの復帰方針へ変更する。

## 暗色化と動画

現行121e47b型CSSはbody全体を反転した後、`video`へ二重反転を付けて正常色へ戻す方式である。全画面custom viewは元文書のCSS継承外に出るため、WebViewのAlgorithmic DarkeningやForce Darkが動画surfaceへ影響すると全画面だけ色が反転し得る。動画ページでは、ユーザーが「動画サイトにも暗色化を適用」をONにしていても、WebView標準のalgorithmic/force darkは常にOFFとし、ページCSSによる二重反転だけを許可する。これにより動画surfaceへのプラットフォーム反転を防ぐ。

## 参考文献

[1] Android Developers, Hardware acceleration: https://developer.android.com/develop/ui/views/graphics/hardware-accel

[2] Android Developers, WebSettings: https://developer.android.com/reference/android/webkit/WebSettings

[3] Android Developers, WebViewClient.onRenderProcessGone: https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail)

## Soul／Berryブラウザの参照結果

Soul Browserの配布情報と利用者の公開報告から、SoulもAndroid System WebViewを描画エンジンとして利用し、広告遮断、動画ダウンロード、UI編集、ダークテーマ、screen filterなどを上位層で提供していることを確認した。[4] [5] 一方で、Soul Browserのソースは公開されておらず、動画・WebGL・WebView設定の内部実装を参照・移植できる資料は確認できない。公開された利用者報告には、SoulでもYouTubeの全画面遷移時に停止や再開が起きるとの記録がある。従って「Soulでは安定して見える」ことはSystem WebViewの上で安定化できることの実例にはなるが、コピーできる独自エンジンはない。

Berry Browserについても、公開ソースとして描画経路を確認できなかった。Soulと同様、System WebViewベースのブラウザとして機能・挙動の比較対象にはできるが、内部のWebView最適化コードを直接取り込める根拠はない。

このため、今回採るべき参考点は、両者が独自ブラウザエンジンを持たないこと、そしてUI・広告遮断・ダークモードをWebView映像surfaceと重ねないことにある。ねこぶらうざは、動画ページに対してプラットフォーム強制暗色化を無効化し、Google動画タブには余分なresource interceptionを残さず、renderer終了はホームへ飛ばさずタブ内で再作成する。

[4] Soul Browser Google Play: https://play.google.com/store/apps/details?id=com.mycompany.app.soulbrowser

[5] Soul Browser discussion, Vivaldi Forum: https://forum.vivaldi.net/topic/105830/soul-browser-unique-and-more-customizable-ui-than-vivaldi-amdroid

[6] Soul Browser discussion, XDA Developers: https://xdaforums.com/t/soul-browser-a-litte-gem-youve-probably-never-heard-of.4159245/
