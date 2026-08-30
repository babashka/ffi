(ns ^:no-doc babashka.ffi.impl.proxy
  "JVM downcalls through an interface proxy.

  MethodHandle.invokeWithArguments is the generic path: it checks and boxes
  every argument on each call, about 40ns. Clojure cannot emit invokeExact,
  so the downcall handle is adapted once to an interface whose method takes
  every argument as a long and returns a long or nothing, and
  MethodHandleProxies binds the two. The JIT inlines the call through the
  interface, about 4ns. Doubles and floats travel as their raw long bits.

  babashka.ffi loads this namespace while it loads itself, on the JVM only.
  A native image calls through its trampolines and never includes this
  code. Do not require this namespace directly."
  (:import [java.lang.foreign Linker]
           [java.lang.invoke MethodHandle MethodHandleProxies MethodHandles MethodType]))

(set! *warn-on-reflection* true)

;; babashka.ffi passes its helpers in, so this namespace depends on nothing
;; and loads in any order

(definterface L0 (^long call []))
(definterface L1 (^long call [^long a]))
(definterface L2 (^long call [^long a ^long b]))
(definterface L3 (^long call [^long a ^long b ^long c]))
(definterface L4 (^long call [^long a ^long b ^long c ^long d]))
(definterface L5 (^long call [^long a ^long b ^long c ^long d ^long e]))
(definterface L6 (^long call [^long a ^long b ^long c ^long d ^long e ^long f]))
(definterface V0 (^void call []))
(definterface V1 (^void call [^long a]))
(definterface V2 (^void call [^long a ^long b]))
(definterface V3 (^void call [^long a ^long b ^long c]))
(definterface V4 (^void call [^long a ^long b ^long c ^long d]))
(definterface V5 (^void call [^long a ^long b ^long c ^long d ^long e]))
(definterface V6 (^void call [^long a ^long b ^long c ^long d ^long e ^long f]))

(def ^:private long-ifaces [L0 L1 L2 L3 L4 L5 L6])
(def ^:private void-ifaces [V0 V1 V2 V3 V4 V5 V6])

(def ^:private long-bits->double
  (delay (.findStatic (MethodHandles/lookup) Double "longBitsToDouble"
                      (MethodType/methodType Double/TYPE Long/TYPE))))

(def ^:private double->long-bits
  (delay (.findStatic (MethodHandles/lookup) Double "doubleToRawLongBits"
                      (MethodType/methodType Long/TYPE Double/TYPE))))

(defn- long-bits-handle
  "Adapts downcall handle h so that each argument is a long, doubles and
  floats as raw bits, and the result is a long or void."
  ^MethodHandle [carrier ^MethodHandle h argtypes rettype]
  (let [carriers (mapv carrier argtypes)
        ret ^Class (case (carrier rettype) :void Void/TYPE :long Long/TYPE Double/TYPE)
        params ^"[Ljava.lang.Class;" (into-array Class (map #(if (= :long %) Long/TYPE Double/TYPE)
                                                             carriers))
        h (MethodHandles/explicitCastArguments h (MethodType/methodType ret params))
        h (reduce (fn [^MethodHandle h i]
                    (if (= :long (carriers i))
                      h
                      (MethodHandles/filterArguments
                       h (int i) (into-array MethodHandle [@long-bits->double]))))
                  h
                  (range (count carriers)))]
    (if (identical? Double/TYPE ret)
      (MethodHandles/filterReturnValue h @double->long-bits)
      h)))

;; Coercers return a primitive long and the return fn takes one, through
;; the IFn$OL and IFn$LO interfaces, so no argument or result is boxed
;; between the caller and the downcall.
(defn- bits-coercer [carrier arg-coercer t]
  (if (= :long (carrier t))
    (arg-coercer t)
    (fn ^long [a] (Double/doubleToRawLongBits (double a)))))

(defn- bits-ret-fn [carrier narrow-ret rettype]
  (case (carrier rettype)
    :long (fn [^long r] (narrow-ret rettype r))
    (fn [^long r] (narrow-ret rettype (Double/longBitsToDouble r)))))

(defmacro ^:private proxy-caller
  "A fn of n arguments that coerces each with the fn at its index in cs,
  calls the proxy in pd, and passes the result to ret."
  [iface n void?]
  (let [args (mapv #(symbol (str "a" %)) (range n))
        cs (mapv #(symbol (str "c" %)) (range n))
        p (with-meta (gensym "p") {:tag iface})
        ret (with-meta 'ret {:tag 'clojure.lang.IFn$LO})
        call `(.call ~p ~@(map (fn [c a] `(.invokePrim ~c ~a)) cs args))]
    `(fn [pd# ~(with-meta 'cs {:tag 'objects}) ~'ret]
       (let [~@(interleave (map #(with-meta % {:tag 'clojure.lang.IFn$OL}) cs)
                           (map (fn [i] `(aget ~'cs ~i)) (range n)))]
         (fn [~@args]
           (let [~p (force pd#)]
             ~(if void?
                `(do ~call nil)
                `(.invokePrim ~ret ~call))))))))

(def ^:private proxy-callers
  {[0 false] (proxy-caller babashka.ffi.impl.proxy.L0 0 false)
   [1 false] (proxy-caller babashka.ffi.impl.proxy.L1 1 false)
   [2 false] (proxy-caller babashka.ffi.impl.proxy.L2 2 false)
   [3 false] (proxy-caller babashka.ffi.impl.proxy.L3 3 false)
   [4 false] (proxy-caller babashka.ffi.impl.proxy.L4 4 false)
   [5 false] (proxy-caller babashka.ffi.impl.proxy.L5 5 false)
   [6 false] (proxy-caller babashka.ffi.impl.proxy.L6 6 false)
   [0 true] (proxy-caller babashka.ffi.impl.proxy.V0 0 true)
   [1 true] (proxy-caller babashka.ffi.impl.proxy.V1 1 true)
   [2 true] (proxy-caller babashka.ffi.impl.proxy.V2 2 true)
   [3 true] (proxy-caller babashka.ffi.impl.proxy.V3 3 true)
   [4 true] (proxy-caller babashka.ffi.impl.proxy.V4 4 true)
   [5 true] (proxy-caller babashka.ffi.impl.proxy.V5 5 true)
   [6 true] (proxy-caller babashka.ffi.impl.proxy.V6 6 true)})

(defn proxy-cfn
  "A JVM binding: the downcall handle behind an interface proxy, arguments
  in declared order. The handle is created on the first call. helpers holds
  the babashka.ffi fns :carrier, :arg-coercer, :narrow-ret,
  :with-string-args, :descriptor, :require-symbol, and :linker."
  [{:keys [carrier arg-coercer narrow-ret with-string-args descriptor require-symbol linker]}
   lib sym argtypes rettype]
  (let [n (count argtypes)
        void? (= :void rettype)
        pd (delay
             (let [handle (.downcallHandle ^Linker (linker)
                                           (require-symbol lib sym)
                                           (descriptor argtypes rettype)
                                           (make-array java.lang.foreign.Linker$Option 0))]
               (MethodHandleProxies/asInterfaceInstance
                (nth (if void? void-ifaces long-ifaces) n)
                (long-bits-handle carrier handle argtypes rettype))))
        fixed ((proxy-callers [n void?])
               pd
               (object-array (map #(bits-coercer carrier arg-coercer %) argtypes))
               (bits-ret-fn carrier narrow-ret rettype))]
    (if (some #(= :string %) argtypes)
      ;; strings need a temporary arena that has to outlive the call
      (fn [& args]
        (if (= (count args) n)
          (with-string-args argtypes (vec args) #(apply fixed %))
          (throw (ex-info (str "babashka.ffi: " sym " expects " n " args, got " (count args))
                          {:symbol sym}))))
      fixed)))
