(ns rewind.core
    (:require [riddley.walk :refer [walk-exprs]]
              [clojure.core.async :refer [go chan >! <!]]))

(defn substitute-in-chan [body sym silent]
      (let [result (atom nil)]
           [(walk-exprs (fn [expr]
                             (when (coll? expr)
                                   (when-let [[a] (seq expr)]
                                             (= a 'r<!))))
                         (fn [[cmd k & more :as expr]]
                             (reset! result k)
                             `(let [[sil# val#] (<! ~sym ~@more)]
                                   (reset! ~silent sil#)
                                   val#))
                         body)
            @result]))

(defn substitute-out-chan [body silent]
      (walk-exprs (fn [expr]
                      (when (coll? expr)
                            (when-let [[a] (seq expr)]
                                      (= a 'r>!))))
                  (fn [[cmd k & more :as expr]]
                      `(when-not (deref ~silent)
                                 (>! ~k ~@more)))
                  body))

(def rchan chan)
(def rewind-cmd ::rewind)

(defmacro rewind
          ([chan & more]
             `(>! ~chan [~rewind-cmd ~@more]))
          ([chan]
             `(>! ~chan [~rewind-cmd 1])))

(defn rewind-steps [val]
      (when (coll? val)
            (when-let [[cmd n] val]
                      (when (= cmd rewind-cmd)
                            (or n 1)))))

(defmacro rgo [& body]
          (let [in-new (gensym)
                silent (gensym)
                [body in-old] (substitute-in-chan body in-new silent)
                body (substitute-out-chan body silent)]
               `(go (loop [ledger# :start]
                          (let [~in-new (chan)]
                               (go (let [~silent (atom (not= ledger# :start))]
                                        ~@body))
                               (when (not= ledger# :start)
                                     (doseq [k# (reverse ledger#)]
                                            (>! ~in-new [true k#])))
                               (recur (loop [ledger# nil]
                                            (let [val# (<! ~in-old)]
                                                 (if-let [steps# (rewind-steps val#)]
                                                         (drop steps# ledger#)
                                                         (do (>! ~in-new [false val#])
                                                             (recur (cons val# ledger#))))))))))))

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))
