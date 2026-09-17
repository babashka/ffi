(ns babashka.ffi-binding-test
  (:require [babashka.ffi :as ffi]
            [babashka.ffi.impl.binding :as binding]
            [clojure.test :refer [deftest is testing]])
  (:import [java.lang.foreign MemorySegment]
           [java.lang.invoke MethodHandle MethodHandles MethodType]
           [java.lang.ref ReferenceQueue WeakReference]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def helpers
  {:arity-ex (fn [sym expects got]
               (ex-info (str "babashka.ffi: " sym " expects " expects " args, got " got)
                        {:symbol sym}))
   :binding-string (fn [sym argtypes rettype] (str sym " " (pr-str argtypes) " -> " rettype))})

(defn make-call [pd n void?]
  (binding/make-binding pd (object-array (repeat n (fn ^long [x] (long x)))) (fn [^long x] x)
                        {:probe true} "test_call" (vec (repeat n :long)) (if void? :void :long)
                        helpers))

(defn drop-args [^MethodHandle h position n]
  (MethodHandles/dropArguments h (int position) ^"[Ljava.lang.Class;" (into-array Class (repeat n Long/TYPE))))

(defn failure [call]
  (try (call) nil (catch Throwable t t)))

(deftest argument-order-and-return-width
  (let [args [Long/MIN_VALUE -4294967297 12345678901 0 -1 Long/MAX_VALUE]]
    (doseq [n (range 1 7)
            i (range n)]
      (let [h (-> (MethodHandles/identity Long/TYPE)
                  (drop-args 0 i)
                  (drop-args (inc i) (- n i 1)))
            f (make-call (delay h) n false)]
        (is (= (nth args i) (apply f (take n args)))))))
  (doseq [n (range 7)]
    (let [h (MethodHandles/empty (MethodType/methodType Void/TYPE ^"[Ljava.lang.Class;" (into-array Class (repeat n Long/TYPE))))]
      (is (nil? (apply (make-call (delay h) n true) (repeat n 1)))))))

(deftest lazy-concurrent-resolution-and-metadata
  (let [calls (atom 0)
        pd (delay (swap! calls inc) (MethodHandles/constant Long/TYPE 42))
        f (make-call pd 0 false)
        copy (with-meta f {:copy true})
        ready (CountDownLatch. 16)
        gate (CountDownLatch. 1)
        jobs (mapv (fn [_] (future (.countDown ready) (.await gate) (copy))) (range 16))]
    (try
      (is (.await ready 10 TimeUnit/SECONDS))
      (is (not (realized? pd)))
      (finally (.countDown gate)))
    (is (= (vec (repeat 16 42)) (mapv #(deref % 10000 :timeout) jobs)))
    (is (= 1 @calls))
    (is (= 42 (f) ((with-meta f nil))))
    (is (= [{:probe true} {:copy true}] [(meta f) (meta copy)]))
    (is (= "test_call [] -> :long" (str f) (str copy)))
    (is (fn? f))
    (is (= 42 (.call ^java.util.concurrent.Callable f)))
    (.run ^Runnable copy)))

(deftest lookup-errors-and-arity-precedence
  (let [error (ex-info "lookup failed" {:probe true})
        calls (atom 0)
        f (ffi/cfn (fn [] (swap! calls inc) (throw error)) "missing_function" [:pointer] :int)
        copy (with-meta f nil)]
    (is (zero? @calls))
    (is (= "babashka.ffi: missing_function expects 1 args, got 0" (ex-message (failure f))))
    (is (zero? @calls))
    (testing "arguments are coerced before the symbol resolves"
      (is (instance? clojure.lang.ExceptionInfo (failure #(f "invalid pointer"))))
      (is (zero? @calls)))
    (is (identical? error (failure #(copy nil))))
    (is (identical? error (failure #(f nil))))
    (is (= 1 @calls))
    (doseq [n [2 20 21 25]]
      (is (= (str "babashka.ffi: missing_function expects 1 args, got " n)
             (ex-message (failure #(apply f (repeat n nil)))))))
    (is (= 1 @calls))))

(deftest target-errors-propagate-unchanged
  (doseq [n (range 7)
          void? [false true]
          error [(RuntimeException. "runtime") (AssertionError. "error") (Exception. "checked")]]
    (let [ret (if void? Void/TYPE Long/TYPE)
          h (-> (MethodHandles/throwException ret Throwable) (.bindTo error) (drop-args 0 n))
          f (make-call (delay h) n void?)]
      (is (identical? error (failure #(apply f (repeat n 1))))))))

(deftest pointer-access-checks
  (let [length (ffi/cfn "strlen" [:pointer] :size_t)
        free (ffi/cfn "free" [:pointer] :void)
        arena (ffi/confined-arena)
        p (ffi/string->ptr arena "pointer")]
    (try
      (is (= 7 (length p) (length (MemorySegment/ofAddress (.address ^MemorySegment p)))))
      (is (nil? (free nil)))
      (is (instance? clojure.lang.ExceptionInfo @(future (failure #(length p)))))
      (is (instance? clojure.lang.ExceptionInfo (failure #(length (MemorySegment/ofArray (byte-array 8))))))
      (is (instance? clojure.lang.ExceptionInfo (failure #(length "invalid"))))
      (finally (.close arena)))
    (is (instance? clojure.lang.ExceptionInfo (failure #(length p))))))

(defn discarded-classes [queue]
  (mapv (fn [hot?]
          (let [f (ffi/cfn "abs" [:int] :int)]
            (when hot? (f -1))
            (WeakReference. (class f) queue)))
        [false true]))

(deftest discarded-bindings-can-unload
  (testing "the class of a cold and of a resolved binding unloads with it"
    (let [queue (ReferenceQueue.)
          refs (discarded-classes queue)]
      (loop [attempt 0]
        (System/gc)
        (when (and (< attempt 20) (some #(.get ^WeakReference %) refs))
          (.remove queue 250)
          (recur (inc attempt))))
      (is (every? #(nil? (.get ^WeakReference %)) refs)))))

(deftest variadic-bindings-are-generated-classes
  (let [arena (ffi/global-arena)
        buf (ffi/alloc arena 256)
        declared (ffi/cfn "snprintf" [:pointer :size_t :string :& :int :string] :int)
        inferred (ffi/cfn "snprintf" [:pointer :size_t :string :&] :int)]
    (testing "a declared tail prints its :& and reports its exact arity"
      (is (= "snprintf [:pointer :size_t :string :& :int :string] -> :int" (str declared)))
      (is (= {:babashka.ffi/backend :ffm} (meta declared)))
      (is (thrown-with-msg? Exception #"snprintf expects 5 args, got 6"
                            (declared buf 256 "%d %s" 1 "a" 2))))
    (testing "an inferred tail names the symbol, not its address, in an arity error"
      (is (= "snprintf [:pointer :size_t :string :&] -> :int" (str inferred)))
      (is (thrown-with-msg? Exception #"snprintf expects at least 3 args" (inferred buf))))
    (testing "more than 20 arguments take the handle path, declared and inferred"
      (let [fmt (apply str (repeat 20 "%d"))
            ints (range 20)
            wide (ffi/cfn "snprintf" (into [:pointer :size_t :string :&] (repeat 20 :int)) :int)]
        (is (= 30 (apply wide buf 256 fmt ints)))
        (is (= (apply str ints) (ffi/ptr->string buf 256)))
        (is (= 30 (apply inferred buf 256 fmt ints)))
        (is (thrown-with-msg? Exception #"snprintf expects 23 args, got 22"
                              (apply wide buf 256 fmt (rest ints))))))))

(defn- class-name-of [f] (.getName (class f)))

(deftest generated-class-covers-up-to-20-arguments
  (let [arena (ffi/global-arena)
        buf (ffi/alloc arena 256)
        fmt (fn [n] (ffi/string->ptr arena (apply str (repeat n "%d"))))
        text (fn [n] (apply str (range n)))
        sig (fn [n] (into [:pointer :size_t :pointer :&] (repeat n :int)))]
    (testing "a declared tail of 17 makes 20 arguments, the last generated class"
      (let [f (ffi/cfn "snprintf" (sig 17) :int)]
        (is (re-find #"impl\.Binding20J" (class-name-of f)))
        (apply f buf 256 (fmt 17) (range 17))
        (is (= (text 17) (ffi/ptr->string buf 256)))
        (is (thrown-with-msg? Exception #"snprintf expects 20 args, got 19"
                              (apply f buf 256 (fmt 17) (range 16))))))
    (testing "a declared tail of 18 makes 21 arguments, which takes the handle path"
      (let [f (ffi/cfn "snprintf" (sig 18) :int)]
        (is (not (re-find #"impl\.Binding" (class-name-of f))))
        (apply f buf 256 (fmt 18) (range 18))
        (is (= (text 18) (ffi/ptr->string buf 256)))))
    (testing "an inferred tail crosses the same boundary"
      (let [f (ffi/cfn "snprintf" [:pointer :size_t :pointer :&] :int)]
        (doseq [n [17 18]]
          (apply f buf 256 (fmt n) (range n))
          (is (= (text n) (ffi/ptr->string buf 256))))))
    (testing "a fixed signature of 8 arguments is a generated class"
      (let [sum8 (ffi/callback arena (fn [& xs] (apply + xs)) (vec (repeat 8 :long)) :long)
            f (ffi/cfn sum8 (vec (repeat 8 :long)) :long)]
        (is (re-find #"impl\.Binding8J" (class-name-of f)))
        (is (= 36 (f 1 2 3 4 5 6 7 8)))
        (is (thrown-with-msg? Exception #"expects 8 args, got 7" (f 1 2 3 4 5 6 7)))))))

(deftest inferred-tail-shapes-and-values
  (let [arena (ffi/global-arena)
        buf (ffi/alloc arena 256)
        f (ffi/cfn "snprintf" [:pointer :size_t :string :&] :int)
        out (fn [& args] (apply f buf 256 args) (ffi/ptr->string buf 256))]
    (testing "a binding stays correct after its shape cache starts over"
      ;; 7 slots of int or double give 128 shapes, the cache holds 64
      (let [shapes (for [i (range 70)] (mapv #(bit-test i %) (range 7)))
            call (fn [shape]
                   (apply out (apply str (map #(if % "%.0f" "%d") shape))
                          (map-indexed (fn [j d?] (if d? (double j) j)) shape)))]
        (is (every? #(= "0123456" (call %)) shapes))
        (is (= "0123456" (call (first shapes))))))
    (testing "a boolean has no C variadic type, so the tail refuses it"
      (is (thrown-with-msg? Exception #"cannot infer variadic tail type of class java.lang.Boolean"
                            (out "%d" true))))))
