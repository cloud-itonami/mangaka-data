# mangaka-data

ai-gftd-mangaka の data/ を分離した DataLad dataset（ADR-2607023000 follow-up）。
バイナリ（ghosthacker/ の character refs 等）は git-annex + Backblaze B2
（special remote `b2`, fileprefix=mangaka-data/）、テキストは text2git で通常 git。

取得（west の project 名は `cloud-itonami-mangaka-data`。path の basename ではない）:

```bash
west update --group-filter +datalad cloud-itonami-mangaka-data
nbb manifest/west_annex.cljs annex-get cloud-itonami-mangaka-data
```

（または dataset 内で `datalad get <path>`。`west annex-get` という west 拡張は無い ——
annex 側は superproject の `manifest/west_annex.cljs` が担う。）

- `ghosthacker/` — ghosthacker 作品 asset graph + character refs（旧 mangaka data/ghosthacker）
- `osamu/` — 手塚系 story .md.edn 台帳
- `originals/`, `style-registry.edn`, `recipe-ledger.jsonl`

検査: `nbb --classpath test run_tests.cljs`（**annex の実体は要らない** —— 参照が
dataset に宣言されているかだけを見る）。文書が EDN / JSON として読めることに加え、
scenario → character ref / beat → panel / genre → style profile / 言語版 → 共有の
カット割り、という 4 つの参照が閉じていることを固定する。**どれも壊れても何も
throw しない**ので、検査が無いと生成物になって初めて分かる。

consumer: `mangaka.graphs.import-ghosthacker`（`GHOSTHACKER_ASSET_GRAPH` env で
path 上書き可、既定は sibling checkout `../../mangaka-data/ghosthacker/...`）。
