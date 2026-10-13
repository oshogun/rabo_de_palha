package io.github.oshogun.rabodepalha.task;

/** Notified by a {@link TaskRunner} when a task reaches a terminal outcome. */
@FunctionalInterface
public interface TaskListener<C> {
	void onFinished(Task<C> task, TaskOutcome outcome);
}
