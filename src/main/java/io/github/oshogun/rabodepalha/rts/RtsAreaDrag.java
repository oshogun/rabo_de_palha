package io.github.oshogun.rabodepalha.rts;

import org.jspecify.annotations.Nullable;

/**
 * The input state of marking a dig area in RTS mode: press on a block, drag to the opposite corner,
 * release, then set the depth with the scroll wheel and confirm. A flat drag (a field to plant) skips
 * the depth step: release fixes the rectangle between the two corners. It holds no Minecraft types so
 * it can be unit-tested.
 */
public final class RtsAreaDrag {
	public enum Phase {
		/** Nothing is being marked. */
		IDLE,
		/** The mouse is held and the opposite corner follows the cursor. */
		DRAGGING,
		/** The rectangle is fixed and the scroll wheel sets the depth. */
		DEPTH,
		/** A flat drag was released: the area between the corners is fixed and waits to be confirmed. */
		FIXED
	}

	private Phase phase = Phase.IDLE;
	private int ax, ay, az;
	private int bx, by, bz;
	private int depth = 1;
	private boolean flat;

	public Phase phase() {
		return phase;
	}

	/** The current depth in layers; only meaningful in {@link Phase#DEPTH}. */
	public int depth() {
		return depth;
	}

	/** Whether the area being marked is flat, with no depth step. */
	public boolean flat() {
		return flat;
	}

	/** Starts a new area with a depth step at the pressed block. Only acts in {@link Phase#IDLE}. */
	public void press(int x, int y, int z) {
		press(x, y, z, false);
	}

	/**
	 * Starts a new area at the pressed block. A {@code flat} area has no depth step: it spans the two
	 * corners and {@link #release()} fixes it. Only acts in {@link Phase#IDLE}.
	 */
	public void press(int x, int y, int z, boolean flat) {
		if (phase != Phase.IDLE) {
			return;
		}
		this.flat = flat;
		ax = bx = x;
		ay = by = y;
		az = bz = z;
		phase = Phase.DRAGGING;
	}

	/** Moves the opposite corner to the block under the cursor. Only acts in {@link Phase#DRAGGING}. */
	public void drag(int x, int y, int z) {
		if (phase != Phase.DRAGGING) {
			return;
		}
		bx = x;
		by = y;
		bz = z;
	}

	/**
	 * Fixes the rectangle and enters the depth step, starting deep enough to reach the lower corner; a
	 * flat area goes to {@link Phase#FIXED} instead. Returns whether a drag was released.
	 */
	public boolean release() {
		if (phase != Phase.DRAGGING) {
			return false;
		}
		if (flat) {
			phase = Phase.FIXED;
			return true;
		}
		depth = RtsDigArea.initialDepth(ay, by);
		phase = Phase.DEPTH;
		return true;
	}

	/**
	 * Adjusts the depth in the depth step. {@code wheelSteps} uses the wheel's own sign: positive is
	 * wheel UP, negative is wheel DOWN. Wheel down makes the area deeper (dig further down) and wheel up
	 * makes it shallower, never below 1 layer; the depth is clamped with {@link RtsDigArea#clampDepth}.
	 *
	 * @return {@code true} if the scroll was consumed, which is always the case in {@link Phase#DEPTH}
	 *         even when the depth is clamped; {@code false} in any other phase, where nothing changes
	 */
	public boolean scroll(int wheelSteps) {
		if (phase != Phase.DEPTH) {
			return false;
		}
		depth = RtsDigArea.clampDepth(depth - wheelSteps);
		return true;
	}

	/** The area being marked, or {@code null} in {@link Phase#IDLE}. */
	public @Nullable RtsDigArea area() {
		if (phase == Phase.IDLE) {
			return null;
		}
		if (flat) {
			return RtsDigArea.between(ax, ay, az, bx, by, bz);
		}
		return switch (phase) {
			case DRAGGING -> RtsDigArea.of(ax, ay, az, bx, by, bz, RtsDigArea.initialDepth(ay, by));
			default -> RtsDigArea.of(ax, ay, az, bx, by, bz, depth);
		};
	}

	/**
	 * Finishes the depth step, or a released flat area, and returns the area. Returns {@code null}
	 * outside {@link Phase#DEPTH} and {@link Phase#FIXED}.
	 */
	public @Nullable RtsDigArea confirm() {
		if (phase != Phase.DEPTH && phase != Phase.FIXED) {
			return null;
		}
		RtsDigArea area = area();
		phase = Phase.IDLE;
		return area;
	}

	/** Drops the area being marked. Returns whether there was one. */
	public boolean cancel() {
		if (phase == Phase.IDLE) {
			return false;
		}
		phase = Phase.IDLE;
		return true;
	}
}
