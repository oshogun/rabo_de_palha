package io.github.oshogun.rabodepalha.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oshogun.rabodepalha.task.TaskOutcome.Cancelled.Cause;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskRunnerTest {
	private static final Object CTX = new Object();

	private TaskRunner<Object> runner;
	private List<String> events;
	private List<TaskOutcome> outcomes;

	@BeforeEach
	void setUp() {
		runner = new TaskRunner<>();
		events = new ArrayList<>();
		outcomes = new ArrayList<>();
		runner.addListener((task, outcome) -> {
			events.add("finished " + task.describe());
			outcomes.add(outcome);
		});
	}

	@Test
	void runsTasksInFifoOrder() {
		runner.enqueue(new FakeTask("a", 1));
		runner.enqueue(new FakeTask("b", 1));
		runner.enqueue(new FakeTask("c", 1));

		tick(3);

		assertEquals(List.of(
				"start a", "tick a", "stop a false", "finished a",
				"start b", "tick b", "stop b false", "finished b",
				"start c", "tick c", "stop c false", "finished c"), events);
		assertTrue(runner.isIdle());
	}

	@Test
	void runsOneTaskAtATime() {
		FakeTask a = new FakeTask("a", 3);
		FakeTask b = new FakeTask("b", 1);
		runner.enqueue(a);
		runner.enqueue(b);

		tick(2);
		assertEquals(a, runner.current().orElseThrow());
		assertEquals(List.of(b), runner.pending());
		assertEquals(0, b.starts);

		tick(1);
		assertEquals(1, a.stops);
		assertTrue(runner.current().isEmpty());
		assertEquals(0, b.starts);

		tick(1);
		assertEquals(1, b.starts);
	}

	@Test
	void startAndStopCalledOnceWithCancelledFlag() {
		FakeTask done = new FakeTask("done", 2);
		FakeTask cancelled = new FakeTask("cancelled", 100);
		runner.enqueue(done);
		runner.enqueue(cancelled);

		tick(5);
		runner.cancelCurrent(CTX, Cause.SUPERSEDED);
		tick(3);

		assertEquals(1, done.starts);
		assertEquals(1, done.stops);
		assertEquals(List.of(false), done.cancelledFlags);
		assertEquals(1, cancelled.starts);
		assertEquals(1, cancelled.stops);
		assertEquals(List.of(true), cancelled.cancelledFlags);
		assertInstanceOf(TaskOutcome.Succeeded.class, outcomes.get(0));
		assertEquals(new TaskOutcome.Cancelled(Cause.SUPERSEDED), outcomes.get(1));
	}

	@Test
	void cancelReportsCauseForStartedTask() {
		FakeTask task = new FakeTask("a", 100);
		runner.enqueue(task);
		tick(1);

		runner.cancelCurrent(CTX, Cause.REMOVED);

		assertEquals(List.of(true), task.cancelledFlags);
		assertEquals(List.of(new TaskOutcome.Cancelled(Cause.REMOVED)), outcomes);
	}

	@Test
	void clearReportsSuperseded() {
		runner.enqueue(new FakeTask("a", 100));
		runner.enqueue(new FakeTask("b", 1));
		tick(1);

		runner.clear(CTX, Cause.SUPERSEDED);

		assertTrue(runner.isIdle());
		assertEquals(List.of(new TaskOutcome.Cancelled(Cause.SUPERSEDED)), outcomes);
	}

	@Test
	void cancelledRejectsNullCause() {
		assertThrows(NullPointerException.class, () -> new TaskOutcome.Cancelled(null));
	}

	@Test
	void terminalOutcomeIsFinal() {
		FakeTask task = new FakeTask("a", 1);
		runner.enqueue(task);

		tick(10);

		assertEquals(1, task.ticks);
		assertEquals(1, outcomes.size());
		runner.cancelCurrent(CTX, Cause.CLEARED);
		runner.clear(CTX, Cause.CLEARED);
		assertEquals(1, task.stops);
		assertEquals(1, outcomes.size());
	}

	@Test
	void failedReasonIsKept() {
		runner.enqueue(new FakeTask("a", TaskOutcome.failed(FailureReason.UNBREAKABLE, "bedrock")));

		tick(1);

		TaskOutcome.Failed failed = assertInstanceOf(TaskOutcome.Failed.class, outcomes.get(0));
		assertEquals(FailureReason.UNBREAKABLE, failed.reason());
		assertEquals("bedrock", failed.detail());
	}

	@Test
	void clearCancelsCurrentAndDropsQueue() {
		FakeTask a = new FakeTask("a", 100);
		FakeTask b = new FakeTask("b", 1);
		runner.enqueue(a);
		runner.enqueue(b);
		tick(2);

		runner.clear(CTX, Cause.CLEARED);
		tick(3);

		assertTrue(runner.isIdle());
		// MineTask clears its crack overlay in stop, so the running task must be stopped exactly once.
		assertEquals(1, a.stops);
		assertEquals(List.of(true), a.cancelledFlags);
		// Pending tasks never started, so they are neither stopped nor reported.
		assertEquals(0, b.starts);
		assertEquals(0, b.stops);
		assertEquals(List.of(new TaskOutcome.Cancelled(Cause.CLEARED)), outcomes);
	}

	@Test
	void exceptionBecomesFailed() {
		FakeTask throwsOnStart = new FakeTask("start", 1);
		throwsOnStart.throwOnStart = true;
		FakeTask throwsOnTick = new FakeTask("tick", 1);
		throwsOnTick.throwOnTick = true;
		FakeTask next = new FakeTask("next", 1);
		runner.enqueue(throwsOnStart);
		runner.enqueue(throwsOnTick);
		runner.enqueue(next);

		tick(3);

		assertEquals(3, outcomes.size());
		for (TaskOutcome outcome : outcomes.subList(0, 2)) {
			TaskOutcome.Failed failed = assertInstanceOf(TaskOutcome.Failed.class, outcome);
			assertEquals(FailureReason.ERROR, failed.reason());
			assertTrue(failed.detail().contains("IllegalStateException"), failed.detail());
			assertTrue(failed.detail().contains("boom"), failed.detail());
		}
		assertEquals(List.of(false), throwsOnStart.cancelledFlags);
		assertEquals(List.of(false), throwsOnTick.cancelledFlags);
		assertInstanceOf(TaskOutcome.Succeeded.class, outcomes.get(2));
	}

	@Test
	void exceptionInStopBecomesFailed() {
		FakeTask throwsOnStop = new FakeTask("stop", 1);
		throwsOnStop.throwOnStop = true;
		runner.enqueue(throwsOnStop);
		runner.enqueue(new FakeTask("next", 1));

		tick(2);

		assertEquals(2, outcomes.size());
		TaskOutcome.Failed failed = assertInstanceOf(TaskOutcome.Failed.class, outcomes.get(0));
		assertEquals(FailureReason.ERROR, failed.reason());
		assertTrue(failed.detail().contains("boom"), failed.detail());
		assertInstanceOf(TaskOutcome.Succeeded.class, outcomes.get(1));
	}

	@Test
	void nullOutcomeBecomesFailed() {
		FakeTask returnsNull = new FakeTask("null", 1);
		returnsNull.returnNull = true;
		FakeTask next = new FakeTask("next", 1);
		runner.enqueue(returnsNull);
		runner.enqueue(next);

		tick(2);

		assertEquals(2, outcomes.size());
		TaskOutcome.Failed failed = assertInstanceOf(TaskOutcome.Failed.class, outcomes.get(0));
		assertEquals(FailureReason.ERROR, failed.reason());
		assertEquals("tick returned null", failed.detail());
		assertEquals(List.of(false), returnsNull.cancelledFlags);
		assertInstanceOf(TaskOutcome.Succeeded.class, outcomes.get(1));
	}

	@Test
	void snapshotAndRestore() {
		FakeTask a = new FakeTask("a", 100);
		FakeTask b = new FakeTask("b", 1);
		runner.enqueue(a);
		runner.enqueue(b);
		tick(1);

		List<Task<Object>> snapshot = runner.snapshot();
		assertEquals(List.of(a, b), snapshot);

		TaskRunner<Object> restored = new TaskRunner<>();
		FakeTask a2 = new FakeTask("a2", 1);
		FakeTask b2 = new FakeTask("b2", 1);
		restored.restore(List.of(a2, b2));

		assertEquals(List.of(a2, b2), restored.snapshot());
		assertEquals(0, a2.starts);
		restored.tick(CTX);
		assertEquals(1, a2.starts);
		assertEquals(List.of(b2), restored.snapshot());
		// The original runner is untouched by the snapshot.
		assertEquals(List.of(a, b), runner.snapshot());
		assertFalse(a.cancelledFlags.contains(true));
	}

	@Test
	void removePendingRemovesOnlyThatTask() {
		FakeTask a = new FakeTask("a", 100);
		FakeTask b = new FakeTask("b", 1);
		FakeTask c = new FakeTask("c", 1);
		FakeTask d = new FakeTask("d", 1);
		runner.enqueue(a);
		runner.enqueue(b);
		runner.enqueue(c);
		runner.enqueue(d);
		tick(1);

		List<TaskRunner.Pending<Object>> entries = runner.pendingEntries();
		assertEquals(List.of(b, c, d), entries.stream().map(TaskRunner.Pending::task).toList());
		assertTrue(runner.removePending(entries.get(1).id()));

		assertEquals(List.of(b, d), runner.pending());
		assertEquals(a, runner.current().orElseThrow());
		assertEquals(0, c.starts);
		assertEquals(0, c.stops);
		assertTrue(outcomes.isEmpty());
		assertFalse(runner.removePending(entries.get(1).id()));
	}

	@Test
	void removePendingRejectsUnknownAndCurrentIds() {
		FakeTask a = new FakeTask("a", 100);
		runner.enqueue(a);
		long currentId = runner.pendingEntries().get(0).id();
		runner.enqueue(new FakeTask("b", 1));
		tick(1);

		assertFalse(runner.removePending(currentId));
		assertFalse(runner.removePending(Long.MAX_VALUE));
		assertEquals(a, runner.current().orElseThrow());
		assertEquals(1, runner.pending().size());
		assertEquals(0, a.stops);
		assertTrue(outcomes.isEmpty());
	}

	@Test
	void pendingIdsStayUnique() {
		runner.enqueue(new FakeTask("a", 1));
		runner.enqueue(new FakeTask("b", 1));
		List<Long> before = runner.pendingEntries().stream().map(TaskRunner.Pending::id).toList();

		runner.restore(List.of(new FakeTask("c", 1), new FakeTask("d", 1)));
		runner.enqueue(new FakeTask("e", 1));
		List<Long> after = runner.pendingEntries().stream().map(TaskRunner.Pending::id).toList();

		List<Long> all = new ArrayList<>(before);
		all.addAll(after);
		assertEquals(5, all.size());
		assertEquals(5, all.stream().distinct().count());
	}

	@Test
	void repeatingTaskNeverFinishesOnItsOwn() {
		FakeTask farm = repeating("farm");
		runner.enqueue(farm);

		tick(50);

		assertEquals(farm, runner.current().orElseThrow());
		assertEquals(1, farm.starts);
		assertEquals(0, farm.stops);
		assertTrue(outcomes.isEmpty());
		assertFalse(runner.hasFiniteWork());
		assertFalse(runner.isIdle());
	}

	@Test
	void appendedTasksRunBeforeRepeatingThenItResumes() {
		FakeTask farm = repeating("farm");
		runner.enqueue(farm);
		tick(3);

		FakeTask a = new FakeTask("a", 2);
		FakeTask b = new FakeTask("b", 1);
		runner.enqueue(a);
		runner.enqueue(b);
		assertEquals(List.of(a, b), runner.pending());
		assertTrue(runner.hasFiniteWork());
		events.clear();

		tick(4);

		assertEquals(List.of(
				"stop farm true", "start a", "tick a",
				"tick a", "stop a false", "finished a",
				"start b", "tick b", "stop b false", "finished b",
				"start farm", "tick farm"), events);
		// Suspending is not an outcome: only a and b were reported.
		assertEquals(2, outcomes.size());
		assertEquals(farm, runner.current().orElseThrow());
		assertEquals(2, farm.starts);
		assertEquals(List.of(true), farm.cancelledFlags);
		assertFalse(runner.hasFiniteWork());
	}

	@Test
	void repeatingTaskStaysLastWhenQueuedBeforeOthers() {
		FakeTask first = new FakeTask("first", 100);
		FakeTask farm = repeating("farm");
		FakeTask later = new FakeTask("later", 1);
		runner.enqueue(first);
		runner.enqueue(farm);
		long farmId = runner.pendingEntries().get(1).id();
		runner.enqueue(later);

		assertEquals(List.of(first, later, farm), runner.pending());
		assertEquals(farmId, runner.pendingEntries().get(2).id());
	}

	@Test
	void secondRepeatingTaskSupersedesFirst() {
		FakeTask farm = repeating("farm");
		runner.enqueue(farm);
		tick(2);

		FakeTask other = repeating("other");
		runner.enqueue(other);
		tick(1);

		assertEquals(List.of(new TaskOutcome.Cancelled(Cause.SUPERSEDED)), outcomes);
		assertEquals(List.of(true), farm.cancelledFlags);
		assertEquals(other, runner.current().orElseThrow());
		assertTrue(runner.pending().isEmpty());
	}

	@Test
	void queuedRepeatingTaskIsReplacedSilently() {
		FakeTask a = new FakeTask("a", 100);
		runner.enqueue(a);
		FakeTask farm = repeating("farm");
		FakeTask other = repeating("other");
		runner.enqueue(farm);
		runner.enqueue(other);

		assertEquals(List.of(a, other), runner.pending());
		assertEquals(0, farm.starts);
		assertTrue(outcomes.isEmpty());
	}

	@Test
	void clearEndsCurrentRepeatingTask() {
		FakeTask farm = repeating("farm");
		runner.enqueue(farm);
		tick(2);

		runner.clear(CTX, Cause.CLEARED);

		assertTrue(runner.isIdle());
		assertEquals(List.of(true), farm.cancelledFlags);
		assertEquals(List.of(new TaskOutcome.Cancelled(Cause.CLEARED)), outcomes);
	}

	@Test
	void clearDropsSuspendedRepeatingTask() {
		FakeTask farm = repeating("farm");
		runner.enqueue(farm);
		tick(2);
		FakeTask a = new FakeTask("a", 100);
		runner.enqueue(a);
		tick(1);
		assertEquals(a, runner.current().orElseThrow());

		runner.clear(CTX, Cause.CLEARED);
		tick(3);

		assertTrue(runner.isIdle());
		// Stopped once when suspended; never restarted.
		assertEquals(1, farm.stops);
		assertEquals(1, farm.starts);
		assertEquals(List.of(new TaskOutcome.Cancelled(Cause.CLEARED)), outcomes);
	}

	@Test
	void hasFiniteWorkIgnoresRepeatingTasks() {
		assertFalse(runner.hasFiniteWork());
		runner.enqueue(repeating("farm"));
		assertFalse(runner.hasFiniteWork());
		runner.enqueue(new FakeTask("a", 1));
		assertTrue(runner.hasFiniteWork());
		tick(2);
		assertFalse(runner.hasFiniteWork());
	}

	@Test
	void snapshotAndRestoreKeepRepeatingTaskLast() {
		FakeTask farm = repeating("farm");
		FakeTask a = new FakeTask("a", 100);
		runner.enqueue(farm);
		tick(1);
		runner.enqueue(a);
		// Not ticked yet: the farm is still current.
		assertEquals(List.of(farm, a), runner.snapshot());

		TaskRunner<Object> restored = new TaskRunner<>();
		restored.restore(runner.snapshot());

		assertEquals(List.of(a, farm), restored.snapshot());
	}

	@Test
	void annotatorRewritesOutcomesBeforeListeners() {
		List<Object> contexts = new ArrayList<>();
		TaskRunner<Object> annotated = new TaskRunner<>((ctx, outcome) -> {
			contexts.add(ctx);
			return outcome instanceof TaskOutcome.Succeeded s ? TaskOutcome.succeeded(s.note() + "; noted") : outcome;
		});
		annotated.addListener((task, outcome) -> outcomes.add(outcome));
		annotated.enqueue(new FakeTask("a", 1));
		annotated.enqueue(new FakeTask("b", 100));

		annotated.tick(CTX);
		annotated.tick(CTX);
		annotated.cancelCurrent(CTX, Cause.REMOVED);

		assertEquals(List.of(TaskOutcome.succeeded("a; noted"), new TaskOutcome.Cancelled(Cause.REMOVED)), outcomes);
		assertEquals(List.of(CTX, CTX), contexts);
	}

	@Test
	void throwingOrNullAnnotatorKeepsOriginalOutcome() {
		TaskRunner<Object> throwing = new TaskRunner<>((ctx, outcome) -> {
			throw new IllegalStateException("boom");
		});
		TaskRunner<Object> nulling = new TaskRunner<>((ctx, outcome) -> null);
		for (TaskRunner<Object> r : List.of(throwing, nulling)) {
			r.addListener((task, outcome) -> outcomes.add(outcome));
			r.enqueue(new FakeTask("a", 1));
			r.tick(CTX);
		}

		assertEquals(List.of(TaskOutcome.succeeded("a"), TaskOutcome.succeeded("a")), outcomes);
	}

	private FakeTask repeating(String name) {
		FakeTask task = new FakeTask(name, TaskOutcome.RUNNING);
		task.repeating = true;
		return task;
	}

	private void tick(int times) {
		for (int i = 0; i < times; i++) {
			runner.tick(CTX);
		}
	}

	/** Returns RUNNING until its scripted outcomes run out, recording every lifecycle call. */
	private final class FakeTask implements Task<Object> {
		private final String name;
		private final Deque<TaskOutcome> script = new ArrayDeque<>();
		private final List<Boolean> cancelledFlags = new ArrayList<>();
		private int starts;
		private int ticks;
		private int stops;
		private boolean throwOnStart;
		private boolean throwOnTick;
		private boolean throwOnStop;
		private boolean returnNull;
		private boolean repeating;

		/** Runs for {@code runningTicks - 1} ticks, then succeeds. */
		FakeTask(String name, int runningTicks) {
			this.name = name;
			for (int i = 1; i < runningTicks; i++) {
				script.add(TaskOutcome.RUNNING);
			}
			script.add(TaskOutcome.succeeded(name));
		}

		FakeTask(String name, TaskOutcome... outcomes) {
			this.name = name;
			script.addAll(List.of(outcomes));
		}

		@Override
		public void start(Object ctx) {
			starts++;
			events.add("start " + name);
			if (throwOnStart) {
				throw new IllegalStateException("boom");
			}
		}

		@Override
		public TaskOutcome tick(Object ctx) {
			ticks++;
			events.add("tick " + name);
			if (throwOnTick) {
				throw new IllegalStateException("boom");
			}
			if (returnNull) {
				return null;
			}
			TaskOutcome next = script.pollFirst();
			return next != null ? next : TaskOutcome.RUNNING;
		}

		@Override
		public void stop(Object ctx, boolean cancelled) {
			stops++;
			cancelledFlags.add(cancelled);
			events.add("stop " + name + " " + cancelled);
			if (throwOnStop) {
				throw new IllegalStateException("boom");
			}
		}

		@Override
		public String describe() {
			return name;
		}

		@Override
		public String typeId() {
			return "fake";
		}

		@Override
		public boolean repeating() {
			return repeating;
		}
	}
}
