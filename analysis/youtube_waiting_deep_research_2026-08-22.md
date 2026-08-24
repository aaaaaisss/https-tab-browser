# YouTube初期再生待機の深掘り調査

調査日: 2026-08-22

## 実機結果

| 版 | 処理 | 横動画広告 | 初期待機 |
| --- | --- | --- | --- |
| `a994015` | Brave多層遮断、player応答除去、Shorts専用prune | 消える | 約15秒残る |
| `1c06e4d` / `922ed5f` | Brave SABR resourceのsession再取得と広告なしplayer補正 | 再出現 | 改善未確認 |
| `f37150b` | SABR protobufのbackoff fieldだけをpatch、session再取得なし | 消える | 残る |

## 確認できたYouTubeの三層

YouTubeのfake bufferingは、InnerTubeのplayer responseで渡されたGVS URLが最初のcontent `/videoplayback`にbackoffを含めることで発生する。広告を遮断すると、広告中に隠れていたbackoffだけがspinnerとして露出する。待機は広告時間の約80%となることがあり、約15秒という実機値と整合する。[1]

同時に、`isInlinePlaybackNoAd:true`を`playbackContext.contentPlaybackContext`へ入れたplayer requestでは、広告slotとGVS backoffが返らないとされる。[1] ただし直URLのcold navigationでは、player responseがサーバー側で`ytInitialPlayerResponse`として埋め込まれるため、クライアントから出るplayer requestへ補正できない。無理に初期responseを消すとlive・描画・読み込みを壊すため採用しない。

## 前回の失敗の意味

Brave公式scriptletの`forceFreshSession()`は、`cancelPlayback()`と`loadVideoById()`を使って新しいsessionを要求する。前回この処理を含めたところ横動画広告が戻った。これは新sessionが広告なし指定を得られない場合、広告slot付きGVS URLが返るためである。player request bodyの`Object.assign`補正も同時に入れたが、YouTubeのlocker scriptやcold navigationにより十分に早く・全経路で効かず、再生session操作のリスクを打ち消せなかった。

## uBlock Origin実装からの示唆

uBlockの`trusted-replace-outbound-text`は、対象関数をproxyし、元関数の返り値ではなく呼出し値のoutbound textを置換する。[2] YouTube向けの`isInlinePlaybackNoAd`対策は、`JSON.stringify`がlocker scriptで固定される場合に`Object.assign`へ切り替える必要があると報告されている。[1]

これは、単一の`Object.assign`ラッパーで全requestを確実に補正するのは不十分であり、warm navigationだけに限定して有効化・確認する必要を示す。

## 次の条件付き案

1. 既存の`f37150b` patch-onlyを維持し、広告を消せる現在のsessionには一切再読込を行わない。
2. `player` requestを実際にクライアントが発行する**warm navigation**だけで、uBlock方式に近いoutbound text hookを使い`isInlinePlaybackNoAd`を補正する。
3. cold navigation、live、Shorts、すでにvideoが再生開始済みの場合は絶対にsessionを作り直さない。
4. 新しいplayer requestが広告なし指定を含むことを確認できた場合だけ、SABR control responseのbackoff patchを有効にする。確認できない場合は安定版のresponse sanitizerだけを使う。
5. 初期responseを削除して強制的にplayer requestを作らせる方式は、liveと読み込みを壊すという公表済みの副作用があるため不採用。

## 参照

[1] https://iter.ca/post/yt-adblock/
[2] https://raw.githubusercontent.com/gorhill/uBlock/master/src/js/resources/scriptlets.js
[3] https://github.com/brave/brave-browser/issues/53930
[4] https://github.com/yuliskov/SmartTube/issues/5928
