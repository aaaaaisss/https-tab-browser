# Brave YouTube広告遮断・PiP改革: 実装計画

## 目的

タブfaviconは取得済みサイト画像を16dpの円形領域へトリミング前提で全面表示する。YouTube広告遮断は、Brave `adblock-rust`の実行結果をこれまでの単純なbool判定から構造化し、通常・攻めたモードの差を明確にする。YouTube PiPは、現行のActivity PiPを維持しつつ、Braveが公開したページ側のPiP阻害解除を追加する。

## 設計

| 層 | 通常モード | 攻めた広告遮断モード | 実装上の境界 |
| --- | --- | --- | --- |
| Brave network rules | YouTubeの`media`・`subdocument`だけは再生保護として通す。広告/計測およびscript/XHRは評価する。Google動画タブは既存どおり全通過。 | YouTube・Google動画タブを含め、全resource typeをBraveエンジンへ渡す。 | 映像chunkが規則により遮断された場合は再生失敗の可能性がある。これはユーザーが選択した攻めたモードの性質であり、通常モードへ戻せる。 |
| Brave resource replacement | APIを構造化し、`$redirect`のdata URLを返せるようにする。 | 同左。 | Android WebViewの`shouldInterceptRequest`は任意URLの安全な再発行APIを持たない。data URL本文をMIME・サイズ検証なしに応答へ流用しないため、今回のリリースでは検出・診断を追加し、置換応答の直接適用は次回の単体検証後に限定する。 |
| hostname cosmetic | 明示的なYouTube広告CSSと安全性確認済みselectorだけ。 | BraveのYouTube hostname selectorを最大2000件に拡張し、明示広告CSSと併用する。 | player/containerを消すselectorも入り得るため、問題時は攻めたモードのみOFFで即座に元へ戻る。 |
| generic cosmetic | YouTubeとGoogle検索は適用しない。 | YouTubeでのみ、document完了後にclass/idを収集してBraveのgeneric cosmeticを一度適用する。Google動画タブには適用しない。 | Google検索の動画プレビューの黒画面回帰は再導入しない。 |
| YouTube PiPページ側 | `disablePictureInPicture`属性・プロパティを解除し続ける。 | 同左。 | `ytcfg`が存在する場合に限り、Brave #28593と同じ5つのPiP阻害実験フラグを`false`へ置換する。存在しない場合はno-op。 |
| Android PiP | native fullscreen custom view、sourceRectHint、auto-enter、PiP中UI非表示を維持する。 | 同左。 | Braveの`WebContents`/`MediaSession`/`FullscreenManager`ベースcontrollerはSystem WebView API外のため移植しない。 |

## JNI変更

`nativeShouldBlock()`は現行どおり遮断可否を返し続ける。追加で`nativeNetworkDecisionJson()`を導入し、`shouldBlock`、`hasRedirect`、`rewrittenUrl`を返す。まず実トラフィックに対してBraveがresource replacementと`$removeparam`を返しているかを可視化する。これにより、実際に活用されていないBrave判定を見える状態にする。

`redirect`本文はadblock-rustではdata URLとして返る。WebViewの既存インターセプトは結果をストリームとして返すのみで、任意のサブリソースURLを書き換えてネットワークへ再発行する公式APIを提供しない。そのため、本文を無検証で返すのはMIME誤判定・サイズ増大・ページ破損を招きやすい。今回はJSONに`hasRedirect`のみを公開し、当該data URL本体をKotlin/JNI境界へ運ばない。

## 検証

1. Rust unit testで通常規則・redirect規則・`$removeparam`の構造化判定を検証する。
2. Kotlin unit testでJNI JSONの不完全値を安全に処理する。
3. Android Lint・unit test・Release assembleをGitHub Actions上で実行する。
4. 実機では通常モードと攻めたモードを分け、YouTube動画再生、広告、Shorts、Google動画タブ、全画面、PiP遷移・復帰を確認する。

> この変更によって「Brave Chromiumのネットワーク層やPiP controllerをそのまま使える」状態にはならない。System WebViewが公開していないChromium内部APIに依存するためである。一方、公開済みのBraveページ側PiP阻害解除、Brave filter engineのnetwork/cosmetic/scriptlet判定、およびAndroid公式PiPは、ねこぶらうざ内で明示的に最大化する。
