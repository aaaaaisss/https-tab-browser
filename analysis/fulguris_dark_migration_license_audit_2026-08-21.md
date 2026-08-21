# Fulguris暗色化コード移行: ライセンス・適用範囲監査

作成日: 2026-08-21（日本時間）

> 注意: これは実装上のライセンス整理であり、法的助言ではない。外部配布や公開を行う場合は、CPAL-1.0の適用・通知表示・ソース公開条件を有資格者にも確認すること。

## 確認したライセンス

Fulgurisリポジトリの`LICENSE`は、commit `53528d0`以後にStéphane Lencludが単独著作権を持つファイルおよび新規ファイルを**CPAL-1.0**、それ以外を**MPL-2.0**としている。今回暗色化の中核として参照する`app/src/main/java/fulguris/view/WebPageTab.kt`には、Stéphane LencludとA.C.R. Developmentの著作権表示がある。このため、同ファイルから実質的なコードを複製・改変する場合、保守的にはCPAL-1.0の条件を満たすべきである。

| 条件 | CPAL-1.0で求められること | 本プロジェクトでの対応 |
|---|---|---|
| 対象ソース | 対象ファイルのライセンス表示・Exhibit A通知を保持する。 | 移行コードを専用ファイルに隔離し、原著作権・CPAL-1.0ヘッダー・元URL・変更日を冒頭へ記す。 |
| 変更記録 | 直接・間接に原コードから派生したこと、初期開発者名、変更内容・日付を記録する。 | `FULGURIS_CPAL_NOTICE.md`と専用ファイルの変更履歴で明示する。 |
| APK配布 | 実行形式を配布する場合、対応する対象ソースを同一媒体または一般的な電子手段で提供し、少なくとも所定期間利用可能にする。 | GitHubリポジトリ内で対象ファイル・CPAL-1.0本文・変更記録を公開し、APK通知と第三者通知からリンクする。 |
| UI帰属 | Fulgurisの公開TermsはCPAL §14に基づき、起動時に「Powered by Fulguris Browser」を目立つ形で表示することを指定している。 | Fulgurisコードを直接移行するなら、アプリ起動ごとにこの正確な帰属表示を含む短い通知を実装する必要がある。 |

## 個人利用について

個人だけが利用し第三者へAPKを渡さない場合でも、CPALで許諾される範囲内で利用・改変はできる。ただし、この作業では署名済みAPKとGitHubリポジトリを使うため、将来の再ダウンロード・共有を想定し、配布時のソース提供・通知条件まで満たす実装にする。個人利用は帰属表示義務を自動的に消す根拠にはしない。

## 移行方法の選択肢

| 方法 | 実現するもの | ライセンス・技術上の判断 |
|---|---|---|
| A. Fulgurisの暗色化部分を直接移行 | `applyDarkMode()`、`setColorMode()`、関連する旧端末画像再補正をファイル単位で改変して使用する。 | CPAL対象として起動時帰属、ソース提供、変更記録を実装する必要がある。依存・Activity構造への結合が強い。 |
| B. Fulgurisの挙動を、Android公式APIで独立再実装 | `setAlgorithmicDarkeningAllowed`、`setForceDark`、strategy、app dark themeの組合せを、ねこぶらうざの既存WebView registryに合う小さなcontrollerとして新規記述する。 | AndroidX APIはApache-2.0。Fulgurisのコードや構造をコピーしない限りCPAL由来コードにはならない。必要な動作は大部分が同じで、現行Compose/WebView構造に安全に適合する。 |
| C. Fulguris全体を大規模に取り込む | Tab/Activity/設定/テーマを広範囲に移す。 | CPAL対象範囲が増え、起動帰属・対象ソース公開・既存Compose実装との競合・回帰リスクが大きい。暗色化だけの目的には過剰。 |

## 技術的な結論

Fulgurisと同じ「ネイティブWebViewを主経路にする」結果は、AndroidX公式APIで達成できる。現在のねこぶらうざにも必要な`androidx.webkit`依存は存在する。したがって、安定性・保守性・ライセンスの明瞭さの観点からは**Bを基本**とし、Fulguris由来の直接コードは、ユーザーが起動時帰属表示を含むCPAL条件を受け入れる場合だけAとして隔離して採用するのが適切である。

## 参照

1. Fulguris LICENSE: https://github.com/Slion/Fulguris/blob/main/LICENSE
2. Fulguris WebPageTab: https://github.com/Slion/Fulguris/blob/main/app/src/main/java/fulguris/view/WebPageTab.kt
3. CPAL-1.0: https://opensource.org/license/cpal-1-0
4. Fulguris Terms & Conditions: https://slions.net/forums/resources.6/
5. Android WebView darkening: https://developer.android.com/develop/ui/views/layout/webapps/dark-theme

## 追加確認: Fulgurisの実際のWebView有効条件

Fulgurisは`app/src/main/res/layout/webview.xml`で`WebViewEx`へ`android:forceDarkAllowed="true"`を明示している。`WebPageTab.applyDarkMode()`は`setAlgorithmicDarkeningAllowed(settings, darkMode)`を呼び、Force Darkが利用可能でアプリテーマがdarkまたはタブのdark modeが有効な場合、strategy設定後に`FORCE_DARK_ON`を設定する。FulgurisはcompileSdk 35を使用している。

現行ねこぶらうざはAndroid 10+でAlgorithmic Darkeningを検出すると、`view.setForceDarkAllowed(false)`を設定している。これはFulgurisのWebView XML指定と逆である。Dark Readerを削除するだけでなく、この親View許可条件・テーマ連携・設定適用の単一化まで移行対象に含める必要がある。

## 直接移行に必須となるCPAL UI帰属

Fulgurisの公開Termsは「Powered by Fulguris Browser」をアプリ起動ごとに、snackbar等で見える形に表示することを指定している。したがってCPAL対象のFulgurisコードを直接採用する場合、この通知を省略できない。個人利用であっても、将来APKを再ダウンロード・共有できる状態にする以上、保守的にはこの条件を満たす必要がある。
