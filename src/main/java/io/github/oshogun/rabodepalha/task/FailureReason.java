package io.github.oshogun.rabodepalha.task;

public enum FailureReason {
	UNREACHABLE,
	STUCK,
	TIMEOUT,
	UNBREAKABLE,
	AIR,
	NO_TOOL,
	/** The worker's own inventory lacks an item the task needs; see {@link #ITEM_NOT_FOUND} for containers. */
	MISSING_ITEM,
	NOT_REPLACEABLE,
	OBSTRUCTED,
	CANNOT_SURVIVE,
	TARGET_CHANGED,
	/** The target block has no container (chest, barrel, ...). */
	NOT_A_CONTAINER,
	/** The container could not take everything the worker tried to put in. */
	CONTAINER_FULL,
	/**
	 * The container's slot rules accept none of the items (a shulker box into a shulker box, dirt into a
	 * brewing stand), whether or not it has free space.
	 */
	ITEM_REFUSED,
	/** The container holds fewer of an item than requested; {@link #MISSING_ITEM} is for the worker's inventory. */
	ITEM_NOT_FOUND,
	/** The worker's inventory has no room for the items the task would take. */
	INVENTORY_FULL,
	/** The block is not something a worker can use (lever, button, hand-openable door or trapdoor, fence gate). */
	NOT_USABLE,
	/** Unexpected failure inside task code (an exception, or tick returned null). */
	ERROR
}
