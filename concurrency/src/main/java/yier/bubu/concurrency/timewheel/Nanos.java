package yier.bubu.concurrency.timewheel;

import java.time.Duration;

/**
 * Time helpers for the timing wheel (JDK-only, Java 8 compatible).
 */
final class Nanos {
    private Nanos() {
    }

    static long positiveToNanos(Duration duration, String name) {
        long nanos = toNanos(duration, name);
        if (nanos <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return nanos;
    }

    static long nonNegativeToNanos(Duration duration, String name) {
        long nanos = toNanos(duration, name);
        if (nanos < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return nanos;
    }

    private static long toNanos(Duration duration, String name) {
        if (duration == null) {
            throw new NullPointerException(name + " must not be null");
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large: " + duration, e);
        }
    }

    static long addExact(long a, long b, String message) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(message + ": a=" + a + ", b=" + b, e);
        }
    }

    static long multiplyExact(long a, long b, String message) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(message + ": a=" + a + ", b=" + b, e);
        }
    }

    static long alignDown(long timeNanos, long tickNanos) {
        return timeNanos - Math.floorMod(timeNanos, tickNanos);
    }

    /**
     * Align upwards to a tick boundary to guarantee "not before deadline".
     */
    static long ceilToTick(long timeNanos, long tickNanos) {
        long down = alignDown(timeNanos, tickNanos);
        if (down == timeNanos) {
            return timeNanos;
        }
        return down + tickNanos;
    }
}

