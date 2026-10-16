# ADR 0010: Names of the copy functions

## Status

Accepted 2026-10-16. `write-string` lands with issue 65. `read-into!` is
planned.

## Context

Four functions copy between memory and a host value: `read-array`,
`write-array`, `string->ptr` and `ptr->string`. Issue 65 adds a write of a
C string into memory C allocated, and `read-into!` will fill an existing
array from memory. The names need one rule for when a function takes a
bang.

Every `write` function mutates memory behind a pointer, so mutation alone
cannot decide it. Clojure's own array functions take no bang: `aset`,
`aset-int`. Its bangs are on transients and references: `conj!`, `assoc!`,
`reset!`, `swap!`, `vreset!`.

jolt ships `jolt.ffi/read-into!` with the arities `(read-into! p t arr
offset)` and `(read-into! p t arr offset index n)`, and `write-array` with
the matching `(write-array p t arr offset index n)`.

## Decision

A bang marks a function whose name does not say it mutates. `write`,
`write-array` and `write-string` say it. A read leaves its arguments alone,
so a read that fills one is `read-into!`.

The names are:

```clojure
(read-array p t n offset)            ; a new array
(read-into! p t arr offset index n)  ; an existing array, returns arr
(write-array p t arr offset index n)
(write-string p s offset)            ; s and a NUL byte
(ptr->string p limit)
(string->ptr arena s)
```

`read-into!` is a separate function, not an array arity of `read-array`.
Its index and count would leave `read-array` with two unrelated shapes.
The numbers follow `System/arraycopy`: source position, destination
position, length.

`read-into!` returns arr, as `read-array` returns its new array, so a call
can be threaded. The caller knows the count it passed.

`write-string` is not `write-bytes`, which jolt has without a NUL byte.
The reading side stays `ptr->string`, because `read-string` shadows
`clojure.core/read-string` for a caller who refers the namespace.

## Consequences

`read-into!` and the `write-array` tail match jolt in name and argument
order. The return values differ. jolt returns the element count from both,
and babashka returns arr from `read-into!` and nil from `write-array`. The last arity of each takes
`index` and `n` side by side, where a swap throws only if it leaves the
array.
