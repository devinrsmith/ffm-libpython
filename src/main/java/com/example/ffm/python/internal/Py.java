package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable.CApi.PyDict_Size;
import static com.example.ffm.python.capi.stable.CApi.PyErr_Print;
import static com.example.ffm.python.capi.stable.CApi.PyEval_EvalCode;
import static com.example.ffm.python.capi.stable.CApi.PyEval_RestoreThread;
import static com.example.ffm.python.capi.stable.CApi.PyEval_SaveThread;
import static com.example.ffm.python.capi.stable.CApi.PyGILState_Ensure;
import static com.example.ffm.python.capi.stable.CApi.PyGILState_Release;
import static com.example.ffm.python.capi.stable.CApi.PyImport_AddModule;
import static com.example.ffm.python.capi.stable.CApi.PyInterpreterState_Get;
import static com.example.ffm.python.capi.stable.CApi.PyInterpreterState_GetDict;
import static com.example.ffm.python.capi.stable.CApi.PyInterpreterState_GetID;
import static com.example.ffm.python.capi.stable.CApi.PyModule_GetDict;
import static com.example.ffm.python.capi.stable.CApi.PyThreadState_Get;
import static com.example.ffm.python.capi.stable.CApi.PyThreadState_GetID;
import static com.example.ffm.python.capi.stable.CApi.PyThreadState_GetInterpreter;
import static com.example.ffm.python.capi.stable.CApi.Py_CompileString;
import static com.example.ffm.python.capi.stable.CApi.Py_DecRef;
import static com.example.ffm.python.capi.stable.CApi.Py_EndInterpreter;
import static com.example.ffm.python.capi.stable.CApi.Py_IncRef;
import static com.example.ffm.python.capi.stable.CApi.Py_NewInterpreter;
import static com.example.ffm.python.capi.stable.CApi.Py_file_input;

import com.example.ffm.python.PythonException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * Typed wrappers for the Stable ABI functions this project calls with objects,
 * thread states or interpreter states (see {@code capi.stable.CApi} for the raw
 * bindings).
 *
 * <p>Conventions: a function CPython documents as returning a <em>borrowed</em>
 * reference returns a bare {@link PyObject}; one returning a <em>new</em> reference
 * returns a {@link NewReference}, to be closed. Where CPython signals an error with
 * {@code NULL} and a Python exception, the wrapper prints and clears the exception
 * and throws a {@link PythonException}. All of them must be called while attached
 * to an interpreter (holding its GIL), except where noted.
 */
public final class Py {

    private Py() {}

    // --- References ------------------------------------------------------------

    /** {@code Py_IncRef}; see {@link NewReference#incRef}. */
    static void incRef(PyObject object) {
        Py_IncRef(object.segment());
    }

    /** {@code Py_DecRef}; see {@link NewReference#close()}. */
    static void decRef(PyObject object) {
        Py_DecRef(object.segment());
    }

    // --- Errors ----------------------------------------------------------------

    /**
     * Prints and clears the pending Python exception ({@code PyErr_Print}), returning
     * a Java one to throw.
     *
     * <p>As with {@code PyRun_SimpleString}, a pending {@code SystemExit} exits the
     * whole process, JVM included.
     */
    public static PythonException errorPrinted(String message) {
        PyErr_Print();
        return new PythonException(message + " (traceback printed to stderr)");
    }

    // --- Modules and code ------------------------------------------------------

    /**
     * {@code PyImport_AddModule}: the module {@code name} from {@code sys.modules},
     * created if absent. <b>Borrowed</b> from {@code sys.modules}, which Python code
     * can change: take a {@link NewReference#incRef} before running any.
     */
    public static PyObject.Module importAddModule(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment module = PyImport_AddModule(arena.allocateFrom(name));
            if (module.equals(MemorySegment.NULL)) {
                throw errorPrinted("could not get the " + name + " module");
            }
            return new PyObject.Module(module);
        }
    }

    /** {@code PyModule_GetDict}: the module's namespace. <b>Borrowed</b> from the module. */
    public static PyObject.Dict moduleGetDict(PyObject.Module module) {
        return new PyObject.Dict(PyModule_GetDict(module.segment()));
    }

    /**
     * {@code Py_CompileString} with {@code Py_file_input}: compiles a module's worth
     * of statements. A <b>new</b> reference.
     *
     * @throws PythonException if it does not compile
     */
    public static NewReference<PyObject.Code> compileFile(String source, String filename) {
        try (Arena arena = Arena.ofConfined()) {
            // NUL-terminated UTF-8 copies, freed with the arena; CPython copies what it keeps.
            MemorySegment code = Py_CompileString(arena.allocateFrom(source), arena.allocateFrom(filename), Py_file_input());
            if (code.equals(MemorySegment.NULL)) {
                throw errorPrinted("Python code failed to compile");
            }
            return NewReference.adopt(new PyObject.Code(code));
        }
    }

    /**
     * {@code PyEval_EvalCode}: runs code with the given namespaces, which it borrows.
     * Returns the result, a <b>new</b> reference.
     *
     * @throws PythonException if the code raised an exception
     */
    public static NewReference<PyObject.Any> evalCode(PyObject.Code code, PyObject.Dict globals, PyObject.Dict locals) {
        MemorySegment result = PyEval_EvalCode(code.segment(), globals.segment(), locals.segment());
        if (result.equals(MemorySegment.NULL)) {
            throw errorPrinted("Python code raised an exception");
        }
        return NewReference.adopt(new PyObject.Any(result));
    }

    /**
     * {@code PyDict_Size}.
     *
     * @throws PythonException if CPython reports an error (only for a non-dict)
     */
    public static long dictSize(PyObject.Dict dict) {
        long size = PyDict_Size(dict.segment());
        if (size < 0) {
            throw errorPrinted("PyDict_Size failed");
        }
        return size;
    }

    // --- Thread states and the GIL ----------------------------------------------

    /** {@code PyThreadState_Get}: the calling thread's current thread state. */
    public static PyThreadState threadStateGet() {
        // CPython aborts the process rather than return NULL.
        return new PyThreadState(PyThreadState_Get());
    }

    /** {@code PyEval_SaveThread}: detaches, releasing the GIL; returns the detached state. */
    public static PyThreadState evalSaveThread() {
        return new PyThreadState(PyEval_SaveThread());
    }

    /** {@code PyEval_RestoreThread}: takes the state's GIL and attaches to it. Needs no GIL. */
    public static void evalRestoreThread(PyThreadState threadState) {
        PyEval_RestoreThread(threadState.segment());
    }

    /** {@code PyGILState_Ensure}: attaches the calling thread to the main interpreter. Needs no GIL. */
    public static GilState gilStateEnsure() {
        return new GilState(PyGILState_Ensure());
    }

    /** {@code PyGILState_Release}: undoes the {@link #gilStateEnsure()} that returned {@code state}. */
    public static void gilStateRelease(GilState state) {
        PyGILState_Release(state.value());
    }

    // --- Sub-interpreters ------------------------------------------------------

    /**
     * {@code Py_NewInterpreter}: a sub-interpreter sharing the main GIL. Returns its
     * thread state, which is now current.
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

    /**
     * {@code Py_EndInterpreter}: ends the sub-interpreter whose (current) thread state
     * this is. Afterwards no thread state is current, no GIL is held, and
     * {@code threadState} must not be used again.
     */
    public static void endInterpreter(PyThreadState threadState) {
        Py_EndInterpreter(threadState.segment());
    }

    // --- Interpreter diagnostics -----------------------------------------------

    /** {@code PyInterpreterState_Get}: the interpreter the calling thread is attached to. */
    public static PyInterpreterState interpreterStateGet() {
        return new PyInterpreterState(PyInterpreterState_Get());
    }

    /** {@code PyThreadState_GetInterpreter}. */
    public static PyInterpreterState threadStateGetInterpreter(PyThreadState threadState) {
        return new PyInterpreterState(PyThreadState_GetInterpreter(threadState.segment()));
    }

    /** {@code PyInterpreterState_GetID}: unique within the process, never reused. */
    public static long interpreterStateGetId(PyInterpreterState interpreter) {
        return PyInterpreterState_GetID(interpreter.segment());
    }

    /** {@code PyThreadState_GetID}: numbered per interpreter. */
    public static long threadStateGetId(PyThreadState threadState) {
        return PyThreadState_GetID(threadState.segment());
    }

    /**
     * {@code PyInterpreterState_GetDict}: the interpreter's dict for per-interpreter
     * data, created on first request. <b>Borrowed</b> from the interpreter. Empty
     * (with no exception set) only if CPython could not allocate it.
     */
    public static Optional<PyObject.Dict> interpreterStateGetDict(PyInterpreterState interpreter) {
        MemorySegment dict = PyInterpreterState_GetDict(interpreter.segment());
        return dict.equals(MemorySegment.NULL) ? Optional.empty() : Optional.of(new PyObject.Dict(dict));
    }
}
