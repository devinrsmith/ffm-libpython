package com.example.ffm.python.internal;

import java.lang.foreign.MemorySegment;

/**
 * A pointer to a CPython object ({@code PyObject *}), typed by what it points to.
 *
 * <p>A bare {@code PyObject} is a <em>borrowed</em> reference: it may be used while
 * its owner keeps the object alive, but must never be released. An owned reference
 * is a {@link NewReference}, which releases it on {@code close()}. The typed wrappers
 * in {@link Py} and {@link Py312} return one or the other exactly as CPython
 * documents for each function.
 *
 * <p>The types are what CPython's documentation promises for each function; they
 * are not checked at runtime.
 */
public sealed interface PyObject permits PyObject.Any, PyObject.Code, PyObject.Dict, PyObject.Module {

    /** The object's address; never {@code NULL}. */
    MemorySegment segment();

    /** An object of no particular type, e.g. the result of running code. */
    record Any(MemorySegment segment) implements PyObject {
        public Any {
            requireObject(segment);
        }
    }

    /** A code object, from {@code Py_CompileString}. */
    record Code(MemorySegment segment) implements PyObject {
        public Code {
            requireObject(segment);
        }
    }

    /** A {@code dict}. */
    record Dict(MemorySegment segment) implements PyObject {
        public Dict {
            requireObject(segment);
        }
    }

    /** A module object. */
    record Module(MemorySegment segment) implements PyObject {
        public Module {
            requireObject(segment);
        }
    }

    private static void requireObject(MemorySegment segment) {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Not an object: NULL");
        }
    }
}
