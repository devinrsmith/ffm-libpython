package com.example.ffm.python;

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
                    System.out.printf("  %-23s = %s%n", name + "()", value == null ? "(not set)" : value));
            System.out.flush();
            python.exec("""
                    import sys
                    print(f"Hello from Python {sys.version.split()[0]}, embedded in Java via FFM!")
                    print(f"sys.prefix = {sys.prefix}")
                    sys.stdout.flush()  # buffered until finalization otherwise, when not a terminal
                    """);
            runSubInterpreters(python, 3);
        }

        System.out.println("Java: Python finalized");
    }

    /** Runs isolated sub-interpreters, each on its own thread and with its own GIL. */
    private static void runSubInterpreters(EmbeddedPython python, int count) throws Exception {
        System.out.println("Java: running " + count + " sub-interpreters on " + count + " threads");
        System.out.flush();
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int n = i;
                results.add(executor.submit(() -> {
                    try (SubInterpreter sub = python.newSubInterpreter()) {
                        System.out.println("Java: [sub-interpreter " + n + "] " + sub.interpreterInfo());
                        System.out.flush();
                        sub.exec("""
                                import sys, threading
                                print(f"[sub-interpreter %d] own sys module at {id(sys):#x}, native thread {threading.get_native_id()}", flush=True)
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
