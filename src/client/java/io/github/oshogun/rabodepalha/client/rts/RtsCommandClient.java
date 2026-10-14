package io.github.oshogun.rabodepalha.client.rts;

import com.mojang.blaze3d.platform.Window;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

import io.github.oshogun.rabodepalha.rts.RtsAreaDrag;
import io.github.oshogun.rabodepalha.rts.RtsArmedMode;
import io.github.oshogun.rabodepalha.rts.RtsCommandBar;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;

/**
 * Client state of the RTS command bar: which command is armed, and the clicks on the bar and in the world that
 * use it. Mouse positions are window pixels, as the mouse handler reports them; the bar is laid out in GUI pixels.
 */
public final class RtsCommandClient {
	private static final RtsArmedMode<RtsCommands.RtsCommand> STATE = new RtsArmedMode<>();
	/** The area being marked by an armed {@link RtsCommands.Area} command. */
	private static final RtsAreaDrag DRAG = new RtsAreaDrag();

	private RtsCommandClient() {
	}

	/** Uses a command from its button or hotkey: an instant command runs, an armed one arms or toggles off. */
	public static void activate(RtsCommands.RtsCommand command) {
		switch (command.action()) {
			case RtsCommands.Instant instant -> instant.run().run();
			case RtsCommands.Armed armed -> arm(command);
			case RtsCommands.Area area -> arm(command);
		}
	}

	/** Arms or toggles off {@code command}, dropping any area being marked. */
	private static void arm(RtsCommands.RtsCommand command) {
		DRAG.cancel();
		STATE.arm(command);
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
		if (command != null && command.action() instanceof RtsCommands.Area area) {
			onAreaPress(area, x, y, shift);
			return true;
		}
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

	/**
	 * A left press with an area command armed: in the idle phase it starts the area on the block under the
	 * cursor; in the depth step it confirms the area, unless the area is invalid, which shows the reason and
	 * stays in the depth step.
	 */
	private static void onAreaPress(RtsCommands.Area area, double x, double y, boolean shift) {
		switch (DRAG.phase()) {
			case IDLE -> {
				RtsSelectionClient.Hover hover = RtsSelectionClient.hoverAt(x, y);
				RtsOrderHint.Hint hint = area.hint().apply(hover);
				BlockHitResult block = hover.block();
				if (!hint.valid() || block == null) {
					RtsHud.showFeedback(false, RtsOrderHintClient.text(hint, hover));
					return;
				}
				BlockPos pos = block.getBlockPos();
				DRAG.press(pos.getX(), pos.getY(), pos.getZ());
			}
			case DRAGGING -> {
				// The release ends the drag; a second press meanwhile changes nothing.
			}
			case DEPTH -> {
				RtsDigArea marked = DRAG.area();
				if (marked == null) {
					return;
				}
				Component problem = RtsOrdersClient.digProblem(marked);
				if (problem != null) {
					RtsHud.showFeedback(false, problem);
					return;
				}
				RtsDigArea confirmed = DRAG.confirm();
				if (confirmed != null) {
					area.fire().accept(confirmed, shift);
					STATE.onLeftClick(true, shift);
				}
			}
		}
	}

	/** Handles a left release. Returns true if it ended an area drag, which then enters the depth step. */
	public static boolean onLeftRelease() {
		return DRAG.release();
	}

	/**
	 * Moves the area's opposite corner to the block under the window pixel ({@code x}, {@code y}) while
	 * dragging. Without a block there, the last corner stays.
	 */
	public static void onDragMove(double x, double y) {
		if (DRAG.phase() != RtsAreaDrag.Phase.DRAGGING) {
			return;
		}
		BlockHitResult block = RtsSelectionClient.hoverAt(x, y).block();
		if (block != null) {
			BlockPos pos = block.getBlockPos();
			DRAG.drag(pos.getX(), pos.getY(), pos.getZ());
		}
	}

	/**
	 * Handles the scroll wheel. In the depth step it sets the depth, unless Shift is held, which leaves the
	 * wheel to the zoom. {@code yoffset} keeps the wheel's own sign (positive is wheel up);
	 * {@link RtsAreaDrag#scroll} maps wheel down to a deeper area. Returns true if it consumed the scroll.
	 */
	public static boolean onScroll(double yoffset, boolean shift) {
		if (shift) {
			return false;
		}
		return DRAG.scroll((int) Math.signum(yoffset));
	}

	/** Whether the window pixel ({@code x}, {@code y}) is on the bar, gaps between buttons included. */
	public static boolean overBar(double x, double y) {
		Window window = Minecraft.getInstance().getWindow();
		double scale = window.getGuiScale();
		return RtsCommandBar.contains(RtsCommands.COMMANDS.size(), window.getGuiScaledWidth(),
				window.getGuiScaledHeight(), RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
	}

	/**
	 * Handles a right press. It first drops an area being marked, keeping the mode armed; otherwise it disarms.
	 * Returns true if it did either; false means the caller sends a plain move.
	 */
	public static boolean onRightClick() {
		return DRAG.cancel() || STATE.onRightClick();
	}

	/**
	 * Handles Esc. It first drops an area being marked, keeping the mode armed; otherwise it disarms. Returns true
	 * if it did either, which consumes the key.
	 */
	public static boolean onEscape() {
		return DRAG.cancel() || STATE.onEscape();
	}

	/** The armed command, or null. */
	public static RtsCommands.@Nullable RtsCommand armed() {
		return STATE.armed();
	}

	/** Whether an area is being dragged, while the mouse is held. */
	public static boolean isAreaDragging() {
		return DRAG.phase() == RtsAreaDrag.Phase.DRAGGING;
	}

	/** The area being marked, while dragging or in the depth step, or null. */
	public static @Nullable RtsDigArea digArea() {
		return DRAG.area();
	}

	/** The phase of marking an area. */
	public static RtsAreaDrag.Phase digPhase() {
		return DRAG.phase();
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
		if (command != null && command.action() instanceof RtsCommands.Area area) {
			return area.hint().apply(hover);
		}
		return RtsOrderHintClient.current(hover, RtsOrderKind.MOVE);
	}

	/** Disarms any armed command, when RTS mode ends or a screen opens. */
	public static void clear() {
		DRAG.cancel();
		STATE.disarm();
	}

	private static int buttonAt(double x, double y) {
		Window window = Minecraft.getInstance().getWindow();
		double scale = window.getGuiScale();
		return RtsCommandBar.hitTest(RtsCommands.COMMANDS.size(), window.getGuiScaledWidth(),
				window.getGuiScaledHeight(), RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
	}
}
