package babashka.ffi.impl;

import clojure.lang.ArraySeq;
import clojure.lang.IFn;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

/** The handler of every libffi closure in a native image.
 *
 * A native image runs an upcall through a method handle that it did not
 * compile in an interpreter, at about 2 us a call. A static target that the
 * image registers as a direct upcall is compiled instead. It cannot carry
 * the function to call, so each closure passes an index into a table as its
 * user data, and the handler looks the closure up there.
 *
 * The handler reads the arguments itself, through raw pointers: a read
 * through a MemorySegment costs about 30 ns in an image, a raw read is a
 * load. The type codes are the ones babashka.ffi passes to register. */
public final class Upcall {
    private Upcall() {}

    // argument codes
    static final int I32 = 0, U32 = 1, I64 = 2, PTR = 3, I16 = 4, U16 = 5, BOOL = 6,
        I8 = 7, U8 = 8, F64 = 9, F32 = 10, ADDR = 11, DECODE = 12;
    // return codes
    static final int R_VOID = 0, R_LONG = 1, R_DOUBLE = 2, R_FLOAT = 3, R_ENCODE = 4;

    private static final class Entry {
        final IFn f;
        final int[] kinds;
        final Object[] decs;
        final long[] sizes;
        final int retKind;
        final IFn ret;
        final long retSize;

        Entry(IFn f, int[] kinds, Object[] decs, long[] sizes, int retKind, IFn ret, long retSize) {
            this.f = f; this.kinds = kinds; this.decs = decs; this.sizes = sizes;
            this.retKind = retKind; this.ret = ret; this.retSize = retSize;
        }
    }

    private static volatile Entry[] entries = new Entry[64];
    private static int next = 0;
    private static int[] free = new int[16];
    private static int freeCount = 0;

    /** Stores a closure and returns its index.
     * f is the function to call, kinds the argument codes. decs holds a
     * decoder for each DECODE argument and sizes its byte size. ret coerces
     * a scalar result to a number, or writes an R_ENCODE result into a
     * segment of retSize bytes. */
    public static synchronized long register(IFn f, int[] kinds, Object[] decs, long[] sizes,
                                             int retKind, IFn ret, long retSize) {
        int i;
        if (freeCount > 0) {
            i = free[--freeCount];
        } else {
            i = next++;
            if (i == entries.length) {
                Entry[] grown = new Entry[entries.length * 2];
                System.arraycopy(entries, 0, grown, 0, entries.length);
                entries = grown;
            }
        }
        Entry[] t = entries;
        t[i] = new Entry(f, kinds, decs, sizes, retKind, ret, retSize);
        entries = t;
        return i;
    }

    /** Clears the index i for reuse. */
    public static synchronized void release(long i) {
        Entry[] t = entries;
        t[(int) i] = null;
        entries = t;
        if (freeCount == free.length) {
            int[] grown = new int[free.length * 2];
            System.arraycopy(free, 0, grown, 0, free.length);
            free = grown;
        }
        free[freeCount++] = (int) i;
    }

    private static Pointer ptr(long address) {
        return WordFactory.pointer(address);
    }

    private static Object arg(Entry e, long argv, int i) {
        long p = ptr(argv).readLong(8 * i);
        switch (e.kinds[i]) {
            case I32: return (long) ptr(p).readInt(0);
            case U32: return ptr(p).readInt(0) & 0xFFFFFFFFL;
            case I64: return ptr(p).readLong(0);
            case PTR: return MemorySegment.ofAddress(ptr(p).readLong(0));
            case I16: return (long) ptr(p).readShort(0);
            case U16: return ptr(p).readShort(0) & 0xFFFFL;
            case BOOL: return ptr(p).readByte(0) != 0;
            case I8: return (long) ptr(p).readByte(0);
            case U8: return ptr(p).readByte(0) & 0xFFL;
            case F64: return ptr(p).readDouble(0);
            case F32: return ptr(p).readFloat(0);
            case ADDR: return ptr(p).readLong(0);
            default: return ((IFn) e.decs[i]).invoke(MemorySegment.ofAddress(p).reinterpret(e.sizes[i]));
        }
    }

    /** The libffi closure handler: void (*)(ffi_cif *, void *ret, void **args, void *user_data). */
    public static void closure(MemorySegment cif, MemorySegment ret, MemorySegment args, MemorySegment userData) {
        Entry e = entries[(int) userData.address()];
        long a = args.address();
        IFn f = e.f;
        Object r;
        switch (e.kinds.length) {
            case 0: r = f.invoke(); break;
            case 1: r = f.invoke(arg(e, a, 0)); break;
            case 2: r = f.invoke(arg(e, a, 0), arg(e, a, 1)); break;
            case 3: r = f.invoke(arg(e, a, 0), arg(e, a, 1), arg(e, a, 2)); break;
            case 4: r = f.invoke(arg(e, a, 0), arg(e, a, 1), arg(e, a, 2), arg(e, a, 3)); break;
            default: {
                Object[] xs = new Object[e.kinds.length];
                for (int i = 0; i < xs.length; i++) xs[i] = arg(e, a, i);
                r = f.applyTo(ArraySeq.create(xs));
            }
        }
        long out = ret.address();
        switch (e.retKind) {
            case R_VOID: break;
            // libffi widens an integer return to ffi_arg, a word
            case R_LONG: ptr(out).writeLong(0, ((Number) e.ret.invoke(r)).longValue()); break;
            case R_DOUBLE: ptr(out).writeDouble(0, ((Number) e.ret.invoke(r)).doubleValue()); break;
            case R_FLOAT: ptr(out).writeFloat(0, ((Number) e.ret.invoke(r)).floatValue()); break;
            default: e.ret.invoke(MemorySegment.ofAddress(out).reinterpret(e.retSize), r);
        }
    }

    // Made on first use, not in a static initializer: an image may
    // initialize this class when it is built, and a stub made then does not
    // exist at run time.
    private static long stub = 0;

    /** The address of the one upcall stub that every closure calls. */
    public static synchronized long stub() {
        if (stub == 0) {
            try {
                stub = Linker.nativeLinker().upcallStub(
                    MethodHandles.lookup().findStatic(Upcall.class, "closure",
                        MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                              MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                              ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    Arena.global()).address();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return stub;
    }
}
