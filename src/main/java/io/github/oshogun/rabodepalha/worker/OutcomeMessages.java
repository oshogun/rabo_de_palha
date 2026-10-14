package io.github.oshogun.rabodepalha.worker;

import io.github.oshogun.rabodepalha.task.TaskOutcome;

/** How a worker reports task outcomes to its commanders. Kept free of Minecraft classes for unit tests. */
public final class OutcomeMessages {
	private OutcomeMessages() {
	}

	public static String describe(TaskOutcome outcome) {
		return switch (outcome) {
			case TaskOutcome.Succeeded s -> s.note().isEmpty() ? "done" : "done (" + s.note() + ")";
			case TaskOutcome.Failed f -> f.detail().isEmpty()
					? "failed: " + f.reason()
					: "failed: " + f.reason() + " (" + f.detail() + ")";
			case TaskOutcome.Cancelled c -> c.cause() == TaskOutcome.Cancelled.Cause.REMOVED
					? "cancelled (worker removed)"
					: "cancelled";
			case TaskOutcome.Running r -> "running";
		};
	}

	/**
	 * False for cancellations the commander caused (a replacing order, {@code /rdp clear}); the HUD or
	 * command feedback already confirms those, and reporting them would flood chat.
	 */
	public static boolean notifiesCommanders(TaskOutcome outcome) {
		return !(outcome instanceof TaskOutcome.Cancelled c && c.cause().commanderInitiated());
	}
}
