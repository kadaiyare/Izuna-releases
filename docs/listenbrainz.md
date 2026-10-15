# ListenBrainz互換サーバー

ユーザーがAPI URLとトークンを指定し、ListenBrainzまたは同じAPIを実装したサーバーへ再生情報を送る。
現在設定できる互換サーバーは1つ。v0.1.0の配布版ではLast.fmは未設定だが、ListenBrainz互換送信は利用できる。

## 接続する

1. ライブラリの歯車 → 設定 → 外部連携 →「ListenBrainz互換サーバー」を開く。
2. 利用するサーバーのAPI URLとユーザートークンを入力する。
3. 「接続を確認して保存」を押し、接続済みのアカウント名とURLを確認する。
4. 「Scrobbleを送信」をオンにする。設定の「再生履歴を記録」もオンにする。
5. 新しく曲の再生を始める。接続確認だけでは再生中通知も履歴も送らない。

URL例:

- 本家: `https://api.listenbrainz.org`
- APIバージョンを含む指定: `https://api.listenbrainz.org/1`
- リバースプロキシ: `https://example.com/apis/listenbrainz/1`

末尾の `/1` と `/` は正規化し、プロキシのパスは維持する。
個別の `/submit-listens` や `/validate-token`、クエリ・フラグメント・URL内のユーザー情報は指定しない。
この版は**HTTPSのみ**。標準の証明書検証を使い、HTTP・自己署名証明書の無条件許可は行わない。
接続先の変更やトークン更新は一度接続を解除して入力し直す。接続後の送信は毎回オフから始める。

## 互換APIの範囲

- `GET <APIルート>/1/validate-token`
  - `Authorization: Token <ユーザートークン>` を送る。URLやクエリへトークンを含めない。
  - HTTP 200、JSONの `valid: true` と空でない `user_name` を接続成功とする。
- `POST <APIルート>/1/submit-listens`
  - 同じ認証ヘッダーと `Content-Type: application/json` を送る。
  - `listen_type: single`、1件の `payload`、再生開始時のUTC秒を `listened_at` に設定する。
  - `track_metadata` に `track_name`・`artist_name`・任意の `release_name`、
    `additional_info` に長さ（`duration_ms`）と送信クライアント名・版を設定する。
  - 再生中の通知は `listen_type: playing_now` とし、`listened_at` を含めない。
  - HTTP 200かつJSONの `status: ok` を受付成功とする。
- リダイレクトを追跡しない。別のホストへトークンを転送しない。
- タイムアウトは接続・読取各15秒、応答本文は64 KiBまで。

この条件を満たすAPIを対象とし、任意の「Scrobble API」全般との互換性は保証しない。
仕様の根拠は [ListenBrainz Core API](https://listenbrainz.readthedocs.io/en/latest/users/api/core.html) と
[Submission JSON](https://listenbrainz.readthedocs.io/en/latest/users/json.html)。

## 再生時間・送信データ

履歴は曲の半分または4分の早い方に到達すると送信待ちに保存する。
Playerが実際に再生していた時間を使い、一時停止・バッファリング・シークで飛ばした時間は含めない。
長さが不明な曲は履歴を送らない。ListenBrainz側は短い曲も半分で対象とし、Last.fmの「30秒を超える曲」の条件とは別に判定する。
オンにした時刻より前に開始した曲や保存済みの古い履歴は追加しない。

曲名・アーティスト・アルバムはnative FLACのVorbis commentを使う。
欠落・読み取り失敗時にファイル名や表示用の補完名義を代用しない。
音源ファイル、端末URI、埋め込み画像、ALBUMARTISTは送らない。

再生中の曲は新しい再生につき1回だけ通知する。周期更新・一時停止からの再開で繰り返さない。
準備中の曲変更・一時停止・履歴記録オフ・接続変更、30秒を超える準備待ちでは古い通知を破棄する。
通知は永続キューへ保存せず、失敗しても再送しない。停止時の再生中表示の即時消去は行わない。
外部連携の失敗をPlayerへ伝播させない。

## 保存と再送

- URL・トークン・確認済みアカウント・有効化時刻はAndroid KeystoreのAES-GCMで暗号化し、バックアップ対象外の専用ファイルへ保存する。
- 入力中のトークンはsaved instance stateへ保存せず、画面の再作成で消える。接続済みのトークンを再表示しない。
- `listenbrainz.db` は既存の履歴DB・Last.fmジョブと独立したRoom DB。端末移行・バックアップの対象外。
- 正規化したAPIルートと確認済みアカウントで送信先を識別し、同じ再生のチェックポイントでジョブを重複作成しない。
- オフ・接続解除では新しい送信を止め、古いジョブを残す。同じURL・アカウントで再接続してオンにすると再開する。
  別のURL・アカウントには以前の送信待ちを送らない。送信中の1件は完了する場合がある。
- WorkManagerでネットワーク接続時に処理する。送信前の接続失敗とHTTP 429は再送待ちへ戻す。
  指数バックオフと数値秒の `Retry-After` に従う。省電力・WorkManager制約による遅延はあり得る。
- HTTP 401/403では接続を解除して送信を止め、送信待ちは保持する。
- 応答不明・送信後の通信断・HTTP 5xx・プロセス中断時は「結果不明」とする。
  既に受け付けられている可能性があるため自動再送せず、件数と重複の可能性を示して手動再送する。
- 他のHTTP 4xxは対象外とし、自動で繰り返さない。
- 再生中の通知は送信件数に含めない。強制停止後のバックグラウンド送信はアプリを再度開くまで再開しない。

## 検証範囲

架空のトークン・曲名・応答を使ってAPIのJSON、接続確認、暗号化保存、時間条件、
送信先の隔離、重複防止、永続化、429、認証エラー、結果不明の手動再送、古い再生中通知の取消しを検証する。
設定画面はGalaxy S26画面相当のAndroid 16 / x86_64 / 16 KB仮想端末で確認する。
実際のユーザー指定サーバーとの接続・受信確認、ARM64実機でのListenBrainz連携の確認は未実施。
実サービスでの受付は未確認。[既知の制限](../KNOWN_ISSUES.md)と[プライバシー](../PRIVACY.md)を参照。
