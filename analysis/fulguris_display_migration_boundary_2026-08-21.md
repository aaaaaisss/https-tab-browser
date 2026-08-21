# Fulguris WebView表示経路の移植境界

## 結論

Fulgurisからサイト表示に関わる部分を移植することは可能である。ただしFulgurisはAndroid System WebViewを使うブラウザであり、YouTube・Google動画タブを独自のvideo decoderやChromium forkで描画しているわけではない。Fulgurisの表示経路を丸ごと移しても、YouTubeが自ら付ける`disablePictureInPicture`や、現在のBraveフィルタ統合がGoogle動画プレビューの要求を阻害する問題を自動的には解決しない。

したがって、下部バー・ホーム・設定を維持する条件では、最初にFulgurisの実際に表示へ効く最小部分を移植し、YouTube固有のPiP制限とGoogle動画タブの遮断境界を分離して直す。これで改善しない場合だけ、通常WebViewの親ViewまでComposeからActivity native hostへ広げて移す。FulgurisのActivity全体、RxJava、Hilt、XMLタブUI、独自のタブモデルまで移す必要はない。

| 表示要素 | Fulguris | 現行ねこぶらうざ | 判断 |
|---|---|---|---|
| WebView engine | Android System WebView | Android System WebView | 同じ。Fulgurisを全体移植してもengineは変わらない。 |
| 通常viewport | `useWideViewPort=false`。desktop mode時のみtrue | 現行はfalseへ修正済み | 移植済み。YouTubeの幅・初期縮尺に直接関与する。 |
| normal parent | XMLでinflateした`WebViewEx`をnative tab viewとして保持 | Compose `AndroidView`が通常WebViewを保持 | 追加のnative host移植は可能だが、下部UIの保持との統合が必要。直近の原因候補を除外してから実施する。 |
| fullscreen parent | decor/rootにnative `FrameLayout`を追加しcustom viewを一度だけ保持 | 現行はActivity rootのnative `FrameLayout`へ移行済み | 重要部分は移植済み。 |
| focus/highlight | `focusable=true`、`focusableInTouchMode=true`、`defaultFocusHighlightEnabled=false` | 明示設定なし | Fulguris同等に追加する。ページ全体にかかるfocus highlightを避ける。 |
| lifecycle | main-frame、page startで完了状態をrearm。`progress==100`の最初のfinishだけ処理 | 現行へ移植済み | 移植済み。 |
| request filtering | Fulguris独自ABP/ネットワークengineへ単純委譲 | Brave JNI、動画用保護、cosmetic分岐が追加 | Google動画タブの黒画面では現行固有の遮断境界が最有力。動画タブではBrave network filterを完全にバイパスして切り分ける。 |
| YouTube PiP制限 | System WebViewに委譲 | OS Activity PiPへ接続 | Brave issueでYouTube自身の`disablePictureInPicture`が候補と判明。document-startで属性解除を最小導入する。 |

## 次の実装境界

次の改修では、Fulguris由来のfocus/initial-focus設定、Google動画タブに対するBrave network filter完全バイパス、YouTubeの`disablePictureInPicture`属性を解除するdocument-start scriptを導入する。player response JSON、YouTubeのネットワーク応答、広告ブロックの大域的無効化は変更しない。

この段階で実機のYouTube表示とGoogle動画タブの映像表示が改善しなければ、通常WebViewも`MainActivity`のnative content hostへ移し、Composeはホームと操作バーだけを描画する第二段階へ進む。
