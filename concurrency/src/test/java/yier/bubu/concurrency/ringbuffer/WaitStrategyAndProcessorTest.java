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

    @Test
    public void batchEventProcessor_halt_shouldNotDrainAlreadyAvailableBatch() throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 8, new BlockingWaitStrategy());
        BlockingHaltingHandler handler = new BlockingHaltingHandler();
        BatchEventProcessor<TestEvent> processor =
                new BatchEventProcessor<TestEvent>(
                        ringBuffer,
                        ringBuffer.newBarrier(),
                        handler,
                        new LoggingExceptionHandler<TestEvent>());
        handler.setProcessor(processor);
        ringBuffer.addGatingSequences(processor.getSequence());

        publishValue(ringBuffer, 1);
        publishValue(ringBuffer, 2);
        publishValue(ringBuffer, 3);

        processor.start();

        Assert.assertTrue(handler.awaitHaltCalled(1L, TimeUnit.SECONDS));
        Assert.assertTrue(processor.isRunning());
        handler.releaseHandler();
        Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
        Assert.assertEquals(Arrays.asList(1), handler.values());
        Assert.assertEquals(0L, processor.getSequence().get());
        Assert.assertFalse(processor.isRunning());
    }

    @Test
    public void batchEventProcessor_start_shouldRejectWhilePreviousThreadIsStillUnwinding() throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 8, new BlockingWaitStrategy());
        BlockingHaltingHandler handler = new BlockingHaltingHandler();
        BatchEventProcessor<TestEvent> processor =
                new BatchEventProcessor<TestEvent>(
                        ringBuffer,
                        ringBuffer.newBarrier(),
                        handler,
                        new LoggingExceptionHandler<TestEvent>());
        handler.setProcessor(processor);
        ringBuffer.addGatingSequences(processor.getSequence());

        processor.start();
        publishValue(ringBuffer, 1);

        try {
            Assert.assertTrue(handler.awaitHaltCalled(1L, TimeUnit.SECONDS));
            Assert.assertTrue(processor.isRunning());
            try {
                processor.start();
                Assert.fail("Expected IllegalStateException");
            } catch (IllegalStateException expected) {
                Assert.assertTrue(expected.getMessage().contains("running"));
            }
        } finally {
            handler.releaseHandler();
            Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
        }
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

    private boolean awaitStopped(BatchEventProcessor<TestEvent> processor, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNanos) {
            if (!processor.isRunning()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return !processor.isRunning();
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

    private static final class BlockingHaltingHandler implements EventHandler<TestEvent> {
        private final CountDownLatch haltCalledLatch = new CountDownLatch(1);
        private final CountDownLatch releaseHandlerLatch = new CountDownLatch(1);
        private final List<Integer> values = new ArrayList<Integer>();
        private BatchEventProcessor<TestEvent> processor;

        @Override
        public void onEvent(TestEvent event, long sequence) throws InterruptedException {
            synchronized (values) {
                values.add(event.value);
            }
            if (sequence == 0L) {
                processor.halt();
                haltCalledLatch.countDown();
                Thread.interrupted();
                releaseHandlerLatch.await();
            }
        }

        private void setProcessor(BatchEventProcessor<TestEvent> processor) {
            this.processor = processor;
        }

        private boolean awaitHaltCalled(long timeout, TimeUnit unit) throws InterruptedException {
            return haltCalledLatch.await(timeout, unit);
        }

        private void releaseHandler() {
            releaseHandlerLatch.countDown();
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
