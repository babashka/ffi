# ADR 0008: A Node.js host through node:ffi

## Status

Proposed 2026-09-17. Experimental. node:ffi has stability 1 and requires
Node.js 26.1 or newer.

## Context

Node.js 26.1 added `node:ffi`: `DynamicLibrary`, `getFunction` with a
signature of type names, typed `get` and `set` on a raw address, and
`registerCallback`. That is enough for the scalar, pointer, layout and
callback part of `babashka.ffi`, so a script written for babashka can run
under nbb.

Probed on Node.js 26.9.0, macOS arm64:

- An address is a bigint everywhere. A number is refused.
- An argument is not converted: `-0`, a fraction, an integer outside the
  width, and a number for a 64-bit type all throw.
- `getFunction` takes a symbol name only. There is no call through an
  address. The Node.js binary does not export libffi for use by
  examples/libffi.clj.
- No struct by value, no variadic call.
- No allocator.
- `DynamicLibrary(null)` opens the process. The docs say Windows does not
  support it.
- nbb has no `with-open`, and its `deftype` takes only `toString` under
  `Object`.
- ClojureScript resolves a fixed list of Node.js modules. `"node:ffi"` in
  an ns require fails with `No such namespace: node:ffi`, and the module
  has no name without the prefix.

## Decision

`src/babashka/ffi.cljs` is a second implementation of the same namespace.
`ffi.clj` stays one file that depends only on the JDK, because babashka
embeds it. The layout code is duplicated, not shared through a `.cljc`.

- A pointer is `(deftype Pointer [addr size scope keep])`. `scope` is the
  arena, and a closed arena makes every access throw, as a closed FFM arena
  does. `keep` holds the Buffer or the callback function for the garbage
  collector.
- The namespace reaches node:ffi through `process.getBuiltinModule`, one
  path for nbb, ClojureScript, shadow-cljs, CommonJS and ESM.
- `ffi.cljs` requires its macros from `babashka.ffi`, as babashka.fs does.
  The ClojureScript compiler resolves that to `ffi.clj`, so `ffi.clj` has
  `with-open` too: clojure.core's on the JVM, a try and finally around
  `.close` when it expands for ClojureScript. One script closes arenas the
  same way on every host. The compiler's JVM loads `ffi.clj` and needs JDK
  25 or newer. nbb uses the defmacros in `ffi.cljs`.
- An arena is `(deftype Arena [kind closed bufs cleanups close])`. `close`
  is a field that holds a function, because nbb's deftype takes no methods. An allocation is
  a zeroed `Buffer`, over-allocated for alignment, and its address comes
  from `getRawPointer`. The arena holds its buffers until it closes.
- A 64-bit return is a number when it is a safe integer, else a bigint. An
  unsigned 64-bit value stays unsigned. Arguments take either.
- One coercion function per type wraps, truncates or widens the value to
  what node:ffi accepts, chosen when the binding is made (ADR 0007).
- A callback registers on the default library. A closeable arena
  unregisters it at close, the automatic arena uses `unrefCallback` with
  the pointer as the strong reference, the global arena never releases it.
- `cfn` throws when the binding is made for a struct by value, a `:&`, or
  a pointer as the symbol.
- On Windows the default library is `ucrtbase.dll`.

## Consequences

- A change to a layout rule, a type keyword or an error message goes in
  both files. test-node/babashka/ffi_test.cljs follows ffi_test.clj case by
  case to catch drift.
- Libraries that bind a function pointer, such as a vtable entry or a
  callback round trip, require changes to run on Node.js.
- Measured under nbb 1.5.212: a scalar call costs about 150 ns, against
  about 12 ns from plain JavaScript and 25 to 100 ns for the raw node:ffi
  function called from SCI. A multi-arity function costs SCI about 250 ns
  per call, so a binding with up to 4 arguments is a single-arity function
  with a sentinel parameter, which cost 530 ns as a multi-arity one. `read`
  and `write` are multi-arity and cost 550 to 900 ns.
- The same 14 tests pass under nbb, ClojureScript `:none` and `:advanced`,
  and shadow-cljs plain and `:advanced`. A ClojureScript `:advanced` build
  needs `:infer-externs true`. Every Pointer, Arena and node:ffi access
  carries a type hint, so shadow-cljs compiles without infer warnings.
- The multi-arity cost is SCI's. 2 million calls each, ns per call:

      fn shape          planck   nbb 1.5.212   compiled, node 26
      one arity             40            11                 4.3
      multi fixed           75           273                 4.3
      multi + variadic     109           267                 4.3
      variadic only        225            16                  15
