package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable312.Stable312.PyGILState_Ensure;
import static com.example.ffm.python.capi.stable312.Stable312.PyGILState_Release;

/**
 * The token {@code PyGILState_Ensure()} returns, to be given back to exactly one
 * {@code PyGILState_Release()} on the same thread.
 */
public record GilState(int value) {

    /** {@code PyGILState_Ensure}: attaches the calling thread to the main interpreter. Needs no GIL. */
    public static GilState ensure() {
        return new GilState(PyGILState_Ensure());
    }

    /** {@code PyGILState_Release}: undoes the {@link #ensure()} that returned this token. */
    public void release() {
        PyGILState_Release(value);
    }
}
