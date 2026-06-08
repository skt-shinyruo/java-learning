package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

public class BackpressureTest {
    @Test
    public void tryPublish_shouldReturnFalseWhenRingIsFull() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 2, new BlockingWaitStrategy());
        Sequence slowConsumer = new Sequence(-1L);
        ringBuffer.addGatingSequences(slowConsumer);

        Assert.assertTrue(ringBuffer.tryPublish(new NoOpTranslator()));
        Assert.assertTrue(ringBuffer.tryPublish(new NoOpTranslator()));
        Assert.assertFalse(ringBuffer.tryPublish(new NoOpTranslator()));

        slowConsumer.set(0L);

        Assert.assertTrue(ringBuffer.tryPublish(new NoOpTranslator()));
    }

    @Test
    public void publishWithTimeout_shouldReturnFalseWhenRingStaysFull() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 1, new BlockingWaitStrategy());
        Sequence slowConsumer = new Sequence(-1L);
        ringBuffer.addGatingSequences(slowConsumer);

        Assert.assertTrue(ringBuffer.tryPublish(new NoOpTranslator()));

        Assert.assertFalse(ringBuffer.publish(new NoOpTranslator(), 5L, TimeUnit.MILLISECONDS));
        Assert.assertEquals(0L, ringBuffer.getCursor());
    }

    private static final class NoOpTranslator implements EventTranslator<TestEvent> {
        @Override
        public void translateTo(TestEvent event, long sequence) {
        }
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
