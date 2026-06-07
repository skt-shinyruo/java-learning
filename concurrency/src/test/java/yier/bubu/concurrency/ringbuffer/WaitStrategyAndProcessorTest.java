package yier.bubu.concurrency.ringbuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

public class WaitStrategyAndProcessorTest {
    @Test
    public void batchEventProcessor_shouldBroadcastEventsToMultipleConsumers() throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 8, new BlockingWaitStrategy());
        RecordingHandler firstHandler = new RecordingHandler(3);
        RecordingHandler secondHandler = new RecordingHandler(3);
        BatchEventProcessor<TestEvent> firstProcessor =
                new BatchEventProcessor<TestEvent>(
                        ringBuffer,
                        ringBuffer.newBarrier(),
                        firstHandler,
                        new LoggingExceptionHandler<TestEvent>());
        BatchEventProcessor<TestEvent> secondProcessor =
                new BatchEventProcessor<TestEvent>(
                        ringBuffer,
                        ringBuffer.newBarrier(),
                        secondHandler,
                        new LoggingExceptionHandler<TestEvent>());
        ringBuffer.addGatingSequences(firstProcessor.getSequence(), secondProcessor.getSequence());

        try {
            firstProcessor.start();
            secondProcessor.start();

            publishValue(ringBuffer, 1);
            publishValue(ringBuffer, 2);
            publishValue(ringBuffer, 3);

            Assert.assertTrue(firstHandler.await(1L, TimeUnit.SECONDS));
            Assert.assertTrue(secondHandler.await(1L, TimeUnit.SECONDS));
            Assert.assertEquals(Arrays.asList(1, 2, 3), firstHandler.values());
            Assert.assertEquals(Arrays.asList(1, 2, 3), secondHandler.values());
        } finally {
            firstProcessor.halt();
            secondProcessor.halt();
        }
    }

    @Test
    public void waitStrategies_shouldBeConstructibleAndUsableWithBarrier() throws Exception {
        assertBarrierCanObservePublishedSequence(new BlockingWaitStrategy());
        assertBarrierCanObservePublishedSequence(new YieldingWaitStrategy());
        assertBarrierCanObservePublishedSequence(new BusySpinWaitStrategy());
        assertBarrierCanObservePublishedSequence(new SleepingWaitStrategy());
    }

    private void assertBarrierCanObservePublishedSequence(WaitStrategy waitStrategy) throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, waitStrategy);
        SequenceBarrier barrier = ringBuffer.newBarrier();

        publishValue(ringBuffer, 99);

        Assert.assertEquals(0L, barrier.waitFor(0L));
        Assert.assertEquals(99, ringBuffer.get(0L).value);
    }

    private void publishValue(RingBuffer<TestEvent> ringBuffer, final int value) {
        ringBuffer.publish(new EventTranslator<TestEvent>() {
            @Override
            public void translateTo(TestEvent event, long sequence) {
                event.value = value;
            }
        });
    }

    private static final class RecordingHandler implements EventHandler<TestEvent> {
        private final CountDownLatch latch;
        private final List<Integer> values = new ArrayList<Integer>();

        private RecordingHandler(int expectedEventCount) {
            this.latch = new CountDownLatch(expectedEventCount);
        }

        @Override
        public void onEvent(TestEvent event, long sequence) {
            synchronized (values) {
                values.add(event.value);
            }
            latch.countDown();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        private List<Integer> values() {
            synchronized (values) {
                return new ArrayList<Integer>(values);
            }
        }
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
