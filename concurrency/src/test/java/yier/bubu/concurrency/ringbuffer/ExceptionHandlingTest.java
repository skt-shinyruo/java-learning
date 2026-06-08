package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    public void fatalExceptionHandler_shouldStopProcessorAndReleaseProducerGating() throws Exception {
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
            for (int value = 2; value <= 5; value++) {
                Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(value)));
            }
        } finally {
            if (processor.isRunning()) {
                processor.halt();
                Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void fatalExceptionHandler_shouldReleaseOnlyFailedProcessorWhenOtherGatingSequencesRemain()
            throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());
        Sequence remainingConsumer = new Sequence(0L);
        BatchEventProcessor<TestEvent> processor =
                new BatchEventProcessor<TestEvent>(
                        ringBuffer,
                        ringBuffer.newBarrier(),
                        new EventHandler<TestEvent>() {
                            @Override
                            public void onEvent(TestEvent event, long sequence) {
                                throw new IllegalStateException("boom");
                            }
                        },
                        new FatalExceptionHandler<TestEvent>());
        ringBuffer.addGatingSequences(processor.getSequence(), remainingConsumer);

        try {
            processor.start();
            publishValue(ringBuffer, 1);

            Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
            Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(2)));
            Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(3)));
            Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(4)));
            Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(5)));
            Assert.assertFalse(ringBuffer.tryPublish(valueTranslator(6)));
            remainingConsumer.set(1L);
            Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(6)));
        } finally {
            if (processor.isRunning()) {
                processor.halt();
                Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void fatalExceptionHandler_shouldRejectRestartAfterTerminalFailure() throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());
        BatchEventProcessor<TestEvent> processor =
                new BatchEventProcessor<TestEvent>(
                        ringBuffer,
                        ringBuffer.newBarrier(),
                        new EventHandler<TestEvent>() {
                            @Override
                            public void onEvent(TestEvent event, long sequence) {
                                throw new IllegalStateException("boom");
                            }
                        },
                        new FatalExceptionHandler<TestEvent>());
        ringBuffer.addGatingSequences(processor.getSequence());

        try {
            processor.start();
            publishValue(ringBuffer, 1);

            Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
            try {
                processor.start();
                Assert.fail("Expected IllegalStateException");
            } catch (IllegalStateException expected) {
                Assert.assertTrue(expected.getMessage().contains("fatal"));
            }
        } finally {
            if (processor.isRunning()) {
                processor.halt();
                Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void fatalExceptionHandler_shouldStopProcessorWithoutUncaughtThreadException() throws Exception {
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        AtomicReference<Throwable> uncaughtException = new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable exception) {
                if ("batch-event-processor".equals(thread.getName())) {
                    uncaughtException.set(exception);
                } else if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, exception);
                }
            }
        });

        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());
        BatchEventProcessor<TestEvent> processor =
                new BatchEventProcessor<TestEvent>(
                        ringBuffer,
                        ringBuffer.newBarrier(),
                        new EventHandler<TestEvent>() {
                            @Override
                            public void onEvent(TestEvent event, long sequence) {
                                throw new IllegalStateException("boom");
                            }
                        },
                        new FatalExceptionHandler<TestEvent>());
        ringBuffer.addGatingSequences(processor.getSequence());

        try {
            processor.start();
            publishValue(ringBuffer, 1);

            Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
            Assert.assertFalse(awaitUncaught(uncaughtException, 200L, TimeUnit.MILLISECONDS));
        } finally {
            if (processor.isRunning()) {
                processor.halt();
                Assert.assertTrue(awaitStopped(processor, 1L, TimeUnit.SECONDS));
            }
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
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

    private EventTranslator<TestEvent> valueTranslator(final int value) {
        return new EventTranslator<TestEvent>() {
            @Override
            public void translateTo(TestEvent event, long sequence) {
                event.value = value;
            }
        };
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

    private boolean awaitUncaught(AtomicReference<Throwable> uncaughtException, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNanos) {
            if (uncaughtException.get() != null) {
                return true;
            }
            Thread.sleep(10L);
        }
        return uncaughtException.get() != null;
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
