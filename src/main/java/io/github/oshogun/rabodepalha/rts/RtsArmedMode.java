package io.github.oshogun.rabodepalha.rts;

import org.jspecify.annotations.Nullable;

/**
 * The armed order mode of the RTS command bar. A button or hotkey arms a mode; the next valid
 * left-click in the world fires it. Shift keeps the mode armed for the next target, and an invalid
 * target leaves it armed. Right-click and Esc disarm it.
 *
 * <p>The caller handles Esc in this order: cancel a box drag, then {@link #onEscape()}, then clear
 * the selection.
 *
 * @param <M> the mode type
 */
public final class RtsArmedMode<M> {
	private @Nullable M armed;

	/** The armed mode, or null when nothing is armed. */
	public @Nullable M armed() {
		return armed;
	}

	/** Arms {@code mode}. Arming the mode that is already armed disarms it instead. */
	public void arm(M mode) {
		armed = mode.equals(armed) ? null : mode;
	}

	public void disarm() {
		armed = null;
	}

	/**
	 * Handles a left-click in the world.
	 *
	 * @param valid whether the armed mode can be carried out on the clicked target
	 * @param shift whether Shift is held, which queues the order and keeps the mode armed
	 * @return the mode to fire, or null when nothing is armed or the target is invalid; in the
	 *     latter case the mode stays armed
	 */
	public @Nullable M onLeftClick(boolean valid, boolean shift) {
		M mode = armed;
		if (mode == null || !valid) {
			return null;
		}
		if (!shift) {
			armed = null;
		}
		return mode;
	}

	/** Handles a right-click. Returns true if it disarmed a mode; false means the caller should send a plain MOVE. */
	public boolean onRightClick() {
		return disarmIfArmed();
	}

	/** Handles Esc. Returns true if it disarmed a mode, which then consumes the key. */
	public boolean onEscape() {
		return disarmIfArmed();
	}

	private boolean disarmIfArmed() {
		if (armed == null) {
			return false;
		}
		armed = null;
		return true;
	}
}
