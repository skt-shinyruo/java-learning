package yier.bubu.base;

import org.junit.Assert;
import org.junit.Test;

public class HelloBaseTest {
    @Test
    public void greet_shouldReturnHelloName() {
        HelloBase helloBase = new HelloBase();
        Assert.assertEquals("Hello, Bob", helloBase.greet("Bob"));
    }

    @Test
    public void greet_shouldFallbackToAnonymousForBlank() {
        HelloBase helloBase = new HelloBase();
        Assert.assertEquals("Hello, anonymous", helloBase.greet("   "));
    }
}

