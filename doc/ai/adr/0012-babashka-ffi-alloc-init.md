# ADR 0012: alloc takes an init value

## Status

Accepted 2026-11-24. Implemented on branch `alloc-init` of babashka/ffi.

## Context

Many C APIs take an options struct in which a zero field means the default.
C callers fill one with a designated initializer, `&(cui_box){ .pad = 32 }`,
and set two of forty fields. CUI, sokol and raylib config structs work this
way.

`write` takes a struct value only with every field present, so the caller
either writes each field through a place or merges the value over a map of
zeros. Neither coffi nor jolt.ffi has a shorter form. coffi throws a
NullPointerException on a missing number field, and jolt.ffi has the same
rule as babashka.ffi. LuaJIT FFI (`ffi.new(ct, init)`) and Python ctypes
(`Box(pad=32)`) fill the named fields and zero the rest.

Cost of one `cui_box` with two fields set, in ns:

| approach | bb | JVM |
|---|---|---|
| alloc only | 265 | 262 |
| alloc, a place made per field | 830 | 613 |
| alloc, cached places | 673 | 359 |
| alloc, write merged over zeros | 3580 | 1463 |

## Decision

`(alloc arena layout init)` allocates zeroed memory and writes init to it.
A struct value may omit fields, and an array value may omit trailing
elements. An unknown field and an array value longer than the layout are
errors. A union value is a member pair, as in `write`. A string for a
`:string` field is copied into the same arena, so it lives as long as the
struct.

The third argument of `alloc` was the alignment. An integer stays an
alignment. Any other value is init, accepted only with a layout, so
`(alloc arena :int 8)` keeps its meaning.

The encoder takes an `init?` flag and the init codec is cached per layout,
next to the encode and decode codecs.

## Alternatives

Let `write` take a partial struct value. On memory that was written before,
the omitted fields keep their old bytes, and the missing-field error stops
catching a typo in a full value.

Add a separate function. `alloc` already takes the arena that a `:string`
field needs, and LuaJIT puts the init value on its allocator too.

## Consequences

ffi.cljs encoder functions take `(arena base v)`, as in ffi.clj, so a
`:string` field can be copied into the init arena on Node.js too.
