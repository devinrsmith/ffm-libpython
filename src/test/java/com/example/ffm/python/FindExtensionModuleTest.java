package com.example.ffm.python;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

// One interpreter for the whole class; the build runs each test class in its own JVM.
// Each test uses a different stdlib extension module (all standalone .so files in
// lib-dynload), since they share the interpreter.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindExtensionModuleTest {

    private EmbeddedPython python;

    @BeforeAll
    void start() {
        python = EmbeddedPython.start();
    }

    @AfterAll
    void stop() {
        python.close();
    }

    @Test
    void findsATopLevelExtensionWithoutImportingIt() {
        python.exec("import sys; assert '_json' not in sys.modules");
        Path file = python.findExtensionModule("_json").orElseThrow();
        assertTrue(Files.isRegularFile(file), file::toString);
        assertTrue(file.getFileName().toString().startsWith("_json."), file::toString);
        python.exec("import sys; assert '_json' not in sys.modules, 'found by importing it'");
    }

    /** The use case: load the library from Java first, then let Python import it. */
    @Test
    void javaLoadsTheLibraryAndPythonReusesIt() throws IOException {
        Path file = python.findExtensionModule("_csv").orElseThrow();
        System.load(file.toString());
        python.exec("""
                import _csv, os
                assert os.path.samefile(_csv.__file__, %s), _csv.__file__
                """.formatted(EmbeddedPythonTest.pythonString(file.toString())));
        // One load: the file is mapped once from its start (offset 0), not twice.
        Path real = file.toRealPath();
        long loads = Files.readAllLines(Path.of("/proc/self/maps")).stream()
                .map(line -> line.split("\\s+"))
                .filter(fields -> fields.length >= 6 && fields[2].equals("00000000"))
                .filter(fields -> Path.of(fields[5]).equals(real))
                .count();
        assertEquals(1, loads, "the library was loaded more than once");
    }

    @Test
    void findsSubmodulesWithoutImportingTheirPackages(@TempDir Path root) {
        // pkg/__init__.py and pkg/sub/__init__.py fail if imported; pkg/sub/_native is
        // a copy of a real extension module.
        python.exec("""
                import importlib.machinery, importlib.util, os, shutil, sys
                root = %s
                os.makedirs(os.path.join(root, "pkg", "sub"))
                for init in ("pkg/__init__.py", "pkg/sub/__init__.py"):
                    with open(os.path.join(root, init), "w") as f:
                        f.write("raise RuntimeError('this package must not be imported')\\n")
                native = os.path.join(root, "pkg", "sub", "_native" + importlib.machinery.EXTENSION_SUFFIXES[0])
                shutil.copy(importlib.util.find_spec("_bisect").origin, native)
                sys.path.insert(0, root)
                """.formatted(EmbeddedPythonTest.pythonString(root.toString())));
        try {
            Path file = python.findExtensionModule("pkg.sub._native").orElseThrow();
            assertTrue(file.startsWith(root.resolve("pkg/sub")), file::toString);
            python.exec("import sys; assert 'pkg' not in sys.modules and 'pkg.sub' not in sys.modules");
            // importlib.util.find_spec would have imported pkg, and failed.
            assertThrows(PythonException.class,
                    () -> python.exec("import importlib.util; importlib.util.find_spec('pkg.sub._native')"));
            // Not found: in an existing package, or under a module that is not a package.
            assertEquals(Optional.empty(), python.findExtensionModule("pkg.sub.missing"));
            assertEquals(Optional.empty(), python.findExtensionModule("_bisect.sub"));
        } finally {
            python.exec("import sys; sys.path.remove(%s)".formatted(EmbeddedPythonTest.pythonString(root.toString())));
        }
    }

    @Test
    void usesTheFindersOnSysMetaPath() {
        // A custom finder (as editable installs register), behind one without
        // find_spec (skipped, as importlib does).
        python.exec("""
                import importlib.util, sys
                class VirtualFinder:
                    def find_spec(self, name, path, target=None):
                        if name == "virtual_ext":
                            origin = importlib.util.find_spec("_statistics").origin
                            return importlib.util.spec_from_file_location(name, origin)
                        return None
                sys.meta_path[:0] = [object(), VirtualFinder()]
                """);
        try {
            Path file = python.findExtensionModule("virtual_ext").orElseThrow();
            assertTrue(file.getFileName().toString().startsWith("_statistics."), file::toString);
        } finally {
            python.exec("import sys; del sys.meta_path[:2]");
        }
    }

    @Test
    void reportsMissingAndNonExtensionModules() {
        assertEquals(Optional.empty(), python.findExtensionModule("no_such_module_xyz"));
        IllegalArgumentException pure = assertThrows(IllegalArgumentException.class,
                () -> python.findExtensionModule("json"));
        assertTrue(pure.getMessage().contains("SourceFileLoader"), pure::getMessage);
        IllegalArgumentException builtin = assertThrows(IllegalArgumentException.class,
                () -> python.findExtensionModule("sys"));
        assertTrue(builtin.getMessage().contains("BuiltinImporter"), builtin::getMessage);
        assertThrows(IllegalArgumentException.class, () -> python.findExtensionModule(""));
        assertThrows(IllegalArgumentException.class, () -> python.findExtensionModule("a..b"));
        // The interpreter is unaffected.
        assertDoesNotThrow(() -> python.exec("pass"));
    }
}
