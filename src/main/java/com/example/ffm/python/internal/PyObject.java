package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable.CApi.PyDict_Size;
import static com.example.ffm.python.capi.stable.CApi.PyEval_EvalCode;
import static com.example.ffm.python.capi.stable.CApi.PyImport_AddModule;
import static com.example.ffm.python.capi.stable.CApi.PyModule_GetDict;
import static com.example.ffm.python.capi.stable.CApi.Py_CompileString;
import static com.example.ffm.python.capi.stable.CApi.Py_file_input;

import com.example.ffm.python.PythonException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A pointer to a CPython object ({@code PyObject *}), typed by what it points to,
 * with the Stable ABI functions that apply to it.
 *
 * <p>A bare {@code PyObject} is a <em>borrowed</em> reference: it may be used while
 * its owner keeps the object alive, but must never be released. An owned reference
 * is a {@link NewReference}, which releases it on {@code close()}. Each method
 * returns one or the other exactly as CPython documents for the function it wraps.
 * Where CPython signals an error with {@code NULL} and a Python exception, the
 * method prints and clears the exception and throws a {@link PythonException}.
 *
 * <p>All methods must be called while attached to the interpreter that owns the
 * object (holding its GIL). The types are what CPython's documentation promises for
 * each function; they are not checked at runtime.
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

    /** A code object. */
    record Code(MemorySegment segment) implements PyObject {
        public Code {
            requireObject(segment);
        }

        /**
         * {@code Py_CompileString} with {@code Py_file_input}: compiles a module's
         * worth of statements. A <b>new</b> reference.
         *
         * @throws PythonException if it does not compile
         */
        public static NewReference<Code> compile(String source, String filename) {
            try (Arena arena = Arena.ofConfined()) {
                // NUL-terminated UTF-8 copies, freed with the arena; CPython copies what it keeps.
                MemorySegment code = Py_CompileString(arena.allocateFrom(source), arena.allocateFrom(filename), Py_file_input());
                if (code.equals(MemorySegment.NULL)) {
                    throw Py.errorPrinted("Python code failed to compile");
                }
                return NewReference.adopt(new Code(code));
            }
        }

        /**
         * {@code PyEval_EvalCode}: runs this code with the given namespaces, which it
         * borrows. Returns the result, a <b>new</b> reference.
         *
         * @throws PythonException if the code raised an exception
         */
        public NewReference<Any> eval(Dict globals, Dict locals) {
            MemorySegment result = PyEval_EvalCode(segment, globals.segment(), locals.segment());
            if (result.equals(MemorySegment.NULL)) {
                throw Py.errorPrinted("Python code raised an exception");
            }
            return NewReference.adopt(new Any(result));
        }
    }

    /** A {@code dict}. */
    record Dict(MemorySegment segment) implements PyObject {
        public Dict {
            requireObject(segment);
        }

        /**
         * {@code PyDict_Size}.
         *
         * @throws PythonException if CPython reports an error (only for a non-dict)
         */
        public long size() {
            long size = PyDict_Size(segment);
            if (size < 0) {
                throw Py.errorPrinted("PyDict_Size failed");
            }
            return size;
        }
    }

    /** A module object. */
    record Module(MemorySegment segment) implements PyObject {
        public Module {
            requireObject(segment);
        }

        /**
         * {@code PyImport_AddModule}: the module {@code name} from {@code sys.modules},
         * created if absent. <b>Borrowed</b> from {@code sys.modules}, which Python
         * code can change: take a {@link NewReference#incRef} before running any.
         *
         * @throws PythonException if CPython fails to get or create it
         */
        public static Module importAdd(String name) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment module = PyImport_AddModule(arena.allocateFrom(name));
                if (module.equals(MemorySegment.NULL)) {
                    throw Py.errorPrinted("could not get the " + name + " module");
                }
                return new Module(module);
            }
        }

        /** {@code PyModule_GetDict}: the module's namespace. <b>Borrowed</b> from the module. */
        public Dict dict() {
            return new Dict(PyModule_GetDict(segment));
        }
    }

    private static void requireObject(MemorySegment segment) {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Not an object: NULL");
        }
    }
}
