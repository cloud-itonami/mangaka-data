# operator quickstart — mangaka-data

**この dataset にはコードが無い。** 動かすものが無いので「動いているか」は
`起動する / 応答する` では測れない。測れるのは **文書どうしの参照が閉じているか**
だけで、それを見るのが `run_tests.cljk` である。

この文書は、その 1 本の検査を軸にした運用手順書である。書いてある手順は
**すべて実際に踏んで出力を確認した**（2026-09-01、pin `86f61060`）。数値は
その日の実測で、再測定のコマンドを各節に併記してある —— 引用するときは
値ではなくコマンドの方を使うこと。

---

## 0. 前提

| 要るもの | 確認 | 無いとどうなるか |
|---|---|---|
| `nbb` | `nbb --version` | 検査が回せない（この workspace の script host は nbb 一本） |
| `git` | `git --version` | 検査が **答えを拒否する**（exit 1 + `合格を報告しない`。緑の印は出ない） |
| `git-annex` | `git annex version` | §4 のバイナリ取得だけができない。**検査は回る** |
| B2 の credential | §4 で初めて要る | §4 だけができない。§1〜§3 と §5 には要らない |

---

## 1. 取得する

```bash
# superproject（com-junkawasaki/root）のルートで
west update --group-filter +datalad cloud-itonami-mangaka-data
```

**project 名は `cloud-itonami-mangaka-data` であって、path の basename
（`mangaka-data`）ではない。** basename を渡すと exit 1 で止まる:

```
FATAL ERROR: unknown project name/path: mangaka-data
  Hint: use "west list" to list all projects.
```

west.yml の当該 entry で正しい名前を確かめられる:

```bash
grep -A5 'name: cloud-itonami-mangaka-data' manifest/west.yml
#   remote: cloud-itonami / repo-path: mangaka-data
#   path: orgs/cloud-itonami/mangaka-data / groups: [datalad]
```

`groups: [datalad]` なので **既定の `west update` では取得されない**。
`--group-filter +datalad` が要るのはそのため。

この時点で来ているのは **テキストだけ**である。バイナリは §4。

---

## 2. 「繋がっている」ことを確かめる（ここが本体）

```bash
cd orgs/cloud-itonami/mangaka-data
nbb --classpath test run_tests.cljk
```

緑のときの最後の 1 行は決め打ちされている:

```
Ran 9 tests containing 17 assertions.
0 failures, 0 errors.

mangaka-data corpus: all green
```

**この最終行は飾りではない。** `run_tests.cljk` は緑のときだけこれを印字し、
赤のときは印字せずに exit 1 する。上流（`scripts/maturity-loop/mutations.edn`）は
この行の有無で「検査が噛んだか」を判定するので、**文言を変えるときは
mutations.edn も一緒に直す**。

### ⚠ ルート以外から実行しない

検査は `process.cwd()` を dataset のルートとみなす。サブディレクトリから

```bash
cd osamu && nbb --classpath ../test ../run_tests.cljk
```

と叩くと **exit 1 / 6 failures + 1 error** になる。これは壊れているのではなく、
**列挙が空になったことを検査が自分で捕まえている**（`shotlist-family: 0 件しか
読めていない（床 4）`）。データを歩く検査の既定の失敗は「入力を見つけられずに
黙って緑になる」ことなので、ここは**赤くなる方が正しい**。赤を見たらまず
`pwd` を疑うこと。

---

## 3. 検査が赤いとき — 3 つに読み分ける

出力のどれが出たかで、直す場所が違う。

| 出たもの | 意味 | 直す場所 |
|---|---|---|
| `... 件しか読めていない（床 N）` | **列挙が縮んだ**。文書を消したか、cwd を間違えたか、checkout が壊れた | まず `pwd`。意図して消したなら `corpus_test.cljs` の `floor` を下げる（下げた理由を commit に書く） |
| `git ls-files が実行できないので…合格を報告しない` / `git ls-files が 0 件を返した` / `git が quote した path` | **検査が答えを拒否した**。合格でも不合格でもない | git が動くか。git repo の中に居るか。path に改行や非 ASCII が入っていないか |
| それ以外（`:body を持たない台帳` `panel を外している beat` 等） | **本物の参照切れ** | 名指しされた文書 |

床は現在 **corpus の実数ちょうど**に置いてある（2026-09-01 実測）:

```bash
# 床と実数を突き合わせる
git ls-files | grep -c '\.edn$'                    # 133  (floor :edn 133)
git ls-files | grep -c '\.json$'                   # 34   (floor :json 34)
git ls-files | grep -c '\.md\.edn$'                # 105 → README.md.edn を除いて 104 (floor :story 104)
git ls-files | grep -c '^ghosthacker/resources/scenarios/.*\.edn$'   # 6  (floor :scenario 6)
git ls-files | grep -c '^ghosthacker/character-refs/.*\.png$'        # 12 (floor :character-ref 12)
```

つまり **文書を 1 つ消せば必ず赤くなる。** これは意図した設計で、
「気づかないうちに corpus が縮む」を消すための床である。増やすときは
`floor` も上げること（上げ忘れても赤くはならないが、床が緩む）。

---

## 4. バイナリ（annex）— 要るとき・要らないとき

**検査にも consumer の graph 読み込みにも annex の実体は要らない。** 実測:

```bash
# 宣言されている 44 件のうち、実体が手元にあるのは何件か
for f in $(git ls-files -s | awk '$1=="120000"{print $4}'); do
  [ -e "$f" ] && echo present || echo absent
done | sort | uniq -c
#   44 absent      ← 取得直後の既定。この状態で §2 は緑になる
```

44 件の内訳は `ghosthacker/character-refs/*.png` 43 件 +
`ghosthacker/resources/motion-comic/*.mp4` 1 件。実体は Backblaze B2 にある:

```bash
git annex info b2
#   remote annex keys: 44 / remote annex size: 54.13 megabytes
```

`corpus_test.cljs` が存在確認に `lstatSync`（path が宣言されているか）を使い
`existsSync`（中身が取得済みか）を使わないのは、このためである。後者にすると
**custody の話を参照整合性の違反として報告する**ので、既定で常に赤い検査になる。

この不変条件は 3 つの状態で実測してある —— **present が 0 / 1 / 44 のどれでも
`9 tests, 17 assertions, 0 failures`**。検査の結果は annex の取得状況に依存しない:

| present | §2 の結果 |
|---|---|
| 0（取得直後） | 緑 |
| 1（1 件だけ get） | 緑 |
| 44（annex-get 済み） | 緑 |

### 実体が本当に要るとき（render する / 画像を見る）

```bash
# superproject のルートで。B2 credential は scripts/b2-creds.cljs が解決する
nbb manifest/west_annex.cljs annex-get cloud-itonami-mangaka-data
```

実測（44 件・54 MB、所要は回線次第）:

```
action summary:
  get (ok: 44)
```

戻すのは `annex-drop`（同じ引数）。`drop (ok: 45)` まで見て present が 0 に戻る。
**b2 に copy があるので drop で失われるものは無い** —— numcopies の判定に通らなければ
git-annex が自分で拒否する。

`b2` special remote は **取得直後の checkout では有効化されていない**。上のコマンドが
`git annex init` → `git annex enableremote b2` → 取得、の順で面倒を見る。最後の一段は
`datalad` が PATH にあれば `datalad get .`、無ければ `git annex get --from b2`
（出力の `get(ok):` という形は datalad 経路のもの）。

credential が解決できないと `B2 creds 未解決(AWS_ACCESS_KEY_ID 無し)` を出して
その project を飛ばす —— これは `west_annex.cljs` の `enable-b2!` を読んだ記述で、
**この手順書では踏んでいない**（creds が解決する環境で walk したため）。

有効化の有無はこれで見える:

```bash
git config --get-regexp '^remote\.b2\.'
# 取得直後（未有効化）: 何も返らない
# 一度 annex-get を通したあと: annex-s3 / annex-uuid / skipfetchall が残る
```

**空でないこと自体は異常ではない** —— 一度でも annex-get を通した checkout には
残る。credential はここには入らない（`.git/annex/creds/<uuid>` 側）。
⚠ linked worktree は `.git/config` を元の checkout と**共有する**ので、
worktree で enableremote すると共有 checkout 側にもこの 3 行が現れる。

1 件だけ試したいなら、enableremote まで済ませたあと:

```bash
git annex get --from b2 ghosthacker/character-refs/akira.png   # 実測 1012 KiB, checksum ok
git annex drop ghosthacker/character-refs/akira.png            # 戻す。b2 に copy があるので通る
```

### ⚠ 最初の annex コマンドが出す警告は正常

```
Remote cloud-itonami does not have git-annex installed; setting annex-ignore
```

**GitHub は annex の実体を持たない**（持っているのは b2）。git-annex は remote を
一度突いてから `annex-ignore` を立てるので、この警告は初回に必ず出て、以後出ない。
`.git/config` に `remote.cloud-itonami.annex-ignore true` が入るのが正しい状態。

`git annex whereis <path>` は b2 のほかに `local` や過去の作業用 clone を
copy として挙げることがある。**b2 だけが恒久的な custody**で、残りは記録に
過ぎない。

---

## 5. consumer から読めることを確かめる

この dataset の主な読み手は `mangaka.graphs.import-ghosthacker`
（`orgs/cloud-itonami/mangaka/clj`）である。JVM では次の順で path を探す:

1. 環境変数 `GHOSTHACKER_ASSET_GRAPH`
2. `../../mangaka-data/ghosthacker/resources/asset_graph.json`
3. `../data/ghosthacker/resources/asset_graph.json`

west の配置（`orgs/cloud-itonami/{mangaka,mangaka-data}`）では **2 番目が
そのまま当たる** —— consumer 側で環境変数を設定する必要は無い:

```bash
cd orgs/cloud-itonami/mangaka/clj
ls -l ../../mangaka-data/ghosthacker/resources/asset_graph.json   # 18701 bytes
```

この JSON は **annex ではなく通常の git blob** なので（`git ls-files -s` が
`100644`、symlink の `120000` ではない）、§4 を踏まずに読める:

```bash
cd orgs/cloud-itonami/mangaka-data
node -e 'const g=JSON.parse(require("fs").readFileSync("ghosthacker/resources/asset_graph.json","utf8"));
         for (const k of Object.keys(g)) console.log(k, typeof g[k]==="object"&&g[k]?Object.keys(g[k]).length:g[k]);'
# characters 5 / bubbles 8 / scenes 6 / props 6 / schema_version 0.1
```

`characters` と `scenes` は **配列ではなく map** である。`.length` で数えると
`undefined` が返る（この節を書く途中で一度踏んだ）。

---

## 6. 変更を入れるとき

1. 共有 checkout（`orgs/cloud-itonami/mangaka-data`）で直接 commit しない。
   superproject の外に worktree を切る:
   `git worktree add -b <branch> /tmp/<name> cloud-itonami/main`
2. 変更前に §2 を 1 回通す。**変更前から赤い状態で作業を始めない** ——
   自分の変更の可否を判定できなくなる。
3. 文書を増やしたら `corpus_test.cljs` の `floor` を上げる。減らしたなら
   下げて、**なぜ減らしてよいのかを commit message に書く**。
4. 着地は server side merge（`gh api repos/cloud-itonami/mangaka-data/merges`）。
   rebase も force-push もしない。
5. superproject 側の pin を進めるのは **当該 entry だけ**
   （`nbb scripts/gen-west-manifest.cljs --entry cloud-itonami-mangaka-data`）。

---

## 参照

- `README.md` — dataset の輪郭と consumer
- `MIGRATION.edn` — `ai-gftd-apps-gftdcojp` からの回収記録（`:annex-content-unavailable 44`
  は §4 の 44 件と同じもの）
- `test/mangaka_data/corpus_test.cljk` — 各検査が何を守っているかは docstring が持つ
- superproject `manifest/west_annex.cljs` — annex 側の実装。`west annex-get` という
  west 拡張は**無い**
