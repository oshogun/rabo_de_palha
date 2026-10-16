package io.github.oshogun.rabodepalha.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FIFO queue of {@link Task}s that runs one task at a time. Call {@link #tick} once per game tick.
 *
 * <p>A terminal outcome is final: the task is stopped, listeners are notified and the next
 * task starts on the following tick. A RuntimeException thrown by a task becomes
 * {@code Failed(ERROR, e.toString())} (and is logged with its stack trace); a null outcome from
 * {@code tick} becomes {@code Failed(ERROR, "tick returned null")}.
 *
 * <p>{@link Task#repeating() Repeating} orders follow the rules documented on that method; with
 * no repeating task queued the runner is a plain FIFO.
 */
public class TaskRunner<C> {
	private static final Logger LOGGER = LoggerFactory.getLogger(TaskRunner.class);

	private final ArrayDeque<Pending<C>> queue = new ArrayDeque<>();
	private final List<TaskListener<C>> listeners = new CopyOnWriteArrayList<>();
	/** Rewrites each terminal outcome (e.g. to append notes) before listeners see it. */
	private final BiFunction<C, TaskOutcome, TaskOutcome> annotator;
	private Task<C> current;
	private boolean started;
	/** Runtime-only ids for queued tasks; not saved, so ids change after a reload. */
	private long nextId;

	public TaskRunner() {
		this((ctx, outcome) -> outcome);
	}

	/**
	 * {@code annotator} is applied to every outcome of a task that ran (finished, failed or was
	 * cancelled after starting) before listeners are notified. If it throws or returns null, the
	 * original outcome is reported.
	 */
	public TaskRunner(BiFunction<C, TaskOutcome, TaskOutcome> annotator) {
		this.annotator = Objects.requireNonNull(annotator, "annotator");
	}

	/** A queued task with its runtime id, used to remove one specific task. */
	public record Pending<C>(long id, Task<C> task) {
		public Pending {
			Objects.requireNonNull(task, "task");
		}
	}

	/**
	 * Queues {@code task}. A {@link Task#repeating() repeating} order is always kept last and at most
	 * one exists: queuing one drops any queued repeating order, and a running one is superseded on
	 * the next {@link #tick}.
	 */
	public void enqueue(Task<C> task) {
		Objects.requireNonNull(task, "task");
		if (task.repeating()) {
			queue.removeIf(entry -> entry.task().repeating());
			queue.addLast(new Pending<>(nextId++, task));
			return;
		}
		Pending<C> last = queue.peekLast();
		if (last != null && last.task().repeating()) {
			queue.pollLast();
			queue.addLast(new Pending<>(nextId++, task));
			queue.addLast(last);
			return;
		}
		queue.addLast(new Pending<>(nextId++, task));
	}

	public void tick(C ctx) {
		if (current != null && current.repeating() && !queue.isEmpty()) {
			// enqueue keeps a repeating order last, so a repeating tail means a newer one replaced it.
			if (queue.peekLast().task().repeating()) {
				cancelCurrent(ctx, TaskOutcome.Cancelled.Cause.SUPERSEDED);
			} else {
				suspendCurrent(ctx);
			}
		}
		if (current == null) {
			Pending<C> next = queue.pollFirst();
			started = false;
			if (next == null) {
				return;
			}
			current = next.task();
		}

		Task<C> task = current;
		TaskOutcome outcome;
		try {
			if (!started) {
				started = true;
				task.start(ctx);
			}
			outcome = task.tick(ctx);
			if (outcome == null) {
				LOGGER.warn("Task {} tick returned null", task.describe());
				outcome = TaskOutcome.failed(FailureReason.ERROR, "tick returned null");
			}
		} catch (RuntimeException e) {
			LOGGER.warn("Task {} threw", task.describe(), e);
			outcome = TaskOutcome.failed(FailureReason.ERROR, e.toString());
		}

		if (outcome.isTerminal()) {
			finish(ctx, task, outcome, false);
		}
	}

	/**
	 * Stops the current task (if any) with {@code cancelled = true} and reports
	 * {@link TaskOutcome.Cancelled} with {@code cause}.
	 */
	public void cancelCurrent(C ctx, TaskOutcome.Cancelled.Cause cause) {
		TaskOutcome.Cancelled outcome = TaskOutcome.cancelled(cause);
		Task<C> task = current;
		if (task == null) {
			return;
		}
		if (started) {
			finish(ctx, task, outcome, true);
		} else {
			// Never started, so there is nothing to stop.
			current = null;
			notifyListeners(task, outcome);
		}
	}

	/** Cancels the current task with {@code cause} and drops everything queued. */
	public void clear(C ctx, TaskOutcome.Cancelled.Cause cause) {
		queue.clear();
		cancelCurrent(ctx, cause);
	}

	public Optional<Task<C>> current() {
		return Optional.ofNullable(current);
	}

	/** Queued tasks after the current one. */
	public List<Task<C>> pending() {
		return queue.stream().map(Pending::task).toList();
	}

	/** Queued tasks after the current one, with their runtime ids. */
	public List<Pending<C>> pendingEntries() {
		return List.copyOf(queue);
	}

	/**
	 * Drops the queued task with {@code id}. The task never started, so it is neither stopped nor
	 * reported to listeners. Returns false if no queued task has that id (including the current one).
	 */
	public boolean removePending(long id) {
		return queue.removeIf(entry -> entry.id() == id);
	}

	public boolean isIdle() {
		return current == null && queue.isEmpty();
	}

	/** True if the current or any queued task is not {@link Task#repeating() repeating}. */
	public boolean hasFiniteWork() {
		return (current != null && !current.repeating())
				|| queue.stream().anyMatch(entry -> !entry.task().repeating());
	}

	/** The current task followed by the queued ones, e.g. for saving. */
	public List<Task<C>> snapshot() {
		List<Task<C>> all = new ArrayList<>(queue.size() + 1);
		if (current != null) {
			all.add(current);
		}
		queue.forEach(entry -> all.add(entry.task()));
		return List.copyOf(all);
	}

	/**
	 * Replaces the whole queue (including the current task) without starting or stopping anything.
	 * Meant for loading; the first task starts from scratch on the next tick.
	 */
	public void restore(List<Task<C>> tasks) {
		current = null;
		started = false;
		queue.clear();
		tasks.forEach(this::enqueue);
	}

	public void addListener(TaskListener<C> listener) {
		listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	private void finish(C ctx, Task<C> task, TaskOutcome outcome, boolean cancelled) {
		current = null;
		started = false;
		try {
			task.stop(ctx, cancelled);
		} catch (RuntimeException e) {
			LOGGER.warn("Task {} threw while stopping", task.describe(), e);
			outcome = TaskOutcome.failed(FailureReason.ERROR, e.toString());
		}
		try {
			TaskOutcome annotated = annotator.apply(ctx, outcome);
			if (annotated != null) {
				outcome = annotated;
			}
		} catch (RuntimeException e) {
			LOGGER.warn("Annotating the outcome of task {} failed", task.describe(), e);
		}
		notifyListeners(task, outcome);
	}

	/**
	 * Moves the current repeating task back to the tail of the queue so the orders appended before
	 * it run first. It is stopped as cancelled but listeners are not told: it has not ended.
	 */
	private void suspendCurrent(C ctx) {
		Task<C> task = current;
		boolean wasStarted = started;
		current = null;
		started = false;
		if (wasStarted) {
			try {
				task.stop(ctx, true);
			} catch (RuntimeException e) {
				LOGGER.warn("Task {} threw while suspending", task.describe(), e);
				notifyListeners(task, TaskOutcome.failed(FailureReason.ERROR, e.toString()));
				return;
			}
		}
		queue.addLast(new Pending<>(nextId++, task));
	}

	private void notifyListeners(Task<C> task, TaskOutcome outcome) {
		for (TaskListener<C> listener : listeners) {
			try {
				listener.onFinished(task, outcome);
			} catch (RuntimeException e) {
				LOGGER.error("Task listener failed for {}", task.describe(), e);
			}
		}
	}
}
