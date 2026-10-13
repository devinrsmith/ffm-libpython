package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable312.Stable312.PyDict_Size;
import static com.example.ffm.python.capi.stable312.Stable312.PyList_GetItem;
import static com.example.ffm.python.capi.stable312.Stable312.PyList_Size;
import static com.example.ffm.python.capi.stable312.Stable312.PyType_GetFlags;
import static com.example.ffm.python.capi.stable312.Stable312.PyUnicode_AsUTF8AndSize;
import static com.example.ffm.python.capi.stable312.Stable312.PyEval_EvalCode;
import static com.example.ffm.python.capi.stable312.Stable312.PyImport_AddModule;
import static com.example.ffm.python.capi.stable312.Stable312.PyModule_GetDict;
import static com.example.ffm.python.capi.stable312.Stable312.Py_CompileString;
import static com.example.ffm.python.capi.stable312.Stable312.Py_file_input;
import static com.example.ffm.python.capi.stable313.Stable313.PyImport_AddModuleRef;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.example.ffm.python.PythonException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
 * <p>Methods use only the Stable ABI: the 3.12 level, plus 3.13 additions behind a
 * runtime check of the loaded library's version ({@link ApiLevel}).
 *
 * <p>All methods must be called while attached to the interpreter that owns the
 * object (holding its GIL). The types are what CPython's documentation promises for
 * each function; they are not checked at runtime.
 */
public sealed interface PyObject
        permits PyObject.Any, PyObject.Code, PyObject.Dict, PyObject.List, PyObject.Module, PyObject.Str, PyObject.Type {

    /** The object's address; never {@code NULL}. */
    MemorySegment segment();

    /** {@code Py_TYPE}: the object's type, <b>borrowed</b> from the object. */
    default Type type() {
        return HeaderInlines.pyType(this);
    }

    /**
     * {@code Py_IS_TYPE}: whether the object's type is exactly {@code type}; not a
     * subclass of it (as C's {@code Py*_CheckExact} macros).
     */
    default boolean isType(Type type) {
        return HeaderInlines.pyIsType(this, type);
    }

    /** An object of no particular type, e.g. the result of running code. */
    record Any(MemorySegment segment) implements PyObject {
        public Any {
            requireObject(segment);
        }

        /**
         * This object as a {@code str}, if it is exactly one ({@code PyUnicode_CheckExact}):
         * the same object, so a reference with the same ownership as this one.
         */
        public Optional<Str> asStr() {
            return isType(Type.unicode()) ? Optional.of(new Str(segment)) : Optional.empty();
        }

        /** This object as a {@code list}, if it is exactly one; see {@link #asStr()}. */
        public Optional<List> asList() {
            return isType(Type.list()) ? Optional.of(new List(segment)) : Optional.empty();
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

    /** A {@code list}. */
    record List(MemorySegment segment) implements PyObject {
        public List {
            requireObject(segment);
        }

        /**
         * {@code PyList_Size}.
         *
         * @throws PythonException if CPython reports an error (only for a non-list)
         */
        public long size() {
            long size = PyList_Size(segment);
            if (size < 0) {
                throw Py.errorPrinted("PyList_Size failed");
            }
            return size;
        }

        /**
         * {@code PyList_GetItem}: the item at {@code index}. <b>Borrowed</b> from the
         * list, which Python code could change: use it before running any.
         *
         * @throws PythonException if {@code index} is out of range
         */
        public Any get(long index) {
            MemorySegment item = PyList_GetItem(segment, index);
            if (item.equals(MemorySegment.NULL)) {
                throw Py.errorPrinted("PyList_GetItem failed");
            }
            return new Any(item);
        }
    }

    /** A {@code str}. */
    record Str(MemorySegment segment) implements PyObject {
        public Str {
            requireObject(segment);
        }

        /**
         * The string's value, via {@code PyUnicode_AsUTF8AndSize}. CPython owns and
         * caches the UTF-8 buffer in the object; it is copied out at once.
         *
         * @throws PythonException if it cannot be encoded as UTF-8 (lone
         *     surrogates, e.g. from undecodable file names)
         */
        public String value() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment size = arena.allocate(JAVA_LONG); // Py_ssize_t
                MemorySegment utf8 = PyUnicode_AsUTF8AndSize(segment, size);
                if (utf8.equals(MemorySegment.NULL)) {
                    throw Py.errorPrinted("str is not encodable as UTF-8");
                }
                return new String(utf8.asSlice(0, size.get(JAVA_LONG, 0)).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
            }
        }
    }

    /** A module object. */
    record Module(MemorySegment segment) implements PyObject {
        public Module {
            requireObject(segment);
        }

        /**
         * The module {@code name} from {@code sys.modules}, created if absent. A
         * <b>new</b> reference, with the semantics of {@code PyImport_AddModuleRef}.
         *
         * <p>That function is new in 3.13, and chosen at runtime: on 3.12 this uses
         * {@code PyImport_AddModule} and takes a reference itself. Not offered with
         * the borrowed result of {@code PyImport_AddModule}: it is borrowed from
         * {@code sys.modules}, which any Python code can change, freeing the module
         * while the caller still uses it. (CPython added the {@code ...Ref} variants
         * in 3.13 for exactly such cases.)
         *
         * @throws PythonException if CPython fails to get or create it
         */
        public static NewReference<Module> importAddRef(String name) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cName = arena.allocateFrom(name);
                if (ApiLevel.atLeast(3, 13)) {
                    MemorySegment module = PyImport_AddModuleRef(cName);
                    if (module.equals(MemorySegment.NULL)) {
                        throw Py.errorPrinted("could not get the " + name + " module");
                    }
                    return NewReference.adopt(new Module(module));
                }
                // 3.12: borrowed, so take a reference before anything else can run.
                MemorySegment module = PyImport_AddModule(cName);
                if (module.equals(MemorySegment.NULL)) {
                    throw Py.errorPrinted("could not get the " + name + " module");
                }
                return NewReference.incRef(new Module(module));
            }
        }

        /** {@code PyModule_GetDict}: the module's namespace. <b>Borrowed</b> from the module. */
        public Dict dict() {
            return new Dict(PyModule_GetDict(segment));
        }
    }

    /**
     * A type object ({@code PyTypeObject *}). Its layout is opaque in the Limited
     * API; only its address is used.
     */
    record Type(MemorySegment segment) implements PyObject {
        public Type {
            requireObject(segment);
        }

        /** {@code &PyUnicode_Type}: {@code str}. */
        public static Type unicode() {
            return Builtins.UNICODE;
        }

        /** {@code &PyList_Type}: {@code list}. */
        public static Type list() {
            return Builtins.LIST;
        }

        /** {@code &PyDict_Type}: {@code dict}. */
        public static Type dict() {
            return Builtins.DICT;
        }

        /** {@code &PyModule_Type}: {@code module}. */
        public static Type module() {
            return Builtins.MODULE;
        }

        /**
         * {@code PyType_GetFlags}: the type's {@code Py_TPFLAGS_*}, e.g. for checks
         * that also accept subclasses (as C's {@code Py*_Check} macros).
         */
        public long flags() {
            return PyType_GetFlags(segment);
        }

        /**
         * Built-in types: statically allocated, exported data symbols in every
         * version. jextract cannot bind them as variables (their type is opaque in
         * the Limited API), so their addresses are looked up, once, on first use.
         */
        private static final class Builtins {
            static final Type UNICODE = lookUp("PyUnicode_Type");
            static final Type LIST = lookUp("PyList_Type");
            static final Type DICT = lookUp("PyDict_Type");
            static final Type MODULE = lookUp("PyModule_Type");

            private static Type lookUp(String symbol) {
                return new Type(SymbolLookup.loaderLookup().findOrThrow(symbol));
            }
        }
    }

    private static void requireObject(MemorySegment segment) {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Not an object: NULL");
        }
    }
}
