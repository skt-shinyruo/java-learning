package yier.bubu.jdk;

import java.util.List;

public final class StreamSamples {
    private StreamSamples() {
    }

    public static int sumOfSquaresOfEvenNumbers(List<Integer> numbers) {
        if (numbers == null) {
            throw new IllegalArgumentException("numbers must not be null");
        }
        return numbers.stream()
                .filter(n -> n != null && n % 2 == 0)
                .mapToInt(n -> n * n)
                .sum();
    }
}

