# アーキテクチャ

## 方針

- ソルバーはブラウザで動かす。Worker は認証付きの保存 API だけを担当する。
- パイプラインの保存・共有は D1 の 1 テーブルで行う。
- 共有は「URL で閲覧 + フォーク(自分のコピーを作る)」とする。同時編集はしない。
- レシピデータとテクスチャはビルド時に前処理し、静的アセットとして配信する。

## 全体構成

```
Browser (React + TanStack Router)
 ├─ UI
 ├─ Web Worker
 │   ├─ レシピデータの fetch / パース / インデックス構築
 │   └─ ソルバー (純 TS)
 └─ Clerk (ログイン、JWT 取得)
        │  /api/* に Bearer JWT
        ▼
Cloudflare Worker (Hono)
 ├─ 静的アセット配信 (SPA、レシピ JSON、テクスチャアトラス)
 ├─ Clerk ミドルウェア (@hono/clerk-auth で JWT 検証)
 └─ パイプライン CRUD ──▶ D1
```

図中の「Web Worker」はブラウザの別スレッドで、Cloudflare Workers とは別物。ユーザーの端末で動くので、Cloudflare の CPU 制限はかからない。レシピ JSON やアトラスの配信は静的アセットとして直接返され、Worker のコードは `/api/*` でしか実行されない。

### ソルバーをブラウザで動かす理由

- Workers の Free プランは 1 リクエストあたり CPU 10ms で、レシピ探索には足りない。
- レシピは公開データなので、サーバーに置く理由がない。
- 未ログインでも計算できる。ログインが必要なのは保存と共有だけになる。

### Durable Objects を使わない理由

- 「自分の一覧」のような横断クエリは D1 のほうが素直に書ける。
- DO が必要になるのはリアルタイム同時編集で、今回の要件にはない。
- 将来必要になった場合は、パイプライン 1 件につき DO 1 個を立て、D1 は一覧用インデックスとして残す。

## ディレクトリ構成

```
src/
 ├─ solver/        純 TS。React にも Workers にも依存しない
 ├─ data/          レシピデータの型、ローダー、インデックス
 ├─ react-app/     UI
 └─ worker/        Hono API
scripts/
 └─ build-data.ts  生のレシピダンプ → 配信用データへの変換
data-src/          生のレシピダンプとテクスチャ (変換前)
public/data/       変換後の配信用データ (生成物)
```

## レシピデータ

生のダンプをそのまま配信せず、`scripts/build-data.ts` で配信用に変換する。

### 配信形式

```
public/data/<recipeSet>/
 ├─ manifest.json          チャンク一覧、各ファイルのハッシュ、データのバージョン
 ├─ items.json             アイテム ID、表示名、アトラス上の位置
 ├─ recipes/<namespace>.<hash>.json   mod (名前空間) ごとのレシピ
 └─ atlas/<namespace>.<hash>.png      mod ごとのテクスチャアトラス
```

- `recipeSet` はゲームや modpack の単位 (例: `vanilla-1.21`、`gtnh-2.7`)。
- レシピは mod ごとに分割する。1 ファイルは数 MB 以内を目安にし、超える場合は機械種別でさらに分ける。
- アイテムは文字列 ID (`minecraft:iron_ingot`) をファイル内で数値インデックスに置き換え、サイズを減らす。
- ファイル名にハッシュを入れ、`public/_headers` で `Cache-Control: immutable` を付ける。`manifest.json` だけは毎回再検証する。

### 読み込み

1. `manifest.json` と `items.json` を取得する。
2. Web Worker 内で全チャンクを並列に fetch してパースする。
3. 「アイテム → それを出力するレシピ」のインデックスを Web Worker 内で構築する。
4. UI とは `postMessage` でやりとりする (Comlink を使う)。

パースをメインスレッドでやらないので、データが大きくても UI は固まらない。

### 制約

- 静的アセットは 1 ファイル 25MiB まで、Free プランは 1 バージョンあたり 20,000 ファイルまで。
- この上限を超える場合、またはデプロイなしでデータを更新したい場合、ユーザーがレシピをアップロードできるようにする場合は R2 に移す。配信形式は同じなので、ローダーのベース URL を変えるだけで済むようにしておく。

## テクスチャ

- アイテムごとの PNG を個別に配信しない。数千リクエストになることが遅さの原因になる。
- ビルド時に mod ごとの 1 枚のアトラス画像にまとめ、位置を `items.json` に持つ。
- 表示は CSS の `background-position` と `image-rendering: pixelated` で行う。
- 静的アセットも Cloudflare のエッジから配信されるので、R2 に置いても速くはならない。R2 に移す条件はレシピデータと同じ。
- mod のテクスチャは各 mod 作者の著作物なので、再配布してよいライセンスかを recipeSet ごとに確認する。

## 認証

- Clerk を使う。フロントは `@clerk/clerk-react`、Worker は `@hono/clerk-auth`。
- Worker は JWT を検証し、Clerk の user id を `owner_id` として使う。
- users テーブルと Webhook 同期は作らない。

## パイプラインの保存

### テーブル

```sql
CREATE TABLE pipelines (
  id          TEXT PRIMARY KEY,   -- nanoid。推測不能なので共有 URL にそのまま使う
  owner_id    TEXT NOT NULL,      -- Clerk の user id
  title       TEXT NOT NULL,
  recipe_set  TEXT NOT NULL,      -- 対象の recipeSet
  doc         TEXT NOT NULL,      -- パイプライン本体の JSON
  visibility  TEXT NOT NULL DEFAULT 'private',  -- private | unlisted | public
  forked_from TEXT,               -- フォーク元の id
  version     INTEGER NOT NULL DEFAULT 1,       -- 楽観ロック用
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX idx_pipelines_owner ON pipelines(owner_id, updated_at DESC);
```

### `doc` の中身

- 入力だけを保存する: 目標出力、選んだレシピ、機械設定、ノードの配置。
- 台数などの計算結果は保存せず、開いたときに再計算する。
- アイテムとレシピは文字列 ID で参照する。配信用の数値インデックスはデータ更新で変わるので使わない。
- `schemaVersion` を持たせ、読み込み時にマイグレーションする。
- API で 256KB に制限する (D1 の上限は 1 行 2MB)。

### 公開範囲

| 値 | 読める人 | 一覧に出るか |
|---|---|---|
| `private` | 所有者のみ | 出ない |
| `unlisted` | URL を知っている人 | 出ない |
| `public` | 誰でも | 出る (公開一覧を作る場合) |

編集できるのは常に所有者だけ。他人のパイプラインを変更したい場合はフォークする。

## API

| エンドポイント | 認証 | 内容 |
|---|---|---|
| `GET /api/pipelines` | 必須 | 自分の一覧 |
| `POST /api/pipelines` | 必須 | 作成。`forkedFrom` を付けるとフォーク |
| `GET /api/pipelines/:id` | 任意 | `private` は所有者のみ。それ以外は誰でも読める |
| `PUT /api/pipelines/:id` | 必須 | 所有者のみ。`version` が一致しなければ 409 |
| `DELETE /api/pipelines/:id` | 必須 | 所有者のみ |

- 入力は zod で検証する。
- Hono RPC (`hc`) でフロントと型を共有する。
- D1 へのアクセスは Drizzle、スキーマ変更は wrangler の D1 マイグレーションで行う。

## 未ログイン時

- 計算とパイプライン編集はすべて使える。
- 下書きは localStorage に保存する。
- ログイン後に「保存」を押すと D1 に書き込む。

## 実装順

1. `scripts/build-data.ts` とデータローダー (vanilla の小さなデータで形式を固める)。
2. ソルバーと UI。下書きは localStorage。
3. Clerk と D1 で保存・一覧。
4. 公開範囲、共有 URL、フォーク。
