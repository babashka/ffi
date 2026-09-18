#!/usr/bin/env bash
# Builds a native image of babashka.ffi and runs test-native against it.
#
# The image is what decides the call path: the trampolines, the upcall
# shapes it registered and the errors for what it cannot call. None of that
# is observable from the JVM suite, and running the tests against a released
# babashka only reports the babashka.ffi that binary was built with.
#
# Needs GRAALVM_HOME and a C compiler, cc or on Windows cl. Without libffi,
# which the build does not link, a struct call and a variadic signature are
# expected to throw.
#
#     GRAALVM_HOME=... script/native_test.sh

set -euo pipefail

if [ -z "${GRAALVM_HOME:-}" ]; then
  echo "GRAALVM_HOME is not set" >&2
  exit 1
fi

out=target/native
classes=$out/classes
image=$out/ffi-native-test
sep=:
cmd=
extra=()

case "$(uname -s)" in
  Darwin) lib=target/libffistructs.dylib ;;
  MINGW*|MSYS*|CYGWIN*)
    lib=target/ffistructs.dll
    sep=';'
    cmd=.cmd
    image=$image.exe
    # Windows assigns argument registers by position, so its image loads the
    # ordered trampolines, and the upcall shapes only they need are in a
    # metadata file of their own
    extra=(-H:+UnlockExperimentalVMOptions
           -H:ConfigurationResourceRoots=babashka/ffi/native-image-windows) ;;
  *) lib=target/libffistructs.so ;;
esac

rm -rf "$out"
mkdir -p "$classes" target

echo "== the fixture"
if [ -n "$cmd" ]; then
  cl //nologo //LD test-resources/struct_lib.c "//Fe:$lib" "//Fo:$lib.obj"
else
  cc -shared -fPIC -o "$lib" test-resources/struct_lib.c
fi

echo "== javac"
sdk=$(clojure -Spath -A:native)
"$GRAALVM_HOME/bin/javac" --release 25 -cp "$sdk" -d "$classes" \
  src-java/babashka/ffi/impl/FfiTrampoline.java \
  src-java/babashka/ffi/impl/FfiTrampolineOrdered.java

cp="$sdk${sep}test-native${sep}$classes"

echo "== compiling the namespaces"
clojure -Scp "$cp" \
  -J--enable-native-access=ALL-UNNAMED \
  -e "(binding [*compile-path* \"$classes\"] (compile 'babashka.ffi.native-test))"

echo "== native-image"
"$GRAALVM_HOME/bin/native-image$cmd" \
  -cp "$cp" \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  -H:+ForeignAPISupport \
  --enable-native-access=ALL-UNNAMED \
  --no-fallback \
  -O1 \
  ${extra[@]+"${extra[@]}"} \
  -o "$out/ffi-native-test" \
  babashka.ffi.native_test

echo "== running the image"
"./$image" "$lib"
