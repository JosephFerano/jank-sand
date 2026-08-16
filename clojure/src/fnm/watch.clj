(ns fnm.watch
  "A pull-based watch: the game drops a snapshot into an atom, Emacs polls it on
  its own timer. Nothing is pushed over nREPL, so the REPL stays clean and the
  watch rate is decoupled from the frame rate."
  (:require [clojure.string :as str])
  (:import (java.util.concurrent ConcurrentHashMap)))

(set! *warn-on-reflection* true)

;; Compile-time flag. -Dfnm.dev=false makes every macro here expand to nothing
;; (spy forms collapse back to the bare expression), so a production build
;; carries no cost at all.
(def ^:const enabled? (not= "false" (System/getProperty "fnm.dev" "true")))

(defonce values (atom {}))

(defmacro watch!
  "Publish a map of label -> value for the pinned watch buffer.

  Call this once per frame, never inside a hot inner loop -- one reset! of the
  whole map is 120/sec and free, while per-key swap!s both allocate in the loop
  and let the reader see a torn, half-updated frame."
  [m]
  (when enabled?
    `(reset! values ~m)))

;;; --------------------------------------------------------------------- spy

(defonce ^ConcurrentHashMap spied (ConcurrentHashMap.))

(defn spy* [label v]
  (.put spied label v)
  v)

(defmacro spy
  "Record the value of expr under label and return it unchanged, so you can wrap
  an expression in place without restructuring the code:

      (let [target (spy :target (min (dec rows) (+ row (long vv))))]
        ...)

  Last write wins. That is fine per frame, but from a hot loop you only ever see
  whichever cell happened to run last -- use spy-long there instead."
  [label expr]
  (if enabled?
    `(spy* ~label ~expr)
    expr))

;;; Numeric spy for hot loops. State lives in a double-array per label -- no
;;; boxing, no allocation, so this survives being called thousands of times a
;;; frame. Slots: 0 count, 1 min, 2 max, 3 last, 4 sum.
(defonce ^ConcurrentHashMap counters (ConcurrentHashMap.))

(defn slot ^doubles [label]
  (or (.get counters label)
      (.computeIfAbsent counters label
                        (reify java.util.function.Function
                          (apply [_ _]
                            (double-array [0 Double/POSITIVE_INFINITY
                                           Double/NEGATIVE_INFINITY 0 0]))))))

(defn record! [^doubles s ^double v]
  (aset s 0 (unchecked-inc (aget s 0)))
  (when (< v (aget s 1)) (aset s 1 v))
  (when (> v (aget s 2)) (aset s 2 v))
  (aset s 3 v)
  (aset s 4 (unchecked-add (aget s 4) v))
  nil)

(defmacro spy-num
  "Like spy, but for a numeric expression in a hot loop. Accumulates
  count/min/max/last/mean instead of keeping one sample, which is what you
  actually want when the expression runs thousands of times per frame.

  Works on longs, doubles and floats alike. Note it returns the *original*
  value, not a coerced one -- wrapping a float in something that hands back a
  long silently truncates it and changes what the surrounding code computes."
  [label expr]
  (if enabled?
    `(let [v# ~expr]
       (record! (slot ~label) (double v#))
       v#)
    expr))

(defn reset-spies!
  "Clear accumulated spy state. Stats are cumulative until you call this."
  []
  (.clear spied)
  (.clear counters))

;;; ------------------------------------------------------------------ render

(defn- fmt-num [^double d]
  ;; Print whole numbers as integers so a spy on an index doesn't read as 66.00.
  (if (== d (Math/rint d)) (str (long d)) (format "%.4f" d)))

(defn- fmt-slot [^doubles s]
  (let [n (aget s 0)]
    (if (zero? n)
      "(no samples)"
      (format "n=%d  min=%s  max=%s  last=%s  mean=%s"
              (long n) (fmt-num (aget s 1)) (fmt-num (aget s 2))
              (fmt-num (aget s 3)) (fmt-num (/ (aget s 4) n))))))

(defn render
  "Format the current snapshot as plain text. Emacs calls this, not you."
  []
  ;; grid is 91,200 ints -- watch it by accident without these bound and the
  ;; render hangs instead of printing.
  (binding [*print-length* 20
            *print-level*  3]
    (let [rows (concat (for [[k v] @values]         [(str k) (pr-str v)])
                       (for [[k v] (into {} spied)] [(str k) (pr-str v)])
                       (for [[k v] (into {} counters)] [(str k) (fmt-slot v)]))
          rows (sort-by first rows)
          w    (reduce max 1 (map (comp count first) rows))]
      (if (empty? rows)
        "(nothing watched)"
        (str/join "\n"
                  (for [[k v] rows]
                    (format (str "%-" w "s  %s") k v)))))))
