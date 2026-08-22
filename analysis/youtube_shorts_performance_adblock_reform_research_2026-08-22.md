# YouTube Shorts・読み込み時間・広告遮断の最適化調査

調査日: 2026-08-22

## 現状監査

ユーザー実機では、横動画の広告表示は減った一方、動画開始前の待機時間が長くなった。Shortsではスポンサー動画が残る。

| 観測 | 原因候補 | 対応 |
| --- | --- | --- |
| 横動画の開始待機 | 現在のYouTube補助は対象playerレスポンスを`JSON.parse`し、オブジェクトを再帰走査して`JSON.stringify`する。さらにAdGuard BaseとEasyList単体の101が重複しており、全要求のnative規則評価が増えている。 | player応答では全文JSON走査をやめ、広告キーだけを文字列置換する。EasyList単体101をBaseへ統合して削除する。 |
| Shortsスポンサー動画が残る | `reel_watch_sequence`で広告判定が配列の深部にあり、現行の`isAd`条件が`isAd`/`adClientParams.isAd`だけで表現不足。また通常player応答と同じ全JSON処理が必要以上に走る。 | Shorts URLだけで専用の再帰削除を許可し、`isAd`, `adClientParams.isAd`, `adVideoId`, `adBadge`等の広告構造を判定する。 |
| Baseと101の重複 | 101の公式ヘッダは「EasyListでありAdGuard Baseにすでに含まれる」と明記する。 | 101を標準リストから外す。Base、Japanese、Brave Specificの3本へ整理する。 |

## Braveの未使用・周辺施策

`adblock-rust`はネットワーク遮断、cosmetic、resource replacement、uBlock構文拡張、resource/scriptletを提供する。現行アプリではこれらのWebViewで安全に適用可能な経路を使用中である。Braveの最新ブラウザはFlatBuffersなどの内部エンジン最適化を採用しているが、アプリは`adblock-rust 0.13.3`に固定しており、この大規模なストレージ形式更新を直接バックポートする対象にはしない。

Braveの公開issue #679では、YouTube上のjson-edit scriptletが再生前広告の挙動を悪化させ得る事例が報告された。ねこぶらうざのpage-side JSON処理も、対象を広げすぎると同種の初期再生悪化を招く。したがって、横動画ではキー名の軽量置換に限定し、再帰処理はShortsの広告配列にだけ適用する。

## 代替エンジンの評価

Adblock Plus Android WebViewはWebView向けresource filtering・element hidingを備える一方、HTTP(S)要求を`HttpURLConnection`で独自に処理しCookie同期も行う。現行のSystem WebViewのログイン・Chromium動画経路・native fullscreen/PiPとの二重ネットワーク層になるため、YouTube再生の遅延を減らす追加エンジンではない。置換導入は採用しない。

AdGuard CoreLibは個人用OSSとして無制限に組み込む代替ではない。DNS/VPN/hosts系はYouTubeのfirst-party広告レスポンスを分離できず、Shortsスポンサー対策にはならない。

> 結論: 追加エンジンを重ねると、WebViewのrequest interceptionと動画配信の重複評価により待機時間を悪化させる。Brave adblock-rustを単一のネットワークエンジンとして維持し、重複リストの削除、Brave公式補助、YouTubeの限定page-side処理を組み合わせることが最も軽量で効果的である。

## 実装方針

1. EasyList単体のAdGuard 101を削除する。AdGuard Base、AdGuard Japanese、Brave Specificを標準とする。
2. 通常横動画のplayer/get_watch/playlist応答は、`adPlacements`、`playerAds`、`adSlots`、`adBreakHeartbeatParams`を直接無効化する軽量文字列処理だけにする。
3. Shorts `reel_watch_sequence`だけはJSONを解析し、広告マーカーを持つentriesを配列から削除する。`adVideoId`、`adBadge`、`adClientParams.isAd`、`isAd`を対象にする。
4. 既存のBrave network/cosmetic/scriptlet/redirectは維持し、攻めたモードだけが動画・iframeを含む全リソース評価を行う。

## 参照

1. https://github.com/brave/adblock-rust/issues/679
2. https://github.com/brave/adblock-rust
3. https://brave.com/privacy-updates/36-adblock-memory-reduction/
4. https://github.com/adblockplus/libadblockplus-android
5. https://github.com/uBlockOrigin/uAssets/blob/master/filters/filters.txt
6. https://filters.adtidy.org/android/filters/101_optimized.txt
7. https://filters.adtidy.org/android/filters/2_optimized.txt
