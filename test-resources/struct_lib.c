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
