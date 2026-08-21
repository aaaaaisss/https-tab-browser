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

## 2026-08-21 追加判断: 描画安定性

WebGLとoverview modeを全面移植の根拠にはしない。FulgurisのHardware layerはWebGLを有効にできる選択肢だが、現行の`LAYER_TYPE_NONE`もハードウェアアクセラレーション有効なActivity上では通常GPU合成である。明示Hardware layerの恒久利用は動画メモリを増やし、renderer process終了を悪化させ得る。Software layerへの固定はWebGLを切るため、動画再生の既定経路には採用しない。

overview modeは初期縮尺の設定であり、動画デコーダ、iframe、WebGL、renderer processを安定化する機構ではない。Fulgurisの通常表示と同じ`useWideViewPort=false`、`loadWithOverviewMode=false`を維持する。端末別のページ幅問題を解く必要が生じた場合のみ、個別設定として追加して実験する。

今回の実装では、Fulgurisから実際に表示に寄与する以下を採用済みである。通常モバイルviewport、focus highlight無効化、touch focus、initial focus抑止、ページ完了状態の再armと一回化、Activity native fullscreen container、renderer process終了時のタブ内復旧方針である。

動画表示の安定性を優先し、動画ページではWebView標準のalgorithmic darkeningとForce Darkを常に無効化する。ユーザーが動画サイト暗色化を有効にした場合もページCSSだけを使い、video要素は二重反転で正常色へ戻す。このため全画面custom video surfaceがWebView標準暗色化で反転する経路を排除する。

現時点では通常WebViewをComposeからActivity native hostへ移す広範移植は保留する。理由は、Fulgurisも同じSystem WebViewを使っており、WebGL・overview mode・通常WebView親ViewだけではGoogle動画タブの黒画面を直接解決しないためである。今回の動画暗色化分離、Google動画タブのinterception撤去、rendererタブ内復旧を実機確認後も問題が残る場合にのみ、通常WebView hostのActivity移行を次段階として実施する。
