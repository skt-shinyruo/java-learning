package yier.bubu.concurrency;

import org.junit.Assert;
import org.junit.Test;

public class JucSynchronizersDemoTest {
    @Test(timeout = 2000)
    public void countDownLatch_shouldWaitUntilAllWorkersFinish() throws Exception {
        int completedWorkers = JucSynchronizersDemo.waitForWorkersWithCountDownLatch(4);

        Assert.assertEquals(4, completedWorkers);
    }

    @Test(timeout = 2000)
    public void cyclicBarrier_shouldSynchronizeEveryPhase() throws Exception {
        JucSynchronizersDemo.PhasedRunResult result =
                JucSynchronizersDemo.synchronizePhasesWithCyclicBarrier(3, 2);

        Assert.assertEquals(6, result.getArrivals());
        Assert.assertEquals(2, result.getCompletedPhases());
    }

    @Test(timeout = 2000)
    public void semaphore_shouldLimitConcurrentAccess() throws Exception {
        JucSynchronizersDemo.SemaphoreRunResult result =
                JucSynchronizersDemo.limitConcurrentAccessWithSemaphore(8, 2);

        Assert.assertEquals(8, result.getCompletedTasks());
        Assert.assertTrue(result.getMaxConcurrent() <= 2);
        Assert.assertTrue(result.getMaxConcurrent() >= 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void countDownLatchNonPositiveWorkers_shouldThrow() throws Exception {
        JucSynchronizersDemo.waitForWorkersWithCountDownLatch(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void cyclicBarrierNonPositivePhases_shouldThrow() throws Exception {
        JucSynchronizersDemo.synchronizePhasesWithCyclicBarrier(2, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void semaphoreNonPositivePermits_shouldThrow() throws Exception {
        JucSynchronizersDemo.limitConcurrentAccessWithSemaphore(4, 0);
    }
}
