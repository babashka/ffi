(ns babashka.ffi-test
  "The home for API tests: anything that must hold on both hosts belongs
  here, written as plain clojure.test with no harness, so one file covers
  both. `bb test:bb` runs it through babashka's built-in babashka.ffi, and
  `bb test:jvm` runs it on the JVM. Babashka keeps its own suite for what
  only babashka can observe, such as the libffi backend selection, the
  trampoline set, and builds without libffi."
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [clojure.test :refer [deftest is testing]]))

;; strlen lives in the C runtime, which the default lookup finds on every OS
;; that has one. Binding is lazy, so this is safe even where it does not.
(defcfn strlen "strlen" [:string] :long)


(def default-lookup?
  "A statically linked musl binary has no dlopen and no FFM default lookup,
  so it cannot reach the C runtime by name. Memory still works there, so only
  the tests that call a C function ask this."
  (delay (try (strlen "probe") true (catch Throwable _ false))))

(def point [:struct [[:x :int] [:y :int]]])

(deftest call-test
  (if-not @default-lookup?
    (println "C call skipped: this build has no default lookup")
    (testing "a C call through the default lookup"
      (is (= 5 (strlen "hello"))))))

(deftest memory-test
  (testing "an arena allocation roundtrip"
    (with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena :int64)]
        (ffi/write p :int64 42)
        (is (= 42 (ffi/read p :int64))))))
  (testing "the byte offset is the last argument"
    (with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena 16)]
        (ffi/write p :int 7 4)
        (is (= 0 (ffi/read p :int)))
        (is (= 7 (ffi/read p :int 4))))))
  (testing "the arena is required"
    #_{:clj-kondo/ignore [:invalid-arity]}
    (is (thrown? clojure.lang.ArityException (ffi/alloc 8)))))

(deftest layout-test
  (testing "sizeof and alignof resolve a layout"
    (is (= 8 (ffi/sizeof point)))
    (is (= 4 (ffi/alignof point))))
  (testing "alloc and slice take a layout where they take a size"
    (with-open [arena (ffi/confined-arena)]
      (let [arr (ffi/alloc arena [:struct [[:lo point] [:hi point]]])]
        (is (= 16 (ffi/size arr)))
        (is (= 8 (ffi/size (ffi/slice arr 8 point)))))))
  (testing "a string allocated in an arena reads back"
    (with-open [arena (ffi/confined-arena)]
      (is (= "hello" (ffi/ptr->string (ffi/string->ptr arena "hello")))))))

(def struct-access?
  "Reading a struct through a layout arrived after the first release, so the
  built-in namespace of an older babashka does not have it. The suite runs on
  whatever babashka is installed, so it asks rather than assumes."
  (delay (with-open [arena (ffi/confined-arena)]
           (try (ffi/read (ffi/alloc arena point) point) true
                (catch Exception _ false)))))

(deftest struct-access-test
  (if-not @struct-access?
    (println "struct read and write skipped: this babashka predates them")
    (let [rect [:struct [[:lo point] [:hi point]]]]
      (testing "a struct reads back as a map and writes from one"
        (with-open [arena (ffi/confined-arena)]
          (let [p (ffi/alloc arena point)]
            (ffi/write p point {:x 3 :y 4})
            (is (= {:x 3 :y 4} (ffi/read p point))))))
      (testing "nested layouts round trip"
        (with-open [arena (ffi/confined-arena)]
          (let [r (ffi/alloc arena rect)
                v {:lo {:x 1 :y 2} :hi {:x 3 :y 4}}]
            (ffi/write r rect v)
            (is (= v (ffi/read r rect))))))
      (testing "an offset addresses one element of an array of structs"
        (with-open [arena (ffi/confined-arena)]
          (let [arr (ffi/alloc arena 24)]
            (dotimes [i 3]
              (ffi/write arr point {:x i :y (* 2 i)} (* i 8)))
            (is (= [{:x 0 :y 0} {:x 1 :y 2} {:x 2 :y 4}]
                   (mapv #(ffi/read arr point (* % 8)) (range 3)))))))
      (testing "a :string field takes a pointer the caller owns"
        (with-open [arena (ffi/confined-arena)]
          (let [named [:struct [[:id :int] [:name :string]]]
                p (ffi/alloc arena named)]
            (ffi/write p named {:id 7 :name (ffi/string->ptr arena "seven")})
            (is (= {:id 7 :name "seven"} (ffi/read p named))))))
      (testing "a bare string has no owner, so it is refused with the remedy"
        (with-open [arena (ffi/confined-arena)]
          (is (thrown-with-msg?
               Exception #"string->ptr arena"
               (ffi/write (ffi/alloc arena 16) [:struct [[:s :string]]] {:s "x"})))))
      (testing "an invalid struct value reports the field"
        (with-open [arena (ffi/confined-arena)]
          (is (thrown-with-msg?
               Exception #"misses field :y"
               (ffi/write (ffi/alloc arena point) point {:x 1}))))))))

(defn- sizeless
  "A pointer with no size, the way a C function returns one. Built from an
  arena string rather than a C call: reaching the C runtime by name is not
  portable, and on Windows it resolves to something that is not the function
  it names, which crashed the process here."
  [arena s]
  (ffi/segment (ffi/address (ffi/string->ptr arena s))))

(def unsized-string?
  "Reading a string from a pointer with no size arrived after the first
  release; an older built-in namespace refuses one."
  (delay (with-open [arena (ffi/confined-arena)]
           (try (= "probe" (ffi/ptr->string (sizeless arena "probe")))
                (catch Exception _ false)))))

(deftest ptr->string-test
  (if-not @unsized-string?
    (println "ptr->string skipped: this babashka predates reading a sizeless pointer")
    (with-open [arena (ffi/confined-arena)]
      (testing "a pointer with no size reads to the NUL"
        (let [p (sizeless arena "hello")]
          (is (zero? (ffi/size p)))
          (is (= "hello" (ffi/ptr->string p)))))
      (testing "a limit stops the read"
        (let [p (sizeless arena "hello")]
          (is (= "hello" (ffi/ptr->string p 64)))
          (is (= "hello" (ffi/ptr->string p 6)))))
      (testing "a limit with no NUL inside it is an error, not a walk"
        (is (thrown-with-msg? Exception #"no NUL byte in the first 3 bytes"
                              (ffi/ptr->string (sizeless arena "hello") 3))))
      (testing "a limit narrows but never widens an existing bound"
        (let [p (ffi/alloc arena 8)]
          ;; every byte non-NUL: a scan that respects the size must throw,
          ;; and must report the size rather than the larger limit
          (ffi/write-bytes p (byte-array (repeat 8 (byte 65))))
          (is (thrown-with-msg? Exception #"no NUL byte in the first 8 bytes"
                                (ffi/ptr->string p 64)))))
      (testing "NULL is nil, with and without a limit"
        (is (nil? (ffi/ptr->string ffi/null)))
        (is (nil? (ffi/ptr->string ffi/null 8)))))))
