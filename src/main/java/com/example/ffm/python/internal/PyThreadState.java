package com.example.ffm.python.internal;

import java.lang.foreign.MemorySegment;

/**
 * A {@code PyThreadState *}: a thread's state in one interpreter. Not a
 * {@link PyObject} and not refcounted: it lives until CPython deletes it (e.g. in
 * {@code Py_EndInterpreter}), so holders must not use it after that.
 */
public record PyThreadState(MemorySegment segment) {
    public PyThreadState {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Not a thread state: NULL");
        }
    }
}
