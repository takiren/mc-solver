# Nomi-CEu のレシピダンプ

## 手順

1. `zz_mcsolver_dump.groovy` をインスタンスの `groovy/post/addon/` にコピーする。
2. サーバーを起動し、`logs/groovy_server.log` に `MCSOLVER_DONE` が出たら止める。
3. ログから JSONL を取り出す。`overrides.jsonl` は手書きの補完レコードで、末尾に追加される。

   ```
   node scripts/dump/extract-log.ts <インスタンス>/logs/groovy_server.log scripts/dump/groovy.jsonl scripts/dump/nomi-ceu/overrides.jsonl
   ```

4. インスタンスからスクリプトを削除する。

## overrides.jsonl

スクリプトから読めないレシピを手書きで補う。`"manual": true` を付ける。
nomifactory1.7.7で試していた時、意図的にレシピがさわれないようになっていたためこの対応をとっている。

- `nomiceu:casing_assembly_control` (Assembly Control Casing)
  - ノーマルモードでは Nomi-CEu が出力数を 2 から 4 に変えており、その実装クラス
    `com.nomiceu.nomilabs.groovy.PartialRecipe` は GroovyScript から触れない (`@GroovyBlacklist`)。
  - 材料は GregTech CEu の `MetaTileEntityLoader.java` にある元レシピ、出力数は Nomi-CEu の
    `overrides/groovy/post/main/general/late/lategame.groovy` から書き起こした。
  - パックを更新したときは、このレシピが変わっていないか確認する。
