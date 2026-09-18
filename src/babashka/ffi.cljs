(ns babashka.ffi
  "Call functions in native shared libraries with node:ffi on Node.js.

  Use the same names and argument order as the JVM namespace:

      (require '[babashka.ffi :as ffi])
      (ffi/load-system-library \"sqlite3\")
      (def sqlite3-open (ffi/cfn \"sqlite3_open\" [:string :pointer] :int))
      (ffi/with-open [arena (ffi/confined-arena)]
        (let [pp (ffi/alloc arena :pointer)]
          (sqlite3-open \"x.db\" pp)
          (ffi/read pp :pointer)))

  Needs Node.js 26.1 or newer. Runs under nbb, ClojureScript and
  shadow-cljs. A ClojureScript compile needs JDK 25 or newer, because the
  macros come from ffi.clj.

  Use these type keywords:

      :void
      :int :uint :long :ulong :int8 :uint8 :int16 :uint16 :int32
      :uint32 :int64 :uint64 :size_t :ssize_t :char :byte
      :bool :pointer :string :double :float

  A pointer is a Pointer: an address with a size and the arena that owns it.
  read and write check each access against this size. Pointers from C have
  size zero. reinterpret specifies their size before access.

  A 64-bit integer returns as a number when it is a safe integer, otherwise
  as a bigint. Arguments accept either.

  Use ffi/with-open to close an arena. It closes the arena when the body returns, so do not return
  a promise that still uses the arena.

  Layouts, place, read-array, write-array, copy and clone work as on the
  JVM. read-array returns a typed array.

  node:ffi does not support these, and cfn throws for each:

  - a struct by value in a signature
  - a variadic signature, :&
  - a function pointer as the symbol. Bind a function by name."
  (:refer-clojure :exclude [clone])
  ;; The ClojureScript compiler takes defcfn and with-open from ffi.clj, so
  ;; its JVM needs JDK 25 or newer. nbb uses the defmacros in this file when
  ;; it interprets it. The compiler emits nothing for a defmacro here, so a
  ;; compiled build that hands the namespace to SCI, as nbb does with its
  ;; built-in module, makes the two macros from defcfn-form and
  ;; with-open-form.
  (:require-macros [babashka.ffi])
  (:require [clojure.string :as str]))

;; getBuiltinModule loads node:ffi in both CommonJS and ESM.
(def ^:private ^js nffi (js/process.getBuiltinModule "node:ffi"))

(when-not nffi
  (throw (ex-info "babashka.ffi: needs Node.js 26.1 or newer, node:ffi is missing"
                  {:node (.-version js/process)})))
(def ^:private ^js node-fs (js/process.getBuiltinModule "node:fs"))

;; Default externs preserve the close field name in advanced builds.
(deftype Arena [kind ^:mutable closed ^:mutable bufs ^:mutable cleanups ^:mutable close])

;; -- pointers -----------------------------------------------------------------

;; keep retains the allocation Buffer or callback function.
(deftype Pointer [addr size scope keep]
  Object
  (toString [_] (str "pointer " addr " size " size)))

(def ^:private big-zero (js/BigInt 0))

(defn- bigint? [x]
  (identical? js/BigInt (type x)))

(defn- from-big
  "Returns a number for a safe integer, otherwise the bigint."
  [b]
  (if (and (<= b js/Number.MAX_SAFE_INTEGER) (>= b js/Number.MIN_SAFE_INTEGER))
    (js/Number b)
    b))

(defn- live? [^Pointer p]
  (let [^Arena scope (.-scope p)]
    (not (and scope (.-closed scope)))))

(defn- pointer-ex [^Pointer p]
  (ex-info (if (instance? Pointer p)
             ;; C can access released memory through a closed arena's pointer.
             (str "babashka.ffi: the pointer at address " (.-addr p)
                  " belongs to a closed arena")
             (str "babashka.ffi: expected a pointer, got " (pr-str p)
                  ". Wrap a raw address with (ffi/segment addr)"))
           {:value p}))

(defn- ^Pointer as-pointer [p]
  (if (and (instance? Pointer p) (live? p)) p (throw (pointer-ex p))))

(defn- pointer-address
  "Returns the native address of p as a bigint. Treats nil as the NULL pointer."
  [p]
  (if (nil? p) big-zero (.-addr (as-pointer p))))

(defn- ^Pointer accessible
  "Returns p when it is a live pointer with a nonzero size."
  [p]
  (let [p (as-pointer p)]
    (when (zero? (.-size p))
      (throw (ex-info (str "babashka.ffi: the pointer at address " (.-addr p)
                           " has size 0; give it a size with reinterpret")
                      {:pointer p})))
    p))

(defn- check-bounds [^Pointer p off n]
  (when (or (neg? off) (neg? n) (> (+ off n) (.-size p)))
    (throw (ex-info (str "babashka.ffi: out of bounds: " n " bytes at offset " off
                         " of a pointer with size " (.-size p))
                    {:pointer p :offset off :bytes n}))))

(defn- to-big
  "Converts a number, bigint, nil or pointer to a bigint."
  [a]
  (cond (bigint? a) a
        (number? a) (js/BigInt (js/Math.trunc a))
        (nil? a) big-zero
        (instance? Pointer a) (pointer-address a)
        :else (throw (ex-info (str "babashka.ffi: expected an integer, got " (pr-str a))
                              {:value a}))))

(defn segment
  "Returns a pointer to addr. The default size is zero.
  A specified nonzero size enables bounds checks.

  CAUTION: Keep addr before size. A transposed call can stop the process at
  the first read."
  ([addr] (segment addr 0))
  ([addr size] (Pointer. (js/BigInt.asUintN 64 (to-big addr)) size nil nil)))

(defn- on-close [^Arena arena f]
  (when (.-closed arena)
    (throw (ex-info "babashka.ffi: the arena is closed" {:arena arena})))
  (.push (.-cleanups arena) f))

(defn reinterpret
  "Returns a view of pointer seg with byte size size.

  Without an arena, the view retains seg's lifetime.

  With an arena, the view is valid only while that arena is open. A read after
  the arena closes throws. The arena calls the optional cleanup function with
  the view when it closes. Use this function for a C library deallocator.

  CAUTION: Give the actual size. The runtime cannot know if this size is
  correct. A larger size permits out-of-bounds reads.

  CAUTION: If the arena is closed, do not pass the view to C. C can access the
  released memory."
  ([seg size]
   (let [p (as-pointer seg)]
     (Pointer. (.-addr p) size (.-scope p) (.-keep p))))
  ([seg size arena] (reinterpret seg size arena nil))
  ([seg size arena cleanup]
   (let [p (as-pointer seg)
         view (Pointer. (.-addr p) size arena (.-keep p))]
     (on-close arena (fn [] (when cleanup (cleanup (Pointer. (.-addr p) size nil (.-keep p))))))
     view)))

(declare sizeof alignof)

(defn slice
  "Returns a slice of seg at byte offset. By default, the slice ends with seg.
  len is an integer byte count, a type keyword, or a layout. To select one
  struct from an array:

      (slice arr (* i (sizeof point)) point)

  CAUTION: Keep offset before len. A transposed call throws only if the result
  does not fit in seg."
  ([seg offset]
   (let [p (as-pointer seg)]
     (slice p offset (- (.-size p) offset))))
  ([seg offset len]
   (let [p (as-pointer seg)
         n (if (integer? len) len (sizeof len))]
     (check-bounds p offset n)
     (Pointer. (+ (.-addr p) (js/BigInt offset)) n (.-scope p) (.-keep p)))))

(defn address
  "Returns the native address of pointer p: a number when it is a safe
  integer, else a bigint."
  [p]
  (from-big (.-addr (as-pointer p))))

(defn size
  "Returns the size of pointer p in bytes. A pointer that C returned has
  size 0."
  [p]
  (.-size (as-pointer p)))

(defn pointer?
  "Returns true when x is a pointer whose arena is open."
  [x]
  (and (instance? Pointer x) (live? x)))

(def null
  "The NULL pointer."
  (Pointer. big-zero 0 nil nil))

(defn null?
  "Returns true for a NULL pointer. Returns false for all other pointers."
  [p]
  (= big-zero (.-addr (as-pointer p))))

(defn- string-at [addr]
  (.toString nffi addr))

(defn ptr->string
  "Returns the NUL-terminated UTF-8 string at p. Returns nil for a NULL
  pointer.

  A pointer returned by C has no size, so the read runs to the first NUL
  byte. This is what a :string return type does.

  limit is a maximum byte count. If p has a nonzero size, the read is also
  bounded by that size. Throws if no NUL byte occurs within these bounds.

  CAUTION: Without a limit, ptr->string can read past a buffer that has no
  NUL byte. This can stop the process."
  ([p]
   (let [p (as-pointer p)]
     (cond (= big-zero (.-addr p)) nil
           (zero? (.-size p)) (string-at (.-addr p))
           :else (ptr->string p (.-size p)))))
  ([p limit]
   (let [p (as-pointer p)
         size (.-size p)
         ;; a limit narrows, it never widens
         limit (if (zero? size) limit (min limit size))]
     (when-not (= big-zero (.-addr p))
       (let [buf (.toBuffer nffi (.-addr p) limit false)
             n (.indexOf buf 0)]
         (when (neg? n)
           (throw (ex-info (str "babashka.ffi: no NUL byte in the first " limit
                                " bytes at address " (.-addr p))
                           {:limit limit})))
         (.toString buf "utf8" 0 n))))))

;; -- argument and return conversion --------------------------------------------

;; Coerce arguments to the numeric types and ranges node:ffi accepts.

(defn- to-number [a]
  (cond (number? a) a
        (bigint? a) (js/Number a)
        (nil? a) 0
        :else (throw (ex-info (str "babashka.ffi: expected a number, got " (pr-str a))
                              {:value a}))))

(defn- to-int32 [a]
  (cond (number? a) (bit-or a 0)
        (bigint? a) (js/Number (js/BigInt.asIntN 32 a))
        :else (bit-or (to-number a) 0)))

(def ^:private arg-coercer
  (let [i32 to-int32
        u32 (fn [a] (unsigned-bit-shift-right (to-int32 a) 0))
        i16 (fn [a] (bit-shift-right (bit-shift-left (to-int32 a) 16) 16))
        u16 (fn [a] (bit-and (to-int32 a) 0xFFFF))
        i8 (fn [a] (bit-shift-right (bit-shift-left (to-int32 a) 24) 24))
        u8 (fn [a] (bit-and (to-int32 a) 0xFF))
        i64 (fn [a] (js/BigInt.asIntN 64 (to-big a)))
        u64 (fn [a] (js/BigInt.asUintN 64 (to-big a)))]
    {:int i32 :int32 i32 :uint u32 :uint32 u32
     :int16 i16 :uint16 u16
     :int8 i8 :byte i8 :char i8 :uint8 u8
     :long i64 :int64 i64 :ssize_t i64
     :ulong u64 :uint64 u64 :size_t u64
     :double to-number :float to-number
     :bool (fn [a] (if a 1 0))
     :pointer pointer-address
     ;; node:ffi copies a string to a C string for the call
     :string (fn [a] (if (string? a) a (pointer-address a)))}))

(def ^:private ret-converter
  {:long from-big :int64 from-big :ssize_t from-big
   :ulong from-big :uint64 from-big :size_t from-big
   :bool (fn [r] (not (zero? r)))
   :pointer (fn [r] (Pointer. r 0 nil nil))
   :string string-at
   :void (fn [_] nil)})

(def ^:private node-type
  {:void "void"
   :int "int32" :int32 "int32" :uint "uint32" :uint32 "uint32"
   :int16 "int16" :uint16 "uint16"
   :int8 "int8" :byte "int8" :char "int8" :uint8 "uint8" :bool "uint8"
   :long "int64" :int64 "int64" :ssize_t "int64"
   :ulong "uint64" :uint64 "uint64" :size_t "uint64"
   :double "float64" :float "float32"
   :pointer "pointer" :string "pointer"})

(defn- check-type [t]
  (when-not (contains? node-type t)
    (throw (ex-info (str "babashka.ffi: unknown type " t) {:type t}))))

(defn- signature [argtypes rettype]
  #js {:arguments (to-array (map node-type argtypes))
       :return (node-type rettype)})

;; -- libraries ----------------------------------------------------------------

(def ^:private libraries (atom []))

(defn- os-key []
  (case js/process.platform
    "darwin" :mac
    "win32" :windows
    :linux))

(defn- search-dirs
  "Directories probed for bare library names after the system's own dlopen
  search fails."
  []
  (case (os-key)
    :mac ["/opt/homebrew/lib" "/usr/local/lib" "/opt/local/lib" "/usr/lib"]
    :windows []
    (concat
     (when-let [p (unchecked-get js/process.env "LD_LIBRARY_PATH")]
       (remove str/blank? (str/split p #":")))
     (let [multiarch (if (= "arm64" js/process.arch)
                       "aarch64-linux-gnu"
                       "x86_64-linux-gnu")]
       ["/usr/local/lib"
        "/usr/lib64"
        "/usr/lib"
        (str "/usr/lib/" multiarch)
        "/lib64"
        "/lib"
        (str "/lib/" multiarch)]))))

(def ^:private last-lookup-error (volatile! nil))

(defn- try-open [path]
  (try (new (.-DynamicLibrary nffi) path)
       (catch :default e
         (vreset! last-lookup-error e)
         nil)))

(defn- lookup-one
  "Tries path, then common installation directories for a bare name.
  Returns a map with :path and :lookup, or nil when not found."
  [path]
  (or (when-let [lk (try-open path)]
        {:path path :lookup lk})
      (when-not (str/includes? path "/")
        (some (fn [dir]
                (let [p (str dir "/" path)]
                  (when-let [lk (try-open p)]
                    {:path p :lookup lk})))
              (search-dirs)))))

(defn load-library
  "Loads a shared library and adds it to the symbol search.

  Use load-system-library for file names that follow platform conventions.

  lib can be a path, a vector of candidates, or a map of operating systems to
  candidates. The function tries vector entries in order. An operating-system
  map uses the keys :mac, :linux, and :windows:

      (ffi/load-library
        {:mac [\"/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib\"
               \"/usr/local/opt/openssl@3/lib/libcrypto.3.dylib\"]
         :linux \"libcrypto.so.3\"})

  :darwin is an alias for :mac. For a bare name, the function also searches
  common installation directories. Returns a library map whose :path value
  identifies the loaded candidate. The map can be the first argument to cfn.
  In that form, cfn searches only this library."
  [lib]
  (let [paths (cond
                (map? lib)
                (let [v (or (get lib (os-key))
                            (when (= :mac (os-key)) (get lib :darwin))
                            (throw (ex-info (str "babashka.ffi: no library for OS " (os-key))
                                            {:libs lib})))]
                  (mapv str (if (vector? v) v [v])))
                (vector? lib) (mapv str lib)
                :else [(str lib)])
        m (or (some lookup-one paths)
              (throw (ex-info (str "babashka.ffi: cannot load library: "
                                   (str/join ", " paths)
                                   " (bare names also searched in "
                                   (pr-str (vec (search-dirs))) ")")
                              {:library lib}
                              @last-lookup-error)))]
    (swap! libraries conj (:lookup m))
    m))

(defn load-system-library
  "Loads a shared library by its short name. For example, \"z\" selects
  libz.dylib, libz.so, or z.dll. On Linux, the search also includes versioned
  names such as libz.so.1. Returns the same library map as load-library."
  [name]
  (case (os-key)
    :mac (load-library (str "lib" name ".dylib"))
    :windows (load-library (str name ".dll"))
    (let [base (str "lib" name ".so")]
      (or (try (load-library base) (catch :default _ nil))
          ;; glob lib<name>.so.* in the search dirs
          (when-let [m (some (fn [dir]
                               (let [;; newest soname first, numerically:
                                     ;; libz.so.10 beats libz.so.9
                                     vkey (fn [f]
                                            (mapv #(or (parse-long %) -1)
                                                  (rest (str/split (subs f (count base)) #"\."))))
                                     newest-first (fn [x y]
                                                    (let [a (vkey x) b (vkey y)
                                                          n (max (count a) (count b))
                                                          pad #(into % (repeat (- n (count %)) -1))]
                                                      (compare (pad b) (pad a))))
                                     cands (when (.existsSync node-fs dir)
                                             (->> (.readdirSync node-fs dir)
                                                  (filter #(str/starts-with? % (str base ".")))
                                                  (sort newest-first)))]
                                 (some (fn [c]
                                         (let [p (str dir "/" c)]
                                           (when-let [lk (try-open p)]
                                             {:path p :lookup lk})))
                                       cands)))
                             (search-dirs))]
            (swap! libraries conj (:lookup m))
            m)
          (throw (ex-info (str "babashka.ffi: cannot find library " name
                               " (tried " base " and " base ".* in "
                               (pr-str (vec (search-dirs))) ")")
                          {:library name}
                          @last-lookup-error))))))

;; Use the C runtime on Windows and the process lookup elsewhere.
(def ^:private default-library
  (delay (new (.-DynamicLibrary nffi) (when (= :windows (os-key)) "ucrtbase.dll"))))

(defn- resolve-library
  "Returns the DynamicLibrary for a :library value. The value can be a library
  map or a function that returns one. It can also be a delay, atom or var
  that holds a library map."
  [lib]
  (let [lib (cond (map? lib) lib
                  (fn? lib) (lib)
                  (or (delay? lib) (var? lib) (instance? Atom lib)) @lib
                  :else lib)
        lookup (when (map? lib) (:lookup lib))]
    (if (instance? (.-DynamicLibrary nffi) lookup)
      lookup
      (throw (ex-info (str "babashka.ffi: :library must be a library map, a function that returns one, or a delay, atom or var that holds one, got "
                           (pr-str lib))
                      {:library lib})))))

(defn- lookups [lib]
  (if (nil? lib)
    (conj @libraries @default-library)
    [(resolve-library lib)]))

(defn find-symbol
  "Finds sym and returns a pointer to it. Returns nil for an unknown symbol.

  A library value limits the search to one library and its dependencies.
  Without a library value, find-symbol searches all loaded libraries. Then it
  searches the default system lookup."
  ([sym] (find-symbol nil sym))
  ([lib sym]
   (some (fn [^js l]
           (try (Pointer. (.getSymbol l (str sym)) 0 nil nil)
                (catch :default _ nil)))
         (lookups lib))))

;; -- foreign functions --------------------------------------------------------

(def ^:private layout-kinds #{:struct :array :union})

(defn- layout-vector? [t]
  (and (vector? t) (contains? layout-kinds (first t))))

(defn- unsupported-ex [sym argtypes rettype why]
  (ex-info (str "babashka.ffi: unsupported signature on Node.js: " sym " "
                (pr-str argtypes) " -> " (pr-str rettype) ". " why)
           {:symbol sym :argtypes argtypes :rettype rettype}))

(defn- native-function [lib sym argtypes rettype]
  (let [sig (signature argtypes rettype)]
    (or (some (fn [^js l]
                (when (try (.getSymbol l sym) (catch :default _ nil))
                  (.getFunction l sym sig)))
              (lookups lib))
        (throw (ex-info (str "babashka.ffi: symbol not found: " sym) {:symbol sym})))))

(defn cfn
  "Creates a function that calls the C function sym. sym is a C symbol name.
  argtypes is a vector of type keywords. rettype is a type keyword.

  A library value limits the search to one library and its dependencies.
  Without a library value, cfn searches all loaded libraries. Then it searches
  the default system lookup. The first call resolves the symbol. You can
  create the binding before you load its library.

  node:ffi does not pass a struct by value, make a variadic call or call
  through a function pointer. cfn throws for each when the binding is made."
  ([sym argtypes rettype] (cfn nil sym argtypes rettype))
  ([lib sym argtypes rettype]
   (when (instance? Pointer sym)
     (throw (unsupported-ex (str sym) argtypes rettype
                            "node:ffi calls a function by name and cannot call a function pointer")))
   (when-not (string? sym)
     (throw (ex-info (str "babashka.ffi: C symbol must be a string: " (pr-str sym))
                     {:sym sym})))
   (doseq [t argtypes]
     (when (= :void t)
       (throw (ex-info (str "babashka.ffi: :void is not an argument type: " (pr-str argtypes))
                       {:argtypes argtypes})))
     ;; A layout kind here usually means that argtypes and rettype are transposed.
     (when (contains? layout-kinds t)
       (throw (ex-info (str "babashka.ffi: " t " is a layout kind, not an argument type. "
                            "A layout goes in one type position as " (pr-str [t '...])
                            ". Make sure that argtypes and the return type are in the correct order: "
                            (pr-str argtypes))
                       {:argtypes argtypes :rettype rettype}))))
   (doseq [t (cons rettype argtypes)]
     (when (and (vector? t) (= :array (first t)))
       (throw (ex-info (str "babashka.ffi: an array is not a C argument or return type: " (pr-str t)
                            ". C passes an array as a pointer, so declare :pointer.")
                       {:argtypes argtypes :rettype rettype})))
     (when (vector? t)
       (throw (unsupported-ex sym argtypes rettype
                              (str "node:ffi does not pass a " (name (first t))
                                   " by value. Declare :pointer and pass the layout through memory")))))
   (when (some #(= :& %) argtypes)
     (throw (unsupported-ex sym argtypes rettype "node:ffi does not make variadic calls")))
   (run! check-type argtypes)
   (check-type rettype)
   (let [n (count argtypes)
         coercers (to-array (map arg-coercer argtypes))
         [c0 c1 c2 c3] coercers
         convert (or (ret-converter rettype) identity)
         resolved (volatile! nil)
         native (fn [] (or @resolved (vreset! resolved (native-function lib sym argtypes rettype))))
         arity-error (fn [got]
                       (throw (ex-info (str "babashka.ffi: " sym " expects " n " args, got " got)
                                       {:symbol sym})))
         general (fn [& args]
                   (let [arr (to-array args)]
                     (when-not (== n (alength arr))
                       (arity-error (alength arr)))
                     (dotimes [i n]
                       (aset arr i ((aget coercers i) (aget arr i))))
                     (convert (.apply (native) nil arr))))]
     (with-meta
       ;; Fixed arities avoid argument arrays. The extra parameter detects excess arguments.
       (case n
         0 (fn [x]
             (if (undefined? x) (convert ((native))) (arity-error "more than 0")))
         1 (fn [a x]
             (if (and (undefined? x) (not (undefined? a)))
               (convert ((native) (c0 a)))
               (arity-error (if (undefined? x) 0 "more than 1"))))
         2 (fn [a b x]
             (if (and (undefined? x) (not (undefined? b)))
               (convert ((native) (c0 a) (c1 b)))
               (arity-error (if (undefined? x) "fewer than 2" "more than 2"))))
         3 (fn [a b c x]
             (if (and (undefined? x) (not (undefined? c)))
               (convert ((native) (c0 a) (c1 b) (c2 c)))
               (arity-error (if (undefined? x) "fewer than 3" "more than 3"))))
         4 (fn [a b c d x]
             (if (and (undefined? x) (not (undefined? d)))
               (convert ((native) (c0 a) (c1 b) (c2 c) (c3 d)))
               (arity-error (if (undefined? x) "fewer than 4" "more than 4"))))
         general)
       {:babashka.ffi/backend :node}))))

(defn ^:no-doc defcfn-form
  "The form defcfn expands to. A function, so a compiled build has it: see
  the ns form."
  [name args]
  (when (< (count args) 3)
    (throw (ex-info "babashka.ffi: defcfn needs a C symbol, argtypes and a return type"
                    {:name name})))
  (let [anchor (first (keep-indexed (fn [i a]
                                      (when (and (vector? a)
                                                 (not (layout-vector? a)))
                                        i))
                                    args))
        [prefix sym argtypes rettype wrapper]
        (if (and anchor (pos? anchor))
          [(take (dec anchor) args)
           (nth args (dec anchor))
           (nth args anchor)
           (when (> (count args) (inc anchor)) (nth args (inc anchor)))
           (drop (+ anchor 2) args)]
          [(drop-last 3 args)
           (first (take-last 3 args))
           (second (take-last 3 args))
           (last args)
           nil])
        docstring (first (filter string? prefix))
        attr-map (first (filter map? prefix))]
    (when (nil? rettype)
      (throw (ex-info "babashka.ffi: defcfn needs a C symbol, argtypes and a return type"
                      {:name name})))
    (when-not (and (<= (count prefix) 2)
                   (<= (count (filter string? prefix)) 1)
                   (<= (count (filter map? prefix)) 1)
                   (every? #(or (string? %) (map? %)) prefix))
      (throw (ex-info "babashka.ffi: defcfn accepts at most one docstring and one attribute map before the C symbol. The wrapper form needs a literal argtypes vector"
                      {:name name})))
    (when (and (seq wrapper)
               (not (and (symbol? (first wrapper)) (next wrapper))))
      (throw (ex-info "babashka.ffi: defcfn needs a raw binding name and a fn tail after the return type"
                      {:name name})))
    (when (= (first wrapper) name)
      (throw (ex-info "babashka.ffi: the raw binding name must differ from the defcfn name"
                      {:name name})))
    (let [native-fn (first wrapper)
          fn-tail (next wrapper)
          name (with-meta name (cond-> (meta name)
                                 attr-map (merge (dissoc attr-map :library))
                                 docstring (assoc :doc docstring)))
          binding-form `(cfn ~(:library attr-map) ~sym ~argtypes ~rettype)]
      (if native-fn
        `(def ~name
           (let [~native-fn ~binding-form]
             (fn ~name ~@fn-tail)))
        `(def ~name ~binding-form)))))

(defmacro defcfn
  "Defines name as a C function binding created by cfn:

      (defcfn sqlite3-open \"sqlite3_open\" [:string :pointer] :int)

      (defcfn sqlite3-open
        \"Opens the database at path, storing the handle in out-param pp.\"
        \"sqlite3_open\" [:string :pointer] :int)

  An optional docstring and attribute map can precede the C symbol. The final
  three arguments are the C symbol, argument types, and return type. defcfn
  preserves all metadata on name. This metadata includes ^:private.

  The :library key in the attribute map selects a library for cfn:

      (def sqlite (delay (ffi/load-library (extract-bundled-library!))))
      (defcfn sqlite3-open {:library sqlite} \"sqlite3_open\"
        [:string :pointer] :int)

  The value can be a library map or a function that returns one. It can also
  be an IDeref object that holds a library map.

  Without :library, a binding searches all loaded libraries. Then it searches
  the default system lookup. A system library with the same name can supply
  the symbol.

  The wrapper form binds the raw C function to a local name and defines name
  as the wrapper:

      (defcfn open-db
        \"sqlite3_open_v2\" [:string :pointer :int :string] :int
        open-native
        [filename flags]
        (ffi/with-open [arena (ffi/confined-arena)]
          (let [pdb (ffi/alloc arena :pointer)
                code (open-native filename pdb flags nil)]
            (if (zero? code)
              (ffi/read pdb :pointer)
              (throw (ex-info \"open failed\" {:code code}))))))

  The symbol after the return type names the raw binding. Only the wrapper
  body can use this name. The forms after the raw name are a normal fn tail.
  The wrapper can have multiple arities. Its argument lists can differ from
  the C function. The raw name does not enter the namespace. The wrapper
  form needs a literal argtypes vector. Only the plain form accepts an
  argtypes expression."
  [name & args]
  (defcfn-form name args))

;; -- arenas -------------------------------------------------------------------

;; Arenas retain allocation Buffers until close.

(defn- arena [kind closeable?]
  (let [a (Arena. (name kind) false #js [] #js [] nil)]
    (set! (.-close a)
          (fn []
            (when-not closeable?
              (throw (ex-info (str "babashka.ffi: cannot close the " (name kind) " arena") {})))
            (when (.-closed a)
              (throw (ex-info "babashka.ffi: the arena is already closed" {})))
            (set! (.-closed a) true)
            (let [cleanups (.-cleanups a)]
              (set! (.-cleanups a) #js [])
              (set! (.-bufs a) #js [])
              (run! (fn [f] (f)) cleanups))
            nil))
    a))

(defn confined-arena
  "Returns an arena for one thread.
  Create this arena in ffi/with-open to release its memory."
  []
  (arena :confined true))

(defn shared-arena
  "Returns an arena. On Node.js, behaves like confined-arena.
  Create this arena in ffi/with-open to release its memory."
  []
  (arena :shared true))

(defn auto-arena
  "Returns an arena that the garbage collector manages. It releases an
  allocation when no pointer to it is reachable. You cannot close it."
  []
  (arena :auto false))

(def ^:private the-global-arena (delay (arena :global false)))

(defn global-arena
  "Returns the global arena. Its memory exists until the process stops.
  You cannot close this arena."
  []
  @the-global-arena)

(defn ^:no-doc with-open-form
  "The form with-open expands to. A function, so a compiled build has it."
  [bindings body]
  (if (zero? (count bindings))
    `(do ~@body)
    `(let [~(nth bindings 0) ~(nth bindings 1)]
       (try
         (babashka.ffi/with-open ~(subvec bindings 2) ~@body)
         (finally (.close ~(nth bindings 0)))))))

(defmacro with-open
  "Evaluates body with each name bound to its value. Calls .close on each
  value in reverse order when body returns or throws.

  CAUTION: On Node.js the arena closes when body returns. Do not return a
  promise that still uses it."
  [bindings & body]
  (with-open-form bindings body))

(defn- arena? [x]
  (instance? Arena x))

(defn- size-and-alignment [n]
  (cond (integer? n) [n 16]
        (keyword? n) (let [size (sizeof n)] [size (max 1 (min 8 size))])
        (layout-vector? n) [(sizeof n) (alignof n)]
        :else (throw (ex-info (str "babashka.ffi: alloc takes an integer byte count, a type keyword or a layout, got " (pr-str n))
                              {:n n}))))

(defn alloc
  "Allocates zeroed native memory in arena and returns its pointer.
  n is an integer byte count, a type keyword, or a struct layout.

  A type or layout uses natural alignment. An integer byte count uses
  alignment 16. Specify an alignment, a power of two, to override this value.

  There is no unscoped form. If C allocates the memory, bind its allocator with
  cfn. Release the result with the matching C deallocator.

  CAUTION: Do not close the arena while C uses its memory."
  ([arena n]
   (let [[size align] (size-and-alignment n)]
     (alloc arena size align)))
  ([^Arena arena n alignment]
   (when-not (arena? arena)
     (throw (ex-info (str "babashka.ffi: alloc takes an arena first, got " (pr-str arena))
                     {:arena arena})))
   (when (.-closed arena)
     (throw (ex-info "babashka.ffi: the arena is closed" {:arena arena})))
   (when-not (and (integer? alignment) (pos? alignment)
                  (zero? (bit-and alignment (dec alignment))))
     (throw (ex-info (str "babashka.ffi: alloc takes a power of two as alignment, got " (pr-str alignment))
                     {:alignment alignment})))
   (let [size (first (size-and-alignment n))
         buf (js/Buffer.alloc (+ (max 1 size) alignment))
         base (.getRawPointer nffi buf)
         low (js/Number (js/BigInt.asUintN 30 base))
         pad (bit-and (- alignment (bit-and low (dec alignment))) (dec alignment))]
     (when-not (= "auto" (.-kind arena))
       (.push (.-bufs arena) buf))
     (Pointer. (+ base (js/BigInt pad)) size arena buf))))

(defn string->ptr
  "Copies s into arena as a NUL-terminated UTF-8 string and returns its
  pointer. The arena controls the lifetime of the string."
  [arena s]
  (let [n (inc (js/Buffer.byteLength s "utf8"))
        p (alloc arena n 1)]
    (.exportString nffi s (.-addr p) n)
    p))

;; -- layouts ------------------------------------------------------------------

(def ^:private sizes
  {:int 4 :uint 4 :int32 4 :uint32 4 :float 4
   :long 8 :ulong 8 :int64 8 :uint64 8 :size_t 8 :ssize_t 8
   :pointer 8 :string 8 :double 8
   :int16 2 :uint16 2 :int8 1 :uint8 1 :byte 1 :char 1 :bool 1})

(defn- align-up [n a]
  (* a (quot (+ n (dec a)) a)))

(defn- check-members [kind t]
  (let [members (second t)
        what (if (= :struct kind) "field" "member")]
    (when-not (= 2 (count t))
      (throw (ex-info (str "babashka.ffi: a " (name kind) " layout is [" kind " "
                           (if (= :struct kind) "fields" "members") "], got " (pr-str t))
                      {:layout t})))
    (when-not (and (vector? members)
                   (seq members)
                   (every? #(and (vector? %) (= 2 (count %)) (keyword? (first %)))
                           members))
      (throw (ex-info (str "babashka.ffi: " kind " needs a non-empty vector of [name type] pairs, with keyword names: "
                           (pr-str t))
                      {:layout t})))
    (when-not (apply distinct? (map first members))
      (throw (ex-info (str "babashka.ffi: a " (name kind) " layout names a " what " twice: " (pr-str t))
                      {:layout t})))))

(def ^:private layout-cache (atom {}))
(def ^:private cache-limit 256)

(declare layout-of)

(defn- layout-of* [t]
  (cond
    (and (vector? t) (= :struct (first t)))
    (do (check-members :struct t)
        (let [fields (mapv (fn [[nm ty]] (assoc (layout-of ty) :name nm)) (second t))
              align (reduce max 1 (map :align fields))
              [fields end] (reduce (fn [[fs off] f]
                                     (let [off (align-up off (:align f))]
                                       [(conj fs (assoc f :offset off))
                                        (+ off (:size f))]))
                                   [[] 0] fields)]
          {:type :struct :fields fields :align align :size (align-up end align)}))

    (and (vector? t) (= :union (first t)))
    (do (check-members :union t)
        ;; every member starts at offset 0; the union is as large as its
        ;; largest member, rounded up to its strictest alignment
        (let [fields (mapv (fn [[nm ty]] (assoc (layout-of ty) :name nm :offset 0)) (second t))
              align (reduce max 1 (map :align fields))
              size (reduce max 0 (map :size fields))]
          {:type :union :fields fields :align align :size (align-up size align)}))

    (and (vector? t) (= :array (first t)))
    (let [[_ elem n] t]
      (when-not (= 3 (count t))
        (throw (ex-info (str "babashka.ffi: an array layout is [:array elem n], got " (pr-str t))
                        {:layout t})))
      (when-not (and (integer? n) (pos? n))
        (throw (ex-info (str "babashka.ffi: :array needs a positive element count, got " (pr-str t))
                        {:layout t})))
      (when (= :void elem)
        (throw (ex-info (str "babashka.ffi: :void is not an element type: " (pr-str t))
                        {:layout t})))
      (let [el (layout-of elem)]
        {:type :array :elem el :count n
         :align (:align el) :size (* n (:size el))}))

    (keyword? t)
    (if-let [size (sizes t)]
      {:type t :size size :align size}
      (throw (ex-info (str "babashka.ffi: unknown type " t) {:type t})))

    (vector? t)
    (throw (ex-info (str "babashka.ffi: unknown layout kind " (pr-str (first t)) " in " (pr-str t))
                    {:layout t}))

    :else
    (throw (ex-info (str "babashka.ffi: not a type keyword or a layout: " (pr-str t))
                    {:layout t}))))

(defn- layout-of
  "Resolves a type keyword or layout. Returns a map with :type, :size,
  and :align. A struct or union also has :fields. Each field has a :name
  and :offset. Fields keep declaration order and use natural C alignment.

  A layout is a vector that starts with its kind, such as [:struct fields].
  A keyword is a primitive type."
  [t]
  (if (keyword? t)
    (layout-of* t)
    (or (get @layout-cache t)
        (let [v (layout-of* t)]
          (swap! layout-cache
                 (fn [m] (if (<= cache-limit (count m)) m (assoc m t v))))
          v))))

(defn sizeof
  "Returns the size of a type keyword or struct layout, in bytes. The size
  of a struct includes padding."
  [t]
  (:size (layout-of t)))

(defn alignof
  "Returns the alignment, in bytes, of type keyword t or of a struct layout."
  [t]
  (:align (layout-of t)))

;; -- scalar access ------------------------------------------------------------

;; A getter takes an address and a byte offset. A setter takes a value that
;; arg-coercer already converted for the type.
(def ^:private scalar-get
  (let [i64 (fn [a o] (from-big (.getInt64 nffi a o)))
        u64 (fn [a o] (from-big (.getUint64 nffi a o)))
        i8 (fn [a o] (.getInt8 nffi a o))]
    {:int (.-getInt32 nffi) :int32 (.-getInt32 nffi)
     :uint (.-getUint32 nffi) :uint32 (.-getUint32 nffi)
     :int16 (.-getInt16 nffi) :uint16 (.-getUint16 nffi)
     :int8 i8 :byte i8 :char i8 :uint8 (.-getUint8 nffi)
     :bool (fn [a o] (not (zero? (.getUint8 nffi a o))))
     :long i64 :int64 i64 :ssize_t i64
     :ulong u64 :uint64 u64 :size_t u64
     :double (.-getFloat64 nffi) :float (.-getFloat32 nffi)
     :pointer (fn [a o] (Pointer. (.getUint64 nffi a o) 0 nil nil))
     :string (fn [a o] (string-at (.getUint64 nffi a o)))}))

(def ^:private scalar-set
  {:int (.-setInt32 nffi) :int32 (.-setInt32 nffi)
   :uint (.-setUint32 nffi) :uint32 (.-setUint32 nffi)
   :int16 (.-setInt16 nffi) :uint16 (.-setUint16 nffi)
   :int8 (.-setInt8 nffi) :byte (.-setInt8 nffi) :char (.-setInt8 nffi)
   :uint8 (.-setUint8 nffi) :bool (.-setUint8 nffi)
   :long (.-setInt64 nffi) :int64 (.-setInt64 nffi) :ssize_t (.-setInt64 nffi)
   :ulong (.-setUint64 nffi) :uint64 (.-setUint64 nffi) :size_t (.-setUint64 nffi)
   :double (.-setFloat64 nffi) :float (.-setFloat32 nffi)
   :pointer (.-setUint64 nffi)})

;; -- codecs -------------------------------------------------------------------

;; The caller checks pointer access and layout bounds.

(defn- at-path [path]
  (if (seq path) (str "at " (pr-str path) ", ") ""))

(defn- encoder
  ([lay offset] (encoder lay offset []))
  ([lay offset path]
   (let [t (:type lay)]
     (case t
       :struct
       (let [fields (:fields lay)
             c (count fields)
             names (mapv :name fields)
             encs (mapv (fn [f] (encoder f (+ offset (:offset f)) (conj path (:name f)))) fields)
             field-error
             (fn [v]
               (let [missing (when (map? v) (remove #(contains? v %) names))
                     unknown (when (map? v) (remove (set names) (keys v)))]
                 (throw (ex-info (str "babashka.ffi: " (at-path path) "struct value "
                                      (cond (not (map? v)) (str "needs a map of " (pr-str names))
                                            (seq missing) (str "misses field " (pr-str (first missing)))
                                            (seq unknown) (str "has unknown field " (pr-str (first unknown)))
                                            :else (str "needs a map of " (pr-str names)))
                                      ", got " (pr-str v))
                                 {:value v :fields names :path path}))))]
         (fn [base v]
           (when-not (and (map? v) (= (count v) c))
             (field-error v))
           (dotimes [i c]
             (let [x (get v (nth names i) ::missing)]
               (when (= ::missing x)
                 (field-error v))
               ((nth encs i) base x)))))

       :string
       (fn [base v]
         (when (string? v)
           ;; write takes no arena, so it cannot own the C string
           (throw (ex-info (str "babashka.ffi: " (at-path path) "a :string field holds a pointer to bytes"
                                " that outlive this write, so their lifetime is yours"
                                " to choose: (string->ptr arena " (pr-str v) ")")
                           {:value v :path path})))
         (.setUint64 nffi base offset (pointer-address v)))

       :union
       ;; a union value is a tagged pair, [member value]. See ADR 0005.
       (let [fields (:fields lay)
             names (mapv :name fields)
             encs (into {} (map (fn [f] [(:name f) (encoder f offset (conj path (:name f)))]) fields))
             member-error
             (fn [v]
               (throw (ex-info (str "babashka.ffi: " (at-path path) "union value "
                                    (if-not (and (vector? v) (= 2 (count v)))
                                      (str "is a pair [member value], with member one of " (pr-str names))
                                      (str "names unknown member " (pr-str (nth v 0)) "; give one of " (pr-str names)))
                                    ", got " (pr-str v))
                               {:value v :members names :path path})))]
         (fn [base v]
           (when-not (and (vector? v) (= 2 (count v)))
             (member-error v))
           (let [enc (get encs (nth v 0))]
             (when-not enc (member-error v))
             (enc base (nth v 1)))))

       :array
       (let [el (:elem lay)
             n (:count lay)
             sz (:size el)
             encs (mapv (fn [i] (encoder el (+ offset (* i sz)) (conj path i))) (range n))
             countable? (fn [v] (or (sequential? v) (array? v) (js/ArrayBuffer.isView v)))
             length-error
             (fn [v]
               (throw (ex-info (str "babashka.ffi: " (at-path path) "array value needs " n " elements, got "
                                    (if (countable? v) (count (seq v)) (pr-str v)))
                               {:value v :count n :path path})))]
         (fn [base v]
           (let [xs (when (countable? v) (vec v))]
             (when-not (= n (count xs))
               (length-error v))
             (dotimes [i n] ((nth encs i) base (nth xs i))))))

       ;; a scalar: a value it cannot take gets the place and the type
       (let [coerce (or (arg-coercer t)
                        (throw (ex-info (str "babashka.ffi: cannot write type " t) {:type t})))
             set-fn (scalar-set t)]
         (fn [base v]
           (let [x (try (when (and (not= :bool t) (not= :pointer t)
                                   (not (or (number? v) (bigint? v) (nil? v) (instance? Pointer v))))
                          (throw (ex-info "not a number" {})))
                        (coerce v)
                        (catch :default e
                          (throw (ex-info (str "babashka.ffi: " (at-path path) "a " t
                                               " field cannot take " (pr-str v))
                                          {:value v :type t :path path}
                                          e))))]
             (set-fn base offset x))))))))

(defn- decoder [lay offset]
  (case (:type lay)
    ;; Return union bytes for the caller to read as a member. See ADR 0005.
    :union
    (let [size (:size lay)]
      (fn [^Pointer p base]
        (Pointer. (+ base (js/BigInt offset)) size (.-scope p) (.-keep p))))

    :array
    (let [el (:elem lay)
          sz (:size el)
          decs (mapv (fn [i] (decoder el (+ offset (* i sz)))) (range (:count lay)))]
      (fn [p base] (mapv (fn [d] (d p base)) decs)))

    :struct
    (let [fields (:fields lay)
          names (mapv :name fields)
          decs (mapv (fn [f] (decoder f (+ offset (:offset f)))) fields)]
      (fn [p base]
        (zipmap names (map (fn [d] (d p base)) decs))))

    (let [get-fn (or (scalar-get (:type lay))
                     (throw (ex-info (str "babashka.ffi: cannot read type " (:type lay))
                                     {:type (:type lay)})))]
      (fn [_ base] (get-fn base offset)))))

(def ^:private codec-cache (atom {}))

(defn- cached-codec [kind lay]
  (let [k [kind lay]]
    (or (get @codec-cache k)
        (let [v (case kind
                  :decode (decoder lay 0)
                  :encode (encoder lay 0))]
          (swap! codec-cache
                 (fn [m] (if (<= cache-limit (count m)) m (assoc m k v))))
          v))))

;; extent is the end offset used for bounds checks. See ADR 0006.
(deftype Place [layout path decode encode extent]
  Object
  (toString [_] (str "place " (pr-str path) " in " (pr-str layout))))

(defn read
  "Reads a value of type t from p. The default byte offset is zero.

  t is a type keyword, a layout, or a place from `place`. A place is a
  member of a layout resolved once, so reading through it does no lookup.

  Checks the access against the size of p. Rejects a zero-size pointer.
  reinterpret specifies a valid size."
  ([p t] (read p t 0))
  ([p t offset]
   (let [p (accessible p)]
     (if-let [get-fn (when (keyword? t) (scalar-get t))]
       (do (check-bounds p offset (sizes t))
           (get-fn (.-addr p) offset))
       (cond
         (instance? Place t)
         (do (check-bounds p offset (.-extent t))
             ((.-decode t) p (+ (.-addr p) (js/BigInt offset))))
         (layout-vector? t)
         (let [lay (layout-of t)]
           (check-bounds p offset (:size lay))
           ((cached-codec :decode lay) p (+ (.-addr p) (js/BigInt offset))))
         :else
         (throw (ex-info (str "babashka.ffi: cannot read type " t) {:type t})))))))

(defn write
  "Writes v as type t to p. The default byte offset is zero. Returns nil.

  t is a type keyword, a layout, or a place from `place`. Through a place
  the member's type is known, so a union member needs no pair.

  Checks the access against the size of p. Rejects a zero-size pointer.
  reinterpret specifies a valid size."
  ([p t v] (write p t v 0))
  ([p t v offset]
   (let [p (accessible p)]
     (if-let [set-fn (when (keyword? t) (scalar-set t))]
       (do (check-bounds p offset (sizes t))
           (set-fn (.-addr p) offset ((arg-coercer t) v)))
       (cond
         (instance? Place t)
         (do (check-bounds p offset (.-extent t))
             ((.-encode t) (+ (.-addr p) (js/BigInt offset)) v))
         (layout-vector? t)
         (let [lay (layout-of t)]
           (check-bounds p offset (:size lay))
           ((cached-codec :encode lay) (+ (.-addr p) (js/BigInt offset)) v))
         :else
         (throw (ex-info (str "babashka.ffi: cannot write type " t) {:type t}))))
     nil)))

;; -- bulk access ---------------------------------------------------------------

(def ^:private array-carriers
  "Maps scalar types to typed array constructors for bulk copies."
  (let [i8 js/Int8Array i16 js/Int16Array i32 js/Int32Array i64 js/BigInt64Array]
    {:int8 i8 :uint8 i8 :byte i8 :char i8 :bool i8
     :int16 i16 :uint16 i16
     :int i32 :uint i32 :int32 i32 :uint32 i32
     :long i64 :ulong i64 :int64 i64 :uint64 i64 :size_t i64 :ssize_t i64 :pointer i64
     :float js/Float32Array :double js/Float64Array}))

(defn- array-carrier [t]
  (or (array-carriers t)
      (throw (ex-info (cond
                        (layout-vector? t)
                        (str "babashka.ffi: read-array and write-array copy scalars into a typed array;"
                             " for a layout use read and write with " (pr-str [:array t 'n]))
                        (= :string t)
                        "babashka.ffi: :string elements are pointers to bytes elsewhere, which a copy cannot follow; copy :pointer and read each"
                        :else
                        (str "babashka.ffi: cannot copy type " (pr-str t)))
                      {:type t}))))

(defn read-array
  "Copies n elements of type t from pointer p, at byte offset (default 0),
  into a new typed array. Returns the array.

  The copy uses memcpy. The type gives the element width and nothing else:
  :int, :uint and :int32 fill an Int32Array with the bits as they are. :long
  and the other eight-byte types fill a BigInt64Array, and :pointer fills a
  BigInt64Array of addresses. :byte, :char, :int8, :uint8 and :bool fill an
  Int8Array.

  For an array of structs, or for elements decoded the way read decodes
  them, use read with an [:array t n] layout."
  ([p t n] (read-array p t n 0))
  ([p t n offset]
   (let [ctor (array-carrier t)
         p (accessible p)
         bytes (* n (sizes t))]
     (check-bounds p offset bytes)
     (if (zero? n)
       (new ctor 0)
       (new ctor (.toArrayBuffer nffi (+ (.-addr p) (js/BigInt offset)) bytes true))))))

(defn write-array
  "Copies typed array arr into memory at pointer p, at byte offset (default
  0), as elements of type t. Returns nil.

  The copy is a memcpy, as in read-array, and the array must be the typed
  array for the type: an Int32Array for :int, a BigInt64Array for :long or
  :pointer, an Int8Array for :char."
  ([p t arr] (write-array p t arr 0))
  ([p t arr offset]
   (let [ctor (array-carrier t)
         p (accessible p)]
     (when-not (instance? ctor arr)
       (throw (ex-info (str "babashka.ffi: " t " needs " (.-name ctor)
                            ", got " (if (nil? arr) "nil" (.. arr -constructor -name)))
                       {:type t :array arr})))
     (check-bounds p offset (.-byteLength arr))
     (when (pos? (.-byteLength arr))
       (.exportArrayBufferView nffi arr (+ (.-addr p) (js/BigInt offset)) (.-byteLength arr)))
     nil)))

(defn copy
  "Copies bytes from pointer src to pointer dst. Without n, copies the byte
  size of src. dst must be at least that large. With n, copies n bytes.
  Returns nil.

  Use reinterpret to specify a size for pointers from C. To copy into the
  middle of dst, slice it first:

      (ffi/copy src (ffi/slice dst 16) n)

  Supports overlapping regions, as with memmove."
  ([src dst] (copy src dst (.-size (accessible src))))
  ([src dst n]
   (let [s (accessible src)
         d (accessible dst)]
     (check-bounds s 0 n)
     (check-bounds d 0 n)
     (when (pos? n)
       ;; through a copy of the source, so that overlapping regions are safe
       (.exportBuffer nffi (.toBuffer nffi (.-addr s) n true) (.-addr d) n))
     nil)))

(defn clone
  "Allocates a copy of pointer src in arena with the same size and returns
  the new pointer. Use reinterpret to specify a size for pointers from C."
  [arena src]
  (let [s (accessible src)
        d (alloc arena (.-size s))]
    (copy s d)
    d))

(defn byte-buffer
  "Returns a Buffer view of n bytes of native memory at pointer p. The Buffer
  and native memory share the same bytes.

  CAUTION: Do not use the Buffer after you release the native memory. An
  invalid memory access can stop the process."
  [p n]
  (let [p (accessible p)]
    (check-bounds p 0 n)
    (.toBuffer nffi (.-addr p) n false)))

;; -- one member of a layout ---------------------------------------------------

(defn- resolve-path
  "Walks resolved layout lay along path. Returns the offset of the place and
  the layout there. Throws for a path that names nothing."
  [lay path]
  (loop [lay lay off 0 done [] todo (seq path)]
    (if-not todo
      [off lay]
      (let [step (first todo)
            here (fn [] (if (seq done) (str " at " (pr-str done)) ""))]
        (case (:type lay)
          (:struct :union)
          (let [f (some #(when (= step (:name %)) %) (:fields lay))]
            (when-not (keyword? step)
              (throw (ex-info (str "babashka.ffi: " (pr-str step) " is not a member name" (here)
                                   "; the members are " (pr-str (mapv :name (:fields lay))))
                              {:path path :step step})))
            (when-not f
              (throw (ex-info (str "babashka.ffi: no member " (pr-str step) (here)
                                   "; the members are " (pr-str (mapv :name (:fields lay))))
                              {:path path :step step})))
            (recur f (+ off (:offset f)) (conj done step) (next todo)))
          :array
          (let [n (:count lay)]
            (when-not (and (integer? step) (<= 0 step) (< step n))
              (throw (ex-info (str "babashka.ffi: " (pr-str step) " is not an index into " n " elements" (here))
                              {:path path :step step :count n})))
            (recur (:elem lay) (+ off (* step (:size (:elem lay)))) (conj done step) (next todo)))
          (throw (ex-info (str "babashka.ffi: the path continues past " (pr-str (:type lay)) (here)
                               " with " (pr-str step) ", but a " (pr-str (:type lay)) " has no members")
                          {:path path :step step})))))))

(def ^:private place-cache (atom {}))

(defn place
  "Returns a place for read and write in layout t. path is a member name
  or a vector of member names and array indices. Without a path, returns
  a place for the whole layout.

      (def parent (place bone :parent))
      (read p parent)                          ;=> 7
      (write p parent 3)
      (read p (place outer [:msgs 1 :data :result]))
      (read p (place point))

  Uses the member's type for reads and writes: a struct as a map, an array
  as a vector, a union as a pointer on read and a pair on write. A path to
  a union member accepts the member's value directly on write.

  Throws for an invalid path. Create a place once and reuse it."
  ([t] (place t []))
  ([t path]
   (let [path (if (vector? path) path [path])
         k [t path]]
     (or (get @place-cache k)
         (let [[off lay] (resolve-path (layout-of t) path)
               v (Place. t path (decoder lay off) (encoder lay off path) (+ off (:size lay)))]
           (swap! place-cache (fn [m] (if (<= cache-limit (count m)) m (assoc m k v))))
           v)))))

;; -- callbacks ----------------------------------------------------------------

(defn callback
  "Creates a C function pointer that invokes f. arena owns the pointer, which
  is valid until the arena releases it. There is no separate release function.
  argtypes and rettype use the cfn type keywords. f receives :pointer
  arguments as zero-size pointers and :bool arguments as booleans. For a
  :pointer return f returns a pointer, or nil for null.

  C must call the pointer on the JavaScript thread. f must not throw and
  must not return a promise.

  The global arena never releases the pointer. An automatic arena releases
  it once the pointer itself becomes unreachable. The garbage collector
  cannot see the copy that C holds.

  CAUTION: Unregister the callback before its arena releases the pointer."
  [^Arena arena f argtypes rettype]
  (run! check-type argtypes)
  (check-type rettype)
  (when (some #(= :void %) argtypes)
    (throw (ex-info (str "babashka.ffi: :void is not an argument type: " (pr-str argtypes))
                    {:argtypes argtypes})))
  (when (or (= :string rettype) (some #(= :string %) argtypes))
    (throw (ex-info "babashka.ffi: a callback takes and returns :pointer, not :string"
                    {:argtypes argtypes :rettype rettype})))
  (when-not (arena? arena)
    (throw (ex-info (str "babashka.ffi: callback takes an arena first, got " (pr-str arena))
                    {:arena arena})))
  (when (.-closed arena)
    (throw (ex-info "babashka.ffi: the arena is closed" {:arena arena})))
  (let [n (count argtypes)
        in (to-array (map #(get ret-converter %) argtypes))
        out (when-not (= :void rettype) (arg-coercer rettype))
        wrapper (fn [& args]
                  (let [arr (to-array args)]
                    (dotimes [i n]
                      (when-let [c (aget in i)]
                        (aset arr i (c (aget arr i)))))
                    (let [r (.apply f nil arr)]
                      (if out (out r) js/undefined))))
        ^js lib @default-library
        addr (.registerCallback lib (signature argtypes rettype) wrapper)]
    (case (.-kind arena)
      "global" nil
      ;; weak: the pointer below keeps the wrapper reachable
      "auto" (.unrefCallback lib addr)
      (on-close arena (fn [] (.unregisterCallback lib addr))))
    (Pointer. addr 0 arena wrapper)))
