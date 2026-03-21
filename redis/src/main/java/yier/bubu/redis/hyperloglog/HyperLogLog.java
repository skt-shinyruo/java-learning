package yier.bubu.redis.hyperloglog;

import java.util.Objects;
import java.util.function.ToLongFunction;

public final class HyperLogLog<T> {
    private static final int MIN_PRECISION = 4;
    private static final int MAX_PRECISION = 18;

    private final int precision;
    private final int registerCount;
    private final ToLongFunction<? super T> hashFunction;
    private final byte[] registers;

    public HyperLogLog(int precision) {
        this(precision, defaultHashFunction());
    }

    public HyperLogLog(int precision, ToLongFunction<? super T> hashFunction) {
        validatePrecision(precision);
        this.precision = precision;
        this.registerCount = 1 << precision;
        this.hashFunction = Objects.requireNonNull(hashFunction, "hashFunction");
        this.registers = new byte[registerCount];
    }

    public void add(T value) {
        long hash = hash(value);
        int index = registerIndex(hash);
        int rank = rank(hash);
        if (rank > registers[index]) {
            registers[index] = (byte) rank;
        }
    }

    public long estimate() {
        double sum = 0.0D;
        int zeroCount = 0;
        for (byte register : registers) {
            sum += Math.scalb(1.0D, -register);
            if (register == 0) {
                zeroCount++;
            }
        }
        double estimate = alpha(registerCount) * registerCount * registerCount / sum;
        if (estimate <= 2.5D * registerCount && zeroCount > 0) {
            estimate = registerCount * Math.log(registerCount / (double) zeroCount);
        }
        return Math.round(estimate);
    }

    public void merge(HyperLogLog<?> other) {
        Objects.requireNonNull(other, "other");
        if (precision != other.precision) {
            throw new IllegalArgumentException("precision must match for merge");
        }
        for (int i = 0; i < registers.length; i++) {
            if (other.registers[i] > registers[i]) {
                registers[i] = other.registers[i];
            }
        }
    }

    public int precision() {
        return precision;
    }

    public int registerCount() {
        return registerCount;
    }

    public double standardError() {
        return 1.04D / Math.sqrt(registerCount);
    }

    private static void validatePrecision(int precision) {
        if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("precision must be in [4, 18]");
        }
    }

    private static <T> ToLongFunction<T> defaultHashFunction() {
        return new ToLongFunction<T>() {
            @Override
            public long applyAsLong(T value) {
                long x = Objects.requireNonNull(value, "value").hashCode();
                x += 0x9E3779B97F4A7C15L;
                x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
                x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
                return x ^ (x >>> 31);
            }
        };
    }

    private long hash(T value) {
        Objects.requireNonNull(value, "value");
        return hashFunction.applyAsLong(value);
    }

    private int registerIndex(long hash) {
        return (int) (hash >>> (Long.SIZE - precision));
    }

    private int rank(long hash) {
        long remaining = (hash << precision) | (1L << (precision - 1));
        return Long.numberOfLeadingZeros(remaining) + 1;
    }

    private static double alpha(int registerCount) {
        switch (registerCount) {
            case 16:
                return 0.673D;
            case 32:
                return 0.697D;
            case 64:
                return 0.709D;
            default:
                return 0.7213D / (1.0D + 1.079D / registerCount);
        }
    }
}
