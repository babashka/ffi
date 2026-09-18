(ns native-test
  "Builds a native image of babashka.ffi and runs test-native against it.

  The image is what decides the call path: the trampolines, the upcall
  shapes it registered and the errors for what it cannot call. None of that
  is observable from the JVM suite, and running the tests against a released
  babashka only reports the babashka.ffi that binary was built with.

  Needs GRAALVM_HOME and a C compiler, cc or on Windows cl. Without libffi,
  which the build does not link, a struct call and a variadic signature are
  expected to throw.

      bb test:native"
  (:require [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def graalvm
  (or (System/getenv "GRAALVM_HOME")
      (do (binding [*out* *err*] (println "GRAALVM_HOME is not set"))
          (System/exit 1))))

(defn- graalvm-bin [program]
  (str (or (fs/which program {:paths [(str (fs/path graalvm "bin"))]})
           (throw (ex-info (str program " not found under " graalvm) {})))))

(def out (fs/path "target" "native"))
(def classes (fs/path out "classes"))
(def image (fs/path out (if (fs/windows?) "ffi-native-test.exe" "ffi-native-test")))

(def lib
  (str (fs/path "target"
                (cond (fs/windows?) "ffistructs.dll"
                      (str/starts-with? (System/getProperty "os.name") "Mac") "libffistructs.dylib"
                      :else "libffistructs.so"))))

(def fixture (str (fs/path "test-resources" "struct_lib.c")))

(defn- step [title] (println "==" title))

(defn- classpath
  "The classpath of this project with alias, from the Clojure CLI that
  babashka carries. It prints the path itself and starts no process."
  [alias]
  (str/trim (with-out-str (deps/clojure ["-Spath" (str "-A" alias)]))))

(defn- clojure
  "Runs Clojure with args through the same CLI and waits for it."
  [& args]
  (-> (deps/clojure (vec args)) deref p/check))

(defn -main [& _]
  (fs/delete-tree out)
  (fs/create-dirs classes)

  (step "the fixture")
  (if (fs/windows?)
    (p/shell "cl" "/nologo" "/LD" fixture (str "/Fe:" lib)
             (str "/Fo:" (fs/path "target" "ffistructs.obj")))
    (p/shell "cc" "-shared" "-fPIC" "-o" lib fixture))

  (step "javac")
  (let [sdk (classpath :native)
        cp (str/join fs/path-separator [sdk "test-native" (str classes)])]
    (p/shell (graalvm-bin "javac") "--release" "25" "-cp" sdk "-d" (str classes)
             "src-java/babashka/ffi/impl/FfiTrampoline.java"
             "src-java/babashka/ffi/impl/FfiTrampolineOrdered.java")

    (step "compiling the namespaces")
    (clojure "-Scp" cp "-J--enable-native-access=ALL-UNNAMED"
             "-e" (pr-str (list 'binding ['*compile-path* (str classes)]
                                '(compile 'babashka.ffi.native-test))))

    (step "native-image")
    (apply p/shell (graalvm-bin "native-image")
           "-cp" cp
           "--features=clj_easy.graal_build_time.InitClojureClasses"
           "-H:+UnlockExperimentalVMOptions"
           "-H:+ForeignAPISupport"
           "--enable-native-access=ALL-UNNAMED"
           "--no-fallback"
           "-O1"
           (concat
            ;; Windows assigns argument registers by position, so its image
            ;; loads the ordered trampolines, and the upcall shapes only they
            ;; need are in a metadata file of their own
            (when (fs/windows?)
              ["-H:ConfigurationResourceRoots=babashka/ffi/native-image-windows"])
            ["-o" (str (fs/path out "ffi-native-test"))
             "babashka.ffi.native_test"])))

  (step "running the image")
  (p/shell (str (fs/absolutize image)) lib))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
