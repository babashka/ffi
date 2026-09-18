# babashka.ffi

[![Clojars Project](https://img.shields.io/clojars/v/org.babashka/ffi.svg)](https://clojars.org/org.babashka/ffi)
[![bb built-in](https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg)](https://book.babashka.org#badges)

Call C libraries from Clojure and [babashka](https://github.com/babashka/babashka).

This library is the `babashka.ffi` namespace that ships inside babashka,
extracted so that JVM Clojure programs can use it too. Babashka consumes this
repository as a submodule.

Status: experimental. The API can still change.

``` clojure
org.babashka/ffi {:mvn/version "0.1.2"}
```

## Platforms

| Platform | Requirement | Notes |
|---|---|---|
| JVM | JDK 25 or newer | Uses the Java FFM API. |
| babashka | 1.13.220 or newer | Built in, no dependency needed. |
| GraalVM native image | GraalVM 25 or newer | See [Build your own native image](doc/guide.md#build-your-own-native-image). |
| Node.js | 26.1 or newer | Works with [nbb](https://github.com/babashka/nbb), ClojureScript, shadow-cljs and [squint](https://github.com/squint-cljs/squint) through `node:ffi`. See [On Node.js](doc/guide.md#on-nodejs). |

Bun and Deno are not supported.

On the JVM, start with `--enable-native-access=ALL-UNNAMED`, or set the
`Enable-Native-Access` manifest attribute in an uberjar. Without the flag,
modern JDKs warn, and a future JDK release refuses the calls.

In babashka the namespace is built in: `(require '[babashka.ffi :as ffi])`.

## Libraries built on it

- [babashka.sqlite](https://github.com/babashka/babashka.sqlite): SQLite
- [babashka.postgres](https://github.com/babashka/babashka.postgres): PostgreSQL through libpq
- [babashka.duckdb](https://github.com/babashka/babashka.duckdb): DuckDB
- [filewatcher](https://github.com/babashka/filewatcher): file watching through FSEvents, inotify and ReadDirectoryChangesW

None of them expose an arena, a pointer, or a layout: the FFI stays inside
the library.

## Documentation

[doc/guide.md](doc/guide.md) documents the API: library loading, function
binding, memory, arenas, structs, callbacks, and performance limits for each
host.

[API.md](API.md) lists every public var with its arities and docstring.
Regenerate it with `bb quickdoc`.

## clj-kondo

The library exports a clj-kondo hook for `defcfn`. Copy the config with:

    clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint

In a babashka project, use the built-in classpath:

    clj-kondo --lint "$(bb print-deps --format classpath)" --copy-configs --skip-lint

## License

Copyright © 2026 Michiel Borkent

Distributed under the MIT License. See LICENSE.

Babashka embeds this library and is itself EPL licensed. The two licenses
apply to their own repositories.
