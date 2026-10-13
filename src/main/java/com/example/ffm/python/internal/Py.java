package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable312.Stable312.PyErr_Print;
import static com.example.ffm.python.capi.stable312.Stable312.Py_DecRef;
import static com.example.ffm.python.capi.stable312.Stable312.Py_IncRef;

import com.example.ffm.python.PythonException;

/**
 * Plumbing shared by the typed wrappers: reference counting (for
 * {@link NewReference}) and error reporting. The functions themselves live on the
 * types they apply to ({@link PyObject}, {@link PyThreadState},
 * {@link PyInterpreterState}, {@link GilState}), all from the Stable ABI; the
 * version-specific ones are in {@link Py312}.
 */
final class Py {

    private Py() {}

    /** {@code Py_IncRef}; see {@link NewReference#incRef}. */
    static void incRef(PyObject object) {
        Py_IncRef(object.segment());
    }

    /** {@code Py_DecRef}; see {@link NewReference#close()}. */
    static void decRef(PyObject object) {
        Py_DecRef(object.segment());
    }

    /**
     * Prints and clears the pending Python exception ({@code PyErr_Print}), returning
     * a Java one to throw.
     *
     * <p>As with {@code PyRun_SimpleString}, a pending {@code SystemExit} exits the
     * whole process, JVM included.
     */
    static PythonException errorPrinted(String message) {
        PyErr_Print();
        return new PythonException(message + " (traceback printed to stderr)");
    }
}
