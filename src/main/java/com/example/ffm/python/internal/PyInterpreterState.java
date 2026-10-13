package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable.CApi.PyInterpreterState_Get;
import static com.example.ffm.python.capi.stable.CApi.PyInterpreterState_GetDict;
import static com.example.ffm.python.capi.stable.CApi.PyInterpreterState_GetID;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * A {@code PyInterpreterState *}: one interpreter, with the Stable ABI functions
 * that apply to it. Not a {@link PyObject} and not refcounted: it lives until the
 * interpreter ends. (The main interpreter is {@link Py312#interpreterStateMain()}:
 * that function is not in the Stable ABI.)
 */
public record PyInterpreterState(MemorySegment segment) {

    public PyInterpreterState {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Not an interpreter state: NULL");
        }
    }

    /** {@code PyInterpreterState_Get}: the interpreter the calling thread is attached to. */
    public static PyInterpreterState current() {
        return new PyInterpreterState(PyInterpreterState_Get());
    }

    /** {@code PyInterpreterState_GetID}: unique within the process, never reused. */
    public long id() {
        return PyInterpreterState_GetID(segment);
    }

    /**
     * {@code PyInterpreterState_GetDict}: the interpreter's dict for per-interpreter
     * data, created on first request. <b>Borrowed</b> from the interpreter. Empty
     * (with no exception set) only if CPython could not allocate it.
     */
    public Optional<PyObject.Dict> dict() {
        MemorySegment dict = PyInterpreterState_GetDict(segment);
        return dict.equals(MemorySegment.NULL) ? Optional.empty() : Optional.of(new PyObject.Dict(dict));
    }
}
