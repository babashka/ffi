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
