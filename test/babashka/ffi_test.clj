(ns babashka.ffi-test
  "The home for API tests: anything that must hold on both hosts belongs
  here, written as plain clojure.test with no harness, so one file covers
  both. `bb test:bb` runs it through babashka's built-in babashka.ffi, and
  `bb test:jvm` runs it on the JVM. Babashka keeps its own suite for what
  only babashka can observe, such as the libffi backend selection, the
  trampoline set, and builds without libffi."
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
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

(def native-image?
  (boolean (System/getProperty "org.graalvm.nativeimage.imagecode")))

(deftest struct-call-test
  (if-not @default-lookup?
    (println "struct call skipped: this build has no default lookup")
    (let [div-t [:struct [[:quot :int] [:rem :int]]]
          c-div (ffi/cfn "div" [:int :int] div-t)]
      (testing "libc div returns a struct by value, as a map"
        (is (= [{:quot 3 :rem 1} {:quot -3 :rem -1}]
               [(c-div 7 2) (c-div -7 2)])))
      (testing "the JVM links a struct call itself, an image asks libffi"
        (is (= (if native-image? :libffi :ffm)
               (:babashka.ffi/backend (meta c-div)))))
      (testing "a struct value must name every field and no other"
        (let [f (ffi/cfn "div" [div-t] :void)]
          (is (thrown-with-msg? Exception #"misses field :rem" (f {:quot 1})))
          (is (thrown-with-msg? Exception #"has unknown field :x" (f {:quot 1 :rem 2 :x 3})))
          (is (thrown-with-msg? Exception #"needs a map of" (f [1 2]))))))))

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

(def callback-arena?
  "An arena owns a callback pointer since the first release; an older
  built-in namespace takes the function first and frees by pointer."
  (delay (try (ffi/callback (ffi/global-arena) (fn [] 0) [] :int) true
              (catch Throwable _ false))))

(defn- sort-ints
  "Sorts xs through libc qsort with cmp as the comparison callback. qsort
  calls back on this thread, during the call, which is what a confined arena
  allows."
  [make-callback xs]
  (let [qsort (ffi/cfn "qsort" [:pointer :size_t :size_t :pointer] :void)
        n (count xs)]
    (with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena (* 4 n))]
        (dotimes [i n] (ffi/write p :int (nth xs i) (* 4 i)))
        (qsort p n 4 (make-callback))
        (mapv #(ffi/read p :int (* 4 %)) (range n))))))

(defn- compare-ints [a b]
  (- (ffi/read (ffi/reinterpret a 4) :int)
     (ffi/read (ffi/reinterpret b 4) :int)))

(deftest callback-test
  (cond
    (not @default-lookup?)
    (println "callback skipped: this build has no default lookup")
    (not @callback-arena?)
    (println "callback skipped: this babashka predates the arena argument")
    :else
    (let [xs [5 3 9 1 7 2]
          sorted [1 2 3 5 7 9]]
      (testing "every arena kind owns a callback"
        (with-open [a (ffi/confined-arena)]
          (is (= sorted (sort-ints #(ffi/callback a compare-ints [:pointer :pointer] :int) xs))))
        (with-open [a (ffi/shared-arena)]
          (is (= sorted (sort-ints #(ffi/callback a compare-ints [:pointer :pointer] :int) xs))))
        (is (= sorted (sort-ints #(ffi/callback (ffi/global-arena) compare-ints
                                                [:pointer :pointer] :int)
                                 xs)))
        (is (= sorted (sort-ints #(ffi/callback (ffi/auto-arena) compare-ints
                                                [:pointer :pointer] :int)
                                 xs))))
      (testing "a closed arena releases the pointer"
        (let [a (ffi/confined-arena)
              cb (ffi/callback a compare-ints [:pointer :pointer] :int)]
          (.close a)
          (is (thrown? Exception (sort-ints (constantly cb) xs)))))
      (testing "a shared arena accepts a call from another thread"
        (with-open [a (ffi/shared-arena)]
          (let [cb (ffi/callback a compare-ints [:pointer :pointer] :int)]
            (is (= sorted @(future (sort-ints (constantly cb) xs))))))))))

;; -- struct arguments ---------------------------------------------------------
;; Nothing portable in libc takes a struct by value, so this half of the ABI
;; needs a fixture. test-resources/struct_lib.c is compiled here when a C
;; compiler is on PATH, and the tests skip when it is not.

(def struct-lib
  "Compiles and loads the struct fixture. The value is true when the library
  is loaded, and a string saying why when it is not, so a skip names its own
  cause instead of guessing one."
  (delay
    (let [ext (cond (str/starts-with? (System/getProperty "os.name") "Windows") ".dll"
                    (str/starts-with? (System/getProperty "os.name") "Mac") ".dylib"
                    :else ".so")
          out (fs/path "target" (str "libffistructs" ext))
          src (fs/path "test-resources" "struct_lib.c")]
      (cond
        ;; the suite also runs from babashka's root, where this repo's
        ;; test-resources is not the working directory
        (not (fs/exists? src))
        (str "the fixture source is not under " (fs/absolutize src))

        (and (fs/exists? out)
             (not (neg? (compare (fs/last-modified-time out)
                                 (fs/last-modified-time src)))))
        (do (ffi/load-library (str (fs/absolutize out))) true)

        :else
        (do (fs/create-dirs (fs/parent out))
            (let [{:keys [exit err]}
                  (try (if (= ".dll" ext)
                         (p/sh "cl" "/nologo" "/LD" (str src)
                               (str "/Fe:" out) (str "/Fo:" out ".obj"))
                         (p/sh "cc" "-shared" "-fPIC" "-o" (str out) (str src)))
                       (catch Exception e {:exit -1 :err (ex-message e)}))]
              (if (zero? exit)
                (do (ffi/load-library (str (fs/absolutize out))) true)
                (str "the fixture did not compile: " (str/trim (str err))))))))))

(def p2 [:struct [[:x :int] [:y :int]]])
(def v3 [:struct [[:x :double] [:y :double] [:z :double]]])
(def big [:struct [[:a :long] [:b :long] [:c :long] [:d :long]]])
(def pad [:struct [[:c :char] [:d :double]]])
(def rect [:struct [[:lo p2] [:hi p2]]])
(def named [:struct [[:id :int] [:name :string]]])

(deftest struct-argument-test
  (if-not (and @struct-access? (true? @struct-lib))
    (println "struct arguments skipped:"
             (cond (not @struct-access?) "this babashka predates struct access"
                   (string? @struct-lib) @struct-lib
                   :else "unknown reason"))
    (do
      (testing "each ABI class of struct argument"
        (is (= 7 ((ffi/cfn "p2_sum" [p2] :int) {:x 3 :y 4})))
        (is (= 6.0 ((ffi/cfn "v3_sum" [v3] :double) {:x 1.0 :y 2.0 :z 3.0})))
        (is (= 10 ((ffi/cfn "big_sum" [big] :long) {:a 1 :b 2 :c 3 :d 4})))
        (is (= 9.5 ((ffi/cfn "pad_sum" [pad] :double) {:c 7 :d 2.5})))
        (is (= 10 ((ffi/cfn "rect_sum" [rect] :int) {:lo {:x 1 :y 2} :hi {:x 3 :y 4}}))))
      (testing "structs mixed with scalar arguments"
        (is (= 26.0 ((ffi/cfn "mixed_sum" [:int p2 :double v3] :double)
                     1 {:x 3 :y 4} 5.0 {:x 6.0 :y 7.0 :z 0.0}))))
      (testing "a :string field is a pointer the caller owns"
        (with-open [arena (ffi/confined-arena)]
          (is (= 10 ((ffi/cfn "named_len" [named] :int)
                     {:id 5 :name (ffi/string->ptr arena "hello")})))))
      (testing "a struct goes in and another comes back"
        (is (= {:lo {:x 3 :y 4} :hi {:x 1 :y 2}}
               ((ffi/cfn "rect_swap" [rect] rect) {:lo {:x 1 :y 2} :hi {:x 3 :y 4}}))))
      (testing "a struct argument with a :void return"
        (with-open [arena (ffi/confined-arena)]
          (let [out (ffi/alloc arena :int)]
            ((ffi/cfn "p2_store" [p2 :pointer] :void) {:x 3 :y 4} out)
            (is (= 304 (ffi/read out :int)))))))))

;; -- fixed arrays -------------------------------------------------------------

(def array-layout?
  "[:array elem n] arrived after the first release; an older built-in
  namespace does not know the kind."
  (delay (try (= 16 (ffi/sizeof [:array :int 4])) (catch Exception _ false))))

(def bone [:struct [[:name [:array :char 32]] [:parent :int]]])
(def spine
  "The bytes of \"spine\" in a char[32], as C stores a fixed-width string."
  (vec (concat (map long (.getBytes "spine")) (repeat 27 0))))

(deftest array-layout-test
  (if-not @array-layout?
    (println "array layouts skipped: this babashka predates them")
    (do
      (testing "an array is its elements back to back"
        (is (= 16 (ffi/sizeof [:array :int 4])))
        (is (= 4 (ffi/alignof [:array :int 4])))
        (is (= 36 (ffi/sizeof bone)))
        (is (= 32 (ffi/sizeof [:array [:array :double 2] 2])))
        (is (= 16 (ffi/sizeof [:array p2 2]))))
      (testing "an array reads as a vector and writes from any sequence"
        (with-open [arena (ffi/confined-arena)]
          (let [p (ffi/alloc arena [:array :int 4])]
            (ffi/write p [:array :int 4] [1 2 3 4])
            (is (= [1 2 3 4] (ffi/read p [:array :int 4])))
            (is (= 3 (ffi/read p :int 8)))
            (ffi/write p [:array :int 4] (int-array [5 6 7 8]))
            (is (= [5 6 7 8] (ffi/read p [:array :int 4])))
            (ffi/write p [:array :int 4] (list 9 9 9 9))
            (is (= [9 9 9 9] (ffi/read p [:array :int 4]))))))
      (testing "a char array in a struct, and the fixed-width string in it"
        (with-open [arena (ffi/confined-arena)]
          (let [p (ffi/alloc arena bone)]
            (ffi/write p bone {:name spine :parent 7})
            (is (= {:name spine :parent 7} (ffi/read p bone)))
            ;; the string read is a bounded ptr->string over the field
            (is (= "spine" (ffi/ptr->string (ffi/slice p 0 32) 32))))))
      (testing "arrays nest, and hold structs and pointers"
        (with-open [arena (ffi/confined-arena)]
          (let [m [:array [:array :double 2] 2]
                q (ffi/alloc arena m)]
            (ffi/write q m [[1.0 2.0] [3.0 4.0]])
            (is (= [[1.0 2.0] [3.0 4.0]] (ffi/read q m))))
          (let [pair [:array p2 2]
                q (ffi/alloc arena pair)]
            (ffi/write q pair [{:x 1 :y 2} {:x 3 :y 4}])
            (is (= [{:x 1 :y 2} {:x 3 :y 4}] (ffi/read q pair))))
          (let [ptrs [:array :pointer 2]
                q (ffi/alloc arena ptrs)]
            (ffi/write q ptrs [(ffi/string->ptr arena "one") (ffi/string->ptr arena "two")])
            (is (= ["one" "two"] (mapv ffi/ptr->string (ffi/read q ptrs)))))))
      (testing "the element count is part of the layout"
        (with-open [arena (ffi/confined-arena)]
          (let [p (ffi/alloc arena [:array :int 4])]
            (is (thrown-with-msg? Exception #"needs 4 elements, got 3"
                                  (ffi/write p [:array :int 4] [1 2 3])))
            (is (thrown-with-msg? Exception #"needs 4 elements, got 5"
                                  (ffi/write p [:array :int 4] [1 2 3 4 5])))
            (is (thrown-with-msg? Exception #"needs 4 elements, got 42"
                                  (ffi/write p [:array :int 4] 42))))))
      (testing "a malformed array layout is an error at resolve time"
        (is (thrown-with-msg? Exception #"positive element count" (ffi/sizeof [:array :int 0])))
        (is (thrown-with-msg? Exception #"is \[:array elem n\]" (ffi/sizeof [:array :int])))
        (is (thrown-with-msg? Exception #":void is not an element" (ffi/sizeof [:array :void 2]))))
      (testing "C passes an array as a pointer, so a bare array is not a signature type"
        (is (thrown-with-msg? Exception #"C passes an array as a pointer"
                              (ffi/cfn "abs" [[:array :int 4]] :int)))
        (is (thrown-with-msg? Exception #"C passes an array as a pointer"
                              (ffi/cfn "abs" [:int] [:array :int 4])))))))

(deftest array-in-struct-call-test
  (if-not (and @array-layout? (true? @struct-lib))
    (println "array struct calls skipped:"
             (cond (not @array-layout?) "this babashka predates array layouts"
                   (string? @struct-lib) @struct-lib
                   :else "unknown reason"))
    (let [quad [:struct [[:v [:array :int 4]]]]
          mat2 [:struct [[:m [:array [:array :double 2] 2]]]]
          pair [:struct [[:pts [:array p2 2]]]]]
      (testing "an array of ints in two integer registers"
        (is (= 10 ((ffi/cfn "quad_sum" [quad] :int) {:v [1 2 3 4]})))
        (is (= {:v [5 6 7 8]} ((ffi/cfn "quad_make" [:int] quad) 5))))
      (testing "a char array in a struct passed in memory, both directions"
        (is (= 12 ((ffi/cfn "bone_len" [bone] :int) {:name spine :parent 7})))
        (is (= {:name spine :parent 3} ((ffi/cfn "bone_make" [:int] bone) 3))))
      (testing "a two-dimensional array of doubles, which is an HFA"
        (is (= 5.0 ((ffi/cfn "mat2_trace" [mat2] :double) {:m [[1.0 2.0] [3.0 4.0]]}))))
      (testing "an array of structs"
        (is (= 10 ((ffi/cfn "pair_sum" [pair] :int) {:pts [{:x 1 :y 2} {:x 3 :y 4}]})))))))
