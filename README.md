# mangaka-data

ai-gftd-mangaka の data/ を分離した DataLad dataset（ADR-2607023000 follow-up）。
バイナリ（ghosthacker/ の character refs 等）は git-annex + Backblaze B2
（special remote `b2`, fileprefix=mangaka-data/）、テキストは text2git で通常 git。

取得: `west update --group-filter +datalad mangaka-data && west annex-get mangaka-data`
（または dataset 内で `datalad get <path>`）。

- `ghosthacker/` — ghosthacker 作品 asset graph + character refs（旧 mangaka data/ghosthacker）
- `osamu/` — 手塚系 story .md.edn 台帳
- `originals/`, `style-registry.edn`, `recipe-ledger.jsonl`

consumer: `mangaka.graphs.import-ghosthacker`（`GHOSTHACKER_ASSET_GRAPH` env で
path 上書き可、既定は sibling checkout `../../mangaka-data/ghosthacker/...`）。
