package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class MultiProducerSequencerTest {
    @Test
    public void publish_shouldNotAdvanceCursorPastGap() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createMultiProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());

        long first = ringBuffer.next();
        long second = ringBuffer.next();

        ringBuffer.get(second).sequence = second;
        ringBuffer.publish(second);

        Assert.assertEquals(-1L, ringBuffer.getCursor());

        ringBuffer.get(first).sequence = first;
        ringBuffer.publish(first);

        Assert.assertEquals(1L, ringBuffer.getCursor());
    }

    @Test
    public void createMultiProducer_shouldPublishFromMultipleThreads() throws InterruptedException {
        final RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createMultiProducer(new TestEventFactory(), 64, new BlockingWaitStrategy());
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(2);
        final AtomicInteger published = new AtomicInteger();

        Runnable publisher = new Runnable() {
            @Override
            public void run() {
                ready.countDown();
                try {
                    Assert.assertTrue(start.await(1L, TimeUnit.SECONDS));
                    for (int index = 0; index < 25; index++) {
                        ringBuffer.publish(new EventTranslator<TestEvent>() {
                            @Override
                            public void translateTo(TestEvent event, long sequence) {
                                event.sequence = sequence;
                                published.incrementAndGet();
                            }
                        });
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    Assert.fail("Publisher interrupted");
                } finally {
                    done.countDown();
                }
            }
        };

        Thread firstPublisher = new Thread(publisher, "multi-producer-1");
        Thread secondPublisher = new Thread(publisher, "multi-producer-2");
        firstPublisher.start();
        secondPublisher.start();

        Assert.assertTrue(ready.await(1L, TimeUnit.SECONDS));
        start.countDown();

        Assert.assertTrue(done.await(1L, TimeUnit.SECONDS));
        Assert.assertEquals(50, published.get());
        Assert.assertEquals(49L, ringBuffer.getCursor());
    }

    private static final class TestEvent {
        private long sequence = -1L;
    }

    private static final class TestEventFactory implements EventFactory<TestEvent> {
        @Override
        public TestEvent newInstance() {
            return new TestEvent();
        }
    }
}
