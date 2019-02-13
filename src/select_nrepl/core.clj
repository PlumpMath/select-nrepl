(ns select-nrepl.core
  (:require
   [select-nrepl.rewrite-clj.node :as n]
   [select-nrepl.rewrite-clj.zip :as z]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]))

(def ^:private start-position z/position)

(defn- end-position [z]
  (let [[i j] (start-position z)
        s (z/string z)
        lines (count (filter #{\newline} (seq s)))
        columns (count (re-find #"[^\n]*$" s))]
    [(+ i lines) (if (zero? lines)
                   (+ j columns -1)
                   columns)]))

(defn- position<=? [a b]
  (<= (compare a b) 0))

(defn- element? [z]
  (and (not (some-> z z/up z/tag #{:meta :meta* :reader-macro}))
       (loop [z z]
         (case (z/tag z)
           (:meta :meta* :reader-macro) (some-> z z/down z/right recur)
           (:token :regex :multi-line)  true
           false))))

(defn- form? [z]
  (and (not (some-> z z/up z/tag #{:namespaced-map :meta :meta* :reader-macro
                                   :syntax-quote :unquote :unquote-splicing
                                   :quote}))
       (loop [z z]
         (case (z/tag z)
           (:namespaced-map :meta :meta* :reader-macro)      (some-> z z/down z/right recur)
           (:syntax-quote :unquote :unquote-splicing :quote) (some-> z z/down recur)
           (:list :map :set :vector)                         true
           false))))

(defn- toplevel? [z]
  (and (form? z)
       (or (not (some-> z z/up))
           (some-> z z/up z/tag #{:forms}))))

(defn- inside?
  "True if the selection is wholly inside, but not exactly, the node at z."
  [z cursor anchor]
  (let [[selection-start selection-end] (sort [cursor anchor])]
    (and (position<=? (start-position z) selection-start)
         (position<=? selection-end (end-position z))
         (or (not= selection-start (start-position z))
             (not= selection-end (end-position z))))))

(defn- acceptable?
  "A node is acceptable if it contains the cursor or starts after
  cursor.  In other words, if it ends after the cursor (inclusive)."
  [z cursor anchor]
  (and (position<=? cursor (end-position z))
       (let [[start end] (sort [cursor anchor])]
         (not (and (position<=? start (start-position z))
                   (position<=? (end-position z) end))))))

(defn- bottom [z]
  (loop [z z]
    (if-let [z' (z/down z)]
      (recur z')
      z)))

(defn- depth-first-next [z]
  (if-let [z' (z/right z)]
    (bottom z')
    (z/up z)))

(defn- traverse [z]
  (->> (iterate depth-first-next (bottom z))
       (take-while some?)))

(defn- find-object [z cursor anchor ok?]
  (let [all (->> (traverse z)
                 (filter ok?)
                 (filter #(acceptable? % cursor anchor)))]
    (or (first (filter #(inside? % cursor anchor) all))
        (first all))))

(def ^:private object-predicates
  {"element"  element?
   "form"     form?
   "toplevel" toplevel?})

(defn- select
  [{:keys [kind code cursor-line cursor-column anchor-line anchor-column] :as message}]
  (let [ok? (get object-predicates kind)
        z (-> (z/of-string code {:track-position? true})
              (find-object [cursor-line cursor-column] [anchor-line anchor-column] ok?))]
    (assoc message :z z)))

(defn- shrink [z start-offset end-offset]
  (let [[si sj] (start-position z)
        [ei ej] (end-position z)]
    [[si (+ sj start-offset)] [ei (- ej end-offset)]]))

(defn- outside-extent [z]
  [(start-position z) (end-position z)])

(defn- inside-extent [z]
  (case (z/tag z)
    (:list :map :multi-line :vector)           (shrink z 1 1)
    (:regex :set)                              (shrink z 2 1)
    (:namespaced-map :reader-macro)            (recur (-> z z/down z/right))
    (:syntax-quote :unquote :unquote-splicing) (recur (-> z z/down))
    (:token)                                   (if (string? (z/value z))
                                                 (shrink z 1 1)
                                                 (outside-extent z))
    #_otherwise                                (outside-extent z)))

(defn- update-selection-extent [message]
  (if-let [z (:z message)]
    (let [[[ci cj] [ai aj]] (if (= "inside" (:extent message))
                              (inside-extent z)
                              (outside-extent z))]
      (assoc message
             :cursor-line ci
             :cursor-column cj
             :anchor-line ai
             :anchor-column aj))
    (dissoc message :cursor-line :cursor-column :anchor-line :anchor-column)))

(defn- response-for-select
  [{:keys [count] :or {count 1}, :as message}]
  (response-for message (try (-> (nth (iterate (comp update-selection-extent select) message)
                                      (max 1 count))
                                 (select-keys [:cursor-line :cursor-column :anchor-line :anchor-column])
                                 (assoc :status :done))
                             (catch Throwable t
                                {:status :done}))))

(defn wrap-select
  [f]
  (fn [{:keys [op transport] :as message}]
    (if (= "select" op)
      (t/send transport (response-for-select message))
      (f message))))

(set-descriptor! #'wrap-select
  {:doc "Middleware that aids an editor in finding structural objects in source."
   :handles
   {"select"
    {:doc "Find an object"
     :requires
     {"code" "The entire source of the file."
      "kind" "The kind of object (i.e. \"element\")"
      "cursor-line" "The current ones-based selection start/cursor line."
      "cursor-column" "The current ones-based selection start/cursor column."}
     :optional
     {"count" "number of times to expand or repeat the selection."
      "extent" "\"whole\" for the whole object, or \"inside\" for its insides."
      "anchor-line" "The ones-based ending line of the last character of the selection."
      "anchor-column" "The ones-based ending column of the last character of the selection."}
     :returns
     {"cursor-line" "The ones-based starting line of the found object."
      "cursor-column" "The ones-based starting column of the found object."
      "anchor-line" "The ones-based line of the last character of the object."
      "anchor-column" "The ones-based column of the last character of the object."}}}})
