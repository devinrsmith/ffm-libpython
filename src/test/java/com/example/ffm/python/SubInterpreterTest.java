package com.example.ffm.python;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

// One interpreter for the whole class; the build runs each test class in its own JVM.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubInterpreterTest {

    /** CPU-bound Python, pure bytecode: holds its GIL throughout. */
    private static final String BUSY_LOOP = "total = sum(i * i for i in range(3_000_000))";

    private EmbeddedPython python;

    @BeforeAll
    void start() {
        python = EmbeddedPython.start();
    }

    @AfterAll
    void stop() {
        python.close();
    }

    /** A sub-interpreter with its own GIL. */
    private SubInterpreter isolated() {
        return python.newSubInterpreterFromConfig(InterpreterConfig.isolated());
    }

    @Test
    void subInterpretersAreIsolated() {
        // Both kinds have their own modules, sys and __main__.
        for (Supplier<SubInterpreter> kind : List.<Supplier<SubInterpreter>>of(python::newSubInterpreter, this::isolated)) {
            try (SubInterpreter a = kind.get(); SubInterpreter b = kind.get()) {
                a.exec("import sys; x = 1; sys.marker = 'a'");
                b.exec("import sys; assert 'x' not in globals(); assert not hasattr(sys, 'marker')");
                python.exec("import sys; assert 'x' not in globals(); assert not hasattr(sys, 'marker')");
                a.exec("assert x == 1 and sys.marker == 'a'");
            }
        }
    }

    @Test
    void stableConstructorUsesTheLegacyConfig() {
        try (SubInterpreter sub = python.newSubInterpreter()) {
            assertEquals(InterpreterConfig.legacy(), sub.config());
            // Unlike isolated ones, legacy sub-interpreters import single-phase-init
            // extension modules.
            assertDoesNotThrow(() -> sub.exec("import _curses"));
        }
        try (SubInterpreter sub = isolated()) {
            assertEquals(InterpreterConfig.isolated(), sub.config());
        }
    }

    @Test
    void appliesAnExplicitConfig() {
        try (SubInterpreter sub = python.newSubInterpreterFromConfig(InterpreterConfig.isolated().withAllowThreads(false))) {
            // CPython refuses to start threads: "thread is not supported for isolated subinterpreters".
            assertThrows(PythonException.class,
                    () -> sub.exec("import threading; threading.Thread(target=lambda: None).start()"));
        }
        try (SubInterpreter sub = python.newSubInterpreterFromConfig(InterpreterConfig.isolated())) {
            assertDoesNotThrow(() -> sub.exec("import threading; t = threading.Thread(target=lambda: None); t.start(); t.join()"));
        }
        // The legacy configuration, explicitly: shared GIL, through Py_NewInterpreterFromConfig.
        try (SubInterpreter sub = python.newSubInterpreterFromConfig(InterpreterConfig.legacy())) {
            assertDoesNotThrow(() -> sub.exec("import _curses"));
        }
    }

    @Test
    void reportsInterpreterDiagnostics() {
        InterpreterInfo main = python.interpreterInfo();
        assertEquals(0, main.id());
        assertTrue(main.main());
        try (SubInterpreter a = python.newSubInterpreter(); SubInterpreter b = isolated()) {
            InterpreterInfo infoA = a.interpreterInfo();
            InterpreterInfo infoB = b.interpreterInfo();
            for (InterpreterInfo sub : List.of(infoA, infoB)) {
                assertFalse(sub.main(), sub::toString);
                assertTrue(sub.id() > 0, sub::toString);
                assertNotEquals(main.stateAddress(), sub.stateAddress(), sub::toString);
            }
            assertNotEquals(infoA.id(), infoB.id());
            assertNotEquals(infoA.stateAddress(), infoB.stateAddress());
            // Stable while the interpreter lives.
            assertEquals(infoA, a.interpreterInfo());
        }
        // IDs are never reused.
        long highest = Math.max(python.interpreterInfo().id(), 0);
        try (SubInterpreter c = python.newSubInterpreter()) {
            assertTrue(c.interpreterInfo().id() > highest, c.interpreterInfo()::toString);
        }
    }

    @Test
    void importsExtensionModules() {
        try (SubInterpreter sub = isolated()) {
            // Extension modules supporting multi-phase initialization (most of the
            // stdlib) load fine in an isolated interpreter...
            assertDoesNotThrow(() -> sub.exec("import math, json, zlib, _sqlite3; assert math.isqrt(49) == 7"));
            // ...legacy single-phase ones are refused with an ImportError (_curses on
            // every version; on 3.12 also _ctypes, _decimal and _datetime).
            assertThrows(PythonException.class, () -> sub.exec("import _curses"));
            assertDoesNotThrow(() -> sub.exec("pass"));
        }
    }

    @Test
    void reportsPythonErrors() {
        try (SubInterpreter sub = python.newSubInterpreter()) {
            assertThrows(PythonException.class, () -> sub.exec("raise ValueError('expected')"));
            // Still usable afterwards.
            assertDoesNotThrow(() -> sub.exec("pass"));
        }
    }

    @Test
    void runsOnManyThreadsConcurrently() throws Exception {
        int threads = 4;
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                results.add(executor.submit(() -> {
                    // Alternate between both kinds.
                    try (SubInterpreter sub = n % 2 == 0 ? python.newSubInterpreter() : isolated()) {
                        sub.exec("n = " + n + "\n" + BUSY_LOOP);
                        sub.exec("assert n == " + n);
                    }
                }));
            }
            for (Future<?> result : results) {
                result.get(60, TimeUnit.SECONDS);
            }
        }
        // The main interpreter remained usable throughout.
        python.exec("pass");
    }

    @Test
    void ownGilRunsInParallel() throws Exception {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2, "needs at least 2 CPUs");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            // Warm up, then time one busy loop alone and two on separate threads.
            // With a shared GIL two would take about twice as long as one.
            timeOnThreads(executor, 1, this::isolated);
            Duration one = timeOnThreads(executor, 1, this::isolated);
            Duration two = timeOnThreads(executor, 2, this::isolated);
            System.out.printf("own GIL: 1 busy loop %d ms, 2 on 2 threads %d ms%n", one.toMillis(), two.toMillis());
            assertTrue(two.toNanos() < one.toNanos() * 1.5,
                    "2 sub-interpreters took " + two.toMillis() + " ms vs " + one.toMillis() + " ms for 1");
        }
    }

    @Test
    void sharedGilTakesTurns() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            timeOnThreads(executor, 1, python::newSubInterpreter);
            Duration one = timeOnThreads(executor, 1, python::newSubInterpreter);
            Duration two = timeOnThreads(executor, 2, python::newSubInterpreter);
            System.out.printf("shared GIL: 1 busy loop %d ms, 2 on 2 threads %d ms%n", one.toMillis(), two.toMillis());
            assertTrue(two.toNanos() > one.toNanos() * 1.5,
                    "2 sub-interpreters sharing a GIL took " + two.toMillis() + " ms vs " + one.toMillis() + " ms for 1");
        }
    }

    /**
     * Runs the busy loop in {@code count} sub-interpreters on separate threads, all
     * created first and then started together, and returns the wall-clock time from
     * the first start to the last finish.
     */
    private Duration timeOnThreads(ExecutorService executor, int count, Supplier<SubInterpreter> kind)
            throws InterruptedException, ExecutionException {
        CyclicBarrier ready = new CyclicBarrier(count);
        List<Future<long[]>> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            results.add(executor.submit(() -> {
                try (SubInterpreter sub = kind.get()) {
                    ready.await();
                    long start = System.nanoTime();
                    sub.exec(BUSY_LOOP);
                    return new long[] {start, System.nanoTime()};
                }
            }));
        }
        long firstStart = Long.MAX_VALUE;
        long lastEnd = Long.MIN_VALUE;
        for (Future<long[]> result : results) {
            long[] startEnd = result.get();
            firstStart = Math.min(firstStart, startEnd[0]);
            lastEnd = Math.max(lastEnd, startEnd[1]);
        }
        return Duration.ofNanos(lastEnd - firstStart);
    }

    @Test
    void isConfinedToItsThread() throws Exception {
        try (SubInterpreter sub = python.newSubInterpreter();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> result = executor.submit(() -> sub.exec("pass"));
            ExecutionException e = assertThrows(ExecutionException.class, result::get);
            assertEquals(IllegalStateException.class, e.getCause().getClass());
        }
    }

    @Test
    void rejectsVirtualThreads() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> result = executor.submit(() -> python.newSubInterpreter());
            ExecutionException e = assertThrows(ExecutionException.class, result::get);
            assertEquals(IllegalStateException.class, e.getCause().getClass());
        }
    }

    @Test
    void mainInterpreterCannotCloseWhileSubInterpretersAreOpen() {
        try (SubInterpreter sub = python.newSubInterpreter()) {
            assertThrows(IllegalStateException.class, python::close);
            sub.exec("pass");
        }
        // And sub-interpreters cannot be used once ended.
        SubInterpreter ended = python.newSubInterpreter();
        ended.close();
        assertThrows(IllegalStateException.class, () -> ended.exec("pass"));
    }
}
