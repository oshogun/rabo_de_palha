package io.github.oshogun.rabodepalha.rts;

import org.jspecify.annotations.Nullable;

/**
 * What a right-click would order in RTS mode, and what the hover highlight shows, from what is under the
 * cursor. It has no client dependencies, so the rules can be unit-tested; the client fills in the
 * {@link Target} and turns the {@link Hint} key into text with the block or item name.
 */
public final class RtsOrderHint {
	private RtsOrderHint() {
	}

	/**
	 * What the cursor is over.
	 *
	 * @param workerHit whether a worker is under the cursor. It drives only the hover highlight and never
	 *     affects the order: right-click targets the block behind or under the worker.
	 * @param blockHit whether the cursor ray hits a block
	 * @param blockIsAir whether the hit block is air (only meaningful with {@code blockHit})
	 * @param pickedBlock whether the item picked from the workers' inventory is a block
	 * @param targetDistSqr squared distance from the player's eyes to the order's target block
	 */
	public record Target(boolean workerHit, boolean blockHit, boolean blockIsAir, boolean pickedBlock,
			double targetDistSqr) {
	}

	/**
	 * @param key translation key: {@code rabodepalha.rts.hint.*} when valid, the rejection reason otherwise
	 * @param valid whether the order would be accepted as far as the client can tell
	 * @param kind the order kind being evaluated, set even when the order is rejected; {@code null} for a spawn or a
	 *     container order
	 */
	public record Hint(String key, boolean valid, @Nullable RtsOrderKind kind) {
	}

	/** What to highlight under the cursor. */
	public enum HoverKind {
		WORKER,
		BLOCK,
		NONE
	}

	/** A worker beats a block, for the highlight only. */
	public static HoverKind hoverKind(Target t) {
		if (t.workerHit()) {
			return HoverKind.WORKER;
		}
		return t.blockHit() ? HoverKind.BLOCK : HoverKind.NONE;
	}

	/**
	 * An order of the given kind at the target. Rejections are checked in this order: nothing selected,
	 * no block hit, mining air, nothing to place, target too far.
	 */
	public static Hint evaluate(int selectionCount, RtsOrderKind kind, Target t) {
		if (selectionCount <= 0) {
			return reject("rabodepalha.rts.order.nothing_selected", kind);
		}
		if (!t.blockHit()) {
			return reject("rabodepalha.rts.order.no_target", kind);
		}
		if (kind == RtsOrderKind.MINE && t.blockIsAir()) {
			return reject("rabodepalha.rts.order.air", kind);
		}
		if (kind == RtsOrderKind.PLACE && !t.pickedBlock()) {
			return reject("rabodepalha.rts.order.no_block", kind);
		}
		if (t.targetDistSqr() > RtsOrders.MAX_TARGET_DISTANCE * RtsOrders.MAX_TARGET_DISTANCE) {
			return reject("rabodepalha.rts.order.target_too_far", kind);
		}
		return new Hint(switch (kind) {
			case MOVE -> "rabodepalha.rts.hint.move";
			case MINE -> "rabodepalha.rts.hint.mine";
			case PLACE -> "rabodepalha.rts.hint.place";
		}, true, kind);
	}

	/**
	 * Spawning a worker on top of the target block. It needs no selection, and the target distance is
	 * measured to the spawn spot. Rejections are checked in this order: no block hit or air, target too
	 * far, no room for a worker at the spawn spot.
	 *
	 * @param roomFree whether a worker fits on top of the target block
	 */
	public static Hint evaluateSpawn(Target t, boolean roomFree) {
		if (!t.blockHit() || t.blockIsAir()) {
			return reject("rabodepalha.rts.order.no_target", null);
		}
		if (t.targetDistSqr() > RtsOrders.MAX_TARGET_DISTANCE * RtsOrders.MAX_TARGET_DISTANCE) {
			return reject("rabodepalha.rts.order.target_too_far", null);
		}
		if (!roomFree) {
			return reject("rabodepalha.rts.order.no_room", null);
		}
		return new Hint("rabodepalha.rts.hint.spawn", true, null);
	}

	/**
	 * A deposit into or withdrawal from the clicked container. Rejections are checked in this order:
	 * nothing selected, not a container. The server also checks distance and the loaded chunk.
	 *
	 * @param isContainer whether the target block holds a container, as far as the client can tell
	 * @param okKey the hint key when valid: {@code rabodepalha.rts.hint.deposit} or {@code .withdraw}
	 */
	public static Hint evaluateContainer(int selectionCount, boolean isContainer, String okKey) {
		if (selectionCount <= 0) {
			return reject("rabodepalha.rts.order.nothing_selected", null);
		}
		if (!isContainer) {
			return reject("rabodepalha.rts.order.not_container", null);
		}
		return new Hint(okKey, true, null);
	}

	private static Hint reject(String key, @Nullable RtsOrderKind kind) {
		return new Hint(key, false, kind);
	}
}
