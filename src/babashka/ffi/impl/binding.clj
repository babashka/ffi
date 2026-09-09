(ns ^:no-doc babashka.ffi.impl.binding
  "JVM downcalls through a generated class that holds its handle as a
  constant.

  MethodHandle.invokeWithArguments is the generic path: it checks and boxes
  every argument on each call, about 40ns. Clojure cannot emit invokeExact,
  a generated class can. Each binding is one hidden class whose static
  final field holds the downcall handle. The JIT folds a static final and
  inlines the downcall stub, about 3ns. The bytes vary only by arity and by
  a void return, so one set per shape serves every binding. Doubles and
  floats travel as their raw long bits.

  The handle resolves on the first call: the static final holds the
  invoker of a MutableCallSite whose first target resolves the symbol,
  installs the downcall handle and calls it. The JIT treats the target as
  a constant and recompiles when it changes.

  The bytes come from the Class-File API, java.lang.classfile, final since
  JDK 24.

  babashka.ffi loads this namespace while it loads itself, on the JVM only,
  through requiring-resolve on a quoted symbol. A static require would make
  it reachable in a native image. Do not require this namespace directly."
  (:import [clojure.lang IFn IPersistentMap]
           [java.lang.classfile ClassBuilder ClassFile CodeBuilder]
           [java.lang.constant ClassDesc ConstantDescs MethodTypeDesc]
           [java.lang.foreign Linker]
           [java.lang.invoke MethodHandle MethodHandles MethodHandles$Lookup$ClassOption
            MethodType MutableCallSite]
           [java.lang.reflect Constructor]
           [java.util.function Consumer]))

(set! *warn-on-reflection* true)

;; babashka.ffi passes its helpers in, so this namespace depends on nothing
;; and loads in any order

;; -- the handle: every argument a long, the result a long or nothing --------

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

(defn- long-type ^MethodType [n void?]
  (MethodType/methodType ^Class (if void? Void/TYPE Long/TYPE)
                         ^"[Ljava.lang.Class;" (into-array Class (repeat n Long/TYPE))))

(defn- lazy-invoker
  "A handle of type t. Its first call runs resolve, installs the handle
  that returns and calls it. Later calls go to that handle."
  ^MethodHandle [^MethodType t resolve]
  (let [site (MutableCallSite. t)
        install (fn [] (let [^MethodHandle h (resolve)] (.setTarget site h) h))
        resolver (-> (.findVirtual (MethodHandles/lookup) IFn "invoke"
                                   (MethodType/methodType Object ^"[Ljava.lang.Class;" (make-array Class 0)))
                     (.bindTo install)
                     (.asType (MethodType/methodType MethodHandle ^"[Ljava.lang.Class;" (make-array Class 0))))]
    (.setTarget site (MethodHandles/foldArguments (MethodHandles/invoker t) resolver))
    (.dynamicInvoker site)))

;; Coercers return a primitive long and the return fn takes one, through
;; the IFn$OL and IFn$LO interfaces, so no argument or result is boxed
;; between the caller and the downcall.
(defn- bits-coercer [carrier arg-coercer t]
  (if (= :long (carrier t))
    (arg-coercer t)
    (fn ^long [a] (Double/doubleToRawLongBits (double a)))))

(defn- bits-ret-fn [narrow-ret rettype]
  ;; Resolve the type once, keeping the raw result primitive until conversion.
  (case rettype
    :void (fn [^long _] nil)
    :bool (fn [^long r] (not (zero? r)))
    (:int :int32) (fn [^long r] (long (unchecked-int r)))
    (:uint :uint32) (fn [^long r] (bit-and r 0xFFFFFFFF))
    :int16 (fn [^long r] (long (unchecked-short r)))
    :uint16 (fn [^long r] (bit-and r 0xFFFF))
    (:int8 :byte :char) (fn [^long r] (long (unchecked-byte r)))
    :uint8 (fn [^long r] (bit-and r 0xFF))
    (:double :float) (fn [^long r] (Double/longBitsToDouble r))
    :pointer (fn [^long r] (narrow-ret :pointer r))
    :string (fn [^long r] (narrow-ret :string r))
    (fn [^long r] r)))

;; -- the class ---------------------------------------------------------------

(defn- cd ^ClassDesc [internal-name] (ClassDesc/ofInternalName internal-name))

(defn- mt ^MethodTypeDesc [^ClassDesc ret & params]
  (MethodTypeDesc/of ret ^"[Ljava.lang.constant.ClassDesc;" (into-array ClassDesc params)))

(def ^:private ^ClassDesc cd-object ConstantDescs/CD_Object)
(def ^:private ^ClassDesc cd-objects (.arrayType ConstantDescs/CD_Object))
(def ^:private ^ClassDesc cd-string ConstantDescs/CD_String)
(def ^:private ^ClassDesc cd-class ConstantDescs/CD_Class)
(def ^:private ^ClassDesc cd-integer ConstantDescs/CD_Integer)
(def ^:private ^ClassDesc cd-mh ConstantDescs/CD_MethodHandle)
(def ^:private ^ClassDesc cd-mhs ConstantDescs/CD_MethodHandles)
(def ^:private ^ClassDesc cd-lookup ConstantDescs/CD_MethodHandles_Lookup)
(def ^:private ^ClassDesc cd-afn (cd "clojure/lang/AFn"))
(def ^:private ^ClassDesc cd-ifn (cd "clojure/lang/IFn"))
(def ^:private ^ClassDesc cd-coercer (cd "clojure/lang/IFn$OL"))
(def ^:private ^ClassDesc cd-ret-fn (cd "clojure/lang/IFn$LO"))
(def ^:private ^ClassDesc cd-imap (cd "clojure/lang/IPersistentMap"))
(def ^:private ^ClassDesc cd-iobj (cd "clojure/lang/IObj"))
(def ^:private ^ClassDesc cd-iseq (cd "clojure/lang/ISeq"))
(def ^:private ^ClassDesc cd-rt (cd "clojure/lang/RT"))

(def ^:private ^ClassDesc cd-long ConstantDescs/CD_long)
(def ^:private ^ClassDesc cd-int ConstantDescs/CD_int)
(def ^:private ^ClassDesc cd-void ConstantDescs/CD_void)

(defn- consumer ^Consumer [f]
  (reify Consumer (accept [_ b] (f b))))

(defn- method [^ClassBuilder clb name ^MethodTypeDesc type flags f]
  (.withMethodBody clb ^String name type (int flags) (consumer f)))

(defn- class-bytes*
  "Bytes of the class for n arguments. Static finals TARGET, ARITY and STR
  come from the class data: the handle, the arity error fn and the print
  fn. Instance fields hold the coercers, the return fn, the signature for
  errors and printing, and the metadata."
  ^bytes [n void?]
  (let [;; a hidden class shares the package of the lookup that defines it
        this (cd (str "babashka/ffi/impl/Binding" n (if void? "V" "J")))
        static-final (bit-or ClassFile/ACC_PRIVATE ClassFile/ACC_STATIC ClassFile/ACC_FINAL)
        final ClassFile/ACC_FINAL
        ctor (mt cd-void cd-objects cd-object cd-object cd-imap)
        target (apply mt (if void? cd-void cd-long) (repeat n cd-long))
        invoke (apply mt cd-object (repeat n cd-object))
        throw-arity (mt cd-object cd-int)]
    (.build (ClassFile/of) this
            (consumer
             (fn [^ClassBuilder clb]
               (.withFlags clb (int (bit-or ClassFile/ACC_PUBLIC ClassFile/ACC_FINAL)))
               (.withSuperclass clb cd-afn)
               (.withInterfaceSymbols clb ^"[Ljava.lang.constant.ClassDesc;" (into-array ClassDesc [(cd "clojure/lang/Fn") cd-iobj]))
               (doseq [[flags field type] (concat [[static-final "TARGET" cd-mh]
                                                   [static-final "ARITY" cd-ifn]
                                                   [static-final "STR" cd-ifn]
                                                   [final "cs" cd-objects]
                                                   [final "ret" cd-ret-fn]
                                                   [final "info" cd-object]
                                                   [final "m" cd-imap]]
                                                  (map (fn [i] [final (str "c" i) cd-coercer]) (range n)))]
                 (.withField clb ^String field ^ClassDesc type (int flags)))
               (method clb "<clinit>" (mt cd-void) ClassFile/ACC_STATIC
                       (fn [^CodeBuilder cob]
                         (.invokestatic cob cd-mhs "lookup" (mt cd-lookup))
                         (.loadConstant cob "_")
                         (.loadConstant cob ^ClassDesc cd-objects)
                         (.invokestatic cob cd-mhs "classData" (mt cd-object cd-lookup cd-string cd-class))
                         (.checkcast cob cd-objects)
                         (doseq [[i field type] [[0 "TARGET" cd-mh] [1 "ARITY" cd-ifn] [2 "STR" cd-ifn]]]
                           (when (< i 2) (.dup cob))
                           (.loadConstant cob (int i))
                           (.aaload cob)
                           (.checkcast cob ^ClassDesc type)
                           (.putstatic cob this field type))
                         (.return_ cob)))
               (method clb "<init>" ctor ClassFile/ACC_PUBLIC
                       (fn [^CodeBuilder cob]
                         (.aload cob 0)
                         (.invokespecial cob cd-afn "<init>" (mt cd-void))
                         (doseq [[slot field type cast] [[1 "cs" cd-objects nil]
                                                         [2 "ret" cd-ret-fn cd-ret-fn]
                                                         [3 "info" cd-object nil]
                                                         [4 "m" cd-imap nil]]]
                           (.aload cob 0)
                           (.aload cob slot)
                           (when cast (.checkcast cob ^ClassDesc cast))
                           (.putfield cob this field type))
                         (dotimes [i n]
                           (.aload cob 0)
                           (.aload cob 1)
                           (.loadConstant cob (int i))
                           (.aaload cob)
                           (.checkcast cob cd-coercer)
                           (.putfield cob this (str "c" i) cd-coercer))
                         (.return_ cob)))
               (method clb "invoke" invoke ClassFile/ACC_PUBLIC
                       (fn [^CodeBuilder cob]
                         (when-not void?
                           (.aload cob 0)
                           (.getfield cob this "ret" cd-ret-fn))
                         (.getstatic cob this "TARGET" cd-mh)
                         (dotimes [i n]
                           (.aload cob 0)
                           (.getfield cob this (str "c" i) cd-coercer)
                           (.aload cob (inc i))
                           (.invokeinterface cob cd-coercer "invokePrim" (mt cd-long cd-object)))
                         (.invokevirtual cob cd-mh "invokeExact" target)
                         (if void?
                           (.aconst_null cob)
                           (.invokeinterface cob cd-ret-fn "invokePrim" (mt cd-object cd-long)))
                         (.areturn cob)))
               (method clb "throwArity" throw-arity ClassFile/ACC_PUBLIC
                       (fn [^CodeBuilder cob]
                         (.getstatic cob this "ARITY" cd-ifn)
                         (.aload cob 0)
                         (.getfield cob this "info" cd-object)
                         (.iload cob 1)
                         (.invokestatic cob cd-integer "valueOf" (mt cd-integer cd-int))
                         (.invokeinterface cob cd-ifn "invoke" (mt cd-object cd-object cd-object))
                         (.areturn cob)))
               ;; AFn reports 21 for any call beyond 20 arguments: count them here
               (method clb "invoke" (apply mt cd-object (concat (repeat 20 cd-object) [cd-objects])) ClassFile/ACC_PUBLIC
                       (fn [^CodeBuilder cob]
                         (.aload cob 0)
                         (.bipush cob 20)
                         (.aload cob 21)
                         (.arraylength cob)
                         (.iadd cob)
                         (.invokevirtual cob this "throwArity" throw-arity)
                         (.areturn cob)))
               (method clb "applyTo" (mt cd-object cd-iseq) ClassFile/ACC_PUBLIC
                       (fn [^CodeBuilder cob]
                         (let [matches (.newLabel cob)]
                           (.aload cob 1)
                           (.invokestatic cob cd-rt "count" (mt cd-int cd-object))
                           (.dup cob)
                           (.istore cob 2)
                           (.loadConstant cob (int n))
                           (.if_icmpeq cob matches)
                           (.aload cob 0)
                           (.iload cob 2)
                           (.invokevirtual cob this "throwArity" throw-arity)
                           (.areturn cob)
                           (.labelBinding cob matches)
                           (.aload cob 0)
                           (.aload cob 1)
                           (.invokestatic cob cd-afn "applyToHelper" (mt cd-object cd-ifn cd-iseq))
                           (.areturn cob))))
               (method clb "toString" (mt cd-string) ClassFile/ACC_PUBLIC
                       (fn [^CodeBuilder cob]
                         (.getstatic cob this "STR" cd-ifn)
                         (.aload cob 0)
                         (.getfield cob this "info" cd-object)
                         (.invokeinterface cob cd-ifn "invoke" (mt cd-object cd-object))
                         (.checkcast cob cd-string)
                         (.areturn cob)))
               (method clb "meta" (mt cd-imap) ClassFile/ACC_PUBLIC
                       (fn [^CodeBuilder cob]
                         (.aload cob 0)
                         (.getfield cob this "m" cd-imap)
                         (.areturn cob)))
               (method clb "withMeta" (mt cd-iobj cd-imap) ClassFile/ACC_PUBLIC
                       (fn [^CodeBuilder cob]
                         (.new_ cob this)
                         (.dup cob)
                         (doseq [[field type] [["cs" cd-objects] ["ret" cd-ret-fn] ["info" cd-object]]]
                           (.aload cob 0)
                           (.getfield cob this field type))
                         (.aload cob 1)
                         (.invokespecial cob this "<init>" ctor)
                         (.areturn cob))))))))

(def ^:private class-bytes (memoize class-bytes*))

(def ^:private no-options (make-array MethodHandles$Lookup$ClassOption 0))

(defn make-binding
  "The binding for pd, a delayed handle whose arguments are longs and whose
  result is a long or void: an instance of the class generated for that
  arity. cs holds one coercer per argument, ret converts the result, m is
  the metadata. sym, argtypes and rettype name the binding in errors and
  printing, through the :arity-ex and :binding-string fns in helpers. The
  handle resolves on the first call."
  [pd ^objects cs ret m sym argtypes rettype {:keys [arity-ex binding-string]}]
  (let [n (alength cs)
        void? (= :void rettype)
        info {:sym sym :argtypes argtypes :rettype rettype}
        data (object-array
              [(lazy-invoker (long-type n void?) #(force pd))
               (fn [info got] (throw (arity-ex (:sym info) (count (:argtypes info)) got)))
               (fn [info] (binding-string (:sym info) (:argtypes info) (:rettype info)))])
        cls (.lookupClass (.defineHiddenClassWithClassData (MethodHandles/lookup)
                                                           ^bytes (class-bytes n void?)
                                                           data true no-options))
        ^Constructor ctor (.getConstructor ^Class cls
                                           (into-array Class [(class cs) Object Object IPersistentMap]))]
    (.newInstance ctor (object-array [cs ret info m]))))

(defn jvm-cfn
  "A JVM binding for a fixed signature of up to 6 arguments, arguments in
  declared order. helpers holds the babashka.ffi fns :carrier,
  :arg-coercer, :narrow-ret, :with-string-args, :descriptor,
  :require-symbol, :linker, :arity-ex, :binding-string and
  :binding-with-meta."
  [{:keys [carrier arg-coercer narrow-ret with-string-args descriptor require-symbol linker
           binding-with-meta]
    :as helpers}
   lib sym argtypes rettype]
  (let [m {:babashka.ffi/backend :ffm}
        pd (delay
             (long-bits-handle carrier
                               (.downcallHandle ^Linker (linker)
                                                (require-symbol lib sym)
                                                (descriptor argtypes rettype)
                                                (make-array java.lang.foreign.Linker$Option 0))
                               argtypes rettype))
        fixed (make-binding pd
                            (object-array (map #(bits-coercer carrier arg-coercer %) argtypes))
                            (bits-ret-fn narrow-ret rettype)
                            m sym argtypes rettype helpers)]
    (if (some #(= :string %) argtypes)
      ;; strings need a temporary arena that has to outlive the call
      (binding-with-meta (fn [& args] (with-string-args argtypes (vec args) #(apply fixed %)))
                         m sym argtypes rettype)
      fixed)))
