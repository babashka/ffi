# ADR 0009: Call back through libffi closures in a native image

## Status

Accepted 2026-10-15. Issue #63.

## Context

`callback` took type keywords only. QuickJS host functions take and return
a `JSValue` by value:

```c
typedef JSValue JSCFunction(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv);
```

On the JVM, an FFM upcall stub takes any descriptor, struct layouts
included. A native image makes an upcall stub only for a shape that was
registered when the image was built. A struct descriptor carries the whole
layout, so no finite set of registrations covers it. Struct downcalls hit
the same limit and go through libffi (ADR 0003).

## Decision

On the JVM, `callback` builds the upcall stub from the struct descriptor
that struct downcalls use. A struct argument arrives as a segment and is
decoded to a map. A struct return is encoded into a segment per thread,
which the stub copies before the thread can call again.

In a native image, a callback with a struct by value, or with a shape
outside the registered set, is a libffi closure. `ffi_prep_closure_loc`
takes one handler for every signature:

```c
void handler(ffi_cif *cif, void *ret, void **args, void *user_data);
```

That handler is an upcall stub of four pointers and a `:void` return, a
shape every image registers. It decodes each argument from `args` with the
codecs of the struct downcall path and writes the result to `ret`, widening
an integer to a word as libffi requires. The cif, the ffi_type trees and
the handler live in the callback's arena. A cleanup on the returned pointer
calls `ffi_closure_free` when the arena closes.

The registered shapes keep their direct upcall stub. A build without libffi
refuses the rest when the callback is made.

Node.js refuses a struct callback, as it refuses a struct downcall:
node:ffi does not pass a struct by value.

## Consequences

The native callback limits in doc/guide.md apply only to builds without
libffi. `sizeof(ffi_closure)` is not known at run time, so the closure is
allocated at 256 bytes, the same approach as `cif-bytes`.
