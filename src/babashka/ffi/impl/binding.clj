(ns ^{:no-doc true
      :clj-kondo/config '{:lint-as {babashka.ffi.impl.binding/with-method clojure.core/let}}}
  babashka.ffi.impl.binding
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

  clojure.asm is the ASM copy inside Clojure, an internal, not public API.

  babashka.ffi loads this namespace while it loads itself, on the JVM only,
  through requiring-resolve on a quoted symbol. A static require would make
  it reachable in a native image. Do not require this namespace directly."
  (:import [clojure.asm ClassWriter Label Opcodes Type]
           [clojure.lang IFn IPersistentMap]
           [java.lang.foreign Linker]
           [java.lang.invoke MethodHandle MethodHandles MethodHandles$Lookup$ClassOption
            MethodType MutableCallSite]
           [java.lang.reflect Constructor]))

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

(def ^:private obj "Ljava/lang/Object;")
(def ^:private objs "[Ljava/lang/Object;")
(def ^:private mh "Ljava/lang/invoke/MethodHandle;")
(def ^:private ifn "clojure/lang/IFn")
(def ^:private coercer "clojure/lang/IFn$OL")
(def ^:private ret-fn "clojure/lang/IFn$LO")
(def ^:private imap "Lclojure/lang/IPersistentMap;")

(defmacro ^:private with-method [[v writer] access name descriptor & body]
  `(let [~(with-meta v {:tag 'clojure.asm.MethodVisitor})
         (.visitMethod ~(with-meta writer {:tag 'clojure.asm.ClassWriter}) ~access ~name ~descriptor nil nil)]
     (.visitCode ~v)
     ~@body
     (.visitMaxs ~v 0 0)
     (.visitEnd ~v)))

(defn- class-bytes*
  "Bytes of the class for n arguments. Static finals TARGET, ARITY and STR
  come from the class data: the handle, the arity error fn and the print
  fn. Instance fields hold the coercers, the return fn, the signature for
  errors and printing, and the metadata."
  ^bytes [n void?]
  (let [;; a hidden class shares the package of the lookup that defines it
        name (str "babashka/ffi/impl/Binding" n (if void? "V" "J"))
        w (doto (ClassWriter. ClassWriter/COMPUTE_FRAMES)
            (.visit Opcodes/V1_8 (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL)
                    name nil "clojure/lang/AFn"
                    (into-array String ["clojure/lang/Fn" "clojure/lang/IObj"])))
        static-final (bit-or Opcodes/ACC_PRIVATE Opcodes/ACC_STATIC Opcodes/ACC_FINAL)
        final Opcodes/ACC_FINAL
        ctor (str "(" objs obj obj imap ")V")
        longs (apply str (repeat n "J"))]
    (doseq [[flags field desc] (concat [[static-final "TARGET" mh]
                                        [static-final "ARITY" (str "L" ifn ";")]
                                        [static-final "STR" (str "L" ifn ";")]
                                        [final "cs" objs]
                                        [final "ret" (str "L" ret-fn ";")]
                                        [final "info" obj]
                                        [final "m" imap]]
                                       (map (fn [i] [final (str "c" i) (str "L" coercer ";")]) (range n)))]
      (.visitEnd (.visitField w (int flags) field desc nil nil)))
    (with-method [v w] Opcodes/ACC_STATIC "<clinit>" "()V"
      (.visitMethodInsn v Opcodes/INVOKESTATIC "java/lang/invoke/MethodHandles" "lookup"
                        "()Ljava/lang/invoke/MethodHandles$Lookup;" false)
      (.visitLdcInsn v "_")
      (.visitLdcInsn v (Type/getType ^String objs))
      (.visitMethodInsn v Opcodes/INVOKESTATIC "java/lang/invoke/MethodHandles" "classData"
                        (str "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)" obj) false)
      (.visitTypeInsn v Opcodes/CHECKCAST objs)
      (doseq [[i field desc cast] [[0 "TARGET" mh "java/lang/invoke/MethodHandle"]
                                   [1 "ARITY" (str "L" ifn ";") ifn]
                                   [2 "STR" (str "L" ifn ";") ifn]]]
        (when (< i 2) (.visitInsn v Opcodes/DUP))
        (.visitLdcInsn v (int i))
        (.visitInsn v Opcodes/AALOAD)
        (.visitTypeInsn v Opcodes/CHECKCAST cast)
        (.visitFieldInsn v Opcodes/PUTSTATIC name field desc))
      (.visitInsn v Opcodes/RETURN))
    (with-method [v w] Opcodes/ACC_PUBLIC "<init>" ctor
      (.visitVarInsn v Opcodes/ALOAD 0)
      (.visitMethodInsn v Opcodes/INVOKESPECIAL "clojure/lang/AFn" "<init>" "()V" false)
      (doseq [[slot field desc cast] [[1 "cs" objs nil]
                                      [2 "ret" (str "L" ret-fn ";") ret-fn]
                                      [3 "info" obj nil]
                                      [4 "m" imap nil]]]
        (.visitVarInsn v Opcodes/ALOAD 0)
        (.visitVarInsn v Opcodes/ALOAD slot)
        (when cast (.visitTypeInsn v Opcodes/CHECKCAST cast))
        (.visitFieldInsn v Opcodes/PUTFIELD name field desc))
      (dotimes [i n]
        (.visitVarInsn v Opcodes/ALOAD 0)
        (.visitVarInsn v Opcodes/ALOAD 1)
        (.visitLdcInsn v (int i))
        (.visitInsn v Opcodes/AALOAD)
        (.visitTypeInsn v Opcodes/CHECKCAST coercer)
        (.visitFieldInsn v Opcodes/PUTFIELD name (str "c" i) (str "L" coercer ";")))
      (.visitInsn v Opcodes/RETURN))
    (with-method [v w] Opcodes/ACC_PUBLIC "invoke" (str "(" (apply str (repeat n obj)) ")" obj)
      (when-not void?
        (.visitVarInsn v Opcodes/ALOAD 0)
        (.visitFieldInsn v Opcodes/GETFIELD name "ret" (str "L" ret-fn ";")))
      (.visitFieldInsn v Opcodes/GETSTATIC name "TARGET" mh)
      (dotimes [i n]
        (.visitVarInsn v Opcodes/ALOAD 0)
        (.visitFieldInsn v Opcodes/GETFIELD name (str "c" i) (str "L" coercer ";"))
        (.visitVarInsn v Opcodes/ALOAD (inc i))
        (.visitMethodInsn v Opcodes/INVOKEINTERFACE coercer "invokePrim" (str "(" obj ")J") true))
      (.visitMethodInsn v Opcodes/INVOKEVIRTUAL "java/lang/invoke/MethodHandle" "invokeExact"
                        (str "(" longs ")" (if void? "V" "J")) false)
      (if void?
        (.visitInsn v Opcodes/ACONST_NULL)
        (.visitMethodInsn v Opcodes/INVOKEINTERFACE ret-fn "invokePrim" (str "(J)" obj) true))
      (.visitInsn v Opcodes/ARETURN))
    (with-method [v w] Opcodes/ACC_PUBLIC "throwArity" (str "(I)" obj)
      (.visitFieldInsn v Opcodes/GETSTATIC name "ARITY" (str "L" ifn ";"))
      (.visitVarInsn v Opcodes/ALOAD 0)
      (.visitFieldInsn v Opcodes/GETFIELD name "info" obj)
      (.visitVarInsn v Opcodes/ILOAD 1)
      (.visitMethodInsn v Opcodes/INVOKESTATIC "java/lang/Integer" "valueOf" "(I)Ljava/lang/Integer;" false)
      (.visitMethodInsn v Opcodes/INVOKEINTERFACE ifn "invoke" (str "(" obj obj ")" obj) true)
      (.visitInsn v Opcodes/ARETURN))
    ;; AFn reports 21 for any call beyond 20 arguments: count them here
    (with-method [v w] Opcodes/ACC_PUBLIC "invoke" (str "(" (apply str (repeat 20 obj)) objs ")" obj)
      (.visitVarInsn v Opcodes/ALOAD 0)
      (.visitIntInsn v Opcodes/BIPUSH 20)
      (.visitVarInsn v Opcodes/ALOAD 21)
      (.visitInsn v Opcodes/ARRAYLENGTH)
      (.visitInsn v Opcodes/IADD)
      (.visitMethodInsn v Opcodes/INVOKEVIRTUAL name "throwArity" (str "(I)" obj) false)
      (.visitInsn v Opcodes/ARETURN))
    (with-method [v w] Opcodes/ACC_PUBLIC "applyTo" (str "(Lclojure/lang/ISeq;)" obj)
      (let [matches (Label.)]
        (.visitVarInsn v Opcodes/ALOAD 1)
        (.visitMethodInsn v Opcodes/INVOKESTATIC "clojure/lang/RT" "count" (str "(" obj ")I") false)
        (.visitInsn v Opcodes/DUP)
        (.visitVarInsn v Opcodes/ISTORE 2)
        (.visitLdcInsn v (int n))
        (.visitJumpInsn v Opcodes/IF_ICMPEQ matches)
        (.visitVarInsn v Opcodes/ALOAD 0)
        (.visitVarInsn v Opcodes/ILOAD 2)
        (.visitMethodInsn v Opcodes/INVOKEVIRTUAL name "throwArity" (str "(I)" obj) false)
        (.visitInsn v Opcodes/ARETURN)
        (.visitLabel v matches)
        (.visitVarInsn v Opcodes/ALOAD 0)
        (.visitVarInsn v Opcodes/ALOAD 1)
        (.visitMethodInsn v Opcodes/INVOKESTATIC "clojure/lang/AFn" "applyToHelper"
                          (str "(Lclojure/lang/IFn;Lclojure/lang/ISeq;)" obj) false)
        (.visitInsn v Opcodes/ARETURN)))
    (with-method [v w] Opcodes/ACC_PUBLIC "toString" "()Ljava/lang/String;"
      (.visitFieldInsn v Opcodes/GETSTATIC name "STR" (str "L" ifn ";"))
      (.visitVarInsn v Opcodes/ALOAD 0)
      (.visitFieldInsn v Opcodes/GETFIELD name "info" obj)
      (.visitMethodInsn v Opcodes/INVOKEINTERFACE ifn "invoke" (str "(" obj ")" obj) true)
      (.visitTypeInsn v Opcodes/CHECKCAST "java/lang/String")
      (.visitInsn v Opcodes/ARETURN))
    (with-method [v w] Opcodes/ACC_PUBLIC "meta" (str "()" imap)
      (.visitVarInsn v Opcodes/ALOAD 0)
      (.visitFieldInsn v Opcodes/GETFIELD name "m" imap)
      (.visitInsn v Opcodes/ARETURN))
    (with-method [v w] Opcodes/ACC_PUBLIC "withMeta" (str "(" imap ")Lclojure/lang/IObj;")
      (.visitTypeInsn v Opcodes/NEW name)
      (.visitInsn v Opcodes/DUP)
      (doseq [[field desc] [["cs" objs] ["ret" (str "L" ret-fn ";")] ["info" obj]]]
        (.visitVarInsn v Opcodes/ALOAD 0)
        (.visitFieldInsn v Opcodes/GETFIELD name field desc))
      (.visitVarInsn v Opcodes/ALOAD 1)
      (.visitMethodInsn v Opcodes/INVOKESPECIAL name "<init>" ctor false)
      (.visitInsn v Opcodes/ARETURN))
    (.visitEnd w)
    (.toByteArray w)))

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
