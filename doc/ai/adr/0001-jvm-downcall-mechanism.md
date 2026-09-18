# ADR 0001: the JVM downcall mechanism

Status: accepted (2026-08-30).

## Context

On the JVM a binding from `cfn` called its FFM downcall handle through
`MethodHandle.invokeWithArguments`. That is the generic path: it checks and
boxes every argument on each call. Clojure cannot emit `invokeExact`, which
needs a signature-polymorphic call site that only javac produces.

Two more costs sat on every call, on the JVM and in a native image:

- `with-meta` on a fn. `AFunction.withMeta` returns an `AFunction$1`, a
  `RestFn` that packs the arguments into an `ArraySeq` and calls `applyTo`
  on the original fn. Every binding carried `:babashka.ffi/backend` this
  way. 0.8 vs 4.8 ns per call on the JVM, 13 to 29 ns in an image.
- Coercers returned `Object`, so an address or an integer above 127
  allocated a `Long` per argument, and the result was boxed twice.

Measured with criterium on `memcmp` (3 args), `abs` (1 int) and `strlen`
(1 pointer). coffi, which generates a class per signature with insn and
calls `invokeExact` from bytecode, is the reference.

| call | before | proxy | + Binding, prim coercers | + insn class | coffi |
|---|---|---|---|---|---|
| memcmp 3 args | 66 ns | 23 | 10.6 | 9.2 | 5.9 |
| abs 1 int | 50 ns | 19 | 7.5 | 6.6 | 5.4 |
| strlen pointer | 21 ns | 21 | 10.5 | 10.1 | 7.2 |

In babashka, an A/B of two images from one babashka commit: `abs` 55 to
43 ns, `strlen` 56 to 47, `memcmp` 92 to 67, `pow` 64 to 48, loop floor 18.
Image size +16 KB, 74,120 to 74,156 compilation units.

On a workload, decoding 100k rows of four int8 columns from a libpq
result: 157 ms before, 37 ms after, coffi 36 ms on the same decode loop.

## Decision

Three parts, all on main since #28.

1. On the JVM, `cfn` adapts the downcall handle once to an interface whose
   method takes every argument as a long and returns a long or nothing
   (`L0`-`L6`, `V0`-`V6`), and binds it with `MethodHandleProxies`.
   Doubles and floats travel as their raw bits through `filterArguments`
   and `filterReturnValue`. The JIT inlines the interface call. This lives
   in `babashka.ffi.impl.proxy`, resolved at load time with
   `requiring-resolve` under `(when-not native-image?)`, so an image never
   includes it. The helpers it needs come in as a map, so the namespace
   depends on nothing and loads in any order.
2. A binding is a `Binding` deftype, not a fn with metadata. It implements
   `IFn` with one `invoke` per arity that delegates directly, carries the
   meta, the symbol and the signature, throws `babashka.ffi: abs expects 1
   args, got 2` on a wrong arity, and prints as `abs [:int] -> :int`.
3. Coercers are `IFn$OL` fns returning a primitive long, called with
   `invokePrim`; the return fn is `IFn$LO`. `as-long` tests for a boxed
   `Long` first.

Rejected: generating the call class with insn, as coffi does. Branch
`insn-downcalls` measured it: about 1 ns per call and nothing on the
workload, for a dependency and 119 lines of bytecode emission. The gap that
remains to coffi is not the call mechanism. It is the outer fn that keeps
symbol resolution lazy, the `Binding` layer, and the pointer validity
checks that coffi does not perform.

Rejected: dropping the metadata and recomputing the backend from the
signature. It saves 48 bytes and about 1 ns, and loses the arity message
and the printed signature, which are only free because the type exists.

## Variadic calls, 2026-09-17

Use generated classes for variadic JVM calls (#44). A declared tail uses
one class with `firstVariadicArg`. An inferred tail caches one binding per
shape. Pack the shape into a long with two bits per value and cache the
last binding for repeated calls.

Measured with criterium quick-bench on macOS arm64, JDK 25,
using `snprintf(buf, 64, "%d", 42)` with the format as a pointer:

| Tail | invokeWithArguments | Generated class |
|---|---|---|
| Declared | 196 ns | 30 ns |
| Inferred | 371 ns | 86 ns |

Use generated classes for fixed and variadic signatures up to 20 arguments.
Use `invokeWithArguments` above the `AFn.invoke` limit of 20 arguments.

Reject booleans during tail inference. Integer coercion already rejected
them on the JVM and in babashka. Accepting booleans in the integer coercer
would also affect fixed arguments, struct fields and callback returns.
A separate boolean tail type would require promotion to `int` in libffi.

## Struct calls, 2026-09-18

Issue #44 item 4. The measurement it asked for put 40 to 55 percent of a
flat struct call in `invokeWithArguments` and 10 to 15 percent in the codec,
so the call went the same way as the rest: a generated class, one slot per
handle parameter, a long for a scalar, a `MemorySegment` for a struct and a
`SegmentAllocator` in front when the return is a struct. It is a second,
smaller class rather than a widening of the scalar one, which stays as it
is. babashka.ffi keeps the arena, the encoding and the decoding, so the
class only makes the call. More than 20 slots keeps `invokeWithArguments`,
because `AFn` invokes with at most 20.

criterium quick-bench, macOS AArch64, JDK 25, against the same session on
main. These numbers include the argument width change in ADR 0002, which
landed with them:

    p2_sum     flat struct argument       120 -> 72 ns
    v3_sum     three doubles by value     143 -> 98 ns
    rect_sum   nested struct argument     185 -> 110 ns
    div        struct return              127 -> 79 ns
    rect_swap  struct in and out          226 -> 179 ns

`rect_swap` gains least: its nested encode and decode are about 81 ns of the
total, which no change to the invocation reaches.

## Consequences

- A native image is unchanged in mechanism: trampolines when the shape has
  one, otherwise libffi or the interpreted FFM handle. It gains from
  `Binding` and the coercers only.
- `with-meta` on a fn is now a known trap in this codebase. Anything that
  hands out fns with metadata on a hot path should use a type instead.
- The JVM guide number is about 10 ns per primitive call.
- Bench scripts for these numbers are in the babashka.postgres notes,
  `dev-todo/babashka.ffi/bench/postgres/`, not in this repository.
