(ns babashka.ffi-test
  "The Node.js host of babashka.ffi, run with nbb on Node.js 26.1 or newer:
  `bb test:node`. The cases follow test/babashka/ffi_test.clj, without the
  struct-by-value, variadic and function-pointer cases that node:ffi does
  not support."
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [cljs.test :as t :refer [deftest is testing]]))

(defcfn strlen "strlen" [:string] :long)

(def windows? (= "win32" js/process.platform))

(def point [:struct [[:x :int] [:y :int]]])

(deftest call-test
  (testing "a C call through the default lookup"
    (is (= 5 (strlen "hello")))
    (is (= :node (:babashka.ffi/backend (meta strlen)))))
  (testing "integer arguments coerce negative zero and fractions"
    (let [abs (ffi/cfn "abs" [:int] :int)]
      (is (= [0 5 2] [(abs (- 0)) (abs -5) (abs -2.7)]))))
  (testing "a 64-bit value is a number when it is a safe integer, else a bigint"
    (let [llabs (ffi/cfn "llabs" [:int64] :int64)]
      (is (= 5 (llabs -5)))
      (is (= 5 (llabs (js/BigInt -5))))
      (is (= (js/BigInt "9007199254740993") (llabs (js/BigInt "-9007199254740993"))))))
  (testing "a :bool return is the low byte, the rest of the register is not part of a C bool"
    (is (= [false false true true]
           (mapv (ffi/cfn "toupper" [:int] :bool) [0 1024 1025 1]))))
  (testing "a predicate that returns an int is declared :int"
    (is (= [true false]
           (mapv (comp not zero? (ffi/cfn "isalpha" [:int] :int)) [97 49]))))
  (testing ":double, :float and :string returns"
    (is (= 3 ((ffi/cfn "sqrt" [:double] :double) 9)))
    (is (= 3 ((ffi/cfn "sqrtf" [:float] :float) 9)))
    (is (string? ((ffi/cfn "getenv" [:string] :string) "PATH")))
    (is (nil? ((ffi/cfn "getenv" [:string] :string) "BABASHKA_FFI_NOT_SET"))))
  (testing "the arity is checked, and the message names the symbol"
    #_{:clj-kondo/ignore [:invalid-arity]}
    (is (thrown-with-msg? js/Error #"strlen expects 1 args, got more than 1" (strlen "a" "b")))
    #_{:clj-kondo/ignore [:invalid-arity]}
    (is (thrown-with-msg? js/Error #"strlen expects 1 args, got 0" (strlen)))
    (let [memcmp (ffi/cfn "memcmp" [:pointer :pointer :size_t :int :int] :int)]
      (is (thrown-with-msg? js/Error #"memcmp expects 5 args, got 2" (memcmp nil nil)))))
  (testing "an unknown symbol is an error on the first call"
    (let [f (ffi/cfn "babashka_ffi_no_such_symbol" [] :int)]
      (is (thrown-with-msg? js/Error #"symbol not found" (f)))))
  (testing "find-symbol"
    (is (ffi/pointer? (ffi/find-symbol "strlen")))
    (is (nil? (ffi/find-symbol "babashka_ffi_no_such_symbol")))))

(deftest unsupported-test
  (testing "unsupported signatures throw when the binding is created"
    (is (thrown-with-msg? js/Error #"does not pass a struct by value"
                          (ffi/cfn "div" [:int :int] [:struct [[:quot :int] [:rem :int]]])))
    (is (thrown-with-msg? js/Error #"does not make variadic calls"
                          (ffi/cfn "printf" [:string :&] :int)))
    (is (thrown-with-msg? js/Error #"cannot call a function pointer"
                          (ffi/cfn (ffi/find-symbol "strlen") [:string] :long)))))

(deftest memory-test
  (testing "an arena allocation roundtrip"
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena :int64)]
        (ffi/write p :int64 42)
        (is (= 42 (ffi/read p :int64))))))
  (testing "the byte offset is the last argument"
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena 16)]
        (ffi/write p :int 7 4)
        (is (= 0 (ffi/read p :int)))
        (is (= 7 (ffi/read p :int 4))))))
  (testing "every scalar type round trips, with C's wrapping"
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena 8)
            rt (fn [t v] (ffi/write p t v) (ffi/read p t))]
        (is (= [-1 0xFFFFFFFF] [(rt :int 0xFFFFFFFF) (rt :uint -1)]))
        (is (= [-1 0xFFFF] [(rt :int16 0xFFFF) (rt :uint16 -1)]))
        (is (= [-1 0xFF 44] [(rt :int8 0xFF) (rt :uint8 -1) (rt :char 300)]))
        (is (= [true false] [(rt :bool 1) (rt :bool nil)]))
        (is (= [1.5 1.5] [(rt :double 1.5) (rt :float 1.5)]))
        (is (= -1 (rt :long -1)))
        (is (= (js/BigInt "18446744073709551615") (rt :ulong -1)))
        (is (= (ffi/address p) (ffi/address (rt :pointer p))))
        (is (ffi/null? (rt :pointer nil))))))
  (testing "an access is checked against the size"
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena 4)]
        (is (thrown-with-msg? js/Error #"out of bounds" (ffi/read p :int 1)))
        (is (thrown-with-msg? js/Error #"out of bounds" (ffi/write p :long 1)))
        (is (thrown-with-msg? js/Error #"reinterpret" (ffi/read (ffi/segment (ffi/address p)) :int))))))
  (testing "a closed arena invalidates its pointers"
    (let [arena (ffi/confined-arena)
          p (ffi/alloc arena :int)]
      (.close arena)
      (is (not (ffi/pointer? p)))
      (is (thrown-with-msg? js/Error #"closed arena" (ffi/read p :int)))
      (is (thrown-with-msg? js/Error #"arena is closed" (ffi/alloc arena :int)))
      (is (thrown-with-msg? js/Error #"already closed" (.close arena)))))
  (testing "the automatic and global arenas do not close"
    (is (thrown-with-msg? js/Error #"cannot close" (.close (ffi/auto-arena))))
    (is (thrown-with-msg? js/Error #"cannot close" (.close (ffi/global-arena)))))
  (testing "an alignment is honored"
    (ffi/with-open [arena (ffi/confined-arena)]
      (is (every? (fn [_] (zero? (mod (ffi/address (ffi/alloc arena 3 64)) 64))) (range 8)))))
  (testing "reinterpret with an arena calls the cleanup at close"
    (let [arena (ffi/confined-arena)
          freed (atom nil)]
      (ffi/with-open [owner (ffi/confined-arena)]
        (let [view (ffi/reinterpret (ffi/alloc owner 8) 4 arena #(reset! freed (ffi/size %)))]
          (is (= 4 (ffi/size view)))
          (.close arena)
          (is (= 4 @freed))
          (is (thrown-with-msg? js/Error #"closed arena" (ffi/read view :int)))))))
  (testing "the arena is required"
    (is (thrown-with-msg? js/Error #"takes an arena first" (ffi/alloc 8 8)))))

(deftest layout-test
  (testing "sizeof and alignof resolve a layout"
    (is (= 8 (ffi/sizeof point)))
    (is (= 4 (ffi/alignof point)))
    (is (= 16 (ffi/sizeof [:struct [[:c :char] [:d :double]]]))))
  (testing "alloc and slice take a layout where they take a size"
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [arr (ffi/alloc arena [:struct [[:lo point] [:hi point]]])]
        (is (= 16 (ffi/size arr)))
        (is (= 8 (ffi/size (ffi/slice arr 8 point))))
        (is (= 12 (ffi/size (ffi/slice arr 4))))
        (is (thrown-with-msg? js/Error #"out of bounds" (ffi/slice arr 12 point))))))
  (testing "a string allocated in an arena reads back"
    (ffi/with-open [arena (ffi/confined-arena)]
      (is (= "hello" (ffi/ptr->string (ffi/string->ptr arena "hello"))))
      (let [s (str "h" (js/String.fromCharCode 0xe9) "llo " (js/String.fromCharCode 0x2713))]
        (is (= s (ffi/ptr->string (ffi/string->ptr arena s))))))))

(deftest struct-access-test
  (let [rect [:struct [[:lo point] [:hi point]]]]
    (testing "a struct reads back as a map and writes from one"
      (ffi/with-open [arena (ffi/confined-arena)]
        (let [p (ffi/alloc arena point)]
          (ffi/write p point {:x 3 :y 4})
          (is (= {:x 3 :y 4} (ffi/read p point))))))
    (testing "nested layouts round trip"
      (ffi/with-open [arena (ffi/confined-arena)]
        (let [r (ffi/alloc arena rect)
              v {:lo {:x 1 :y 2} :hi {:x 3 :y 4}}]
          (ffi/write r rect v)
          (is (= v (ffi/read r rect))))))
    (testing "an offset addresses one element of an array of structs"
      (ffi/with-open [arena (ffi/confined-arena)]
        (let [arr (ffi/alloc arena 24)]
          (dotimes [i 3]
            (ffi/write arr point {:x i :y (* 2 i)} (* i 8)))
          (is (= [{:x 0 :y 0} {:x 1 :y 2} {:x 2 :y 4}]
                 (mapv #(ffi/read arr point (* % 8)) (range 3)))))))
    (testing "a :string field takes a pointer the caller owns"
      (ffi/with-open [arena (ffi/confined-arena)]
        (let [named [:struct [[:id :int] [:name :string]]]
              p (ffi/alloc arena named)]
          (ffi/write p named {:id 7 :name (ffi/string->ptr arena "seven")})
          (is (= {:id 7 :name "seven"} (ffi/read p named))))))
    (testing "a string field rejects a string value and suggests string->ptr"
      (ffi/with-open [arena (ffi/confined-arena)]
        (is (thrown-with-msg?
             js/Error #"string->ptr arena"
             (ffi/write (ffi/alloc arena 16) [:struct [[:s :string]]] {:s "x"})))))
    (testing "an invalid struct value reports the field"
      (ffi/with-open [arena (ffi/confined-arena)]
        (is (thrown-with-msg?
             js/Error #"misses field :y"
             (ffi/write (ffi/alloc arena point) point {:x 1})))))
    (testing "C fills a struct through a pointer, and the layout reads it"
      (if windows?
        (println "gettimeofday skipped: not in the Windows C runtime")
        (ffi/with-open [arena (ffi/confined-arena)]
          (let [tv [:struct [[:sec :long] [:usec :long]]]
                p (ffi/alloc arena tv)]
            (is (zero? ((ffi/cfn "gettimeofday" [:pointer :pointer] :int) p nil)))
            (is (< 1700000000 (:sec (ffi/read p tv))))))))))

(defn- sizeless [arena s]
  (ffi/segment (ffi/address (ffi/string->ptr arena s))))

(deftest ptr->string-test
  (ffi/with-open [arena (ffi/confined-arena)]
    (testing "a pointer with no size reads to the NUL"
      (let [p (sizeless arena "hello")]
        (is (zero? (ffi/size p)))
        (is (= "hello" (ffi/ptr->string p)))))
    (testing "a limit stops the read"
      (let [p (sizeless arena "hello")]
        (is (= "hello" (ffi/ptr->string p 64)))
        (is (= "hello" (ffi/ptr->string p 6)))))
    (testing "a string without NUL within the limit throws"
      (is (thrown-with-msg? js/Error #"no NUL byte in the first 3 bytes"
                            (ffi/ptr->string (sizeless arena "hello") 3))))
    (testing "a limit narrows but never widens an existing bound"
      (let [p (ffi/alloc arena 8)]
        (dotimes [i 8] (ffi/write p :int8 65 i))
        (is (thrown-with-msg? js/Error #"no NUL byte in the first 8 bytes"
                              (ffi/ptr->string p 64)))
        (is (thrown-with-msg? js/Error #"no NUL byte in the first 8 bytes"
                              (ffi/ptr->string p)))))
    (testing "NULL is nil, with and without a limit"
      (is (nil? (ffi/ptr->string ffi/null)))
      (is (nil? (ffi/ptr->string ffi/null 8))))))

(defn- sort-ints [make-callback xs]
  (let [qsort (ffi/cfn "qsort" [:pointer :size_t :size_t :pointer] :void)
        n (count xs)]
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena (* 4 n))]
        (dotimes [i n] (ffi/write p :int (nth xs i) (* 4 i)))
        (qsort p n 4 (make-callback))
        (mapv #(ffi/read p :int (* 4 %)) (range n))))))

(defn- compare-ints [a b]
  (- (ffi/read (ffi/reinterpret a 4) :int)
     (ffi/read (ffi/reinterpret b 4) :int)))

(deftest callback-test
  (let [xs [5 3 9 1 7 2]
        sorted [1 2 3 5 7 9]]
    (testing "every arena kind owns a callback"
      (ffi/with-open [a (ffi/confined-arena)]
        (is (= sorted (sort-ints #(ffi/callback a compare-ints [:pointer :pointer] :int) xs))))
      (ffi/with-open [a (ffi/shared-arena)]
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
        (is (thrown? js/Error (sort-ints (constantly cb) xs)))))
    (testing "a callback receives the declared types and returns through the coercion"
      (ffi/with-open [a (ffi/confined-arena)]
        (let [seen (atom nil)
              cb (ffi/callback a (fn [k e] (reset! seen [(ffi/pointer? k) (ffi/size e)]) 0.4)
                               [:pointer :pointer] :int)
              bsearch (ffi/cfn "bsearch" [:pointer :pointer :size_t :size_t :pointer] :pointer)
              arr (ffi/alloc a 8)
              hit (bsearch arr arr 2 4 cb)]
          (is (= [true 0] @seen))
          (is (ffi/pointer? hit))
          (is (not (ffi/null? hit))))))
    (testing ":string is not a callback type"
      (is (thrown-with-msg? js/Error #"not :string"
                            (ffi/callback (ffi/global-arena) identity [:string] :void))))))

(def p2 [:struct [[:x :int] [:y :int]]])
(def bone [:struct [[:name [:array :char 32]] [:parent :int]]])
(def spine
  (vec (concat (map #(.charCodeAt % 0) "spine") (repeat 27 0))))

(deftest array-layout-test
  (testing "an array is its elements back to back"
    (is (= 16 (ffi/sizeof [:array :int 4])))
    (is (= 4 (ffi/alignof [:array :int 4])))
    (is (= 36 (ffi/sizeof bone)))
    (is (= 32 (ffi/sizeof [:array [:array :double 2] 2])))
    (is (= 16 (ffi/sizeof [:array p2 2]))))
  (testing "an array reads as a vector and writes from any sequence"
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena [:array :int 4])]
        (ffi/write p [:array :int 4] [1 2 3 4])
        (is (= [1 2 3 4] (ffi/read p [:array :int 4])))
        (is (= 3 (ffi/read p :int 8)))
        (ffi/write p [:array :int 4] (js/Int32Array. #js [5 6 7 8]))
        (is (= [5 6 7 8] (ffi/read p [:array :int 4])))
        (ffi/write p [:array :int 4] (list 9 9 9 9))
        (is (= [9 9 9 9] (ffi/read p [:array :int 4]))))))
  (testing "a char array in a struct, and the fixed-width string in it"
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena bone)]
        (ffi/write p bone {:name spine :parent 7})
        (is (= {:name spine :parent 7} (ffi/read p bone)))
        (is (= "spine" (ffi/ptr->string (ffi/slice p 0 32) 32))))))
  (testing "arrays nest, and hold structs and pointers"
    (ffi/with-open [arena (ffi/confined-arena)]
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
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena [:array :int 4])]
        (is (thrown-with-msg? js/Error #"needs 4 elements, got 3"
                              (ffi/write p [:array :int 4] [1 2 3])))
        (is (thrown-with-msg? js/Error #"needs 4 elements, got 5"
                              (ffi/write p [:array :int 4] [1 2 3 4 5])))
        (is (thrown-with-msg? js/Error #"needs 4 elements, got 42"
                              (ffi/write p [:array :int 4] 42))))))
  (testing "a malformed array layout is an error at resolve time"
    (is (thrown-with-msg? js/Error #"positive element count" (ffi/sizeof [:array :int 0])))
    (is (thrown-with-msg? js/Error #"is \[:array elem n\]" (ffi/sizeof [:array :int])))
    (is (thrown-with-msg? js/Error #":void is not an element" (ffi/sizeof [:array :void 2]))))
  (testing "C passes an array as a pointer, so a bare array is not a signature type"
    (is (thrown-with-msg? js/Error #"C passes an array as a pointer"
                          (ffi/cfn "abs" [[:array :int 4]] :int)))
    (is (thrown-with-msg? js/Error #"C passes an array as a pointer"
                          (ffi/cfn "abs" [:int] [:array :int 4])))))

(deftest bulk-array-test
  (ffi/with-open [arena (ffi/confined-arena)]
    (let [p (ffi/alloc arena 64)]
      (testing "a copy in and out of a typed array, at an offset"
        (ffi/write-array p :int (js/Int32Array. #js [1 2 3 4]))
        (is (= [1 2 3 4] (vec (ffi/read-array p :int 4))))
        (is (instance? js/Int32Array (ffi/read-array p :int 4)))
        (is (= [3 4] (vec (ffi/read-array p :int 2 8))))
        (ffi/write-array p :double (js/Float64Array. #js [1.5 2.5]) 16)
        (is (= [1.5 2.5] (vec (ffi/read-array p :double 2 16)))))
      (testing "the copy agrees with the layout read"
        (is (= (ffi/read p [:array :int 4]) (vec (ffi/read-array p :int 4)))))
      (testing "a copy is a memcpy: the type gives the width, not the sign"
        (ffi/write p :uint 0xFFFFFFFF)
        (is (= -1 (first (ffi/read-array p :uint 1))))
        (is (= 0xFFFFFFFF (ffi/read p :uint))))
      (testing ":pointer copies addresses"
        (let [s (ffi/string->ptr arena "x")]
          (ffi/write p [:array :pointer 1] [s])
          (is (= [(js/BigInt (ffi/address s))] (vec (ffi/read-array p :pointer 1))))))
      (testing "unsupported array types report alternatives"
        (is (thrown-with-msg? js/Error #"use read and write with \[:array"
                              (ffi/read-array p [:struct [[:x :int]]] 2)))
        (is (thrown-with-msg? js/Error #"pointers to bytes elsewhere"
                              (ffi/read-array p :string 2)))
        (is (thrown-with-msg? js/Error #":int needs Int32Array, got BigInt64Array"
                              (ffi/write-array p :int (js/BigInt64Array. 2)))))
      (testing "a copy past the end throws instead of reading on"
        (is (thrown? js/Error (ffi/read-array p :int 17)))
        (is (thrown? js/Error (ffi/write-array p :long (js/BigInt64Array. 9)))))
      (testing "byte-buffer shares the bytes"
        (let [b (ffi/byte-buffer p 4)]
          (ffi/write p :int 1)
          (is (= 1 (.readInt32LE b 0)))
          (.writeInt32LE b 9 0)
          (is (= 9 (ffi/read p :int))))))))

(deftest segment-copy-test
  (ffi/with-open [arena (ffi/confined-arena)]
    (let [src (ffi/alloc arena 16)
          dst (ffi/alloc arena 32)]
      (ffi/write-array src :int (js/Int32Array. #js [1 2 3 4]))
      (testing "a copy of the whole source, and of n bytes into a slice"
        (ffi/copy src dst)
        (is (= [1 2 3 4] (vec (ffi/read-array dst :int 4))))
        (ffi/copy src (ffi/slice dst 16) 8)
        (is (= [1 2 3 4 1 2 0 0] (vec (ffi/read-array dst :int 8)))))
      (testing "clone allocates a copy of the same size in the arena"
        (let [c (ffi/clone arena src)]
          (is (= 16 (ffi/size c)))
          (is (= [1 2 3 4] (vec (ffi/read-array c :int 4))))
          (is (not= (ffi/address c) (ffi/address src)))))
      (testing "a copy that does not fit throws instead of writing past the end"
        (is (thrown? js/Error (ffi/copy dst src)))
        (is (thrown? js/Error (ffi/copy src dst 17))))
      (testing "a pointer without a size says to reinterpret it"
        (is (thrown-with-msg? js/Error #"reinterpret"
                              (ffi/copy (ffi/segment (ffi/address src)) dst))))
      (testing "overlapping regions copy as memmove"
        (ffi/write-array dst :int (js/Int32Array. #js [1 2 3 4 5 6 7 8]))
        (ffi/copy dst (ffi/slice dst 4) 16)
        (is (= [1 1 2 3 4 6 7 8] (vec (ffi/read-array dst :int 8))))))))

(deftest union-layout-test
  (let [data [:union [[:whatever :pointer] [:result :int]]]
        curl-msg [:struct [[:msg :int] [:easy :pointer] [:data data]]]]
    (testing "a union is as large as its largest member, at its strictest alignment"
      (is (= 24 (ffi/sizeof curl-msg)))
      (is (= [8 8] [(ffi/sizeof [:union [[:c :char] [:d :double]]])
                    (ffi/alignof [:union [[:c :char] [:d :double]]])]))
      (is (= [4 2] [(ffi/sizeof [:union [[:a [:array :char 3]] [:b :int16]]])
                    (ffi/alignof [:union [[:a [:array :char 3]] [:b :int16]]])])))
    (testing "read returns union bytes as a pointer"
      (ffi/with-open [arena (ffi/confined-arena)]
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
      (ffi/with-open [arena (ffi/confined-arena)]
        (let [p (ffi/alloc arena data)]
          (ffi/write p data [:result 9])
          (is (= 9 (ffi/read p :int)))
          (is (thrown-with-msg? js/Error #"is a pair \[member value\]" (ffi/write p data {:result 1})))
          (is (thrown-with-msg? js/Error #"unknown member :nope" (ffi/write p data [:nope 1])))
          (is (thrown-with-msg? js/Error #"is a pair" (ffi/write p data 5))))))
    (testing "a malformed union layout is an error at resolve time"
      (is (thrown-with-msg? js/Error #"is \[:union members\]" (ffi/sizeof [:union [[:a :int]] :x])))
      (is (thrown-with-msg? js/Error #"names a member twice" (ffi/sizeof [:union [[:a :int] [:a :int]]]))))))

(deftest nested-value-error-path-test
  (let [data [:union [[:whatever :pointer] [:result :int]]]
        curl-msg [:struct [[:msg :int] [:easy :pointer] [:data data]]]
        outer [:struct [[:id :int] [:msgs [:array curl-msg 2]]]]
        ok {:msg 1 :easy nil :data [:result 0]}]
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena outer)]
        (is (thrown-with-msg? js/Error #"at \[:msgs 0 :data\], union value is a pair"
                              (ffi/write p outer {:id 1 :msgs [(assoc ok :data [:foo 1 :baz 2]) ok]})))
        (is (thrown-with-msg? js/Error #"at \[:msgs 1 :data\], union value names unknown member :foo"
                              (ffi/write p outer {:id 1 :msgs [ok (assoc ok :data [:foo 1])]})))
        (is (thrown-with-msg? js/Error #"at \[:msgs 1\], struct value misses field :easy"
                              (ffi/write p outer {:id 1 :msgs [ok (dissoc ok :easy)]})))
        (is (thrown-with-msg? js/Error #"at \[:msgs\], array value needs 2 elements"
                              (ffi/write p outer {:id 1 :msgs [ok]})))
        (let [item [:struct [[:id :int] [:name :string] [:q :pointer]]]
              bag [:struct [[:items [:array item 2]]]]
              fine {:id 1 :name (ffi/string->ptr arena "x") :q nil}
              b (ffi/alloc arena bag)]
          (is (thrown-with-msg? js/Error #"at \[:items 0 :name\], a :string field holds a pointer"
                                (ffi/write b bag {:items [(assoc fine :name "bare") fine]})))
          (is (thrown-with-msg? js/Error #"at \[:items 1 :id\], a :int field cannot take \"two\""
                                (ffi/write b bag {:items [fine (assoc fine :id "two")]})))
          (is (thrown-with-msg? js/Error #"at \[:items 1 :q\], a :pointer field cannot take 42"
                                (ffi/write b bag {:items [fine (assoc fine :q 42)]}))))
        (is (thrown-with-msg? js/Error #"^babashka.ffi: union value"
                              (ffi/write p data [:foo 1])))))))

(deftest place-test
  (let [data [:union [[:whatever :pointer] [:result :int]]]
        curl-msg [:struct [[:msg :int] [:easy :pointer] [:data data]]]
        outer [:struct [[:id :int] [:msgs [:array curl-msg 2]]]]]
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [p (ffi/alloc arena bone)
            q (ffi/alloc arena outer)
            pts (ffi/alloc arena 32)
            parent (ffi/place bone :parent)]
        (ffi/write p bone {:name spine :parent 7})
        (testing "read and write take a place where they take a type"
          (is (= 7 (ffi/read p parent)))
          (is (= (ffi/read p :int 32) (ffi/read p parent)))
          (ffi/write p parent 3)
          (is (= 3 (ffi/read p :int 32)))
          (is (= spine (ffi/read p (ffi/place bone :name)))))
        (testing "without a path the place is the whole layout"
          (is (= {:name spine :parent 3} (ffi/read p (ffi/place bone))))
          (ffi/write p (ffi/place bone) {:name spine :parent 11})
          (is (= 11 (ffi/read p parent))))
        (testing "a path through an array, a struct and a union"
          (let [result (ffi/place outer [:msgs 1 :data :result])]
            (ffi/write q outer {:id 1 :msgs [{:msg 1 :easy nil :data [:result 5]}
                                             {:msg 2 :easy nil :data [:result 6]}]})
            (is (= 6 (ffi/read q result)))
            (ffi/write q result 9)
            (is (= 9 (ffi/read q result))))
          (is (= 8 (ffi/size (ffi/read q (ffi/place outer [:msgs 0 :data])))))
          (ffi/write q (ffi/place outer [:msgs 0]) {:msg 7 :easy nil :data [:result 1]})
          (is (= 7 (ffi/read q (ffi/place outer [:msgs 0 :msg]))))
          (ffi/write p (ffi/place bone [:name 0]) 65)
          (is (= 65 (ffi/read p (ffi/place bone [:name 0])))))
        (testing "the byte offset still composes: striding an array of structs"
          (dotimes [i 4] (ffi/write pts point {:x i :y (* 10 i)} (* i 8)))
          (is (= [0 10 20 30] (mapv #(ffi/read pts (ffi/place point :y) (* % 8)) (range 4)))))
        (testing "a place is checked against the size of the pointer"
          (is (thrown-with-msg? js/Error #"out of bounds"
                                (ffi/read (ffi/slice p 0 32) parent))))
        (testing "an invalid path throws when the place is created"
          (is (thrown-with-msg? js/Error #"no member :z; the members are \[:name :parent\]"
                                (ffi/place bone :z)))
          (is (thrown-with-msg? js/Error #"no member :z at \[:msgs 1\]"
                                (ffi/place outer [:msgs 1 :z])))
          (is (thrown-with-msg? js/Error #"2 is not an index into 2 elements at \[:msgs\]"
                                (ffi/place outer [:msgs 2 :msg])))
          (is (thrown-with-msg? js/Error #"continues past :int at \[:id\]"
                                (ffi/place outer [:id :x]))))
        (testing "an invalid value reports the place path"
          (is (thrown-with-msg? js/Error #"at \[:msgs 1 :data\], union value is a pair"
                                (ffi/write q (ffi/place outer [:msgs 1 :data]) {:result 1})))
          (is (thrown-with-msg? js/Error #"at \[:msgs 1 :msg\], a :int field cannot take"
                                (ffi/write q (ffi/place outer [:msgs 1 :msg]) "x"))))))))

(defcfn c-abs "The absolute value." "abs" [:int] :int)
(defcfn twice-abs "abs" [:int] :int
  raw [x] (* 2 (raw x)))

(deftest defcfn-test
  (testing "a docstring and the wrapper form"
    (is (= 5 (c-abs -5)))
    (is (= 10 (twice-abs -5))))
  (testing ":library limits the search to one library"
    (if windows?
      (println "zlib skipped: Windows does not ship it")
      (let [lib (ffi/load-system-library "z")]
        (is (string? (:path lib)))
        (is (string? ((ffi/cfn lib "zlibVersion" [] :string))))
        (is (string? ((ffi/cfn (delay lib) "zlibVersion" [] :string))))
        (is (thrown-with-msg? js/Error #":library must be"
                              ((ffi/cfn :nope "zlibVersion" [] :string))))))))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(defn -main [& _]
  (t/run-tests 'babashka.ffi-test))
