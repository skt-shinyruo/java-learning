/**
 * Timing wheel scheduler (hierarchical).
 *
 * <p>Key semantics:
 * <ul>
 *   <li>Time source is monotonic nano time ({@code System.nanoTime()}).</li>
 *   <li>Tick alignment uses ceil-to-tick, so tasks are never executed earlier than their deadline.</li>
 *   <li>Periodic tasks are non-overlapping: the next run is scheduled only after the previous run completes.</li>
 * </ul>
 *
 * <p>Internal design:
 * <ul>
 *   <li>Each wheel level has {@code wheelSize} buckets and covers {@code interval=tick*wheelSize}.</li>
 *   <li>Tasks beyond the current level interval go to an overflow level with tick=interval.</li>
 *   <li>All buckets share one {@code DelayQueue} to let the worker block until the next bucket expires.</li>
 * </ul>
 */
package yier.bubu.concurrency.timewheel;

