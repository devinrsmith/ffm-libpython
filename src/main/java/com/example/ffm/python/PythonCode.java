package com.example.ffm.python;

import static com.example.ffm.python.capi.stable.CApi.PyErr_Print;
import static com.example.ffm.python.capi.stable.CApi.PyEval_EvalCode;
import static com.example.ffm.python.capi.stable.CApi.PyImport_AddModule;
import static com.example.ffm.python.capi.stable.CApi.PyModule_GetDict;
import static com.example.ffm.python.capi.stable.CApi.Py_CompileString;
import static com.example.ffm.python.capi.stable.CApi.Py_DecRef;
import static com.example.ffm.python.capi.stable.CApi.Py_IncRef;
import static com.example.ffm.python.capi.stable.CApi.Py_file_input;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Runs source code in whichever interpreter the calling thread is attached to. */
final class PythonCode {

    private PythonCode() {}

    /**
     * Runs Python source code in the current interpreter's {@code __main__} module,
     * like {@code PyRun_SimpleString} (which is not part of the Stable ABI). The
     * calling thread must be attached to the interpreter (hold its GIL).
     *
     * @throws PythonException if the code raised an exception; Python has already
     *     printed the traceback to stderr
     */
    static void run(String source) {
        try (Arena arena = Arena.ofConfined()) {
            // A borrowed reference, owned by sys.modules: which the code we are about
            // to run can change, e.g. `del sys.modules["__main__"]` frees the module
            // mid-run. Hold a strong reference until we are done, as
            // PyImport_AddModuleRef() (3.13+) would return.
            MemorySegment main = PyImport_AddModule(arena.allocateFrom("__main__"));
            if (main.equals(MemorySegment.NULL)) {
                throw pythonError("could not get the __main__ module");
            }
            Py_IncRef(main);
            try {
                // Borrowed from main, which we now hold.
                MemorySegment globals = PyModule_GetDict(main);

                // allocateFrom copies the Java string into native memory as
                // NUL-terminated UTF-8, which is what CPython expects; the arena frees
                // it afterwards (CPython keeps copies of what it needs).
                MemorySegment code = Py_CompileString(arena.allocateFrom(source), arena.allocateFrom("<string>"), Py_file_input());
                if (code.equals(MemorySegment.NULL)) {
                    throw pythonError("Python code failed to compile");
                }
                // New references, released as soon as they are no longer needed.
                MemorySegment result = PyEval_EvalCode(code, globals, globals);
                Py_DecRef(code);
                if (result.equals(MemorySegment.NULL)) {
                    throw pythonError("Python code raised an exception");
                }
                Py_DecRef(result);
            } finally {
                Py_DecRef(main);
            }
        }
    }

    /** Prints and clears the pending Python exception, returning a Java one to throw. */
    private static PythonException pythonError(String message) {
        PyErr_Print();
        return new PythonException(message + " (traceback printed to stderr)");
    }
}
