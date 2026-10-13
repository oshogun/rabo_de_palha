package io.github.oshogun.rabodepalha.task;

public enum FailureReason {
	UNREACHABLE,
	STUCK,
	TIMEOUT,
	UNBREAKABLE,
	AIR,
	NO_TOOL,
	MISSING_ITEM,
	NOT_REPLACEABLE,
	OBSTRUCTED,
	CANNOT_SURVIVE,
	TARGET_CHANGED,
	/** Unexpected failure inside task code (an exception, or tick returned null). */
	ERROR
}
