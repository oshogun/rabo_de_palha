package io.github.oshogun.rabodepalha.client.rts;

import com.mojang.blaze3d.platform.Window;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;

import io.github.oshogun.rabodepalha.rts.RtsArmedMode;
import io.github.oshogun.rabodepalha.rts.RtsCommandBar;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;

/**
 * Client state of the RTS command bar: which command is armed, and the clicks on the bar and in the world that
 * use it. Mouse positions are window pixels, as the mouse handler reports them; the bar is laid out in GUI pixels.
 */
public final class RtsCommandClient {
	private static final RtsArmedMode<RtsCommands.RtsCommand> STATE = new RtsArmedMode<>();

	private RtsCommandClient() {
	}

	/** Uses a command from its button or hotkey: an instant command runs, an armed one arms or toggles off. */
	public static void activate(RtsCommands.RtsCommand command) {
		switch (command.action()) {
			case RtsCommands.Instant instant -> instant.run().run();
			case RtsCommands.Armed armed -> STATE.arm(command);
		}
	}

	/**
	 * Handles a left press. Returns true when the bar or the armed mode consumed it, so it must not select or
	 * start a box drag. An invalid target shows the reason and leaves the mode armed.
	 */
	public static boolean onLeftPress(double x, double y, boolean shift) {
		if (overBar(x, y)) {
			// A press in a gap between buttons is consumed too.
			int button = buttonAt(x, y);
			if (button >= 0) {
				activate(RtsCommands.COMMANDS.get(button));
			}
			return true;
		}
		RtsCommands.RtsCommand command = STATE.armed();
		if (command == null || !(command.action() instanceof RtsCommands.Armed armed)) {
			return false;
		}
		RtsSelectionClient.Hover hover = RtsSelectionClient.hoverAt(x, y);
		RtsOrderHint.Hint hint = armed.hint().apply(hover);
		if (!hint.valid()) {
			RtsHud.showFeedback(false, RtsOrderHintClient.text(hint, hover));
		}
		if (STATE.onLeftClick(hint.valid(), shift) != null) {
			armed.fire().accept(hover, shift);
		}
		return true;
	}

	/** Whether the window pixel ({@code x}, {@code y}) is on the bar, gaps between buttons included. */
	public static boolean overBar(double x, double y) {
		Window window = Minecraft.getInstance().getWindow();
		double scale = window.getGuiScale();
		return RtsCommandBar.contains(RtsCommands.COMMANDS.size(), window.getGuiScaledWidth(),
				window.getGuiScaledHeight(), RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
	}

	/** Handles a right press. Returns true if it disarmed a mode; false means the caller sends a plain move. */
	public static boolean onRightClick() {
		return STATE.onRightClick();
	}

	/** Handles Esc. Returns true if it disarmed a mode, which consumes the key. */
	public static boolean onEscape() {
		return STATE.onEscape();
	}

	/** The armed command, or null. */
	public static RtsCommands.@Nullable RtsCommand armed() {
		return STATE.armed();
	}

	/** The index of the bar button under the mouse cursor, or -1. */
	public static int hoveredButton() {
		Minecraft client = Minecraft.getInstance();
		if (client.mouseHandler == null) {
			return -1;
		}
		return buttonAt(client.mouseHandler.xpos(), client.mouseHandler.ypos());
	}

	/** What a left-click would do with the armed command, or what a right-click move would do otherwise. */
	public static RtsOrderHint.Hint currentHint(RtsSelectionClient.Hover hover) {
		RtsCommands.RtsCommand command = STATE.armed();
		if (command != null && command.action() instanceof RtsCommands.Armed armed) {
			return armed.hint().apply(hover);
		}
		return RtsOrderHintClient.current(hover, RtsOrderKind.MOVE);
	}

	/** Disarms any armed command, when RTS mode ends or a screen opens. */
	public static void clear() {
		STATE.disarm();
	}

	private static int buttonAt(double x, double y) {
		Window window = Minecraft.getInstance().getWindow();
		double scale = window.getGuiScale();
		return RtsCommandBar.hitTest(RtsCommands.COMMANDS.size(), window.getGuiScaledWidth(),
				window.getGuiScaledHeight(), RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
	}
}
