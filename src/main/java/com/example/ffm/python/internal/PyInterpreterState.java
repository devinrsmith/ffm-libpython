package com.example.ffm.python.internal;

import java.lang.foreign.MemorySegment;

/**
 * A {@code PyInterpreterState *}: one interpreter. Not a {@link PyObject} and not
 * refcounted: it lives until the interpreter ends.
 */
public record PyInterpreterState(MemorySegment segment) {
    public PyInterpreterState {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Not an interpreter state: NULL");
        }
    }
}
