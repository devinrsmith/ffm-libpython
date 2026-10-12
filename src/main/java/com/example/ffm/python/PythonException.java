package com.example.ffm.python;

/** Signals that the embedded Python interpreter reported an error. */
public final class PythonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PythonException(String message) {
        super(message);
    }
}
