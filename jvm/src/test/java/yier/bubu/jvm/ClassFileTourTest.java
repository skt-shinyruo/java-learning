package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

public class ClassFileTourTest {

    @Test
    public void guardedLength_shouldReturnLengthWhenTextPresent() {
        ClassFileTour<Integer> tour = ClassFileTour.of(3);

        int result = tour.guardedLength("abc");

        Assert.assertEquals(3, result);
    }

    @Test
    public void guardedLength_shouldReturnMinusOneWhenTextNull() {
        ClassFileTour<Integer> tour = ClassFileTour.of(3);

        int result = tour.guardedLength(null);

        Assert.assertEquals(-1, result);
    }
}
