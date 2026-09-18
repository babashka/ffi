#!/usr/bin/env bash
# Builds a native image of babashka.ffi and runs test-native against it.
#
# The image is what decides the call path: the trampolines, the upcall
# shapes it registered and the errors for what it cannot call. None of that
# is observable from the JVM suite, and running the tests against a released
# babashka only reports the babashka.ffi that binary was built with.
#
# Needs GRAALVM_HOME and a C compiler. Without libffi, which the build does
# not link, a struct call and a variadic signature are expected to throw.
#
#     GRAALVM_HOME=... script/native_test.sh

set -euo pipefail

if [ -z "${GRAALVM_HOME:-}" ]; then
  echo "GRAALVM_HOME is not set" >&2
  exit 1
fi

case "$(uname -s)" in
  Darwin) ext=dylib ;;
  *)      ext=so ;;
esac
lib="target/libffistructs.$ext"
out=target/native
classes=$out/classes

rm -rf "$out"
mkdir -p "$classes" target

echo "== the fixture"
cc -shared -fPIC -o "$lib" test-resources/struct_lib.c

echo "== the generated trampolines and metadata"
bb script/gen_ffi_metadata.clj

echo "== javac"
sdk=$(clojure -Spath -A:native)
"$GRAALVM_HOME/bin/javac" --release 25 -cp "$sdk" -d "$classes" \
  src-java/babashka/ffi/impl/FfiTrampoline.java

cp=$(clojure -Spath -A:native):test-native:$classes

echo "== compiling the namespaces"
clojure -Scp "$cp" \
  -J--enable-native-access=ALL-UNNAMED \
  -e "(binding [*compile-path* \"$classes\"] (compile 'babashka.ffi.native-test))"

echo "== native-image"
"$GRAALVM_HOME/bin/native-image" \
  -cp "$cp" \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  -H:+ForeignAPISupport \
  --enable-preview \
  --enable-native-access=ALL-UNNAMED \
  --no-fallback \
  -O1 \
  -o "$out/ffi-native-test" \
  babashka.ffi.native_test

echo "== running the image"
"./$out/ffi-native-test" "$lib"
