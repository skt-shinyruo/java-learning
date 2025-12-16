package yier.bubu.jdk;

import java.util.Arrays;
import java.util.List;

public final class JdkApp {
    public static void main(String[] args) {
        List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);
        int result = StreamSamples.sumOfSquaresOfEvenNumbers(numbers);
        System.out.println("sumOfSquaresOfEvenNumbers=" + result);
    }
}

