# Brave YouTube広告遮断・PiP改革の再調査

調査日: 2026-08-22

## 結論

現行のねこぶらうざはBrave `adblock-rust`をJNI経由で実際に使用しているが、ネットワーク判定のうちKotlin側へ公開しているのは`shouldBlock()`だけである。そのため、エンジンの結果に含まれる`redirect`（置換リソース）と`rewritten_url`（`$removeparam`）は未活用であり、Braveのフィルタエンジンを「完全に」活用しているとは言えない。

また、YouTubeでは再生保護のために`media`と`subdocument`を通常モードで通過させ、Google動画タブはネットワーク要求を全通過させている。これは映像の黒画面回帰を防ぐ保守策であり、広告遮断の最大化とは両立しない。攻めたモードではYouTubeの保護を解除するが、YouTubeのgeneric cosmetic規則はまだ動的適用していない。

## 公開情報から確認した事項

| 項目 | 確認内容 | 現行への示唆 |
| --- | --- | --- |
| `adblock-rust` | network blocking、cosmetic filtering、resource replacements、uBO構文拡張を提供する。 | JNI戻り値を拡張し、少なくとも`redirect`と`$removeparam`の適用可否を把握できるようにする。 |
| `BlockerResult` | `should_block`に加えて`redirect`と`rewritten_url`を返す。 | 現在は`should_block`のみをJNI公開している。リダイレクト本文はサブリソースの代替応答に使える。URL書き換えはWebViewのインターセプトAPIが任意の要求を再発行する仕組みを持たないため、主文書以外には安易に適用しない。 |
| Braveの標準遮断方針 | Standardはfirst-partyネットワーク遮断を限定し、Aggressiveはfirst-partyも対象にする。YouTubeのpre-roll/mid-roll遮断を目標に含める。 | ねこぶらうざの攻めたモードはYouTube固有の保護を解除してfirst-party要求もエンジンへ渡す設計へ寄せる。 |
| Brave Android PiP修正 PR #28593 | `disablePictureInPicture`の解除だけでなく、`window.ytcfg`内のYouTube実験フラグ5件を`true`から`false`へ書き換えるdocument-start相当の最小JSを使う。 | 現在の属性解除に、同じ対象フラグの安全な置換を追加する。YouTube本体の構成が存在しない場合は何もしない。 |
| Brave Android PiP最新PR #36838 | Braveの完全なPiP制御は`WebContents`、`MediaSession`、`FullscreenManager`、Chromiumのfullscreen controllerを統合する。 | System WebViewではこの部分をコピーできない。Activity PiPで到達できる範囲は、全画面custom viewの維持、正確なsourceRectHint、auto-enter、UI非表示、ページ側PiP阻害解除までである。 |
| Android公式PiP | API 26以上でActivity PiP、Android 12以降は`setAutoEnterEnabled(true)`と事前の`sourceRectHint`更新が推奨される。PiP中は動画以外のUIを隠す必要がある。 | 現行は大部分を実装済み。今回、Brave相当のYouTube page-side解除と、PiP遷移中に不要UIを先行非表示にする余地を評価する。 |

## 実装方針の候補

1. faviconは`ContentScale.Crop`とpadding除去により、16dpの外枠に対して取得済み画像を全面表示する。サイト側faviconが正方形でない場合は端が切れることを許容する。
2. Brave JNIに「ネットワーク決定」APIを追加し、遮断・インラインredirect本文・`$removeparam`結果を構造化JSONで返す。通常モードは既存の再生安全策を維持し、攻めたモードはYouTubeとGoogle動画タブを含めてBrave評価へ渡す。
3. 攻めたモードでは、YouTubeにもhostname-specific selectorに加えgeneric cosmetic selectorを遅延一回適用する。例外規則は引き続きnative engineから得る。
4. YouTube専用document-startスクリプトに、Brave #28593と同じ`ytcfg`のPiP阻害フラグ無効化を追加する。`disablePictureInPicture`属性の解除・追加監視も維持する。
5. 置換応答は、Braveが返すインラインresourceの存在時だけサブリソースへ適用する。MIME型が識別できない応答を動画・主文書へ適用しない。`$removeparam`はWebViewの安全なrequest rewrite経路がないため、まずログ・検証対象とする。

## 参照

1. https://github.com/brave/adblock-rust
2. https://docs.rs/adblock/latest/adblock/blocker/struct.BlockerResult.html
3. https://github.com/brave/adblock-resources
4. https://github.com/brave/brave-browser/wiki/Blocking-goals-and-policy
5. https://github.com/brave/brave-core/pull/28593
6. https://github.com/brave/brave-core/pull/36838
7. https://developer.android.com/develop/ui/views/picture-in-picture

> 留意事項: BraveのChromium内部PiP controllerはSystem WebViewで利用できない。よって、同じ成果を保証するのではなく、公開されたページ側のYouTube制限解除とAndroid Activity PiPを最大限組み合わせる。
