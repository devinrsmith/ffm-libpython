package com.example.ffm.python;

import com.example.ffm.python.internal.NewReference;
import com.example.ffm.python.internal.PyObject;

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
        // A strong reference: sys.modules, which owns __main__, can be changed by the
        // code we are about to run (`del sys.modules["__main__"]` would free it mid-run).
        try (NewReference<PyObject.Module> main = PyObject.Module.importAddRef("__main__")) {
            // Borrowed from main, which we hold.
            PyObject.Dict globals = main.get().dict();
            try (NewReference<PyObject.Code> code = PyObject.Code.compile(source, "<string>")) {
                // Running a module evaluates to None: release the result right away.
                code.get().eval(globals, globals).close();
            }
        }
    }
}
