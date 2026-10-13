package com.example.ffm.python.internal;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * An owned (strong) reference to a CPython object, released with {@code Py_DecRef}
 * exactly once, on {@link #close()}: use it with try-with-resources.
 *
 * <p>CPython calls this a <em>new reference</em>: what most functions that return
 * an object return. The methods on {@link PyObject} return one where CPython does;
 * to keep a borrowed reference alive, make one with {@link #incRef}.
 *
 * <p>Not thread-safe, and like every CPython call, {@code close()} must happen while
 * attached to the interpreter that owns the object.
 */
public final class NewReference<T extends PyObject> implements AutoCloseable {

    private final T object;
    private final Consumer<? super T> release;
    private boolean released;

    /** For tests: {@code release} stands in for {@code Py_DecRef}. */
    NewReference(T object, Consumer<? super T> release) {
        this.object = Objects.requireNonNull(object, "object");
        this.release = release;
    }

    /** Takes ownership of a new reference that CPython returned. */
    static <T extends PyObject> NewReference<T> adopt(T object) {
        return new NewReference<>(object, Py::decRef);
    }

    /**
     * Makes an owned reference from a borrowed one ({@code Py_IncRef}), keeping the
     * object alive until this is closed, whatever its previous owner does.
     */
    public static <T extends PyObject> NewReference<T> incRef(T borrowed) {
        Py.incRef(borrowed);
        return adopt(borrowed);
    }

    /**
     * The object, for passing to functions that borrow it.
     *
     * @throws IllegalStateException if already released
     */
    public T get() {
        if (released) {
            throw new IllegalStateException("Reference already released: " + object);
        }
        return object;
    }

    /** Releases the reference ({@code Py_DecRef}); does nothing if already released. */
    @Override
    public void close() {
        if (!released) {
            released = true;
            release.accept(object);
        }
    }
}
