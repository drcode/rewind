(ns rewind.core
    (:require [riddley.walk :refer [walk-exprs]]
              [clojure.core.async :refer [go chan >! <!]]))

(defn substitute-in-chan [body sym]
      (let [result (atom nil)]
           [(walk-exprs (fn [expr]
                             (when (coll? expr)
                                   (when-let [[a] (seq expr)]
                                             (= a 'r<!))))
                         (fn [[cmd k & more :as expr]]
                             (reset! result k)
                             `(<! ~sym ~@more))
                         body)
            @result]))

(defn substitute-out-chan [body silent]
      (walk-exprs (fn [expr]
                      (when (coll? expr)
                            (when-let [[a] (seq expr)]
                                      (= a 'r>!))))
                  (fn [[cmd k & more :as expr]]
                      `(if (deref ~silent)
                           (do (println (str "silent" ~@more))
                               (flush))
                           (do (println (str "loud" ~@more))
                               (>! ~k ~@more))))
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
                [body in-old] (substitute-in-chan body in-new)
                body (substitute-out-chan body silent)]
               `(go (let [~silent (atom false)]
                         (loop [ledger# :start]
                               (let [~in-new (chan)]
                                    
                                    (reset! ~silent (not= ledger# :start))
                                    (go ~@body)
                                    (println "startup done")
                                    (when (not= ledger# :start)
                                          (doseq [k# (reverse ledger#)]
                                                 (println (str "FF" (deref ~silent)) )
                                                 (flush)
                                                 (>! ~in-new k#)))
                                    (reset! ~silent false)
                                    (recur (loop [ledger# nil]
                                                 (let [val# (<! ~in-old)]
                                                      (if-let [steps# (rewind-steps val#)]
                                                              (drop steps# ledger#)
                                                              (do (>! ~in-new val#)
                                                                  (recur (cons val# ledger#)))))))))))))

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))
