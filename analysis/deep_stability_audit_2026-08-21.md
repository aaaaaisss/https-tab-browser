# ねこぶらうざ 根本安定性監査

作成日: 2026-08-21（日本時間）
開始コミット: `2d51208`

## 監査方針

実機が手元にない期間は、実機依存の見た目を推測で変更しない。その代わり、WebViewのライフサイクル、状態復元、JNIハンドル、フィルタ更新、ストレージ、OS連携をコードと公式仕様に照らして確認する。ユーザー端末への中間APK送付や追加通信を発生させず、最後の安定版だけを提供する。

## 初期棚卸しで判明した重点項目

| 優先度 | 領域 | 現状の構造 | リスク | 改善方向 |
|---|---|---|---|---|
| 高 | 広告遮断エンジン復旧 | `compilation_in_progress` が残ると、次回起動時にコンパイルを丸ごと中止する。 | プロセス終了・ストレージ例外の後、広告遮断が永続的に未準備となり得る。 | 古いフラグを復旧対象として扱い、キャッシュ復元または再コンパイルへ必ず進む。 |
| 高 | WebView履歴表示 | タブの `canGoBack` / `canGoForward` を永続化する一方、WebView履歴自体は永続化していない。 | プロセス再起動後に戻る・進むボタンだけが有効に見え、操作が無効になる。 | 永続化から履歴可否を外し、復元直後は両方 false に正規化する。 |
| 高 | private tabの期待値 | タブ・履歴は保存しないが、Cookie/WebStorageは通常タブと同一プロファイル。 | 「シークレット」と表示されても、認証Cookie等は同一アプリ内で共有される。 | 端末WebViewのプロファイル分離APIの可用性を調べ、未対応端末では制約を正確に示す。全Cookie削除のような通常タブを壊す対策は採らない。 |
| 中 | WebView生成とUI再構成 | Composeが再構成されるたびcallbacksを作り直す。WebViewはregistryで保持する。 | 不適切な破棄や非同期コールバックが古いUI状態を操作する可能性。 | callbackの最新性、renderer gone時、fullscreen時の所有権を検査し、必要時のみライフサイクルを単純化する。 |
| 中 | フィルタリスト更新 | 更新Workerはファイル更新のみで、現在の画面のエンジンへ即時反映しない。 | 「自動更新」しても再起動まで遮断結果が変わらない。 | 更新後に安全な再コンパイル/差し替えを行う仕組みを検討する。ただし再生中の動画へ影響しない時点でのみ交換する。 |
| 中 | cosmetic filtering | 一般サイトで最大500のホストCSSと、class/id全走査を行う。 | 動的サイトの表示遅延・偽陽性・動画UIとの競合。 | 適用量、ページ種別、遅延処理の世代管理を見直し、ネットワーク遮断を主経路とする。 |
| 中 | 翻訳 | テキストノードを置換する方式で、iframe/Shadow DOM/動的UIを扱わない。 | 「翻訳済み」と出ても対象が見えないケースが残る。 | 件数・適用成否を正確に通知し、元ページを壊さない範囲の再走査を検証する。 |
| 中 | 自動バックアップ | `android:allowBackup="true"`。 | 閲覧履歴・ブックマークなどが端末の自動バックアップ対象になり得る。 | 個人利用のプライバシー優先として自動バックアップの無効化を検討する。アプリ更新時のデータ保持には影響しない。 |
| 低 | 権限 | 通知権限が宣言されているが、アプリコードから要求していない。 | 必要性が不明な権限宣言は最小権限原則に反する。 | 使用経路を確認し、不要なら削除する。 |

## 確認済みの既存安全策

| 領域 | 既存の対策 |
|---|---|
| HTTPS | メインフレーム遷移をHTTPSへ昇格し、失敗時は接続を拒否する。SSLエラーは常にキャンセルする。 |
| 外部アプリ | intent URIは通常ブロックし、ユーザーが明示確認したときだけ外部アプリを開く。 |
| WebView renderer停止 | `onRenderProcessGone` で破損したWebViewを再利用せず、無限再読み込みを避ける。 |
| ネイティブ遮断 | Rust/JNI各境界で失敗を閉じ、Java側では遮断不能時にページ読み込みを継続する。 |
| scriptlet信頼境界 | ユーザー追加リストにはページJavaScript注入を許可しない。 |
| ダークモード | 動画ドキュメントをDark Reader・背景CSS・広範なcosmetic適用から除外する。 |
| PiP | fullscreen custom viewをCompose層の上に保持し、Android 12以降のauto-enterに最新領域ヒントを渡す。 |

## 次の監査対象

1. Braveエンジンの復旧、アトミック差し替え、WorkManager更新が現行画面へ与える影響。
2. WebView Profile APIとprivate tabの現実的な隔離範囲。
3. AndroidX WebKit / Android公式資料に基づくrenderer停止、状態保存、ダークモードの最新仕様。
4. Fulguris、Lightning Browser、Privacy Browser、Cromite、Brave関連プロジェクトの状態管理・遮断・プライバシー方針。ただしコード複製はしない。
5. 変更案ごとの自動検証可能性と、実機確認が不可欠な項目の明確な分離。

## 外部調査の要点と採用基準

| 参照 | 確認できた事項 | 改修判断 |
|---|---|---|
| Android Developers: WebView termination | renderer停止後のWebViewは再利用せず、参照を外して破棄し、新しいWebViewを作る必要がある。特定URLの即時再読み込みはクラッシュループになり得る。 | 現行の破棄方針は維持しつつ、ユーザーを単にホームへ飛ばすだけでなく、URLを安全に保持して明示的な再試行を可能にする案を検討する。 |
| Android Developers: Manage WebView | Safe Browsingは有効維持を推奨し、`onSafeBrowsingHit`でback-to-safetyを定義できる。WebView package versionの記録は端末依存バグの切り分けに有用。 | 危険ページの処理と、URLを含まないWebView実装バージョン診断を追加候補にする。 |
| Android Developers: Auto Backup | `allowBackup=true`ではshared preferencesとfilesDirの大部分がGoogle Drive backup対象。センシティブ情報を扱うアプリは無効化または明示的除外を選べる。 | 個人利用ブラウザでは履歴・タブ・ブックマークの意図しない同期を避けるため、全自動バックアップを無効化する。更新インストール時のデータ保持には影響しない。 |
| Android Developers: WebView privacy | manifestの`WebView.MetricsOptOut`でWebView利用統計をアプリ単位で拒否できる。クラッシュ報告までは停止しない。 | 個人利用・最小通信の既定値としてメトリクス収集を拒否する。 |
| AndroidX WebKit ProfileStore / WebViewBuilder | profile分離は`MULTI_PROFILE`と実験的WebViewBuilderに依存し、対応していないSystem WebViewでは使えない。profile削除は生存WebViewがあると失敗し、削除も非同期。 | private tabを全端末で「Cookie完全隔離」と表示することは避ける。対応可否をfeature-gateする実装は今後の専用改修候補とし、今回の安定化では既存の通常WebView経路を壊さない。 |
| Fulguris / Lightning Browser | renderer停止時にWebViewを破棄してタブを再生成する。Lightningはincognito専用Activity/プロセス終了時にCookie・cache・WebStorageを一括削除し、通常モードと混在させない構造を採る。 | 現在の同一Activity・同一WebView profile内のprivate tabには根本制約がある。通常ログインを壊す全Cookie削除は導入しない。private tabの保存除外は維持し、隔離の可否を将来の機能対応に分離する。 |

### 参照URL

1. https://developer.android.com/develop/ui/views/layout/webapps/handle-termination
2. https://developer.android.com/develop/ui/views/layout/webapps/managing-webview
3. https://developer.android.com/identity/data/autobackup
4. https://developer.android.com/develop/ui/views/layout/webapps/webview-privacy
5. https://developer.android.com/reference/kotlin/androidx/webkit/ProfileStore
6. https://developer.android.com/reference/kotlin/androidx/webkit/WebViewBuilder
7. https://github.com/Slion/Fulguris
8. https://github.com/anthonycr/Lightning-Browser

## 実装した改善

| 領域 | 実装 | 期待する効果 |
|---|---|---|
| フィルタエンジン復旧 | 残留した`compilation_in_progress`を永続停止条件にせず、診断を残して削除し、キャッシュ復元または再コンパイルへ進むよう変更した。 | 強制終了や更新中断の後にも広告遮断が次回以降ずっと無効になる状態を防ぐ。 |
| フィルタエンジン差し替え | 新engineの生成・ルール数確認が成功してから`activeHandle`を交換し、失敗時は候補engineだけを破棄して旧engineを維持する構造へ変更した。 | フィルタ更新失敗時に既存の広告遮断まで失わない。 |
| 履歴UIの整合性 | `canGoBack`/`canGoForward`を永続化せず、プロセス再生成後はfalseからWebView実測で更新するよう変更した。 | 有効に見える戻る・進むボタンが実際には何もしない回帰を防ぐ。 |
| 最小通信・プライバシー | 自動バックアップを停止し、Android 11以下・12以降の除外規則も明示した。WebView利用統計をmanifestでopt-outした。 | 履歴・タブ・ブックマークの意図しないクラウド同期と、任意のWebView利用統計送信を防ぐ。アプリの上書き更新時のデータ保持には影響しない。 |
| 危険ページ | `WebViewClientCompat`のSafe Browsing応答を実装し、対応端末では報告なしでback-to-safetyを実行する。 | フィッシング等に対して既定の判断を安全側へ固定する。 |
| 端末依存診断 | URLやページ本文を記録せず、WebView providerのpackage名・versionだけを診断記録へ追加した。 | YouTube描画・暗色化・renderer停止の端末依存差を切り分けやすくする。 |
| Cookie性能 | `onPageFinished`ごとの同期flushを750msの遅延集約へ置き換え、タブ破棄・renderer停止時は保留処理を解除し、アプリ終了時にはflushする。 | 連続遷移・動画ページでの主スレッドI/O負荷を抑え、ログイン保持を維持する。 |

## 意図的に今回導入しない案

| 案 | 見送る理由 |
|---|---|
| 実験的WebViewBuilderによるmulti-profile private tab | AndroidXライブラリ側でなく、端末のSystem WebView機能にも依存する。非対応端末の挙動分岐・profile削除の非同期性・生存WebViewとの競合を、実機なしで既存ログインへ導入するのはリスクが高い。 |
| private tab終了時の全Cookie/cache/WebStorage削除 | 同一プロファイルに通常タブのログインも存在するため、通常セッションまで壊す。Lightning Browserの専用Activity/終了構造とは前提が異なる。 |
| WebView履歴のBundle永続化 | 公式仕様上、保存状態はトランザクション容量制限に注意が必要。多数タブ・動画ページでのプロセス再生を安定化させるには専用の状態サイズ管理が必要であり、今回の自動復元誤表示修正より大きなリスクを持つ。 |
| 動画・Google検索に対する追加CSSやDOM操作 | ユーザーが報告した黒白オーバーレイの再発を避けるため、実機診断なしに動画再生経路へ新たな注入を加えない。 |

## ローカル検証状況

`git diff --check` は通過した。サンドボックスにはRust toolchain（`cargo`）が存在せず、Rust unit testはここでは実行不能だった。最終的なRust test、Android build、unit test、lint、署名APK検証は既存GitHub Actionsワークフローで実行する。
