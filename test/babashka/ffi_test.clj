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
          (dotimes [i 8] (ffi/write p :int8 65 i))
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

;; -- bulk copy ----------------------------------------------------------------
;; read-array arrived after the first release. A test that names the var
;; does not load on an older built-in namespace, because the symbol resolves
;; at analysis time, so the two vars are looked up at run time instead.

(def bulk-array?
  (delay (boolean (resolve 'babashka.ffi/read-array))))

(deftest bulk-array-test
  (if-not @bulk-array?
    (println "bulk copy skipped: this babashka predates read-array")
    (let [read-array (resolve 'babashka.ffi/read-array)
          write-array (resolve 'babashka.ffi/write-array)]
      (with-open [arena (ffi/confined-arena)]
        (let [p (ffi/alloc arena 64)]
          (testing "a copy in and out of a Java array, at an offset"
            (write-array p :int (int-array [1 2 3 4]))
            (is (= [1 2 3 4] (vec (read-array p :int 4))))
            (is (instance? (Class/forName "[I") (read-array p :int 4)))
            (is (= [3 4] (vec (read-array p :int 2 8))))
            (write-array p :double (double-array [1.5 2.5]) 16)
            (is (= [1.5 2.5] (vec (read-array p :double 2 16)))))
          (testing "the copy agrees with the layout read"
            (is (= (ffi/read p [:array :int 4]) (vec (read-array p :int 4)))))
          (testing "a copy is a memcpy: the type gives the width, not the sign"
            (ffi/write p :uint 0xFFFFFFFF)
            (is (= -1 (first (read-array p :uint 1))))
            (is (= 0xFFFFFFFF (ffi/read p :uint))))
          (testing ":pointer copies addresses"
            (let [s (ffi/string->ptr arena "x")]
              (ffi/write p [:array :pointer 1] [s])
              (is (= [(ffi/address s)] (vec (read-array p :pointer 1))))))
          (testing "what a copy cannot do says where to go instead"
            (is (thrown-with-msg? Exception #"use read and write with \[:array"
                                  (read-array p [:struct [[:x :int]]] 2)))
            (is (thrown-with-msg? Exception #"pointers to bytes elsewhere"
                                  (read-array p :string 2)))
            (is (thrown-with-msg? Exception #":int needs int\[\], got long\[\]"
                                  (write-array p :int (long-array 2)))))
          (testing "a copy past the end throws instead of reading on"
            (is (thrown? Exception (read-array p :int 17)))
            (is (thrown? Exception (write-array p :long (long-array 9))))))))))

;; -- segment copy -------------------------------------------------------------
;; copy and clone arrived after the first release; the vars are looked up at
;; run time so the namespace loads on an older babashka and skips.

(def segment-copy?
  (delay (boolean (resolve 'babashka.ffi/copy))))

(deftest segment-copy-test
  (if-not @segment-copy?
    (println "segment copy skipped: this babashka predates copy")
    (let [copy (resolve 'babashka.ffi/copy)
          clone (resolve 'babashka.ffi/clone)
          read-array (resolve 'babashka.ffi/read-array)
          write-array (resolve 'babashka.ffi/write-array)]
      (with-open [arena (ffi/confined-arena)]
        (let [src (ffi/alloc arena 16)
              dst (ffi/alloc arena 32)]
          (write-array src :int (int-array [1 2 3 4]))
          (testing "a copy of the whole source, and of n bytes into a slice"
            (copy src dst)
            (is (= [1 2 3 4] (vec (read-array dst :int 4))))
            (copy src (ffi/slice dst 16) 8)
            (is (= [1 2 3 4 1 2 0 0] (vec (read-array dst :int 8)))))
          (testing "clone allocates a copy of the same size in the arena"
            (let [c (clone arena src)]
              (is (= 16 (ffi/size c)))
              (is (= [1 2 3 4] (vec (read-array c :int 4))))
              (is (not= (ffi/address c) (ffi/address src)))))
          (testing "a copy that does not fit throws instead of writing past the end"
            (is (thrown? Exception (copy dst src)))
            (is (thrown? Exception (copy src dst 17))))
          (testing "a pointer without a size says to reinterpret it"
            (is (thrown-with-msg? Exception #"reinterpret"
                                  (copy (ffi/segment (ffi/address src)) dst))))
          (testing "overlapping regions copy as memmove"
            (write-array dst :int (int-array [1 2 3 4 5 6 7 8]))
            (copy dst (ffi/slice dst 4) 16)
            (is (= [1 1 2 3 4 6 7 8] (vec (read-array dst :int 8))))))))))

;; -- unions -------------------------------------------------------------------

(def union-layout?
  "[:union members] arrived after the first release; an older built-in
  namespace does not know the kind."
  (delay (try (= 8 (ffi/sizeof [:union [[:a :int] [:b :double]]])) (catch Exception _ false))))

(def tagged [:struct [[:tag :int] [:u [:union [[:i :int] [:d :double] [:s :pointer]]]]]])

(deftest union-layout-test
  (if-not @union-layout?
    (println "union layouts skipped: this babashka predates them")
    (let [data [:union [[:whatever :pointer] [:result :int]]]
          curl-msg [:struct [[:msg :int] [:easy :pointer] [:data data]]]]
      (testing "a union is as large as its largest member, at its strictest alignment"
        (is (= 24 (ffi/sizeof curl-msg)))
        (is (= [8 8] [(ffi/sizeof [:union [[:c :char] [:d :double]]])
                      (ffi/alignof [:union [[:c :char] [:d :double]]])]))
        (is (= [4 2] [(ffi/sizeof [:union [[:a [:array :char 3]] [:b :int16]]])
                      (ffi/alignof [:union [[:a [:array :char 3]] [:b :int16]]])])))
      (testing "read gives the union's bytes as a pointer; the caller reads the member"
        (with-open [arena (ffi/confined-arena)]
          (let [p (ffi/alloc arena curl-msg)]
            (ffi/write p curl-msg {:msg 1 :easy nil :data [:result 7]})
            (let [{:keys [msg data]} (ffi/read p curl-msg)]
              (is (= 1 msg))
              (is (ffi/pointer? data))
              (is (= 8 (ffi/size data)))
              (is (= 7 (ffi/read data :int)))
              (is (= (+ (ffi/address p) 16) (ffi/address data))))
            (ffi/write p curl-msg {:msg 1 :easy nil :data [:whatever (ffi/string->ptr arena "x")]})
            (is (= "x" (ffi/ptr->string (ffi/read (:data (ffi/read p curl-msg)) :pointer)))))))
      (testing "write takes a pair, [member value]"
        (with-open [arena (ffi/confined-arena)]
          (let [p (ffi/alloc arena data)]
            (ffi/write p data [:result 9])
            (is (= 9 (ffi/read p :int)))
            (is (thrown-with-msg? Exception #"is a pair \[member value\]" (ffi/write p data {:result 1})))
            (is (thrown-with-msg? Exception #"is a pair \[member value\]" (ffi/write p data [:result 1 :whatever nil])))
            (is (thrown-with-msg? Exception #"unknown member :nope" (ffi/write p data [:nope 1])))
            (is (thrown-with-msg? Exception #"is a pair" (ffi/write p data 5))))))
      (testing "a union is not passed by value, bare or inside a struct"
        (is (thrown-with-msg? Exception #"not passed by value" (ffi/cfn "abs" [data] :int)))
        (is (thrown-with-msg? Exception #"not passed by value" (ffi/cfn "abs" [:int] data)))
        (is (thrown-with-msg? Exception #"not passed by value" (ffi/cfn "abs" [curl-msg] :int)))
        (is (fn? (ffi/cfn "abs" [:pointer] :int))))
      (testing "a malformed union layout is an error at resolve time"
        (is (thrown-with-msg? Exception #"is \[:union members\]" (ffi/sizeof [:union [[:a :int]] :x])))
        (is (thrown-with-msg? Exception #"names a member twice" (ffi/sizeof [:union [[:a :int] [:a :int]]])))))))

(deftest union-in-struct-c-test
  (if-not (and @union-layout? (true? @struct-lib))
    (println "union C test skipped:"
             (cond (not @union-layout?) "this babashka predates union layouts"
                   (string? @struct-lib) @struct-lib
                   :else "unknown reason"))
    (let [fill (ffi/cfn "tagged_fill" [:pointer :int] :void)
          value (ffi/cfn "tagged_value" [:pointer] :double)]
      (testing "the compiler and the layout agree on where the union sits"
        (with-open [arena (ffi/confined-arena)]
          (let [p (ffi/alloc arena tagged)]
            (is (= 16 (ffi/sizeof tagged)))
            (fill p 0)
            (is (= 42 (ffi/read (:u (ffi/read p tagged)) :int)))
            (fill p 1)
            (is (= 2.5 (ffi/read (:u (ffi/read p tagged)) :double)))
            (fill p 2)
            (is (= "union" (ffi/ptr->string (ffi/read (:u (ffi/read p tagged)) :pointer))))
            ;; and the other way: C reads what the layout wrote
            (ffi/write p tagged {:tag 1 :u [:d 6.5]})
            (is (= 6.5 (value p)))
            (ffi/write p tagged {:tag 0 :u [:i 9]})
            (is (= 9.0 (value p)))))))))

;; -- the public API is the documented one --------------------------------------

(deftest public-api-is-documented-test
  ;; babashka exposes every public var of this namespace, so a var that is
  ;; public by accident becomes API. API.md is generated from the public vars
  ;; and reviewed in every change, so it serves as the list of intent: a new
  ;; public var fails here until `bb quickdoc` is run on purpose. JVM only:
  ;; in babashka the built-in namespace can be older than this checkout.
  (when-not (System/getProperty "babashka.version")
  (let [doc (slurp "API.md")
        documented (set (map second (re-seq #"<a name=\"babashka.ffi/([^\"]+)\"" doc)))
        public (set (map name (keys (ns-publics 'babashka.ffi))))]
    (is (empty? (sort (remove documented public)))
        "public vars missing from API.md: run bb quickdoc, and check they are meant to be public")
    (is (empty? (sort (remove public documented)))
        "API.md documents vars that are no longer public: run bb quickdoc"))))

(deftest nested-value-error-path-test
  ;; a wrong value deep in a layout names its place, so the reader of the
  ;; message does not have to search the structure for it
  (if-not @union-layout?
    (println "nested error path skipped: this babashka predates union layouts")
    (let [data [:union [[:whatever :pointer] [:result :int]]]
          curl-msg [:struct [[:msg :int] [:easy :pointer] [:data data]]]
          outer [:struct [[:id :int] [:msgs [:array curl-msg 2]]]]
          ok {:msg 1 :easy nil :data [:result 0]}]
      (with-open [arena (ffi/confined-arena)]
        (let [p (ffi/alloc arena outer)]
          (is (thrown-with-msg? Exception #"at \[:msgs 0 :data\], union value is a pair"
                                (ffi/write p outer {:id 1 :msgs [(assoc ok :data [:foo 1 :baz 2]) ok]})))
          (is (thrown-with-msg? Exception #"at \[:msgs 1 :data\], union value names unknown member :foo"
                                (ffi/write p outer {:id 1 :msgs [ok (assoc ok :data [:foo 1])]})))
          (is (thrown-with-msg? Exception #"at \[:msgs 1\], struct value misses field :easy"
                                (ffi/write p outer {:id 1 :msgs [ok (dissoc ok :easy)]})))
          (is (thrown-with-msg? Exception #"at \[:msgs\], array value needs 2 elements"
                                (ffi/write p outer {:id 1 :msgs [ok]})))
          ;; a :string field with a bare string, and a scalar the type cannot take
          (let [item [:struct [[:id :int] [:name :string] [:q :pointer]]]
                bag [:struct [[:items [:array item 2]]]]
                fine {:id 1 :name (ffi/string->ptr arena "x") :q nil}
                b (ffi/alloc arena bag)]
            (is (thrown-with-msg? Exception #"at \[:items 0 :name\], a :string field holds a pointer"
                                  (ffi/write b bag {:items [(assoc fine :name "bare") fine]})))
            (is (thrown-with-msg? Exception #"at \[:items 1 :id\], a :int field cannot take \"two\""
                                  (ffi/write b bag {:items [fine (assoc fine :id "two")]})))
            (is (thrown-with-msg? Exception #"at \[:items 1 :q\], a :pointer field cannot take 42"
                                  (ffi/write b bag {:items [fine (assoc fine :q 42)]})))
            ;; the original exception stays as the cause
            (is (instance? ClassCastException
                           (ex-cause (try (ffi/write b bag {:items [fine (assoc fine :id "two")]})
                                          (catch Exception e e))))))
          ;; the top level has no place to name
          (is (thrown-with-msg? Exception #"^babashka.ffi: union value"
                                (ffi/write p data [:foo 1]))))))))

;; -- a place: one member by name or path ---------------------------------------
;; place arrived after the first release. The var is looked up at run time so
;; the namespace loads on an older babashka and skips.

(def place?
  (delay (boolean (resolve 'babashka.ffi/place))))

(deftest place-test
  (if-not @place?
    (println "place skipped: this babashka predates it")
    (let [place (resolve 'babashka.ffi/place)
          data [:union [[:whatever :pointer] [:result :int]]]
          curl-msg [:struct [[:msg :int] [:easy :pointer] [:data data]]]
          outer [:struct [[:id :int] [:msgs [:array curl-msg 2]]]]]
      (with-open [arena (ffi/confined-arena)]
        (let [p (ffi/alloc arena bone)
              q (ffi/alloc arena outer)
              pts (ffi/alloc arena 32)
              parent (place bone :parent)]
          (ffi/write p bone {:name spine :parent 7})
          (testing "read and write take a place where they take a type"
            (is (= 7 (ffi/read p parent)))
            (is (= (ffi/read p :int 32) (ffi/read p parent)))
            (ffi/write p parent 3)
            (is (= 3 (ffi/read p :int 32)))
            (is (= spine (ffi/read p (place bone :name)))))
          (testing "without a path the place is the whole layout"
            (is (= {:name spine :parent 3} (ffi/read p (place bone))))
            (is (= (ffi/read p bone) (ffi/read p (place bone))))
            (ffi/write p (place bone) {:name spine :parent 11})
            (is (= 11 (ffi/read p parent))))
          (testing "a path through an array, a struct and a union"
            (let [result (place outer [:msgs 1 :data :result])]
              (ffi/write q outer {:id 1 :msgs [{:msg 1 :easy nil :data [:result 5]}
                                               {:msg 2 :easy nil :data [:result 6]}]})
              (is (= 6 (ffi/read q result)))
              (ffi/write q result 9)
              (is (= 9 (ffi/read q result)))
              ;; the path names the union member, so it is the tag: same bytes as the pair route
              (ffi/write q outer {:id 1 :msgs [{:msg 1 :easy nil :data [:result 5]}
                                               {:msg 2 :easy nil :data [:result 9]}]})
              (is (= 9 (ffi/read q result))))
            (is (= 8 (ffi/size (ffi/read q (place outer [:msgs 0 :data])))))
            (ffi/write q (place outer [:msgs 0]) {:msg 7 :easy nil :data [:result 1]})
            (is (= 7 (ffi/read q (place outer [:msgs 0 :msg]))))
            (ffi/write p (place bone [:name 0]) 65)
            (is (= 65 (ffi/read p (place bone [:name 0])))))
          (testing "the byte offset still composes: striding an array of structs"
            (dotimes [i 4] (ffi/write pts point {:x i :y (* 10 i)} (* i 8)))
            (is (= [0 10 20 30] (mapv #(ffi/read pts (place point :y) (* % 8)) (range 4)))))
          (testing "a path that names nothing is an error when the place is made, not nil"
            (is (thrown-with-msg? Exception #"no member :z; the members are \[:name :parent\]"
                                  (place bone :z)))
            (is (thrown-with-msg? Exception #"no member :z at \[:msgs 1\]"
                                  (place outer [:msgs 1 :z])))
            (is (thrown-with-msg? Exception #"2 is not an index into 2 elements at \[:msgs\]"
                                  (place outer [:msgs 2 :msg])))
            (is (thrown-with-msg? Exception #"continues past :int at \[:id\]"
                                  (place outer [:id :x]))))
          (testing "a wrong value at the place says where"
            (is (thrown-with-msg? Exception #"at \[:msgs 1 :data\], union value is a pair"
                                  (ffi/write q (place outer [:msgs 1 :data]) {:result 1})))
            (is (thrown-with-msg? Exception #"at \[:msgs 1 :msg\], a :int field cannot take"
                                  (ffi/write q (place outer [:msgs 1 :msg]) "x")))))))))


;; -- a declared variadic tail ------------------------------------------------

(def declared-tail?
  "Types after :& arrived after the first release; an older built-in
  namespace refuses them. The probe also needs snprintf through the default
  lookup, which a musl build and Windows do not give."
  (delay (try (with-open [arena (ffi/confined-arena)]
                (let [f (ffi/cfn "snprintf" [:pointer :size_t :string :& :int] :int)
                      buf (ffi/alloc arena 16)]
                  (= 2 (f buf 16 "%d" 42))))
              (catch Throwable _ false))))

(deftest declared-variadic-tail-test
  (if-not @declared-tail?
    (println "declared variadic tail skipped: this babashka predates it, or snprintf is not reachable")
    (with-open [arena (ffi/confined-arena)]
      (let [buf (ffi/alloc arena 64)
            declared (ffi/cfn "snprintf" [:pointer :size_t :string :& :int :string] :int)
            inferred (ffi/cfn "snprintf" [:pointer :size_t :string :&] :int)]
        (testing "a declared tail calls with the variadic convention and an exact arity"
          (is (= 12 (declared buf 64 "%d and %s" 42 "hello")))
          (is (= "42 and hello" (ffi/ptr->string buf 64)))
          (is (= (inferred buf 64 "%d and %s" 42 "hello") (declared buf 64 "%d and %s" 42 "hello")))
          (is (thrown-with-msg? Exception #"expects 5 args, got 4" (declared buf 64 "%d" 42))))
        (testing "a double in the tail"
          ((ffi/cfn "snprintf" [:pointer :size_t :string :& :double] :int) buf 64 "%.2f" 2.5)
          ;; the decimal separator follows the process locale
          (is (re-matches #"2[.,]50" (ffi/ptr->string buf 64))))
        (testing "a type C would promote is refused with the type to declare"
          (is (thrown-with-msg? Exception #"promotes it to :double; declare :double"
                                (ffi/cfn "snprintf" [:pointer :size_t :string :& :float] :int)))
          (is (thrown-with-msg? Exception #"promotes it to :int; declare :int"
                                (ffi/cfn "snprintf" [:pointer :size_t :string :& :int8] :int)))
          (is (thrown-with-msg? Exception #"cannot be a variadic tail type"
                                (ffi/cfn "snprintf" [:pointer :size_t :string :& [:struct [[:x :int]]]] :int))))
        (testing "the marker rules"
          (is (thrown-with-msg? Exception #"appears twice" (ffi/cfn "snprintf" [:string :& :int :&] :int)))
          (is (thrown-with-msg? Exception #"at least one fixed" (ffi/cfn "snprintf" [:& :int] :int))))))))
