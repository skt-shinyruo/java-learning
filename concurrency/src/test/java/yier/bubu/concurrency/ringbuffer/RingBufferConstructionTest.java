package yier.bubu.concurrency.ringbuffer;

import org.junit.Assert;
import org.junit.Test;

public class RingBufferConstructionTest {
    @Test
    public void createSingleProducer_shouldRejectNonPowerOfTwoCapacity() {
        try {
            RingBuffer.createSingleProducer(new TestEventFactory(), 10, new BlockingWaitStrategy());
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("power of two"));
        }
    }

    @Test
    public void createSingleProducer_shouldPreallocateAndReuseSlots() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());

        Assert.assertSame(ringBuffer.get(0), ringBuffer.get(4));
        Assert.assertEquals(4, ringBuffer.getBufferSize());
        Assert.assertEquals(-1L, ringBuffer.getCursor());
    }

    private static final class TestEvent {
    }

    private static final class TestEventFactory implements EventFactory<TestEvent> {
        @Override
        public TestEvent newInstance() {
            return new TestEvent();
        }
    }
}
