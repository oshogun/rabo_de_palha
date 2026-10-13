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
}
