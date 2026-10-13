package com.example.ffm.python.internal;

import java.nio.ByteOrder;
import java.nio.charset.Charset;

/** C {@code wchar_t} strings, as some CPython functions take and return. */
public final class WideChars {

    private WideChars() {}

    /** {@code wchar_t} strings: 4-byte UTF-32 in native byte order on Linux. */
    public static final Charset CHARSET = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
            ? Charset.forName("UTF-32LE")
            : Charset.forName("UTF-32BE");
}
