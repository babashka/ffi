# ADR 0012: alloc-from writes an init value to new memory

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

`(alloc-from arena t init)` allocates zeroed memory of type t, with natural
alignment, and writes init to it. t is a type keyword or a layout. A struct
value may omit fields, and an array value may omit trailing elements. An
unknown field and an array value longer than the layout are errors. A union
value is a member pair, as in `write`. A string for a `:string` field, or for
t `:string`, is copied into the same arena, so it lives as long as the
memory.

The name follows FFM's `SegmentAllocator.allocateFrom(layout, value)`. It
differs from FFM in two ways. FFM's varargs `allocateFrom(JAVA_INT, 1, 2, 3)`
takes its size from the values, and alloc-from takes it from the layout and
pads a short value with zeros. A struct init value is a babashka.ffi
addition.

The encoder takes an `init?` flag and the init codec is cached per layout,
next to the encode and decode codecs.

alloc-from takes no alignment. A 4-arity `(alloc-from arena t alignment
init)` can be added later.

## Alternatives

Let the third argument of `alloc` be the alignment if it is an integer and
init otherwise. FFM, coffi and jolt.ffi take an alignment on the allocator,
and none reads one argument as an alignment or a value by its type.
`(alloc arena [:array :int 4] 8)` and `(alloc arena [:array :int 4] [8])`
would look alike and do different things, and LuaJIT's
`ffi.new("int[4]", 8)` fills all four elements with 8. `(alloc arena :int 42)`
must stay an alignment, so a scalar could not take a start value. An
alignment given as `8.0` or `nil` with a layout would become init and throw a
struct error.

Let `write` take a partial struct value. On memory that was written before,
the omitted fields keep their old bytes, and the missing-field error stops
catching a typo in a full value.

## Consequences

ffi.cljs encoder functions take `(arena base v)`, as in ffi.clj, so a
`:string` field can be copied into the init arena on Node.js too.

Mean times on the JVM with criterium, for `cui_box`, in ns. The arena
returns the same 4 KB block on each allocation, which takes 3.5 ns, so the
times show the work babashka.ffi does around it:

| change | call | before | after |
|---|---|---|---|
| size-and-alignment resolves the layout once | alloc of a layout | 114 | 75 |
| a type keyword goes to write | alloc-from :int 42 | 109 | 51 |
| alloc-from resolves the layout once | alloc-from, 2 fields | 218 | 99 |

A cache from the layout vector to its size, alignment and init encoder
brought alloc-from with 2 fields to 52 ns. It was dropped because it is a
third cache on the same key as the layout and codec caches. Most of the
difference is the layout-of lookup, 38 ns for this layout, which a faster
layout cache would remove for every caller.
