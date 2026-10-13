package com.example.ffm.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ffm.python.PythonVersion.ReleaseLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PythonVersionTest {

    @ParameterizedTest
    @CsvSource({
        // hex,      major, minor, micro, level,     serial, string
        "030C00F0,   3,     12,    0,     FINAL,     0,      3.12.0",
        "030F00F0,   3,     15,    0,     FINAL,     0,      3.15.0",
        "030E00A1,   3,     14,    0,     ALPHA,     1,      3.14.0a1",
        "030D03B4,   3,     13,    3,     BETA,      4,      3.13.3b4",
        "030C05C2,   3,     12,    5,     CANDIDATE, 2,      3.12.5rc2",
    })
    void unpacksAndRepacks(String hex, int major, int minor, int micro, ReleaseLevel level, int serial, String string) {
        long packed = Long.parseLong(hex, 16);
        PythonVersion version = PythonVersion.fromHex(packed);

        assertEquals(new PythonVersion(major, minor, micro, level, serial), version);
        assertEquals(string, version.toString());
        assertEquals(packed, version.hex());
    }

    @Test
    void ordersLikeThePackedValue() {
        PythonVersion alpha = PythonVersion.fromHex(0x030E00A1L);
        PythonVersion candidate = PythonVersion.fromHex(0x030E00C1L);
        PythonVersion release = PythonVersion.of(3, 14, 0);
        PythonVersion micro = PythonVersion.of(3, 14, 1);

        assertTrue(alpha.compareTo(candidate) < 0);
        assertTrue(candidate.compareTo(release) < 0);
        assertTrue(release.compareTo(micro) < 0);
    }

    @ParameterizedTest
    @ValueSource(longs = {
        0x030C0000L, // release level 0 does not exist
        0x030C00D0L, // nor does 0xD
        0x1_030C00F0L, // more than 32 bits
    })
    void rejectsInvalidValues(long hex) {
        assertThrows(IllegalArgumentException.class, () -> PythonVersion.fromHex(hex));
    }

    @Test
    void rejectsOutOfRangeComponents() {
        assertThrows(IllegalArgumentException.class, () -> PythonVersion.of(3, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> new PythonVersion(3, 12, 0, ReleaseLevel.ALPHA, 16));
    }

    @Test
    void releaseLevelsUsePythonsNames() {
        assertEquals("candidate", PythonVersion.fromHex(0x030C05C2L).releaseLevel().pythonName());
        assertEquals(0xC, ReleaseLevel.CANDIDATE.code());
    }
}
