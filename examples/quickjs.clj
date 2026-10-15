;; Embedded QuickJS over babashka.ffi: load npm packages from node_modules,
;; compile ClojureScript with the squint compiler inside the engine, and
;; evaluate the result. No Node.js.
;;
;;   git clone https://github.com/quickjs-ng/quickjs
;;   cmake -S quickjs -B quickjs/build -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release
;;   cmake --build quickjs/build --target qjs
;;   npm install squint-cljs
;;   QUICKJS_LIB=quickjs/build/libqjs.dylib bb examples/quickjs.clj
;;
;; On Linux the library is quickjs/build/libqjs.so. Without QUICKJS_LIB the
;; script loads libqjs from the system library path. An optional argument is
;; the node_modules directory, node_modules by default.
;;
;; On the JVM, put cheshire and babashka.fs on the classpath.

(ns quickjs)

(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(if-let [lib (System/getenv "QUICKJS_LIB")]
  (ffi/load-library lib)
  (ffi/load-system-library "qjs"))

(def js-value [:struct [[:u :int64] [:tag :int64]]])

(def tag-exception 6)
(def eval-module (bit-or 1 32))

(defcfn new-runtime "JS_NewRuntime" [] :pointer)
(defcfn new-context "JS_NewContext" [:pointer] :pointer)
(defcfn js-eval "JS_Eval" [:pointer :string :size_t :string :int] js-value)
(defcfn free-value "JS_FreeValue" [:pointer js-value] :void)
(defcfn to-cstring "JS_ToCStringLen2" [:pointer :pointer js-value :bool] :pointer)
(defcfn free-cstring "JS_FreeCString" [:pointer :pointer] :void)
(defcfn get-exception "JS_GetException" [:pointer] js-value)
(defcfn new-error "JS_NewError" [:pointer] js-value)
(defcfn new-string "JS_NewStringLen" [:pointer :string :size_t] js-value)
(defcfn set-property-str "JS_SetPropertyStr" [:pointer js-value :string js-value] :int)
(defcfn js-throw "JS_Throw" [:pointer js-value] js-value)
(defcfn execute-pending-job "JS_ExecutePendingJob" [:pointer :pointer] :int)
(defcfn promise-state "JS_PromiseState" [:pointer js-value] :int)
(defcfn promise-result "JS_PromiseResult" [:pointer js-value] js-value)
(defcfn set-module-loader "JS_SetModuleLoaderFunc" [:pointer :pointer :pointer :pointer] :void)
(defcfn js-malloc "js_malloc" [:pointer :size_t] :pointer)

(def rt (new-runtime))
(def ctx (new-context rt))

(defn utf8-length [^String s]
  (alength (.getBytes s "UTF-8")))

(defn value->str [v]
  (let [p (to-cstring ctx ffi/null v false)]
    (try (ffi/ptr->string p)
         (finally (free-cstring ctx p)))))

(defn take-value
  "Returns v as a string and frees v."
  [v]
  (try (value->str v)
       (finally (free-value ctx v))))

(defn throw-pending! []
  (throw (ex-info (take-value (get-exception ctx)) {})))

(defn eval* [source file-name flags]
  (let [v (js-eval ctx source (utf8-length source) file-name flags)]
    (if (= tag-exception (:tag v))
      (throw-pending!)
      v)))

(defn run-jobs! []
  (with-open [arena (ffi/confined-arena)]
    (let [ctx-out (ffi/alloc arena :pointer)]
      (loop []
        (let [r (execute-pending-job rt ctx-out)]
          (cond (pos? r) (recur)
                (neg? r) (throw-pending!)))))))

(defn eval-js
  "Evaluates a script, runs the pending jobs, and returns the result as a
  string. For a promise, returns its value or throws its rejection."
  [source]
  (let [v (eval* source "<eval>" 0)]
    (run-jobs!)
    (try
      (case (int (promise-state ctx v))
        -1 (value->str v)
        0 (throw (ex-info "Promise did not settle" {:source source}))
        1 (take-value (promise-result ctx v))
        2 (throw (ex-info (take-value (promise-result ctx v)) {:source source})))
      (finally (free-value ctx v)))))

(defn throw-js-error! [message]
  (let [e (new-error ctx)]
    (set-property-str ctx e "message" (new-string ctx message (utf8-length message)))
    (js-throw ctx e)
    nil))

(def node-modules
  (fs/absolutize (or (first *command-line-args*) "node_modules")))

(defn export-target [entry]
  (if (map? entry)
    (some #(some-> (get entry %) export-target) ["import" "module" "default"])
    entry))

(defn package-file [package subpath]
  (let [dir (fs/path node-modules package)
        manifest (json/parse-string (slurp (str (fs/path dir "package.json"))))
        k (if (str/blank? subpath) "." (str "./" subpath))
        exports (get manifest "exports")
        target (or (export-target (if (map? exports) (get exports k) exports))
                   (when (= "." k)
                     (or (get manifest "module") (get manifest "main") "index.js"))
                   subpath)]
    (fs/path dir target)))

(defn resolve-module [base-name module-name]
  (str (fs/normalize
        (if (re-find #"^\.\.?/" module-name)
          (fs/path (or (fs/parent base-name) ".") module-name)
          (let [[_ package subpath] (re-find #"^((?:@[^/]+/)?[^/]+)/?(.*)$" module-name)]
            (package-file package subpath))))))

(defn js-malloc-string [^String s]
  (let [bs (.getBytes s "UTF-8")
        n (alength bs)
        p (ffi/reinterpret (js-malloc ctx (inc n)) (inc n))]
    (ffi/write-array p :char bs)
    (ffi/write p :char 0 n)
    p))

;; JSModuleNormalizeFunc returns the specifier allocated with js_malloc.
(def normalize
  (ffi/callback
   (ffi/global-arena)
   (fn [_ctx base-name module-name _opaque]
     (try
       (js-malloc-string (resolve-module (ffi/ptr->string base-name)
                                         (ffi/ptr->string module-name)))
       (catch Exception e
         (throw-js-error! (str "Cannot resolve " (ffi/ptr->string module-name)
                               ": " (ex-message e))))))
   [:pointer :pointer :pointer :pointer] :pointer))

;; The pointer of a compiled module value is its JSModuleDef.
(def loader
  (ffi/callback
   (ffi/global-arena)
   (fn [_ctx module-name _opaque]
     (let [file (ffi/ptr->string module-name)]
       (try
         (let [source (slurp file)
               v (js-eval ctx source (utf8-length source) file eval-module)]
           (when-not (= tag-exception (:tag v))
             (free-value ctx v)
             (ffi/segment (:u v))))
         (catch Exception e
           (throw-js-error! (str "Cannot load " file ": " (ex-message e)))))))
   [:pointer :pointer :pointer] :pointer))

(set-module-loader rt normalize loader ffi/null)

(eval-js "import('squint-cljs').then(m => { globalThis.squint = m })")
(eval-js "import('squint-cljs/core.js').then(m => { globalThis.squint_core = m })")

(defn eval-cljs
  "Compiles a ClojureScript form with squint in QuickJS, evaluates it, and
  returns the result printed by squint's pr-str. A def stays in scope for the
  next call."
  [form]
  (eval-js (str "squint_core.pr_str((0, eval)(squint.compileString("
                (pr-str (pr-str form))
                ", {context: 'expr', repl: true,"
                " 'elide-imports': true, 'elide-exports': true})))")))

(println "javascript:  " (eval-js "[1,2,3].map(x => x * x).join(',')"))
(println "promise:     " (eval-js "(async () => 6 * 7)()"))
(println "squint:      " (eval-cljs '(->> (range 10) (map #(* % %)) (filter odd?) vec)))
(println "interop:     " (eval-cljs '(js/JSON.stringify {:engine "QuickJS"})))

(eval-cljs '(def counter 41))
(println "def:         " (eval-cljs '(inc counter)))

(println "error:       " (try (eval-js "import('no-such-package')")
                              (catch Exception e (ex-message e))))
