/* Struct-by-value fixture for the babashka.ffi test suite. Struct RETURNS
 * are covered by libc div; nothing portable in libc takes a struct by
 * value, so these functions do. */

#include <stdint.h>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT
#endif

typedef struct { int32_t x, y; } P2;         /* 8 bytes: one integer register */
typedef struct { double x, y, z; } V3;       /* 24-byte HFA on Arm64 */
typedef struct { int64_t a, b, c, d; } Big;  /* 32 bytes: passed in memory */
typedef struct { char c; double d; } Pad;    /* padding after the first field */
typedef struct { P2 lo; P2 hi; } Rect;       /* nested */
typedef struct { int32_t id; const char *name; } Named;  /* a pointer field */

EXPORT int32_t p2_sum(P2 p) { return p.x + p.y; }
EXPORT double  v3_sum(V3 v) { return v.x + v.y + v.z; }
EXPORT int64_t big_sum(Big b) { return b.a + b.b + b.c + b.d; }
EXPORT double  pad_sum(Pad p) { return p.c + p.d; }
EXPORT int32_t rect_sum(Rect r) { return r.lo.x + r.lo.y + r.hi.x + r.hi.y; }

/* a struct argument mixed with scalars, and one after a scalar */
EXPORT double mixed_sum(int32_t a, P2 p, double d, V3 v) {
  return a + p.x + p.y + d + v.x + v.y + v.z;
}

/* reads through a pointer held in a struct field */
EXPORT int32_t named_len(Named n) {
  int32_t i = 0;
  while (n.name[i]) i++;
  return i + n.id;
}

/* struct in and struct out in one call */
EXPORT Rect rect_swap(Rect r) {
  Rect o = { { r.hi.x, r.hi.y }, { r.lo.x, r.lo.y } };
  return o;
}

/* a struct argument with a :void return */
EXPORT void p2_store(P2 p, int32_t *out) { *out = p.x * 100 + p.y; }

/* -- fixed arrays inside structs ------------------------------------------ */

typedef struct { int32_t v[4]; } Quad;               /* 16 bytes: two integer registers */
typedef struct { char name[32]; int32_t parent; } Bone;  /* raylib BoneInfo: 36 bytes, passed in memory */
typedef struct { double m[2][2]; } Mat2;             /* a two-dimensional array */
typedef struct { P2 pts[2]; } Pair;                  /* an array of structs */

EXPORT int32_t quad_sum(Quad q) { return q.v[0] + q.v[1] + q.v[2] + q.v[3]; }
EXPORT Quad quad_make(int32_t a) { Quad q = { { a, a + 1, a + 2, a + 3 } }; return q; }
EXPORT int32_t bone_len(Bone b) {
  int32_t i = 0;
  while (b.name[i]) i++;
  return i + b.parent;
}
EXPORT Bone bone_make(int32_t parent) { Bone b = { "spine", parent }; return b; }
EXPORT double mat2_trace(Mat2 m) { return m.m[0][0] + m.m[1][1]; }
EXPORT int32_t pair_sum(Pair p) { return p.pts[0].x + p.pts[0].y + p.pts[1].x + p.pts[1].y; }

/* -- a union inside a struct, reached through a pointer ---------------------- */

typedef struct {
  int32_t tag;                       /* 0: i, 1: d, 2: s */
  union { int32_t i; double d; const char *s; } u;   /* 8 bytes at offset 8 */
} Tagged;

EXPORT void tagged_fill(Tagged *t, int32_t tag) {
  t->tag = tag;
  if (tag == 0) t->u.i = 42;
  else if (tag == 1) t->u.d = 2.5;
  else t->u.s = "union";
}

/* reads the member the tag names, so a wrong offset on the caller's side shows */
EXPORT double tagged_value(const Tagged *t) {
  if (t->tag == 0) return (double) t->u.i;
  if (t->tag == 1) return t->u.d;
  return (double) (int) t->u.s[0];   /* 'u' */
}

/* 21 parameters, one of them a struct: more slots than a generated class
 * takes, so the call falls back to the generic invoker */
EXPORT int32_t wide_struct_sum(P2 p, int32_t a1, int32_t a2, int32_t a3, int32_t a4,
                               int32_t a5, int32_t a6, int32_t a7, int32_t a8,
                               int32_t a9, int32_t a10, int32_t a11, int32_t a12,
                               int32_t a13, int32_t a14, int32_t a15, int32_t a16,
                               int32_t a17, int32_t a18, int32_t a19, int32_t a20) {
  return p.x + p.y + a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10
       + a11 + a12 + a13 + a14 + a15 + a16 + a17 + a18 + a19 + a20;
}

/* 12 int parameters, no struct: more than the integer argument registers,
 * so the ones past them travel on the stack */
EXPORT int32_t wide_int_sum(int32_t a1, int32_t a2, int32_t a3, int32_t a4,
                            int32_t a5, int32_t a6, int32_t a7, int32_t a8,
                            int32_t a9, int32_t a10, int32_t a11, int32_t a12) {
  return a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12;
}

/* 10 int parameters: inside babashka's compiled trampoline set, and past
 * the integer argument registers on Arm64 */
EXPORT int32_t ten_int_sum(int32_t a1, int32_t a2, int32_t a3, int32_t a4, int32_t a5,
                           int32_t a6, int32_t a7, int32_t a8, int32_t a9, int32_t a10) {
  return a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10;
}

/* C calls back with ten int arguments, so the ones past the argument
 * registers reach the callback off the stack */
typedef int32_t (*ten_cb)(int32_t, int32_t, int32_t, int32_t, int32_t,
                          int32_t, int32_t, int32_t, int32_t, int32_t);
EXPORT int32_t call_with_ten(ten_cb f) { return f(1, 2, 3, 4, 5, 6, 7, 8, 9, 10); }

/* C calls back with negative narrow integers. A 32-bit write leaves the
 * upper half of the register zero, so a callback that declares them wider
 * than C does reads them unsigned */
typedef int64_t (*neg_cb)(int32_t, int32_t);
EXPORT int64_t call_with_negatives(neg_cb f) { return f(-1, -2); }

typedef int64_t (*narrow_cb)(int8_t, int16_t, int32_t);
EXPORT int64_t call_with_narrow(narrow_cb f) { return f(-3, -4, -5); }

/* eight int parameters: the widest pure-integer shape that stays inside the
 * argument registers on AArch64, so a trampoline serves it everywhere */
EXPORT int32_t eight_int_sum(int32_t a1, int32_t a2, int32_t a3, int32_t a4,
                             int32_t a5, int32_t a6, int32_t a7, int32_t a8) {
  return a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8;
}

/* ten 64-bit parameters: the ones on the stack are as wide as the long a
 * trampoline passes them as, so a trampoline serves this on every ABI */
EXPORT int64_t ten_long_sum(int64_t a1, int64_t a2, int64_t a3, int64_t a4, int64_t a5,
                            int64_t a6, int64_t a7, int64_t a8, int64_t a9, int64_t a10) {
  return a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10;
}

/* a double before an integer: one shape where the arguments are sorted, and
 * a shape of its own on Windows, which assigns registers by position */
EXPORT double double_then_long(double d, int64_t l) { return d + (double) l; }

typedef double (*dl_cb)(double, int64_t);
EXPORT double call_double_then_long(dl_cb f) { return f(1.5, 2); }
