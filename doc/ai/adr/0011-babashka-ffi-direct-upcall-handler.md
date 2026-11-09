# ADR 0011: Call every libffi closure through a direct upcall

## Status

Accepted 2026-11-09.

## Context

A callback in a native image cost about 2.3 us for `[:double] :void` with
an empty function, measured from C in a shared library build. A profile put
the time in `LambdaForm_interpretName`, `SubstrateMethodAccessor` and
`Module_allows`. The image runs an upcall stub's method handle in an
interpreter unless the handle is a constant when the image is built, and
`ffm-callback` builds it at run time with `bindTo` and `asType`.

GraalVM registers a static method as a direct upcall under
`foreign.directUpcalls` in the reachability metadata. A stub made from a
`findStatic` handle to that method is compiled. The registration takes no
bound arguments, so the target cannot carry the function.

## Decision

With libffi, every callback in a native image is a libffi closure, not only
a struct or an unregistered shape. Every closure calls one upcall stub, made
on first use, to the static method `LibffiClosure.closure`. The closure's
user data is an index into a table of entries in `LibffiClosure`, and
`libffi-callback` registers the entry and releases it when the arena frees
the closure.

An entry holds the function, a type code per argument, and the return code.
`LibffiClosure.closure` reads each scalar argument through a GraalVM
`Pointer`, because a read through a `MemorySegment` costs about 30 ns in an
image. It takes the four pointers as longs, which saves about 2 ns over
four `MemorySegment` wrappers. It calls the function by arity up to 8 and
through `applyTo` above that. A struct argument or return goes through the
codecs, as before.

Without libffi, the registered shapes keep their FFM stubs at about 2 us.

## Consequences

Measured from C into a shared library image at -O2, 10 million calls:

| Path | ns per call |
|---|---|
| FFM stub of a registered shape | 2,400 |
| libffi closure, handler as a regular upcall | 106 |
| libffi closure, direct upcall handler, segment arguments | 31 |
| libffi closure, direct upcall handler, long arguments | 29 |
| direct upcall to a static method, no arguments decoded | 19 |

A SQLite function in Clojure over 100,000 rows went from 513 ms to 63 ms.

`LibffiClosure.java` uses the GraalVM SDK and Clojure, and every image
compiles it, because the metadata names it whether or not the image links
libffi. The
stub is made on first use because graal-build-time initializes the
`babashka.ffi.impl` package when the image is built, and a stub made then
does not exist at run time.

A read through a `MemorySegment` costs the same 30 ns in `read` and `write`,
which this decision leaves as they are.
