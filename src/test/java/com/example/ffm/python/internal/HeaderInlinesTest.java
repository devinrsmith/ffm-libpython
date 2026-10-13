package com.example.ffm.python.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.ffm.python.EmbeddedPython;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

// One interpreter for the whole class; the build runs each test class in its own JVM.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeaderInlinesTest {

    private EmbeddedPython python;

    @BeforeAll
    void start() {
        python = EmbeddedPython.start();
    }

    @AfterAll
    void stop() {
        python.close();
    }

    /** Runs {@code action} attached to the main interpreter, from this test's thread. */
    private static <T> T attached(Supplier<T> action) {
        GilState gil = GilState.ensure();
        try {
            return action.get();
        } finally {
            gil.release();
        }
    }

    @Test
    void identifiesBuiltinTypes() {
        attached(() -> {
            try (NewReference<PyObject.Module> main = PyObject.Module.importAddRef("__main__")) {
                PyObject.Dict globals = main.get().dict();
                assertEquals(PyObject.Type.module(), main.get().type());
                assertEquals(PyObject.Type.dict(), globals.type());
                assertTrue(main.get().isType(PyObject.Type.module()));
                assertFalse(main.get().isType(PyObject.Type.dict()));
                assertFalse(new PyObject.Any(globals.segment()).asStr().isPresent());
                assertFalse(new PyObject.Any(globals.segment()).asList().isPresent());
                // Distinct type objects.
                assertNotEquals(PyObject.Type.unicode(), PyObject.Type.list());
                // Py_TPFLAGS_UNICODE_SUBCLASS (1 << 28) is set on str only.
                assertTrue((PyObject.Type.unicode().flags() & (1L << 28)) != 0);
                assertTrue((PyObject.Type.list().flags() & (1L << 28)) == 0);
            }
            return null;
        });
    }

    @Test
    void exportedFunctionsAgreeWithFallbacks() {
        // From 3.14 (Py_TYPE) and 3.15 (Py_IS_TYPE), this compares the exported
        // functions against the fallbacks used before them.
        attached(() -> {
            try (NewReference<PyObject.Module> main = PyObject.Module.importAddRef("__main__")) {
                List<PyObject> objects = List.of(main.get(), main.get().dict(), PyObject.Type.unicode());
                List<PyObject.Type> types = List.of(PyObject.Type.module(), PyObject.Type.dict(),
                        PyObject.Type.unicode(), PyObject.Type.list());
                for (PyObject object : objects) {
                    assertEquals(HeaderInlines.pyTypeFallback(object), HeaderInlines.pyType(object), object::toString);
                    for (PyObject.Type type : types) {
                        assertEquals(HeaderInlines.pyIsTypeFallback(object, type), HeaderInlines.pyIsType(object, type),
                                () -> object + " / " + type);
                    }
                }
            }
            return null;
        });
    }

    @Test
    void readsPyConfigGetResults() {
        assumeTrue(python.version().minor() >= 14, "PyConfig_Get is 3.14+");
        attached(() -> {
            try (NewReference<PyObject.Any> prefix = Py314.configGet("prefix");
                    NewReference<PyObject.Any> paths = Py314.configGet("module_search_paths")) {
                assertTrue(prefix.get().asStr().isPresent());
                assertFalse(prefix.get().asList().isPresent());
                PyObject.List list = paths.get().asList().orElseThrow();
                assertTrue(list.size() > 0);
                assertTrue(list.get(0).asStr().isPresent());
            }
            return null;
        });
    }
}
