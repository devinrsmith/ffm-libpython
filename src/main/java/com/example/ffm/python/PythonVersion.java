package com.example.ffm.python;

/**
 * A CPython version, as packed into {@code PY_VERSION_HEX} and the runtime constant
 * {@code Py_Version}: one byte each for major, minor and micro, then a nibble each
 * for the release level and serial. For example {@code 0x030C05C2} is 3.12.5rc2.
 *
 * <p>Ordered like the packed value, so {@code 3.14.0a1 < 3.14.0 < 3.14.1}.
 *
 * @param major e.g. {@code 3}
 * @param minor e.g. {@code 12}
 * @param micro e.g. {@code 5}
 * @param releaseLevel alpha, beta, release candidate, or final
 * @param releaseSerial e.g. {@code 2} for rc2; {@code 0} for final releases
 */
public record PythonVersion(int major, int minor, int micro, ReleaseLevel releaseLevel, int releaseSerial)
        implements Comparable<PythonVersion> {

    /** The {@code PY_RELEASE_LEVEL_*} values, with their {@code sys.version_info} names. */
    public enum ReleaseLevel {
        ALPHA(0xA, "alpha", "a"),
        BETA(0xB, "beta", "b"),
        CANDIDATE(0xC, "candidate", "rc"),
        FINAL(0xF, "final", "");

        private final int code;
        private final String pythonName;
        private final String suffix;

        ReleaseLevel(int code, String pythonName, String suffix) {
            this.code = code;
            this.pythonName = pythonName;
            this.suffix = suffix;
        }

        /** The 4-bit {@code PY_RELEASE_LEVEL_*} code, e.g. {@code 0xC}. */
        public int code() {
            return code;
        }

        /** The name {@code sys.version_info.releaselevel} uses, e.g. {@code "candidate"}. */
        public String pythonName() {
            return pythonName;
        }

        static ReleaseLevel of(int code) {
            for (ReleaseLevel level : values()) {
                if (level.code == code) {
                    return level;
                }
            }
            throw new IllegalArgumentException("Unknown Python release level 0x" + Integer.toHexString(code));
        }
    }

    public PythonVersion {
        checkRange("major", major, 0xFF);
        checkRange("minor", minor, 0xFF);
        checkRange("micro", micro, 0xFF);
        checkRange("releaseSerial", releaseSerial, 0xF);
        if (releaseLevel == null) {
            throw new NullPointerException("releaseLevel");
        }
    }

    /** A final release, e.g. {@code of(3, 12, 0)}. */
    public static PythonVersion of(int major, int minor, int micro) {
        return new PythonVersion(major, minor, micro, ReleaseLevel.FINAL, 0);
    }

    /**
     * Unpacks a {@code PY_VERSION_HEX} value.
     *
     * @throws IllegalArgumentException if it has bits above the 32 used, or an
     *     unknown release level
     */
    public static PythonVersion fromHex(long hex) {
        if (hex >>> 32 != 0) {
            throw new IllegalArgumentException("Not a PY_VERSION_HEX value: 0x" + Long.toHexString(hex));
        }
        return new PythonVersion(
                (int) (hex >>> 24 & 0xFF),
                (int) (hex >>> 16 & 0xFF),
                (int) (hex >>> 8 & 0xFF),
                ReleaseLevel.of((int) (hex >>> 4 & 0xF)),
                (int) (hex & 0xF));
    }

    /** Packs this version as {@code PY_VERSION_HEX}. */
    public long hex() {
        return (long) major << 24 | minor << 16 | micro << 8 | releaseLevel.code() << 4 | releaseSerial;
    }

    @Override
    public int compareTo(PythonVersion other) {
        return Long.compare(hex(), other.hex());
    }

    /** As Python spells it: {@code 3.12.5}, {@code 3.14.0a1}, {@code 3.12.5rc2}. */
    @Override
    public String toString() {
        String version = major + "." + minor + "." + micro;
        return releaseLevel == ReleaseLevel.FINAL ? version : version + releaseLevel.suffix + releaseSerial;
    }

    private static void checkRange(String name, int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(name + " must be in [0, " + max + "]: " + value);
        }
    }
}
