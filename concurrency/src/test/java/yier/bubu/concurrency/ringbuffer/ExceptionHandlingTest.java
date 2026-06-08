package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Assert;
import org.junit.Test;

public class ExceptionHandlingTest {
    @Test
    public void loggingExceptionHandler_shouldLetProcessorAdvanceAfterHandlerFailure() throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());
        CountDownLatch secondEventHandled = new CountDownLatch(1);
        EventHandler<TestEvent> handler = new EventHandler<TestEvent>() {
            @Override
            public void onEvent(TestEvent event, long sequence) {
                if (sequence == 0L) {
                    throw new IllegalStateException("boom");
                }
                if (sequence == 1L) {
                    secondEventHandled.countDown();
                }
            }
        };
        BatchEventProcessor<TestEvent> processor =
                new BatchEventProcessor<TestEvent>(
                        ringBuffer,
                        ringBuffer.newBarrier(),
                        handler,
                        new LoggingExceptionHandler<TestEvent>());
        ringBuffer.addGatingSequences(processor.getSequence());

        try {
            processor.start();
            publishValue(ringBuffer, 1);
            publishValue(ringBuffer, 2);

            Assert.assertTrue(secondEventHandled.await(1L, TimeUnit.SECONDS));
            Assert.assertTrue(awaitSequence(processor, 1L, 1L, TimeUnit.SECONDS));
            Assert.assertEquals(1L, processor.getSequence().get());
        } finally {
            processor.halt();
            Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
        }
    }

    @Test
    public void fatalExceptionHandler_shouldStopProcessorWithoutAdvancingFailedSequence() throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());
        AtomicLong failedAt = new AtomicLong(-1L);
        EventHandler<TestEvent> handler = new EventHandler<TestEvent>() {
            @Override
            public void onEvent(TestEvent event, long sequence) {
                failedAt.set(sequence);
                throw new IllegalStateException("boom");
            }
        };
        BatchEventProcessor<TestEvent> processor =
                new BatchEventProcessor<TestEvent>(
                        ringBuffer,
                        ringBuffer.newBarrier(),
                        handler,
                        new FatalExceptionHandler<TestEvent>());
        ringBuffer.addGatingSequences(processor.getSequence());

        try {
            processor.start();
            publishValue(ringBuffer, 1);

            Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
            Assert.assertEquals(0L, failedAt.get());
            Assert.assertFalse(processor.isRunning());
            Assert.assertEquals(-1L, processor.getSequence().get());
        } finally {
            if (processor.isRunning()) {
                processor.halt();
                Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
            }
        }
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

    private boolean awaitSequence(BatchEventProcessor<TestEvent> processor,
                                  long expectedSequence,
                                  long timeout,
                                  TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNanos) {
            if (processor.getSequence().get() == expectedSequence) {
                return true;
            }
            Thread.sleep(10L);
        }
        return processor.getSequence().get() == expectedSequence;
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
