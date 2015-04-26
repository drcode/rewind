(ns rewind.core-test
    (:require [clojure.test :refer :all]
              [riddley.walk :refer [macroexpand-all]]
              [rewind.core :refer [rgo rchan substitute-in-chan substitute-out-chan rewind]]
              [clojure.core.async :refer [go chan >! <!]]))

(deftest a-test
         #_(testing "inchan extraction"
                  (is (= ['(rgo
                            (r>! out "first step")
                            (clojure.core.async/<! :foo)
                            (r>! out "second step")
                            (clojure.core.async/<! :foo)
                            (r>! out "third step"))
                          'in]
                         (substitute-in-chan '(rgo (r>! out "first step")
                                                   (r<! in)
                                                   (r>! out "second step")
                                                   (r<! in)
                                                   (r>! out "third step"))
                                             :foo))))
         #_(testing "outchan extraction"
                  (is (= '(rgo
                           (clojure.core.async/>! out "first step")
                           (r<! in)
                           (clojure.core.async/>! out "second step")
                           (r<! in)
                           (clojure.core.async/>! out "third step"))
                         (substitute-out-chan '(rgo (or (deref :foo) (r>! out "first step"))
                                                    (r<! in)
                                                    (or (deref :foo) (r>! out "second step"))
                                                    (r<! in)
                                                    (or (deref :foo) (r>! out "third step")))
                                              :foo)))))

(deftest go-blocks
         (testing "just with channels"
                  (let [in     (rchan)
                        out    (rchan)
                        result (atom nil)]
                       (go (>! in true)
                           (>! in true))
                       (go (>! out "first step")
                           (<! in)
                           (>! out "second step")
                           (<! in)
                           (>! out "third step"))
                       (go (<! out)
                           (<! out)
                           (is (= "third step" (<! out))))))
         (testing "basic rgo"
                  (let [in     (rchan)
                        out    (rchan)
                        result (atom nil)]
                       (go (>! in true)
                           (>! in true))
                       (rgo (r>! out "first step")
                            (r<! in)
                            (r>! out "second step")
                            (r<! in)
                            (r>! out "third step"))
                       (go (<! out)
                           (<! out)
                           (is (= "third step" (<! out))))))
         (testing "single rewind"
                  (let [in     (rchan)
                        out    (rchan)
                        result (atom nil)]
                       (go (>! in true)
                           (rewind in)
                           (>! in true))
                       (rgo (r>! out "first step")
                            (r<! in)
                            (r>! out "second step")
                            (r<! in)
                            (r>! out "third step"))
                       (go (<! out)
                           (<! out)
                           (is (= "second step" (<! out))))))
         (testing "partial rewind"
                  (let [in     (rchan)
                        out    (rchan)
                        result (atom nil)]
                       (go (>! in true)
                           (>! in true)
                           (rewind in)
                           (>! in true))
                       (rgo (r>! out "first step")
                            (r<! in)
                            (r>! out "second step")
                            (r<! in)
                            (r>! out "third step")
                            (r<! in)
                            (r>! out "fourth step"))
                       (go (is (= "first step" (<! out)))
                           (is (= "second step" (<! out)))
                           (is (= "third step" (<! out)))
                           (is (= "third step" (<! out)))))))
