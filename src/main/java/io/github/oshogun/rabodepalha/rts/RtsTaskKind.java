package io.github.oshogun.rabodepalha.rts;

import io.github.oshogun.rabodepalha.worker.task.TaskSummary;
import io.github.oshogun.rabodepalha.worker.task.TaskTypes;

/**
 * What kind of order a task is, for the queued-order markers and the command bar's colours. Read from a
 * {@link TaskSummary}'s key, so the client can tell task kinds apart without the task itself.
 */
public enum RtsTaskKind {
	MOVE,
	MINE,
	PLACE,
	DIG,
	BUILD,
	DEPOSIT,
	WITHDRAW,
	USE,
	PLANT,
	HARVEST,
	OTHER;

	/** The kind of the summarised task; an unknown or malformed key is {@link #OTHER}. */
	public static RtsTaskKind of(TaskSummary summary) {
		String key = summary.key();
		if (!key.startsWith(TaskTypes.KEY_PREFIX)) {
			return OTHER;
		}
		return switch (key.substring(TaskTypes.KEY_PREFIX.length())) {
			case "move_to" -> MOVE;
			case "mine" -> MINE;
			case "place" -> PLACE;
			case "dig_area" -> DIG;
			case "build" -> BUILD;
			case "deposit", "deposit_item" -> DEPOSIT;
			case "withdraw" -> WITHDRAW;
			case "use" -> USE;
			case "plant_field" -> PLANT;
			case "harvest_area" -> HARVEST;
			default -> OTHER;
		};
	}

	/** The kind of task an RTS order of {@code kind} queues. */
	public static RtsTaskKind from(RtsOrderKind kind) {
		return switch (kind) {
			case MOVE -> MOVE;
			case MINE -> MINE;
			case PLACE -> PLACE;
			case USE -> USE;
		};
	}
}
