package yier.bubu.base.singleton;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class SingletonTest {
    @Test
    public void eagerSingleton_shouldReturnSameInstance() {
        Assert.assertSame(EagerSingleton.getInstance(), EagerSingleton.getInstance());
    }

    @Test
    public void lazySynchronizedSingleton_shouldReturnSameInstance() {
        Assert.assertSame(LazySynchronizedSingleton.getInstance(), LazySynchronizedSingleton.getInstance());
    }

    @Test
    public void doubleCheckedLockingSingleton_shouldReturnSameInstance() {
        Assert.assertSame(
                DoubleCheckedLockingSingleton.getInstance(),
                DoubleCheckedLockingSingleton.getInstance());
    }

    @Test
    public void holderSingleton_shouldReturnSameInstance() {
        Assert.assertSame(HolderSingleton.getInstance(), HolderSingleton.getInstance());
    }

    @Test
    public void enumSingleton_shouldReturnSameInstance() {
        Assert.assertSame(EnumSingleton.getInstance(), EnumSingleton.getInstance());
        Assert.assertSame(EnumSingleton.INSTANCE, EnumSingleton.getInstance());
    }

    @Test
    public void atomicReferenceSingleton_shouldReturnSameInstance() {
        Assert.assertSame(AtomicReferenceSingleton.getInstance(), AtomicReferenceSingleton.getInstance());
    }

    @Test
    public void fieldUpdaterSingleton_shouldReturnSameInstance() {
        Assert.assertSame(FieldUpdaterSingleton.getInstance(), FieldUpdaterSingleton.getInstance());
    }

    @Test
    public void varHandleSingleton_shouldReturnSameInstance() {
        Assert.assertSame(VarHandleSingleton.getInstance(), VarHandleSingleton.getInstance());
    }

    @Test
    public void unsafeSingleton_shouldReturnSameInstance() {
        Assert.assertSame(UnsafeSingleton.getInstance(), UnsafeSingleton.getInstance());
    }

    @Test
    public void futureTaskSingleton_shouldReturnSameInstance() {
        Assert.assertSame(FutureTaskSingleton.getInstance(), FutureTaskSingleton.getInstance());
    }

    @Test
    public void staticBlockSingleton_shouldReturnSameInstance() {
        Assert.assertSame(StaticBlockSingleton.getInstance(), StaticBlockSingleton.getInstance());
    }

    @Test
    public void serializableSingleton_shouldKeepSingletonAfterSerialization() throws IOException, ClassNotFoundException {
        SerializableSingleton instance = SerializableSingleton.getInstance();
        SerializableSingleton deserialized = (SerializableSingleton) serializeAndDeserialize(instance);
        Assert.assertSame(instance, deserialized);
    }

    @Test
    public void threadLocalSingleton_shouldReturnSameInstanceInSameThreadAndDifferentAcrossThreads()
            throws InterruptedException {
        ThreadLocalSingleton first = ThreadLocalSingleton.getInstance();
        ThreadLocalSingleton second = ThreadLocalSingleton.getInstance();
        Assert.assertSame(first, second);

        final Object[] otherThreadInstance = new Object[1];
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                otherThreadInstance[0] = ThreadLocalSingleton.getInstance();
            }
        });
        t.start();
        t.join();

        Assert.assertNotNull(otherThreadInstance[0]);
        Assert.assertNotSame(first, otherThreadInstance[0]);
    }

    @Test
    public void keyedMultiton_shouldReturnSameInstanceForSameKeyAndDifferentInstanceForDifferentKey() {
        KeyedMultiton a1 = KeyedMultiton.getInstance("A");
        KeyedMultiton a2 = KeyedMultiton.getInstance("A");
        Assert.assertSame(a1, a2);

        KeyedMultiton b = KeyedMultiton.getInstance("B");
        Assert.assertNotSame(a1, b);
        Assert.assertEquals("A", a1.getKey());
        Assert.assertEquals("B", b.getKey());
    }

    @Test
    public void lazy_shouldInitializeOnceUnderConcurrency() throws InterruptedException {
        final AtomicInteger calls = new AtomicInteger();
        final Lazy<Object> lazy = Lazy.of(new java.util.function.Supplier<Object>() {
            @Override
            public Object get() {
                calls.incrementAndGet();
                return new Object();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return lazy.get();
            }
        });
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void concurrency_shouldStillReturnSingleInstance() throws InterruptedException {
        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return EagerSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return LazySynchronizedSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return DoubleCheckedLockingSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return HolderSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return EnumSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return AtomicReferenceSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return FieldUpdaterSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return VarHandleSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return UnsafeSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return FutureTaskSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return StaticBlockSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return SerializableSingleton.getInstance();
            }
        });

        assertSingleInstanceUnderConcurrency(new InstanceProvider() {
            @Override
            public Object get() {
                return KeyedMultiton.getInstance("CONCURRENT-KEY");
            }
        });
    }

    private void assertSingleInstanceUnderConcurrency(InstanceProvider instanceProvider) throws InterruptedException {
        int threadCount = 32;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        Set<Object> instances = Collections.synchronizedSet(new HashSet<Object>());

        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    ready.countDown();
                    try {
                        start.await();
                        instances.add(instanceProvider.get());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            }, "singleton-test-" + i);
            t.start();
        }

        ready.await();
        start.countDown();
        done.await();

        Assert.assertEquals(1, instances.size());
    }

    private Object serializeAndDeserialize(Object object) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
        objectOutputStream.writeObject(object);
        objectOutputStream.flush();

        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
        return objectInputStream.readObject();
    }

    private interface InstanceProvider {
        Object get();
    }
}
