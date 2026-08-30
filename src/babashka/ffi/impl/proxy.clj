(ns ^:no-doc babashka.ffi.impl.proxy
  "JVM downcalls through an interface proxy.

  MethodHandle.invokeWithArguments is the generic path: it checks and boxes
  every argument on each call, about 40ns. Clojure cannot emit invokeExact,
  so the downcall handle is adapted once to an interface whose method takes
  every argument as a long and returns a long or nothing, and
  MethodHandleProxies binds the two. The JIT inlines the call through the
  interface, about 4ns. Doubles and floats travel as their raw long bits.

  babashka.ffi loads this namespace on the JVM only. A native image calls
  through its trampolines and never includes this code."
  (:require [babashka.ffi])
  (:import [java.lang.foreign Linker]
           [java.lang.invoke MethodHandle MethodHandleProxies MethodHandles MethodType]))

(set! *warn-on-reflection* true)

(def ^:private carrier* @#'babashka.ffi/carrier)
(def ^:private arg-coercer* @#'babashka.ffi/arg-coercer)
(def ^:private narrow-ret* @#'babashka.ffi/narrow-ret)
(def ^:private with-string-args* @#'babashka.ffi/with-string-args)
(def ^:private descriptor* @#'babashka.ffi/descriptor)
(def ^:private require-symbol* @#'babashka.ffi/require-symbol)
(def ^:private linker** @#'babashka.ffi/linker*)

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
  ^MethodHandle [^MethodHandle h argtypes rettype]
  (let [carriers (mapv carrier* argtypes)
        ret ^Class (case (carrier* rettype) :void Void/TYPE :long Long/TYPE Double/TYPE)
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

(defn- bits-coercer [t]
  (if (= :long (carrier* t))
    (arg-coercer* t)
    (fn [a] (Double/doubleToRawLongBits (double a)))))

(defn- bits-ret-fn [rettype]
  (case (carrier* rettype)
    :void (fn [_] nil)
    :long (fn [r] (narrow-ret* rettype r))
    (fn [r] (narrow-ret* rettype (Double/longBitsToDouble (long r))))))

(defmacro ^:private proxy-caller
  "A fn of n arguments that coerces each with the fn at its index in cs,
  calls the proxy in pd, and passes the result to ret."
  [iface n]
  (let [args (mapv #(symbol (str "a" %)) (range n))
        cs (mapv #(symbol (str "c" %)) (range n))
        p (with-meta (gensym "p") {:tag iface})]
    `(fn [pd# ~(with-meta 'cs {:tag 'objects}) ~'ret]
       (let [~@(interleave cs (map (fn [i] `(aget ~'cs ~i)) (range n)))]
         (fn [~@args]
           (let [~p (force pd#)]
             (~'ret (.call ~p ~@(map (fn [c a] `(~c ~a)) cs args)))))))))

(def ^:private proxy-callers
  {[0 false] (proxy-caller babashka.ffi.impl.proxy.L0 0)
   [1 false] (proxy-caller babashka.ffi.impl.proxy.L1 1)
   [2 false] (proxy-caller babashka.ffi.impl.proxy.L2 2)
   [3 false] (proxy-caller babashka.ffi.impl.proxy.L3 3)
   [4 false] (proxy-caller babashka.ffi.impl.proxy.L4 4)
   [5 false] (proxy-caller babashka.ffi.impl.proxy.L5 5)
   [6 false] (proxy-caller babashka.ffi.impl.proxy.L6 6)
   [0 true] (proxy-caller babashka.ffi.impl.proxy.V0 0)
   [1 true] (proxy-caller babashka.ffi.impl.proxy.V1 1)
   [2 true] (proxy-caller babashka.ffi.impl.proxy.V2 2)
   [3 true] (proxy-caller babashka.ffi.impl.proxy.V3 3)
   [4 true] (proxy-caller babashka.ffi.impl.proxy.V4 4)
   [5 true] (proxy-caller babashka.ffi.impl.proxy.V5 5)
   [6 true] (proxy-caller babashka.ffi.impl.proxy.V6 6)})

(defn proxy-cfn
  "A JVM binding: the downcall handle behind an interface proxy, arguments
  in declared order. The handle is created on the first call."
  [lib sym argtypes rettype]
  (let [n (count argtypes)
        void? (= :void rettype)
        pd (delay
             (let [handle (.downcallHandle ^Linker @linker**
                                           (require-symbol* lib sym)
                                           (descriptor* argtypes rettype)
                                           (make-array java.lang.foreign.Linker$Option 0))]
               (MethodHandleProxies/asInterfaceInstance
                (nth (if void? void-ifaces long-ifaces) n)
                (long-bits-handle handle argtypes rettype))))
        fixed ((proxy-callers [n void?])
               pd (object-array (map bits-coercer argtypes)) (bits-ret-fn rettype))]
    (if (some #(= :string %) argtypes)
      ;; strings need a temporary arena that has to outlive the call
      (fn [& args]
        (if (= (count args) n)
          (with-string-args* argtypes (vec args) #(apply fixed %))
          (throw (ex-info (str "babashka.ffi: " sym " expects " n " args, got " (count args))
                          {:symbol sym}))))
      fixed)))
