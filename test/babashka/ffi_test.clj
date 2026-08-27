(ns babashka.ffi-test
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [clojure.test :refer [deftest is testing]]))

;; strlen lives in the C runtime, which the default lookup finds on every OS
(defcfn strlen "strlen" [:string] :long)

(deftest call-test
  (testing "a C call through the default lookup"
    (is (= 5 (strlen "hello")))))

(deftest memory-test
  (testing "an arena allocation roundtrip"
    (with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena :int64)]
        (ffi/write p :int64 42)
        (is (= 42 (ffi/read p :int64)))))))
