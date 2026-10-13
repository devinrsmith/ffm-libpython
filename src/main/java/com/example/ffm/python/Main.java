package com.example.ffm.python;

/** Initializes CPython, runs a {@code print} statement, and finalizes it again. */
public final class Main {

    private Main() {}

    static void main() {
        System.out.println("Java: starting embedded Python");
        // Flush Java's buffer before Python writes to the same file descriptor.
        System.out.flush();

        try (EmbeddedPython python = EmbeddedPython.start()) {
            System.out.println("Java: embedding " + python.library());
            python.executable().ifPresent(executable -> System.out.println("Java: as " + executable));
            System.out.println("Java: Python configuration:");
            python.configuration().forEach((name, value) ->
                    System.out.printf("  %-23s = %s%n", name + "()", value == null ? "(not set)" : value));
            System.out.flush();
            python.exec("""
                    import sys
                    print(f"Hello from Python {sys.version.split()[0]}, embedded in Java via FFM!")
                    print(f"sys.prefix = {sys.prefix}")
                    """);
        }

        System.out.println("Java: Python finalized");
    }
}
