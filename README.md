# babashka.ffi

Call C libraries from Clojure and [babashka](https://github.com/babashka/babashka).

This library is the `babashka.ffi` namespace that ships inside babashka,
extracted so that JVM Clojure programs can use it too. Babashka consumes this
repository as a submodule.

Status: work in progress, not yet released.

## Requirements

On the JVM:

- JDK 22 or newer. The library uses the Java FFM API.
- Start the JVM with `--enable-native-access=ALL-UNNAMED`, or set the
  `Enable-Native-Access` manifest attribute in an uberjar. Without the flag,
  modern JDKs warn, and a future JDK release refuses the calls.

In babashka the namespace is built in: `(require '[babashka.ffi :as ffi])`.

## Documentation

[doc/guide.md](doc/guide.md) documents the whole API: loading libraries, binding
functions, memory and arenas, structs, callbacks, and what each host costs.

## clj-kondo

The library exports a clj-kondo hook for `defcfn`. Copy the config with:

    clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint

## Test

On the JVM:

    clojure -M:test

The same suite runs through babashka's native image, where the calls go
through the compiled trampolines instead of FFM handles:

    bb --classpath test -e "(require 'babashka.ffi-test 'clojure.test) (clojure.test/run-tests 'babashka.ffi-test)"

This suite is the home for API tests: anything that must hold on both hosts
belongs here, written as plain `clojure.test` with no harness, so one file
covers both. Babashka keeps its own suite for what only babashka can observe,
such as the libffi backend selection, the trampoline set, and builds without
libffi.

## License

Copyright © 2026 Michiel Borkent

Distributed under the EPL License. See LICENSE.
