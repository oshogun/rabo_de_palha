package io.github.oshogun.rabodepalha.task;

/**
 * A unit of work run by a {@link TaskRunner}. World-free: everything a task needs from the
 * world comes through its context {@code C}.
 *
 * <p>Lifecycle: {@link #start} once, then {@link #tick} until it returns a terminal
 * {@link TaskOutcome}, then {@link #stop} once. A task that was never started is never stopped.
 */
public interface Task<C> {
	void start(C ctx);

	TaskOutcome tick(C ctx);

	/** Releases anything the task holds. {@code cancelled} is true when stopped before finishing. */
	void stop(C ctx, boolean cancelled);

	/** Short human-readable description, e.g. for chat feedback. */
	String describe();

	/** Stable id used to look up the task's codec when saving. */
	String typeId();

	/**
	 * True for an order that never ends by itself (e.g. tending a farm). {@link TaskRunner} applies
	 * the same rules to every repeating order:
	 * <ul>
	 *   <li>A worker has at most one. Queuing another supersedes the old one
	 *       ({@code Cancelled(SUPERSEDED)} if it was current; dropped silently if it was queued).
	 *   <li>It is always kept last. Orders appended while it waits or runs go before it.
	 *   <li>It yields to appended orders. When something is queued behind the running repeating
	 *       order, the runner suspends it ({@link #stop} with {@code cancelled = true}, listeners
	 *       are not notified) and re-queues it at the tail, so it starts afresh once the queue drains.
	 *   <li>It ends only by being cancelled: Stop, clear, replacing the queue or removing the worker.
	 *       A terminal outcome from the task itself (e.g. {@code Failed(ERROR)}) still ends it.
	 *   <li>It does not count as finite work ({@link TaskRunner#hasFiniteWork}), e.g. for chunk tickets.
	 * </ul>
	 * Because a suspended task is stopped and restarted, {@link #stop} must leave it able to
	 * {@link #start} again.
	 */
	default boolean repeating() {
		return false;
	}
}
