;; corpus_test.cljs — mangaka-data が「読める」だけでなく「繋がっている」ことの検査。
;;
;; この repo はコードを持たない DataLad dataset である。だから守るべき不変条件は
;; 関数の振る舞いではなく、**文書どうしの参照が解決すること**と、**言語版が同じ
;; 背骨を持つこと**の 2 つになる。どちらも壊れても何も throw しない —— 壊れたまま
;; render パイプラインに渡り、生成物になって初めて分かる（しかも気づくには
;; その言語版を最後まで観るしかない）。
;;
;; ## 「測れなかった」を「問題なし」と区別する（superproject CLAUDE.md）
;;
;; データを歩く検査は、**入力を見つけられなかったときに黙って緑になる**のが既定の
;; 失敗の形である。ここでは 3 つで防いでいる:
;;
;;   1. ファイルの列挙は `git ls-files` に訊く。fs の walk ではない —— sparse
;;      checkout・walk の打ち切り・深さ制限のどれもが「無い」と「見ていない」を
;;      同じ顔にする。git は dataset が何を宣言しているかを答える。
;;   2. 列挙が 0 件なら、または git が答えられないなら **throw する**。
;;      「0 件を検査して違反 0 件」は合格ではない。
;;   3. 各検査は自分が実際に読んだ件数を `floor` と突き合わせる。corpus が増えたら
;;      floor を上げる。**下回ったら本物の欠落か列挙の破損のどちらか**で、
;;      どちらも報告に値する。
;;
;; ## annex の中身が無いことは、参照が壊れていることではない
;;
;; `ghosthacker/character-refs/*.png` は git-annex（B2 backed）で、既定では実体が
;; 無く symlink が dangling する。だから存在確認は `lstatSync`（path が dataset に
;; 宣言されているか）で行い、`existsSync`（中身が取得済みか）では行わない。後者を
;; 使うと **custody の話（`west annex-get`）を参照整合性の違反として報告する**
;; ことになり、既定で常に赤い検査ができあがる。
(ns mangaka-data.corpus-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

(def root (js/process.cwd))

(defn- tracked
  "dataset が宣言しているファイル。git に訊く —— fs の walk は『無い』と
   『見ていない』を区別できない。git が答えられないなら **答えを拒否する**
   （0 件を『違反なし』として返さない）。"
  []
  (let [out (try
              (.toString (cp/execFileSync "git" #js ["ls-files"]
                                          #js {:cwd root :maxBuffer 33554432}))
              (catch :default e
                (throw (ex-info (str "git ls-files が実行できないので、この dataset が"
                                     " 何を宣言しているか分からない。合格を報告しない。 "
                                     (.-message e))
                                {:root root}))))
        fs* (vec (remove str/blank? (str/split-lines out)))]
    (when (empty? fs*)
      (throw (ex-info "git ls-files が 0 件を返した。0 件の検査は合格ではない。"
                      {:root root})))
    ;; git は改行や非 ASCII を含む path を `"..."` で quote する。quote された行は
    ;; そのままでは開けないので、**黙って読み飛ばさず列挙ごと拒否する**。
    (when-let [q (seq (filter #(str/starts-with? % "\"") fs*))]
      (throw (ex-info (str "git が quote した path があり、列挙を信用できない: "
                           (pr-str (vec (take 3 q))))
                      {:root root})))
    fs*))

(def all-files (tracked))
(defn- ends [ext] (filterv #(str/ends-with? % ext) all-files))
(defn- slurp* [rel] (.toString (fs/readFileSync (path/join root rel) "utf8")))

(defn- declared?
  "path が dataset に宣言されているか。**中身が取得済みかではない** ——
   annex 管理下の実体は既定で不在で、そのとき symlink は dangling する。"
  [rel]
  (try (boolean (fs/lstatSync (path/join root rel))) (catch :default _ false)))

;; 検査が実際に読んだ件数の床。corpus が増えたらここを上げる。**下回ったら本物の
;; 欠落か列挙の破損**で、どちらも黙って通してはならない。2026-08-19 の実測値。
(def floor
  {:edn 133 :json 34 :story 104 :scenario 6 :shotlist-family 4 :character-ref 12})

(defn- at-least [label n k]
  (is (>= n (floor k))
      (str label ": " n " 件しか読めていない（床 " (floor k) "）。"
           "corpus が縮んだか、列挙が壊れている —— どちらでも合格ではない")))

;; ── 文書が読めること ────────────────────────────────────────────────────────

(deftest every-declared-edn-document-parses
  (let [fs* (ends ".edn")
        bad (keep (fn [f]
                    (try (edn/read-string (slurp* f)) nil
                         (catch :default e {:file f :why (.-message e)})))
                  fs*)]
    (at-least "edn" (count fs*) :edn)
    (is (empty? bad) (str "EDN として読めない文書: " (pr-str (vec bad))))))

(deftest every-declared-json-document-parses
  (let [fs* (ends ".json")
        bad (keep (fn [f]
                    (try (js/JSON.parse (slurp* f)) nil
                         (catch :default e {:file f :why (.-message e)})))
                  fs*)]
    (at-least "json" (count fs*) :json)
    (is (empty? bad) (str "JSON として読めない文書: " (pr-str (vec bad))))))

(deftest story-documents-carry-a-body
  (testing "`*.md.edn` は markdown を EDN で包んだ台帳。README.md.edn だけは datom 形の
            tx-data なので別（形が違うことを検査の側が知っている）"
    ;; 読めない文書は上の parse 検査が所有する。ここで throw させると、1 つの
    ;; 破損が 2 つの検査を同時に赤くして、**どの不変条件が破れたのか**が
    ;; 出力から読めなくなる（mutation が噛んだかの判定はテスト名で行う）。
    (let [fs* (remove #(= % "README.md.edn") (ends ".md.edn"))
          bad (keep (fn [f]
                      (let [m (try (edn/read-string (slurp* f))
                                   (catch :default _ ::unreadable))]
                        (when-not (or (= m ::unreadable)
                                      (and (map? m) (string? (:body m))
                                           (not (str/blank? (:body m)))))
                          {:file f :shape (if (map? m) (vec (keys m)) (str (type m)))})))
                    fs*)]
      (at-least "story" (count fs*) :story)
      (is (empty? bad) (str ":body を持たない台帳: " (pr-str (vec bad)))))))

;; ── style registry: 参照が profile に着地すること ───────────────────────────

(deftest style-registry-resolves-every-profile-it-names
  (testing "render パイプライン（lg_mangaka.style_registry.resolve）は genre / work から
            profile を引く。宛先の無い key は解決時に nil を返して既定へ黙って落ちる
            —— その作品だけ作画スタイルが入れ替わる"
    (let [sr (edn/read-string (slurp* "style-registry.edn"))
          profiles (set (keys (:profiles sr)))
          dangling (concat
                    (when-not (profiles (:default sr)) [[:default (:default sr)]])
                    (for [[k v] (:genre->style sr) :when (not (profiles v))] [k v])
                    (for [[k v] (:work->style sr) :when (not (profiles v))] [k v]))]
      (is (pos? (count profiles)) ":profiles が空。検査する対象が無い")
      (is (empty? dangling)
          (str ":profiles に無い profile を指している: " (pr-str (vec dangling)))))))

;; ── scenario: cast / panel / image slot が閉じていること ────────────────────

(def scenarios
  (filterv #(re-find #"^ghosthacker/resources/scenarios/.*\.edn$" %) all-files))

(defn- scenario-docs []
  (mapv (fn [f] [f (edn/read-string (slurp* f))]) scenarios))

(deftest scenario-cast-refs-are-declared-in-the-dataset
  (testing "`:ref` が指す character ref は dataset に宣言されていること。**中身が
            取得済みかは見ない** —— annex の実体は既定で不在で、それは custody の話"
    (let [refs (filterv #(str/starts-with? % "ghosthacker/character-refs/") (ends ".png"))
          bad (for [[f m] (scenario-docs)
                    c (:scene/cast m)
                    :let [p (str "ghosthacker/character-refs/" (:ref c))]
                    :when (not (declared? p))]
                {:file f :name (:name c) :ref (:ref c)})]
      (at-least "scenario" (count scenarios) :scenario)
      (at-least "character-ref" (count refs) :character-ref)
      (is (empty? bad) (str "dataset に無い character ref を指す cast: " (pr-str (vec bad)))))))

(deftest scenario-beats-target-panels-that-exist-at-the-size-they-claim
  (testing "beat の `:panels` は `:scene/page :layout` への索引。範囲外だとそのコマは
            描かれず、`:panel-size` の食い違いは cover-crop の上下トリムで顔が切れる
            （scenario 冒頭のコメントが実障害として記録している）"
    (let [bad (remove nil?
                      (for [[f m] (scenario-docs)
                            :let [layout (get-in m [:scene/page :layout])]
                            b (:scene/beats m)
                            idx (:panels b)]
                        (let [pl (get layout idx)]
                          (cond
                            (nil? pl) {:file f :beat (:beat/id b) :panel idx :why :out-of-range}
                            (and (:panel-size b) (not= (:panel-size b) (:size pl)))
                            {:file f :beat (:beat/id b) :panel idx :why :size-mismatch
                             :beat-size (:panel-size b) :layout-size (:size pl)}))))]
      (is (empty? bad) (str "panel を外している beat: " (pr-str (vec bad)))))))

(deftest scenario-image-slots-are-declared-and-used
  (testing "`@ImageN` は cast の image slot。宣言に無い tag を本文が使うと生成器は空の
            slot を渡され、使われない宣言は cast の取り違えを示す"
    (let [bad (for [[f m] (scenario-docs)
                    :let [declared (set (keep :image (:scene/cast m)))
                          used (set (mapcat (fn [b]
                                              (re-seq #"@Image\d+"
                                                      (str (:action b) " " (:line b) " "
                                                           (:caption b) " " (:shot b) " "
                                                           (:location b))))
                                            (:scene/beats m)))]
                    :when (not= declared used)]
                {:file f
                 :undeclared (vec (remove declared used))
                 :unused (vec (remove used declared))})]
      (is (empty? bad) (str "image slot が閉じていない scenario: " (pr-str (vec bad)))))))

;; ── shotlist: 言語版が同じ背骨を持ち、かつ本当に翻訳されていること ──────────

(def ^:private lang-file #"^(.*shotlist-v2)\.([a-z]{2})\.(edn|json)$")

(defn- shotlist-families
  "`<base>.<lang>.<ext>` を base ごとに束ねる。"
  []
  (->> all-files
       (keep (fn [f] (when-let [[_ base lang _] (re-find lang-file f)]
                       {:file f :base base :lang lang})))
       (group-by :base)))

(defn- load-shots [f]
  (let [t (slurp* f)]
    (if (str/ends-with? f ".edn")
      (edn/read-string t)
      (js->clj (js/JSON.parse t) :keywordize-keys true))))

(def ^:private spine-keys
  "言語に依らない部分。`:scene_prompt` は画像生成の prompt なので**翻訳されない**のが
   正しい —— 翻訳されるのは dialogue の `:text` だけ。"
  [:type :scene_key :scene_prompt :speaker :sfx :fx :dur :shiro_emo :pico_emo])

(deftest localized-shotlists-share-one-spine
  (testing "言語版はカット割りを共有する。本数や順序が言語ごとに違うと、その言語の回
            だけ shot が落ちる／入れ替わる —— 気づくにはその言語版を最後まで観るしかない"
    (let [fams (shotlist-families)
          bad (for [[base entries] fams
                    :let [sorted (sort-by :file entries)
                          b (first sorted)
                          bd (load-shots (:file b))
                          bs (mapv #(select-keys % spine-keys) bd)]
                    o (rest sorted)
                    :let [d (load-shots (:file o))
                          s (mapv #(select-keys % spine-keys) d)]
                    :when (not= s bs)]
                {:base base :file (:file o) :against (:file b)
                 :count [(count d) (count bd)]
                 :first-diff (first (keep-indexed (fn [i [x y]] (when (not= x y) [i x y]))
                                                  (map vector bs s)))})]
      (at-least "shotlist-family" (count fams) :shotlist-family)
      (is (empty? bad) (str "背骨が言語版で食い違う shotlist: " (pr-str (vec bad)))))))

(deftest localized-shotlists-are-actually-localized
  (testing "翻訳ジョブが原文をそのまま吐いた回を捕まえる。合言葉や技名
            （`SHIRO-PICO, ON!` `—Ghost.`）は意図的に共有されるので、**全行が一致した
            組**だけを違反とする"
    (let [fams (shotlist-families)
          dial-of (fn [entries]
                    (into {} (for [{:keys [file lang]} entries]
                               [lang (mapv :text (filter #(= "dialogue" (:type %))
                                                         (load-shots file)))])))
          bad (for [[base entries] fams
                    :let [dial (dial-of entries)]
                    [a va] dial
                    [b vb] dial
                    :when (and (neg? (compare a b)) (= va vb))]
                {:base base :langs [a b] :lines (count va)})
          blank (for [[base entries] fams
                      {:keys [file]} entries
                      :let [ts (mapv :text (filter #(= "dialogue" (:type %)) (load-shots file)))]
                      :when (some #(or (nil? %) (str/blank? %)) ts)]
                  {:base base :file file})]
      (is (empty? bad) (str "台詞が丸ごと同一の言語対（翻訳されていない）: " (pr-str (vec bad))))
      (is (empty? blank) (str "空の台詞を含む言語版: " (pr-str (vec blank)))))))
