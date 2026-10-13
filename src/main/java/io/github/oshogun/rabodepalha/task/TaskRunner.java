package io.github.oshogun.rabodepalha.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FIFO queue of {@link Task}s that runs one task at a time. Call {@link #tick} once per game tick.
 *
 * <p>A terminal outcome is final: the task is stopped, listeners are notified and the next
 * task starts on the following tick. A RuntimeException thrown by a task becomes
 * {@code Failed(ERROR, e.toString())} (and is logged with its stack trace); a null outcome from
 * {@code tick} becomes {@code Failed(ERROR, "tick returned null")}.
 */
public class TaskRunner<C> {
	private static final Logger LOGGER = LoggerFactory.getLogger(TaskRunner.class);

	private final ArrayDeque<Task<C>> queue = new ArrayDeque<>();
	private final List<TaskListener<C>> listeners = new CopyOnWriteArrayList<>();
	private Task<C> current;
	private boolean started;

	public void enqueue(Task<C> task) {
		queue.addLast(Objects.requireNonNull(task, "task"));
	}

	public void tick(C ctx) {
		if (current == null) {
			current = queue.pollFirst();
			started = false;
			if (current == null) {
				return;
			}
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

	/** Stops the current task (if any) with {@code cancelled = true} and reports {@link TaskOutcome.Cancelled}. */
	public void cancelCurrent(C ctx) {
		Task<C> task = current;
		if (task == null) {
			return;
		}
		if (started) {
			finish(ctx, task, TaskOutcome.CANCELLED, true);
		} else {
			// Never started, so there is nothing to stop.
			current = null;
			notifyListeners(task, TaskOutcome.CANCELLED);
		}
	}

	/** Cancels the current task and drops everything queued. */
	public void clear(C ctx) {
		queue.clear();
		cancelCurrent(ctx);
	}

	public Optional<Task<C>> current() {
		return Optional.ofNullable(current);
	}

	/** Queued tasks after the current one. */
	public List<Task<C>> pending() {
		return List.copyOf(queue);
	}

	public boolean isIdle() {
		return current == null && queue.isEmpty();
	}

	/** The current task followed by the queued ones, e.g. for saving. */
	public List<Task<C>> snapshot() {
		List<Task<C>> all = new ArrayList<>(queue.size() + 1);
		if (current != null) {
			all.add(current);
		}
		all.addAll(queue);
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
		notifyListeners(task, outcome);
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
