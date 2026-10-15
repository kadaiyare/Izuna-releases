# ソースからビルドする

Android 16（API 36）以降向けです。JDK 25、Gradle Wrapper 9.6.0、AGP 9.4.1、SDK Platform 37.0、Build Tools 36.0.0、NDK 28.2.13676358、CMake 3.22.1を使用します。

## 準備

1. Android StudioとJDK 25、必要なSDK・NDK・CMakeを導入します。
2. Git対象外のlocal.propertiesへSDKのパスを設定します。
3. [Discord Developer Portal](https://discord.com/developers/applications)で公式Social SDK 1.10.19337のrelease AARを取得し、vendor/discord/discord_partner_sdk.aarへ配置します。
4. 自分が管理するApplication IDを設定します。Bot Token・ユーザートークン・Client Secretは使用しません。

```properties
sdk.dir=C:/path/to/Android/Sdk
discord.applicationId=自分のApplicationの数字ID
discord.sdkVersion=1.10.19337
```

SDKは提供元の利用条件に従って取得してください。本体のビルドにはAARが必要です。SHA-256を.github/discord-sdk.jsonと照合します。Last.fm開発用のAPI情報は任意で、releaseへ継承しません。

## 通常のビルド

Windows PowerShellではJAVA_HOMEとANDROID_HOMEを環境に合わせて指定し、次を実行します。

```powershell
. ./tools/dev-env.ps1
./gradlew.bat :app:assembleDebug :app:lintDebug
```

LinuxなどではJDK・SDKの環境変数を設定し、gradlewを使用します。debug APKはapp/build/outputs/apk/debug/app-debug.apkです。独自debug署名は配布版と異なるため、既存配布版へ上書きできません。

自動テスト、独立した検証アプリ、テスト音源生成ツールは非公開の開発リポジトリで管理します。この公開リポジトリには含めません。配布版のテストは非公開側のリリース工程で実行します。

## Discord SDK検査

```text
python tools/check_discord_sdk.py vendor/discord/discord_partner_sdk.aar --sdk-version 1.10.19337
```

現行AARには既知の16 KB RELRO終端不一致があり、厳密検査は失敗します。条件を緩めて成功扱いにしないでください。

## 署名と秘密情報

配布用の署名鍵は公開していません。自分のreleaseを作る場合は自分の鍵を使用します。tools/build_release.pyはANDROID_KEYSTORE_PATH、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS、ANDROID_KEY_PASSWORD、またはGit対象外の署名設定JSONが必要です。

独自の鍵は公式配布署名と異なります。認証値・local.properties・AAR・キーストア・ビルド成果物・実曲データをGitへ追加しないでください。[署名検証](verify-download.md)・[既知の制限](../KNOWN_ISSUES.md)
