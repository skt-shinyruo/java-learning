package yier.bubu.concurrency.ringbuffer;

public final class SequenceUtil {
    private SequenceUtil() {
    }

    public static long getMinimumSequence(Sequence[] sequences, long defaultValue) {
        long minimum = Long.MAX_VALUE;
        for (Sequence sequence : sequences) {
            long value = sequence.get();
            minimum = Math.min(minimum, value);
        }
        return minimum == Long.MAX_VALUE ? defaultValue : minimum;
    }

    public static int ceilingNextPowerOfTwo(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("value must be > 0");
        }
        int highestOneBit = Integer.highestOneBit(value);
        return value == highestOneBit ? value : highestOneBit << 1;
    }
}
