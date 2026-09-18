(ns babashka.ffi.native-test
  "The checks a native image is the only place to make: the trampolines, the
  upcall shapes it registered, and the errors it gives for what it cannot
  call. Built and run by script/native_test.clj.

  Plain assertions and no test framework, so the image carries this
  namespace, babashka.ffi and nothing else. The suite in test/ covers what
  both hosts share; this covers what only an image decides."
  (:require [babashka.ffi :as ffi])
  (:gen-class))

(def ^:private failures (atom 0))

(def ^:private apple-aarch64?
  (and (= "aarch64" (System/getProperty "os.arch"))
       (.startsWith (System/getProperty "os.name") "Mac")))

(defn- check [what expected actual]
  (if (= expected actual)
    (println "  ok  " what)
    (do (swap! failures inc)
        (println "  FAIL" what)
        (println "        expected:" (pr-str expected))
        (println "        actual:  " (pr-str actual)))))

(defn- check-throws [what re f]
  (let [msg (try (f) nil (catch Throwable e (ex-message e)))]
    (if (and msg (re-find re msg))
      (println "  ok  " what)
      (do (swap! failures inc)
          (println "  FAIL" what)
          (println "        expected a message matching" (pr-str (str re)))
          (println "        actual:  " (pr-str msg))))))

(defn -main [& args]
  (let [lib (or (first args) "target/libffistructs.dylib")]
    (ffi/load-library lib)

    (println "the image runs on the trampolines")
    (let [abs (ffi/cfn "abs" [:int] :int)
          strlen (ffi/cfn "strlen" [:string] :size_t)]
      (check "a scalar call goes through a trampoline"
             :trampoline (:babashka.ffi/backend (meta abs)))
      (check "abs" 5 (abs -5))
      (check "strlen" 5 (strlen "hello")))

    (println "arguments up to and past the argument registers")
    (let [eight (ffi/cfn "eight_int_sum" (vec (repeat 8 :int)) :int)]
      (check "eight int arguments take a trampoline"
             :trampoline (:babashka.ffi/backend (meta eight)))
      (check "and all of them arrive" 36 (apply eight (range 1 9))))
    (let [ten (ffi/cfn "ten_long_sum" (vec (repeat 10 :long)) :long)]
      (check "ten long arguments take a trampoline on every ABI"
             :trampoline (:babashka.ffi/backend (meta ten)))
      (check "and all of them arrive" 55 (apply ten (range 1 11))))
    ;; macOS on AArch64 packs a stack slot to the argument, so narrow
    ;; arguments past the registers go to libffi, which this build lacks
    (if apple-aarch64?
      (check-throws "ten int arguments are declined here, and say so"
                    #"libffi" #(ffi/cfn "ten_int_sum" (vec (repeat 10 :int)) :int))
      (let [ten (ffi/cfn "ten_int_sum" (vec (repeat 10 :int)) :int)]
        (check "ten int arguments take a trampoline here"
               :trampoline (:babashka.ffi/backend (meta ten)))
        (check "and all of them arrive" 55 (apply ten (range 1 11)))))

    (println "a double before an integer, which is a shape of its own on Windows")
    (let [f (ffi/cfn "double_then_long" [:double :long] :double)]
      (check "takes a trampoline" :trampoline (:babashka.ffi/backend (meta f)))
      (check "and both arguments arrive" 3.5 (f 1.5 2)))
    (let [cb (ffi/callback (ffi/global-arena) (fn [d l] (+ d l)) [:double :long] :double)]
      (check "and a callback of that shape is registered"
             3.5 ((ffi/cfn "call_double_then_long" [:pointer] :double) cb)))

    (println "a callback, which an image serves from the shapes it registered")
    (let [arena (ffi/global-arena)]
      (let [cb (ffi/callback arena (fn [a b] (+ a b)) [:int :int] :long)]
        (check "a negative narrow integer keeps its sign"
               -3 ((ffi/cfn "call_with_negatives" [:pointer] :long) cb)))
      (let [seen (atom nil)
            cb (ffi/callback arena (fn [a b c] (reset! seen [a b c]) (+ a b c))
                             [:int8 :int16 :int] :long)]
        (check "so do the narrower ones"
               -12 ((ffi/cfn "call_with_narrow" [:pointer] :long) cb))
        (check "and the callback saw them signed" [-3 -4 -5] @seen))
      (let [xs [5 3 9 1 7 2]
            cmp (ffi/callback arena
                              (fn [a b] (- (ffi/read (ffi/reinterpret a 4) :int)
                                           (ffi/read (ffi/reinterpret b 4) :int)))
                              [:pointer :pointer] :int)
            qsort (ffi/cfn "qsort" [:pointer :size_t :size_t :pointer] :void)]
        (ffi/with-open [a (ffi/confined-arena)]
          (let [p (ffi/alloc a (* 4 (count xs)))]
            (dotimes [i (count xs)] (ffi/write p :int (nth xs i) (* 4 i)))
            (qsort p (count xs) 4 cmp)
            (check "qsort calls back into the image"
                   [1 2 3 5 7 9] (mapv #(ffi/read p :int (* 4 %)) (range (count xs))))))))

    (println "memory, which needs no linker at all")
    (ffi/with-open [arena (ffi/confined-arena)]
      (let [point [:struct [[:x :int] [:y :int]]]
            p (ffi/alloc arena point)]
        (ffi/write p point {:x 3 :y 4})
        (check "a struct reads back through memory" {:x 3 :y 4} (ffi/read p point))
        (check "a string round trips"
               "hello" (ffi/ptr->string (ffi/string->ptr arena "hello")))))

    (println "what a build without libffi refuses, rather than calling wrongly")
    (check-throws "a struct by value" #"libffi"
                  #(let [f (ffi/cfn "p2_sum" [[:struct [[:x :int] [:y :int]]]] :int)]
                     (f {:x 1 :y 2})))
    (check-throws "a variadic signature" #"libffi"
                  #(ffi/cfn "snprintf" [:pointer :size_t :string :&] :int))

    (println)
    (if (zero? @failures)
      (println "native image: all checks passed")
      (do (println "native image:" @failures "failed") (System/exit 1)))))
