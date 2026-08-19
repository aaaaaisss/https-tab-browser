# HTTPS Tab Browser — コード一式

このフォルダには、指定された仕様を実装する **Kotlin + Jetpack Compose + WebView** の Android ブラウザコードだけを入れています。GitHub 連携、ログイン処理、公開設定、クラウドビルド設定は含めていません。

## 1. 先に確認すること

このコードは Android アプリです。ビルドには次の環境が必要です。

| 必要なもの | バージョン・内容 |
|---|---|
| JDK | 17 |
| Android SDK Platform | Android 35 |
| Android SDK Build Tools | 35.0.0 |
| Gradle | 同梱の Gradle Wrapper を使用 |
| Android 最低対応版 | Android 8.0（API 26） |

> Android Studio は必須ではありません。JDK と Android SDK が入った環境なら、同梱の `gradlew` でビルドできます。

## 2. 保存するフォルダ構成

ZIP を展開した後、フォルダ構成を変えずに保存してください。特に Kotlin ファイルは `app/src/main/java/com/example/httpsbrowser/` 以下に置く必要があります。

```text
https-tab-browser-code/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/httpsbrowser/
        │   ├── MainActivity.kt
        │   ├── data/
        │   ├── ui/
        │   └── web/
        └── res/
```

## 3. 初回ビルドの順序

### 3-1. Android SDK の場所を指定する

プロジェクト直下に `local.properties` を作り、Android SDK の実際の場所を指定します。Windows ではバックスラッシュを二重にしてください。

```properties
# macOS / Linux の例
sdk.dir=/Users/your-name/Library/Android/sdk

# Windows の例
# sdk.dir=C\:\\Users\\your-name\\AppData\\Local\\Android\\Sdk
```

### 3-2. Debug APK を作る

macOS / Linux では、プロジェクト直下で次を実行します。

```bash
chmod +x gradlew
./gradlew assembleDebug
```

Windows では次を実行します。

```bat
gradlew.bat assembleDebug
```

正常に完了すると、APK は次に生成されます。

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 4. 各ファイルの役割

| ファイル | 役割 |
|---|---|
| `app/build.gradle.kts` | Android SDK、Kotlin、Compose、WebKit、DataStore の依存関係を指定します。 |
| `AndroidManifest.xml` | インターネット、カメラ、マイク、位置情報、通知の権限と HTTPS 通信方針を指定します。 |
| `res/xml/network_security_config.xml` | HTTP 平文通信をアプリ全体で拒否します。 |
| `MainActivity.kt` | アプリの起動点です。 |
| `data/BrowserModels.kt` | タブ、履歴、ブックマーク、設定、候補のデータ構造です。 |
| `data/BrowserRepository.kt` | タブ、履歴、ブックマーク、設定を端末内へ保存・復元します。 |
| `data/AdBlocker.kt` | HTTPS URL の広告ブロックリストを取得し、URL 規則へ変換します。 |
| `ui/BrowserViewModel.kt` | Google 固定検索、検索候補、タブ、履歴、ブックマークの状態を管理します。 |
| `web/BrowserWebView.kt` | WebView の HTTPS 強制、混在コンテンツ拒否、広告遮断、動画全画面、ダウンロード、権限確認を実装します。 |
| `ui/BrowserControls.kt` | アドレスバー、候補一覧、三段の下部操作バー、タブバー、ショートカット、スクロール操作です。 |
| `ui/BrowserSheets.kt` | タブ一覧、履歴、ブックマーク、広告リスト、設定の下部シートです。 |
| `ui/BrowserScreen.kt` | WebView と全 UI を結合するメイン画面です。 |

## 5. 実装されている機能

| 分類 | 内容 |
|---|---|
| 検索 | URL 以外の入力は Google 検索へ送信します。検索結果では検索語を保持し、アドレスバーをタップすると検索語全体を選択します。 |
| タブ | 追加、閉じる、一覧、選択中タブの色付き枠、端末内へのセッション復元に対応します。 |
| 操作配置 | 画面下部から、タブバー、戻る・検索・進む等の操作バー、アドレスバーの順に積み上げます。左にショートカット、右にページスクロール操作を置きます。 |
| HTTPS | `http://` を `https://` へ置き換えて試行し、HTTP 平文通信と混在コンテンツを拒否します。 |
| 広告ブロック | HTTPS のリスト URL を登録し、ドメイン・URL 規則でリクエストを遮断します。 |
| 動画 | HTML5 の全画面要求時に、操作 UI とシステムバーを隠します。 |
| 権限 | カメラ、マイク、位置情報はサイト要求ごとに確認します。 |
| データ | 履歴、ブックマーク、閲覧データ消去、ダウンロード管理を含みます。 |
| ダークモード | WebView のアルゴリズム暗色化とアプリ UI のダークテーマに対応します。 |

## 6. 最初に動作確認する順序

1. アプリを起動し、Google のトップページが開くことを確認します。
2. `manus` などを検索し、検索結果画面でアドレスバーをタップします。
3. URL ではなく `manus` が選択されることを確認します。
4. 新しいタブを追加し、タブ一覧、戻る、進む、閉じるを確認します。
5. `http://example.com` を入力し、HTTPS へ昇格または拒否されることを確認します。
6. 設定で広告ブロックリストの URL を追加し、更新します。
7. 動画サイトで全画面を選び、操作 UI が隠れることを確認します。
8. 履歴・ブックマーク・閲覧データ消去を確認します。

## 7. 注意点

広告ブロックはネットワークリクエストの URL 規則を対象にします。CSS による要素非表示、スクリプトレット、サイト固有の広告枠除去までは実装していません。また、HTTPS 専用のため、HTTPS を提供しないサイトには接続しません。

このフォルダはコード専用です。GitHub のログイン、リポジトリ公開、認証情報、クラウドビルドに関する設定は含めていません。
