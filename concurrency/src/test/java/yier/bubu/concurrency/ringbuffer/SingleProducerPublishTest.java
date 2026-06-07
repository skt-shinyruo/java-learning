package yier.bubu.concurrency.ringbuffer;

import org.junit.Assert;
import org.junit.Test;

public class SingleProducerPublishTest {
    @Test
    public void publish_shouldTranslateSlotAndAdvanceCursor() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());
        SequenceRecorder sequenceRecorder = new SequenceRecorder();

        ringBuffer.publish(new EventTranslator<TestEvent>() {
            @Override
            public void translateTo(TestEvent event, long sequence) {
                event.value = 42;
                sequenceRecorder.value = sequence;
            }
        });

        Assert.assertEquals(0L, ringBuffer.getCursor());
        Assert.assertEquals(42, ringBuffer.get(0).value);
        Assert.assertEquals(0L, sequenceRecorder.value);
    }

    @Test
    public void publish_shouldPublishClaimedSequenceWhenTranslatorThrows() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());

        try {
            ringBuffer.publish(new EventTranslator<TestEvent>() {
                @Override
                public void translateTo(TestEvent event, long sequence) {
                    event.value = 7;
                    throw new IllegalStateException("translator failed");
                }
            });
            Assert.fail("Expected IllegalStateException");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("translator failed", exception.getMessage());
        }

        Assert.assertEquals(0L, ringBuffer.getCursor());
        Assert.assertEquals(7, ringBuffer.get(0).value);
    }

    private static final class SequenceRecorder {
        private long value = -1L;
    }

    private static final class TestEvent {
        private int value;
    }

    private static final class TestEventFactory implements EventFactory<TestEvent> {
        @Override
        public TestEvent newInstance() {
            return new TestEvent();
        }
    }
}
