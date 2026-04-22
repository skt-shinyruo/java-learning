package yier.bubu.concurrency.timewheel;

/**
 * Cancellation handle for tasks scheduled by {@link TimingWheelScheduler}.
 */
public interface ScheduledTask {
    /**
     * @return true if this call transitioned the task into cancelled state; false if it was already cancelled
     */
    boolean cancel();

    boolean isCancelled();
}

