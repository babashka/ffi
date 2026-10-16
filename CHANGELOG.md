# Changelog

[babashka.ffi](https://github.com/babashka/ffi): call C libraries from
Clojure, babashka and Node.js.

## Unreleased

- [#64](https://github.com/babashka/ffi/issues/64): `cfn` accepts structs in a variadic signature, as return type and as argument
- [#63](https://github.com/babashka/ffi/issues/63): `callback` accepts struct layouts as argument and return types
- In a native image with libffi, `callback` accepts shapes outside the registered upcall set, such as more than 6 arguments or `:float`
- On the JVM, a `callback` receives a `:uint`, `:uint32`, `:uint16` or `:uint8` argument without sign extension

## 0.1.2

- Squint compatibility

## 0.1.1

First standalone release of `babashka.ffi`, available on Clojars for JVM
Clojure, ClojureScript and nbb. The library is also built into babashka
1.13.220 and newer.

Call C libraries from JVM Clojure on JDK 25 or newer, or from applications
compiled with GraalVM 25 or newer. ClojureScript, nbb and shadow-cljs can
call C libraries on Node.js 26.1 or newer through `node:ffi`.

The library supports structs passed by value, functions with variable
numbers of arguments, and callbacks. It also provides arenas to manage
memory lifetimes and typed access to native memory.
