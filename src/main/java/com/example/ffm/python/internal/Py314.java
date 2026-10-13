package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.py314.CPython314.PyConfig_Get;

import com.example.ffm.python.PythonException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Typed wrappers for CPython's version-specific API, as bound from the 3.14 headers
 * ({@code capi.py314.CPython314}); same conventions as {@link PyObject}. None of
 * these exist before 3.14: guard calls with {@link ApiLevel#atLeast}.
 */
public final class Py314 {

    private Py314() {}

    /**
     * {@code PyConfig_Get} (PEP 741): the value of configuration option {@code name}.
     * A <b>new</b> reference.
     *
     * <p>For options that {@code sys} also exposes, this is the current {@code sys}
     * value: e.g. {@code "module_search_paths"} is {@code sys.path}, including what
     * {@code site} added.
     *
     * @throws PythonException if there is no such option
     */
    public static NewReference<PyObject.Any> configGet(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = PyConfig_Get(arena.allocateFrom(name));
            if (value.equals(MemorySegment.NULL)) {
                throw Py.errorPrinted("PyConfig_Get(\"" + name + "\") failed");
            }
            return NewReference.adopt(new PyObject.Any(value));
        }
    }
}
