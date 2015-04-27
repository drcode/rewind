(ns rewind.core
    (:require [riddley.walk :refer [walk-exprs]]
              [clojure.core.async :refer [go chan >! <!]]))

(defn substitute-in-rchan [body sym silent out-counts out-counts-chan]
      (let [result (atom nil)]
           [(walk-exprs (fn [expr]
                            (when (coll? expr)
                                  (when-let [[a] (seq expr)]
                                            (= a 'r<!))))
                        (fn [[cmd k & more :as expr]]
                            (reset! result k)
                            `(do (>! ~out-counts-chan (deref ~out-counts))
                                 (let [[sil# val#] (<! ~sym ~@more)]
                                      (reset! ~silent sil#)
                                      val#)))
                        body)
            @result]))

(defn substitute-out-chan [body silent]
      (walk-exprs (fn [expr]
                      (when (coll? expr)
                            (when-let [[a] (seq expr)]
                                      (= a '>!))))
                  (fn [[cmd k & more :as expr]]
                      `(when-not (deref ~silent)
                                 (>! ~k ~@more)))
                  body))

(defn substitute-out-rchan [body silent out-counts]
      (walk-exprs (fn [expr]
                      (when (coll? expr)
                            (when-let [[a] (seq expr)]
                                      (= a 'r>!))))
                  (fn [[cmd k & more :as expr]]
                      `(let [c# ~k]
                            (swap! ~out-counts
                                   update-in
                                   [c#]
                                   (fn [g#]
                                       (inc (or g# 0))))
                            (when-not (deref ~silent)
                                      (>! c# ~@more))))
                  body))

(def rchan chan)
(def rewind-cmd ::rewind)

(defn rewind-struct [steps]
      [rewind-cmd steps])

(defmacro rewind
          ([chan & more]
             `(>! ~chan (rewind-struct ~@more)))
          ([chan]
             `(>! ~chan (rewind-struct 1))))

(defn rewind-steps [val]
      (when (coll? val)
            (when-let [[cmd n] val]
                      (when (= cmd rewind-cmd)
                            (or n 1)))))

(defmacro rgo [& body]
          (let [;;We define some variables with gensyms here because we need to refer to them ahead of
                ;;time in some transformation functions.
                
                ;;We're defining this channel so we can intercept the "old" incoming channel to the rgo
                ;;block. No "rewinds" will ever be stuck in the new channel. Also, all entries sent via
                ;;the incoming channel are labeled with a "silence" label that is used to update the
                ;;silence atom as needed.
                in-new          (gensym)

                ;;In order to rewind outgoing rchans the right amount, we must keep track of how many
                ;;values were pushed into each channel throught history. To reliably extract this info
                ;;from the rgo block, we have a separate channel that receives this data immediately
                ;;before a new value is pulled in via the incoming rchan.
                out-counts-chan (gensym)

                ;;Since we are replaying all history from "zero time" during every rewind, we need to
                ;;"silence" outgoing chans and rchans by checking an "silent" atom before every outgoing
                ;;push.
                silent          (gensym)

                ;;The actual counts of writes to the outgoing rchans. The "key" is the explicit channel
                ;;object, the "value" is a count integer.
                out-counts      (gensym)

                ;;Here we build the sexp which replaces the incoming rchan with our replacement. This
                ;;macro also sends out the out-count info and updates the silence variable.
                [body in-old]   (substitute-in-rchan body in-new silent out-counts out-counts-chan)

                ;;This is the transformation function for outgoing old-style channels. The important
                ;;thing is that these channels are silenced for replays.
                body            (substitute-out-chan body silent)

                ;;This is the transformation function for outgoing rchans. Along with silencing, extra
                ;;logic is added to count how many times these channels are called with the out-counts
                ;;atom.
                body            (substitute-out-rchan body silent out-counts)]
               `(go (loop [ledger# :start]
                          ;;This loop will cycle every time a "rewind" is performed- ledger info is
                          ;;sent from the last replay, which consists of the data sent in the incoming
                          ;;rchan, as well as counts on the outgoing rchans.

                          (let [~in-new          (chan)
                                ~out-counts-chan (chan)]
                               ;;These channels need to be newly created with every replay, since we're
                               ;;redeclaring a new go block for every rgo block with every replay.

                               ;;Note that the go block below is nested, but not "taken", which means
                               ;;it is a "free" go block, unconnected from anything else, able to
                               ;;execute on its own accord:
                               (go (let [~silent     (atom (not= ledger# :start))
                                         ~out-counts (atom {})]
                                        ;;These "silent" and "out-counts" variables live in the go block
                                        ;;that is replayed. This is needed, because you should never
                                        ;;modify an external atom from a "go" block, since there is
                                        ;;nondeterminism as to when different parts of a go block are
                                        ;;executed.
                                        ~@body))

                               ;;OK, it's possible we need to "replay" the beggining of the go block.
                               ;;We do that here. Note that an empty ledger means "no incoming channels
                               ;;have received data", but events can happen BEFORE the first incoming
                               ;;channel is read from. Therefore, we have an additional magical "start"
                               ;;value for the ledger for the "true" beggining of the rgo block.
                               (when (not= ledger# :start)
                                     (doseq [k# (reverse ledger#)]

                                            ;;We need to read out-counts every time before pushing new
                                            ;;data into the incoming rchan.
                                            (<! ~out-counts-chan)

                                            ;;We mark this with "true" to say silent=true.
                                            (>! ~in-new [true k#])))

                               ;;OK, now we've "replayed" to the correct spot and the program can start running.
                               (recur (loop [ledger# nil]
                                            (let [val# (<! ~in-old)

                                                  ;;oc will contain info on just how many times each outgoing
                                                  ;;rchan has had info pushed to it.
                                                  oc#  (<! ~out-counts-chan)]
                                                 (if-let [steps# (rewind-steps val#)]
                                                         ;;rewind-steps will return a true value of num of steps
                                                         ;;if the value from the incoming rchan was a rewind.
                                                         
                                                         (let [[[_ oc-old#] & ledger-new#] (drop (dec steps#) ledger#)]
                                                              ;;We have now pulled the penultimate out-counts data
                                                              ;;from the ledger, and droped items from the ledger
                                                              ;;that we wish to expunge.

                                                              ;;All outgoing rchans now need a message sent to them
                                                              ;;indicating how many values we have rewound. We
                                                              ;;calculate this by using the new and old out-counts.
                                                              (doseq [[kp# vp#] oc#]
                                                                     (>! kp# (rewind-struct (- vp# (or (oc-old# kp#) 0)))))
                                                              ledger-new#)
                                                         
                                                         ;;The value in the incoming rchan is just a plain old
                                                         ;;value. Let's send it along to the rgo block (After
                                                         ;;we label it with "false" to indicate it is not
                                                         ;;silent.)
                                                         (do (>! ~in-new [false val#])
                                                             ;;Now we loop back with the last incoming value
                                                             ;;and the latest outgoing counts appended to
                                                             ;;the ledger.
                                                             (recur (cons [val# oc#] ledger#))))))))))))

(defn rchan->chan [rc]
      "Turn an rchan into a chan. This function does the conversion in the 'smart' way, by figuring out what the most recent value was before the rewind, and resending it in the new 'stupid' channel. This is the behavior you would want if the data being send through the channel represents 'application state', in which case a rewind means 'The state now update to whatever it was right before the expunged entries.'"
      (let [c (chan)]
           (go (loop [ledger nil]
                     (let [val (<! rc)
                           rs  (rewind-steps val)]
                          (if rs
                              (let [ledger (drop rs ledger)]
                                   (>! c (first ledger))
                                   (recur ledger))
                              (do (>! c val)
                                  (recur (cons val ledger)))))))
           c))

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))
