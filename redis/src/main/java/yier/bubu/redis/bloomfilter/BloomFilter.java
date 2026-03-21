package yier.bubu.redis.bloomfilter;

import java.util.BitSet;
import java.util.Objects;

public final class BloomFilter<T> {
    private static final double LN_2 = Math.log(2.0D);

    private final long expectedInsertions;
    private final double falsePositiveProbability;
    private final int bitSize;
    private final int hashFunctionCount;
    private final BitSet bits;

    public BloomFilter(long expectedInsertions, double falsePositiveProbability) {
        validateExpectedInsertions(expectedInsertions);
        validateFalsePositiveProbability(falsePositiveProbability);
        this.expectedInsertions = expectedInsertions;
        this.falsePositiveProbability = falsePositiveProbability;
        this.bitSize = optimalBitSize(expectedInsertions, falsePositiveProbability);
        this.hashFunctionCount = optimalHashFunctionCount(expectedInsertions, bitSize);
        this.bits = new BitSet(bitSize);
    }

    public void put(T value) {
        for (int index : indexesFor(value)) {
            bits.set(index);
        }
    }

    public boolean mightContain(T value) {
        for (int index : indexesFor(value)) {
            if (!bits.get(index)) {
                return false;
            }
        }
        return true;
    }

    public long expectedInsertions() {
        return expectedInsertions;
    }

    public double falsePositiveProbability() {
        return falsePositiveProbability;
    }

    public int bitSize() {
        return bitSize;
    }

    public int hashFunctionCount() {
        return hashFunctionCount;
    }

    static int optimalBitSize(long expectedInsertions, double falsePositiveProbability) {
        return (int) Math.ceil((-expectedInsertions * Math.log(falsePositiveProbability)) / (LN_2 * LN_2));
    }

    static int optimalHashFunctionCount(long expectedInsertions, int bitSize) {
        return Math.max(1, (int) Math.round((bitSize / (double) expectedInsertions) * LN_2));
    }

    private static void validateExpectedInsertions(long expectedInsertions) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be > 0");
        }
    }

    private static void validateFalsePositiveProbability(double falsePositiveProbability) {
        if (!(falsePositiveProbability > 0.0D && falsePositiveProbability < 1.0D)) {
            throw new IllegalArgumentException("falsePositiveProbability must be in (0, 1)");
        }
    }

    private int[] indexesFor(T value) {
        Objects.requireNonNull(value, "value");
        int[] indexes = new int[hashFunctionCount];
        int hash1 = smear(value.hashCode());
        int hash2 = smear(hash1 ^ 0x5bd1e995);
        for (int i = 0; i < hashFunctionCount; i++) {
            long combined = (hash1 & 0xffffffffL) + (long) i * (hash2 & 0xffffffffL);
            long normalized = combined % bitSize;
            if (normalized < 0) {
                normalized += bitSize;
            }
            indexes[i] = (int) normalized;
        }
        return indexes;
    }

    private static int smear(int hash) {
        hash ^= (hash >>> 16);
        hash *= 0x7feb352d;
        hash ^= (hash >>> 15);
        hash *= 0x846ca68b;
        hash ^= (hash >>> 16);
        return hash;
    }
}
