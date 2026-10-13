package com.example.ffm.python;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Initializes CPython, runs a {@code print} statement and a few sub-interpreters,
 * and finalizes it again.
 */
public final class Main {

    private Main() {}

    static void main() throws Exception {
        System.out.println("Java: starting embedded Python");
        // Flush Java's buffer before Python writes to the same file descriptor.
        System.out.flush();

        try (EmbeddedPython python = EmbeddedPython.start()) {
            System.out.println("Java: embedding " + python.library());
            python.executable().ifPresent(executable -> System.out.println("Java: as " + executable));
            PythonVersion version = python.version();
            System.out.printf("Java: Py_Version = 0x%08X = %s (%s)%n", version.hex(), version, version.releaseLevel().pythonName());
            System.out.println("Java: " + python.interpreterInfo());
            System.out.println("Java: Python configuration:");
            python.configuration().forEach((name, value) ->
                    System.out.printf("  %-19s = %s%n", name, value == null ? "(not set)" : value));

            python.findExtensionModule("jpy").map(Path::toString).ifPresent(path -> {
                System.out.printf("Java: Loading found jpy extension: %s%n", path);
                System.load(path);
            });
            System.out.flush();

            python.exec("""
                    import sys
                    try:
                        import jpy
                        jpy_imported = True
                    except ImportError:
                        jpy_imported = False
                    print(f"Hello from Python {sys.version.split()[0]}, embedded in Java via FFM!")
                    print(f"sys.prefix = {sys.prefix}")
                    print(f"jpy_imported = {jpy_imported}")
                    sys.stdout.flush()  # buffered until finalization otherwise, when not a terminal
                    """);
            runSubInterpreters(python, 3);
        }

        System.out.println("Java: Python finalized");
    }

    /**
     * Runs sub-interpreters, each on its own thread: the first from the Stable ABI's
     * {@code Py_NewInterpreter()} (sharing the main GIL), the rest from an explicit
     * isolated configuration (each with its own GIL).
     */
    private static void runSubInterpreters(EmbeddedPython python, int count) throws Exception {
        System.out.println("Java: running " + count + " sub-interpreters on " + count + " threads");
        System.out.flush();
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int n = i;
                results.add(executor.submit(() -> {
                    try (SubInterpreter sub = n == 0
                            ? python.newSubInterpreter()
                            : python.newSubInterpreterFromConfig(InterpreterConfig.isolated())) {
                        System.out.println("Java: [sub-interpreter " + n + ", " + sub.config().gil() + " GIL] " + sub.interpreterInfo());
                        System.out.flush();
                        sub.exec("""
                                import sys, threading
                                try:
                                    import jpy
                                    jpy_imported = True
                                except ImportError:
                                    jpy_imported = False
                                print(f"[sub-interpreter %d] own sys module at {id(sys):#x}, native thread {threading.get_native_id()}, jpy_imported={jpy_imported}", flush=True)
                                """.formatted(n));
                    }
                }));
            }
            for (Future<?> result : results) {
                result.get();
            }
        }
    }
}
