package com.example.ffm.python.internal;

/**
 * The token {@code PyGILState_Ensure()} returns, to be given back to exactly one
 * {@code PyGILState_Release()} on the same thread.
 */
public record GilState(int value) {}
