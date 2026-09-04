;; Embeds JavaScriptCore and runs ClojureScript in it: squint compiles the
;; ClojureScript to JavaScript and the engine evaluates it, in the babashka
;; process, without Node.js. JavaScript calls back into Clojure through a
;; registered function.
;;
;;   bb examples/javascriptcore.clj
;;
;; On the JVM, add squint to the classpath instead: babashka.deps is
;; babashka only.
;;
;; macOS ships JavaScriptCore. On Linux it comes with webkit2gtk, in the
;; libjavascriptcoregtk-4.1-0 package.

(ns javascriptcore)

(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(when (System/getProperty "babashka.version")
  ((requiring-resolve 'babashka.deps/add-deps)
   '{:deps {io.github.squint-cljs/squint {:mvn/version "0.14.208"}}}))

(require '[squint.compiler :as squint])

(ffi/load-library
 {:mac "/System/Library/Frameworks/JavaScriptCore.framework/JavaScriptCore"
  :linux "libjavascriptcoregtk-4.1.so.0"})

(defcfn context-create "JSGlobalContextCreate" [:pointer] :pointer)
(defcfn global-object "JSContextGetGlobalObject" [:pointer] :pointer)
(defcfn string-create "JSStringCreateWithUTF8CString" [:string] :pointer)
(defcfn string-release "JSStringRelease" [:pointer] :void)
(defcfn string-max-size "JSStringGetMaximumUTF8CStringSize" [:pointer] :size_t)
(defcfn string-get-utf8 "JSStringGetUTF8CString" [:pointer :pointer :size_t] :size_t)
(defcfn evaluate-script "JSEvaluateScript"
  [:pointer :pointer :pointer :pointer :int :pointer] :pointer)
(defcfn value->string-copy "JSValueToStringCopy" [:pointer :pointer :pointer] :pointer)
(defcfn value-make-string "JSValueMakeString" [:pointer :pointer] :pointer)
(defcfn value-make-undefined "JSValueMakeUndefined" [:pointer] :pointer)
(defcfn object-make-function "JSObjectMakeFunctionWithCallback"
  [:pointer :pointer :pointer] :pointer)
(defcfn object-set-property "JSObjectSetProperty"
  [:pointer :pointer :pointer :pointer :uint :pointer] :void)

(def ctx (context-create ffi/null))

(defn jsc-string->str [s]
  (with-open [arena (ffi/confined-arena)]
    (let [n (string-max-size s)
          buf (ffi/alloc arena n)]
      (string-get-utf8 s buf n)
      (ffi/ptr->string buf))))

(defn value->str [c v]
  (let [s (value->string-copy c v ffi/null)]
    (try (jsc-string->str s)
         (finally (string-release s)))))

(defn eval-js [source]
  (with-open [arena (ffi/confined-arena)]
    (let [script (string-create source)
          exception (ffi/alloc arena :pointer)]
      (try
        (let [v (evaluate-script ctx script ffi/null ffi/null 1 exception)
              e (ffi/read exception :pointer)]
          (if (ffi/null? e)
            (value->str ctx v)
            (throw (ex-info (value->str ctx e) {:source source}))))
        (finally (string-release script))))))

;; JSObjectCallAsFunctionCallback takes six integer and pointer arguments,
;; and returns a JSValueRef. A callback cannot return :pointer, so this
;; returns the address as :int64, which uses the same register.
(defn register-fn!
  "Installs f as a global JavaScript function named js-name. f takes the
  arguments as strings and returns a string."
  [js-name f]
  (let [pointer-size (ffi/sizeof :pointer)
        callback
        (ffi/callback
         (ffi/global-arena)
         (fn [c _function _this argc args _exception]
           (try
             (let [args (ffi/reinterpret args (* pointer-size argc))
                   vs (mapv (fn [i]
                              (value->str c (ffi/read args :pointer (* pointer-size i))))
                            (range argc))
                   s (string-create (f vs))
                   v (value-make-string c s)]
               (string-release s)
               (ffi/address v))
             (catch Exception e
               ;; An exception that escapes a callback can take the process down.
               (binding [*out* *err*] (println "callback error:" (ex-message e)))
               (ffi/address (value-make-undefined c)))))
         [:pointer :pointer :pointer :size_t :pointer :pointer] :int64)
        name-str (string-create js-name)]
    (try
      (object-set-property ctx (global-object ctx) name-str
                           (object-make-function ctx name-str callback) 0 ffi/null)
      (finally (string-release name-str)))))

;; squint's core is one ES module without imports, and JSEvaluateScript has no
;; module loader. Stripping the export keywords makes it a classic script, and
;; the exported names become the members of squint_core, the alias the
;; compiler emits.
(let [core (slurp (io/resource "squint/core.js"))
      names (map second (re-seq #"(?m)^export (?:function|const|class) ([\w$]+)" core))]
  (eval-js (str/replace core #"(?m)^export " ""))
  (eval-js (str "var squint_core = {"
                (str/join ", " (map #(str % ": " %) names))
                "};")))

(def compiler-state (atom nil))

(defn eval-cljs
  "Compiles ClojureScript with squint, evaluates it, and returns the result
  printed by squint's pr-str. A def stays in scope for the next call."
  [source]
  (let [{:keys [javascript] :as state}
        (squint/compile* source
                         {:context :repl-return
                          :elide-imports true
                          :elide-exports true
                          :repl true
                          :core-alias "squint_core"}
                         @compiler-state)]
    (reset! compiler-state state)
    (eval-js (str "squint_core.pr_str((function () {\n" javascript
                  "\n;return [undefined];\n})()[0])"))))

(println "javascript:  " (eval-js "[1,2,3].map(x => x * x).join(',')"))
(println "squint:      " (eval-cljs "(->> (range 10) (map #(* % %)) (filter odd?) vec)"))
(println "interop:     " (eval-cljs "(js/JSON.stringify #js {:engine \"JavaScriptCore\"})"))

(eval-cljs "(def counter 41)")
(println "def:         " (eval-cljs "(inc counter)"))

(register-fn! "bbUpper" (fn [[s]] (str/upper-case s)))
(println "callback:    " (eval-cljs "(mapv js/bbUpper [\"babashka\" \"squint\"])"))
