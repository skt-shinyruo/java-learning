/**
 * Single-level timing wheel teaching implementation.
 *
 * <p>The package intentionally keeps only {@link yier.bubu.concurrency.timewheel.SimpleTimingWheel}
 * so the core algorithm is visible in one file: ticks advance through slots, delayed tasks carry a
 * remaining-round count, and due tasks run when their slot is scanned.
 */
package yier.bubu.concurrency.timewheel;
