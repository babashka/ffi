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

## Consequences

- A native image is unchanged in mechanism: trampolines when the shape has
  one, otherwise libffi or the interpreted FFM handle. It gains from
  `Binding` and the coercers only.
- `with-meta` on a fn is now a known trap in this codebase. Anything that
  hands out fns with metadata on a hot path should use a type instead.
- The JVM guide number is about 10 ns per primitive call.
- Bench scripts for these numbers are in the babashka.postgres notes,
  `dev-todo/babashka.ffi/bench/postgres/`, not in this repository.
