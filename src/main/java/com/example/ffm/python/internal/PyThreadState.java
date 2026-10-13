package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable.CApi.PyEval_RestoreThread;
import static com.example.ffm.python.capi.stable.CApi.PyEval_SaveThread;
import static com.example.ffm.python.capi.stable.CApi.PyThreadState_Get;
import static com.example.ffm.python.capi.stable.CApi.PyThreadState_GetID;
import static com.example.ffm.python.capi.stable.CApi.PyThreadState_GetInterpreter;
import static com.example.ffm.python.capi.stable.CApi.Py_EndInterpreter;
import static com.example.ffm.python.capi.stable.CApi.Py_NewInterpreter;

import com.example.ffm.python.PythonException;
import java.lang.foreign.MemorySegment;
import java.util.function.Supplier;

/**
 * A {@code PyThreadState *}: a thread's state in one interpreter, with the Stable ABI
 * functions that apply to it. Not a {@link PyObject} and not refcounted: it lives
 * until CPython deletes it (e.g. in {@link #endInterpreter()}), so holders must not
 * use it after that.
 */
public record PyThreadState(MemorySegment segment) {

    public PyThreadState {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Not a thread state: NULL");
        }
    }

    /** {@code PyThreadState_Get}: the calling thread's current thread state. */
    public static PyThreadState current() {
        // CPython aborts the process rather than return NULL.
        return new PyThreadState(PyThreadState_Get());
    }

    /**
     * {@code PyEval_SaveThread}: detaches the calling thread from its current thread
     * state, releasing that state's GIL, and returns the detached state.
     */
    public static PyThreadState detachCurrent() {
        return new PyThreadState(PyEval_SaveThread());
    }

    /**
     * {@code Py_NewInterpreter}: creates a sub-interpreter sharing the main GIL.
     * Returns its thread state, which is now current.
     *
     * @throws PythonException if CPython fails to create it
     */
    public static PyThreadState newInterpreter() {
        MemorySegment threadState = Py_NewInterpreter();
        if (threadState.equals(MemorySegment.NULL)) {
            throw new PythonException("Py_NewInterpreter failed");
        }
        return new PyThreadState(threadState);
    }

    /** {@code PyEval_RestoreThread}: takes this state's GIL and attaches to it. Needs no GIL. */
    public void attach() {
        PyEval_RestoreThread(segment);
    }

    /**
     * Attaches to this thread state for the duration of {@code action}, then detaches
     * again ({@link #attach()} ... {@link #detachCurrent()}), even if it throws.
     */
    public void runAttached(Runnable action) {
        attach();
        try {
            action.run();
        } finally {
            detachCurrent();
        }
    }

    /** As {@link #runAttached}, returning what {@code action} returns. */
    public <T> T callAttached(Supplier<T> action) {
        attach();
        try {
            return action.get();
        } finally {
            detachCurrent();
        }
    }

    /**
     * {@code Py_EndInterpreter}: ends the sub-interpreter whose (current) thread state
     * this is. Afterwards no thread state is current, no GIL is held, and this
     * thread state must not be used again.
     */
    public void endInterpreter() {
        Py_EndInterpreter(segment);
    }

    /** {@code PyThreadState_GetInterpreter}: the interpreter this thread state belongs to. */
    public PyInterpreterState interpreter() {
        return new PyInterpreterState(PyThreadState_GetInterpreter(segment));
    }

    /** {@code PyThreadState_GetID}: numbered per interpreter. */
    public long id() {
        return PyThreadState_GetID(segment);
    }
}
