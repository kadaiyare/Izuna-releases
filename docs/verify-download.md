# APKのダウンロード検証

ReleaseからAPK、SHA256SUMS.txt、SIGNING-CERT-SHA256.txtを取得します。チェックサムはファイルの一致、証明書は署名の一致を確認する情報です。同じ配布元が提供するチェックサムだけで、その配布元自体の安全性を保証するものではありません。

## SHA-256

Windows PowerShellの例です。

```powershell
Get-FileHash ./Izuna-0.1.1.apk -Algorithm SHA256
```

APKのSHA-256は各ReleaseのSHA256SUMS.txtへ記載します。

SHA256SUMS.txtのAPK行と照合します。Linuxでは `sha256sum -c SHA256SUMS.txt` で添付ファイルを一括確認できます。

## 配布署名

Android SDK Build Toolsのapksignerがある場合は次を実行します。

```text
apksigner verify --verbose --print-certs Izuna-0.1.1.apk
```

配布証明書のSHA-256:

```text
b1a471c0a81af94b27cf8cfd4b11401b2d0683ec86a813db23c96231c8d5795b
```

開発用debug署名とは異なります。署名不一致でインストールできない場合、データを確認せず既存アプリを削除しないでください。リリース間は同じ配布署名を使用します。

## 添付検査の読み方

apk-audit.jsonは配布APKの構成と署名、source-dependency-audit.jsonはビルド元のソース・依存・SDKについての検査結果です。passed=trueは定義した検査の成功で、すべての脆弱性がないことを意味しません。

SDKのstrict_16kb_passed=falseは既知の未通過です。RELRO終端の不一致を開示したまま配布検査を通しています。[既知の制限](../KNOWN_ISSUES.md)を参照してください。
