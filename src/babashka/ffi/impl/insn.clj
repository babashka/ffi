(ns ^:no-doc babashka.ffi.impl.insn
  "JVM downcalls through a generated class.

  MethodHandle.invokeWithArguments is the generic path: it checks and boxes
  every argument on each call, about 40ns. Clojure cannot emit invokeExact,
  so for each signature shape a class is generated with insn: it holds the
  downcall handle and the coercers as fields, and its invoke coerces each
  argument to a primitive, calls invokeExact with the exact descriptor, and
  passes the primitive result to the return fn. One virtual call, nothing
  boxed in between.

  babashka.ffi loads this namespace while it loads itself, on the JVM only.
  A native image calls through its trampolines and never includes this
  code. Do not require this namespace directly."
  (:require [insn.core :as insn])
  (:import [clojure.lang AFunction IFn IFn$DO IFn$LO IFn$OD IFn$OL]
           [java.lang.foreign Linker]
           [java.lang.invoke MethodHandle]))

(set! *warn-on-reflection* true)

;; babashka.ffi passes its helpers in, so this namespace depends on nothing
;; of it and loads in any order

(defn- coercer-type [carrier]
  (if (= :long carrier) IFn$OL IFn$OD))

(defn- prim [carrier]
  (case carrier :long :long :double :double :float :float :void :void))

(defn- downcall-class*
  "The class for one shape: the argument carriers and the return carrier."
  [carriers ret]
  (let [n (count carriers)
        ret-type (case ret :void nil :long IFn$LO IFn$DO)
        fields (concat [{:name "handle" :type MethodHandle :flags #{:final}}]
                       (map-indexed (fn [i c] {:name (str "c" i) :type (coercer-type c) :flags #{:final}})
                                    carriers)
                       (when ret-type [{:name "ret" :type ret-type :flags #{:final}}]))
        ctor-desc (conj (mapv :type fields) :void)
        init (concat [[:aload 0] [:invokespecial :super :init [:void]]]
                     (map-indexed (fn [i {:keys [name type]}]
                                    [[:aload 0] [:aload (inc i)] [:putfield :this name type]])
                                  fields)
                     [[:return]])
        call (concat (when ret-type [[:aload 0] [:getfield :this "ret" ret-type]])
                     [[:aload 0] [:getfield :this "handle" MethodHandle]]
                     (map-indexed (fn [i c]
                                    (let [t (coercer-type c)]
                                      (concat [[:aload 0] [:getfield :this (str "c" i) t]
                                               [:aload (inc i)]
                                               [:invokeinterface t "invokePrim" [Object (if (= :long c) :long :double)]]]
                                              (when (= :float c) [[:d2f]]))))
                                  carriers)
                     [[:invokevirtual MethodHandle "invokeExact" (conj (mapv prim carriers) (prim ret))]]
                     (case ret
                       :void [[:aconst-null]]
                       :long [[:invokeinterface IFn$LO "invokePrim" [:long Object]]]
                       :double [[:invokeinterface IFn$DO "invokePrim" [:double Object]]]
                       :float [[:f2d] [:invokeinterface IFn$DO "invokePrim" [:double Object]]])
                     [[:areturn]])]
    (insn/define
     {:flags #{:public :final}
      :version 8
      :super AFunction
      :fields (vec fields)
      :methods [{:name :init :flags #{:public} :desc ctor-desc :emit (vec init)}
                {:name :invoke :flags #{:public} :desc (vec (repeat (inc n) Object)) :emit (vec call)}]})))

(def ^:private downcall-class (memoize downcall-class*))

(defn- coercer [carrier arg-coercer t]
  (if (= :long carrier)
    (arg-coercer t)
    (fn ^double [a] (double a))))

(defn- ret-fn [carrier narrow-ret rettype]
  (case carrier
    :void nil
    :long (fn [^long r] (narrow-ret rettype r))
    (fn [^double r] (narrow-ret rettype r))))

(defn insn-cfn
  "A JVM binding: a generated class around the downcall handle, arguments
  in declared order. The handle and the instance are created on the first
  call. helpers holds the babashka.ffi fns :carrier, :arg-coercer,
  :narrow-ret, :with-string-args, :descriptor, :require-symbol, and
  :linker."
  [{:keys [carrier arg-coercer narrow-ret with-string-args descriptor require-symbol linker]}
   lib sym argtypes rettype]
  (let [n (count argtypes)
        carriers (mapv carrier argtypes)
        ret (carrier rettype)
        klass ^Class (downcall-class carriers ret)
        coercers (mapv #(coercer %1 arg-coercer %2) carriers argtypes)
        rf (ret-fn ret narrow-ret rettype)
        inst (delay
               (let [handle (.downcallHandle ^Linker (linker)
                                             (require-symbol lib sym)
                                             (descriptor argtypes rettype)
                                             (make-array java.lang.foreign.Linker$Option 0))
                     args (object-array (concat [handle] coercers (when rf [rf])))]
                 (.newInstance ^java.lang.reflect.Constructor (first (.getConstructors klass)) args)))
        fixed (case n
                0 (fn [] (.invoke ^IFn (force inst)))
                1 (fn [a] (.invoke ^IFn (force inst) a))
                2 (fn [a b] (.invoke ^IFn (force inst) a b))
                3 (fn [a b c] (.invoke ^IFn (force inst) a b c))
                4 (fn [a b c d] (.invoke ^IFn (force inst) a b c d))
                5 (fn [a b c d e] (.invoke ^IFn (force inst) a b c d e))
                6 (fn [a b c d e f] (.invoke ^IFn (force inst) a b c d e f)))]
    (if (some #(= :string %) argtypes)
      ;; strings need a temporary arena that has to outlive the call
      (fn [& args]
        (if (= (count args) n)
          (with-string-args argtypes (vec args) #(apply fixed %))
          (throw (ex-info (str "babashka.ffi: " sym " expects " n " args, got " (count args))
                          {:symbol sym}))))
      fixed)))
