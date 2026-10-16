;; Embedded QuickJS over babashka.ffi: load npm packages from node_modules,
;; compile ClojureScript with the squint compiler inside the engine, and
;; evaluate the result. JavaScript calls a registered bb function back, and
;; js-require turns the functions of an npm package into a bb namespace. No
;; Node.js.
;;
;;   brew install quickjs-ng
;;   npm install squint-cljs marked change-case lodash-es
;;   bb examples/quickjs.clj
;;
;; Without Homebrew, build the shared library from source and point
;; QUICKJS_LIB at it:
;;
;;   git clone https://github.com/quickjs-ng/quickjs
;;   cmake -S quickjs -B quickjs/build -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release
;;   cmake --build quickjs/build --target qjs
;;   QUICKJS_LIB=quickjs/build/libqjs.so bb examples/quickjs.clj
;;
;; On macOS the library is quickjs/build/libqjs.dylib. An optional argument is
;; the node_modules directory, node_modules by default.
;;
;; On the JVM, put cheshire and babashka.fs on the classpath.

(ns quickjs
  {:clj-kondo/config '{:linters {:unresolved-namespace {:exclude [marked case lodash]}}}})

(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(if-let [lib (System/getenv "QUICKJS_LIB")]
  (ffi/load-library lib)
  (ffi/load-system-library "qjs"))

;; QuickJS parses numbers with strtod, which follows LC_NUMERIC: 1 on Linux, 4 on macOS.
(when-not (str/starts-with? (System/getProperty "os.name") "Windows")
  (let [setlocale (ffi/cfn "setlocale" [:int :string] :pointer)]
    (setlocale (if (re-find #"(?i)linux" (System/getProperty "os.name")) 1 4) "C")))

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
(defcfn throw-reference-error "JS_ThrowReferenceError" [:pointer :string :& :string] js-value)
(defcfn new-string "JS_NewStringLen" [:pointer :string :size_t] js-value)
(defcfn new-c-function "JS_NewCFunction2" [:pointer :pointer :string :int :int :int] js-value)
(defcfn global-object "JS_GetGlobalObject" [:pointer] js-value)
(defcfn set-property-str "JS_SetPropertyStr" [:pointer js-value :string js-value] :int)
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

(defn throw-js-error!
  "Throws a ReferenceError in the context and returns the exception value."
  [message]
  (throw-reference-error ctx "%s" message))

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

(defn js-malloc-string [s]
  (let [n (inc (utf8-length s))
        p (ffi/reinterpret (js-malloc ctx n) n)]
    (ffi/write-string p s)
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
                               ": " (ex-message e)))
         nil)))
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
           (throw-js-error! (str "Cannot load " file ": " (ex-message e)))
           nil))))
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

(def js-value-size (ffi/sizeof js-value))

;; JSCFunction: JSValue f(JSContext *, JSValueConst this, int argc, JSValueConst *argv).
(defn register-fn!
  "Installs f as a global JavaScript function named js-name. f takes the
  arguments as strings and returns a string. An exception in f becomes a
  ReferenceError in JavaScript."
  [js-name f]
  (let [callback
        (ffi/callback
         (ffi/global-arena)
         (fn [c _this argc argv]
           (try
             (let [argv (ffi/reinterpret argv (* js-value-size argc))
                   args (mapv #(value->str (ffi/read argv js-value (* js-value-size %)))
                              (range argc))
                   s (str (f args))]
               (new-string c s (utf8-length s)))
             (catch Exception e
               (throw-js-error! (str js-name ": " (ex-message e))))))
         [:pointer js-value :int :pointer] js-value)
        global (global-object ctx)]
    (try
      (set-property-str ctx global js-name (new-c-function ctx callback js-name 1 0 0))
      (finally (free-value ctx global)))))

(register-fn! "bbUpper" (fn [[s]] (str/upper-case s)))
(register-fn! "bbSlurp" (fn [[path]] (slurp path)))

(eval-js "globalThis.__bb_modules = {};
globalThis.__bb_call = (m, k, args) => {
  const out = v => JSON.stringify(v ?? null);
  const r = __bb_modules[m][k](...JSON.parse(args));
  return r instanceof Promise ? r.then(out) : out(r);
}")

(defn js-require
  "Imports an npm module into a namespace of the same name. spec is
  [module-name :as alias-sym]. Each exported function becomes a var.
  Arguments and results cross as JSON. A promise result is awaited."
  [[module-name _ alias-sym]]
  (let [m (pr-str module-name)
        exports (json/parse-string
                 (eval-js (str "import(" m ").then(mod => { __bb_modules[" m "] = mod;"
                               " return JSON.stringify(Object.keys(mod)"
                               ".filter(k => typeof mod[k] === 'function')) })")))
        js-ns (create-ns (symbol module-name))]
    (doseq [k exports]
      (intern js-ns (symbol k)
              (fn [& args]
                (json/parse-string
                 (eval-js (str "__bb_call(" m ", " (pr-str k) ", "
                               (pr-str (json/generate-string (vec args))) ")"))
                 true))))
    (alias alias-sym (symbol module-name))))

(js-require '["marked" :as marked])
(js-require '["change-case" :as case])
(js-require '["lodash-es" :as lodash])

(println "javascript:  " (eval-js "[1,2,3].map(x => x * x).join(',')"))
(println "promise:     " (eval-js "(async () => 6 * 7)()"))
(println "squint:      " (eval-cljs '(->> (range 10) (map #(* % %)) (filter odd?) vec)))
(println "interop:     " (eval-cljs '(js/JSON.stringify {:engine "QuickJS"})))

(eval-cljs '(def counter 41))
(println "def:         " (eval-cljs '(inc counter)))

(println "callback:    " (eval-cljs '(mapv js/bbUpper ["babashka" "quickjs"])))
(println "marked:      " (pr-str (marked/parse "# Hello from *marked*")))
(println "change-case: " (case/camelCase "babashka meets quickjs"))
(println "lodash:      " (lodash/chunk (range 7) 3) (lodash/kebabCase "Babashka QuickJS"))
(println "throw:       " (try (eval-js "bbSlurp('/no/such/file')")
                              (catch Exception e (ex-message e))))
(println "error:       " (try (eval-js "import('no-such-package')")
                              (catch Exception e (ex-message e))))
