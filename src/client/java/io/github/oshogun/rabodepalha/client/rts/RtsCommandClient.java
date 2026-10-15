package io.github.oshogun.rabodepalha.client.rts;

import java.util.List;

import com.mojang.blaze3d.platform.Window;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

import io.github.oshogun.rabodepalha.rts.RtsAreaDrag;
import io.github.oshogun.rabodepalha.rts.RtsArmedMode;
import io.github.oshogun.rabodepalha.rts.RtsCommandBar;
import io.github.oshogun.rabodepalha.rts.RtsCommandPage;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;

/**
 * Client state of the RTS command bar: which page is shown, which command is armed, and the clicks on the bar and
 * in the world that use it. Mouse positions are window pixels, as the mouse handler reports them; the bar is laid out in GUI pixels.
 */
public final class RtsCommandClient {
	private static final RtsArmedMode<RtsCommands.RtsCommand> STATE = new RtsArmedMode<>();
	/** The area being marked by an armed {@link RtsCommands.Area} command. */
	private static final RtsAreaDrag DRAG = new RtsAreaDrag();
	/** The page whose buttons the bar shows and the slot keys press. */
	private static RtsCommandPage page = RtsCommands.PAGES.first();

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

	/** The page the bar shows. */
	public static RtsCommandPage page() {
		page = RtsCommands.PAGES.valid(page);
		return page;
	}

	/** Shows {@code next}; changing page cancels an armed command and an area being marked, like Esc. */
	public static void selectPage(RtsCommandPage next) {
		RtsCommandPage target = RtsCommands.PAGES.valid(next);
		if (target == page()) {
			return;
		}
		DRAG.cancel();
		STATE.disarm();
		dropBlueprintUnlessBuild();
		page = target;
	}

	/** Shows the next page, wrapping round; used by the page key. */
	public static void cyclePage() {
		selectPage(RtsCommands.PAGES.next(page()));
	}

	/**
	 * Handles slot key {@code slot} (1 to 9). With Place armed it picks the hotbar slot whose block the order
	 * places, as the digit keys did before the bar had pages; otherwise it uses the command in that slot on the
	 * current page, if there is one.
	 */
	public static void pressSlot(int slot) {
		RtsCommands.RtsCommand armed = STATE.armed();
		if (armed != null && armed.id().equals(RtsCommands.PLACE)) {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				player.getInventory().setSelectedSlot(slot - 1);
			}
			return;
		}
		RtsCommands.PAGES.slot(page(), slot).ifPresent(RtsCommandClient::activate);
	}

	/** Arms or toggles off {@code command}, dropping any area being marked. */
	private static void arm(RtsCommands.RtsCommand command) {
		DRAG.cancel();
		STATE.arm(command);
		dropBlueprintUnlessBuild();
		if (STATE.armed() == command && command.action() instanceof RtsCommands.Armed armed) {
			armed.onArm().run();
		}
	}

	/** Whether the build command is armed. */
	public static boolean buildArmed() {
		RtsCommands.RtsCommand command = STATE.armed();
		return command != null && command.id().equals(RtsCommands.BUILD);
	}

	/** Drops the picked blueprint and closes the picker once the build command is no longer armed. */
	private static void dropBlueprintUnlessBuild() {
		if (!buildArmed()) {
			RtsBlueprintsClient.clear();
		}
	}

	/**
	 * Handles a left press. Returns true when the bar or the armed mode consumed it, so it must not select or
	 * start a box drag. An invalid target shows the reason and leaves the mode armed.
	 */
	public static boolean onLeftPress(double x, double y, boolean shift) {
		if (overBar(x, y)) {
			// A press in a gap between buttons or tabs is consumed too.
			RtsCommandPage tab = tabAt(x, y);
			if (tab != null) {
				selectPage(tab);
				return true;
			}
			RtsCommands.RtsCommand button = buttonAt(x, y);
			if (button != null) {
				activate(button);
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
		// A mode that stays armed fires as if Shift were held, which keeps it armed; it still gets the real Shift.
		if (STATE.onLeftClick(hint.valid(), shift || armed.staysArmed()) != null) {
			armed.fire().accept(hover, shift);
		}
		dropBlueprintUnlessBuild();
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

	/** Whether the window pixel ({@code x}, {@code y}) is on the bar or its page tabs, gaps included. */
	public static boolean overBar(double x, double y) {
		Minecraft client = Minecraft.getInstance();
		Window window = client.getWindow();
		double scale = window.getGuiScale();
		return RtsCommandBar.pagedContains(RtsCommands.PAGES.commands(page()).size(), RtsCommands.PAGES.globals().size(),
				tabWidths(client.font), window.getGuiScaledWidth(), window.getGuiScaledHeight(),
				RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
	}

	/**
	 * The width of each shown page's tab, in tab order. Drawing and hit-testing both use it, so they agree on
	 * where the tabs are.
	 */
	public static int[] tabWidths(Font font) {
		return RtsCommands.PAGES.visiblePages().stream()
				.mapToInt(p -> font.width(RtsCommands.pageName(p)) + 2 * RtsCommandBar.TAB_PAD)
				.toArray();
	}

	/**
	 * Handles a right press. It first drops an area being marked, keeping the mode armed; otherwise it disarms.
	 * Returns true if it did either; false means the caller sends a plain move.
	 */
	public static boolean onRightClick() {
		boolean handled = DRAG.cancel() || STATE.onRightClick();
		dropBlueprintUnlessBuild();
		return handled;
	}

	/**
	 * Handles Esc. It first drops an area being marked, keeping the mode armed; otherwise it disarms. Returns true
	 * if it did either, which consumes the key.
	 */
	public static boolean onEscape() {
		boolean handled = DRAG.cancel() || STATE.onEscape();
		dropBlueprintUnlessBuild();
		return handled;
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

	/** The command whose bar button is under the mouse cursor, or null. */
	public static RtsCommands.@Nullable RtsCommand hoveredButton() {
		Minecraft client = Minecraft.getInstance();
		if (client.mouseHandler == null) {
			return null;
		}
		return buttonAt(client.mouseHandler.xpos(), client.mouseHandler.ypos());
	}

	/** The page whose tab is under the mouse cursor, or null. */
	public static @Nullable RtsCommandPage hoveredTab() {
		Minecraft client = Minecraft.getInstance();
		if (client.mouseHandler == null) {
			return null;
		}
		return tabAt(client.mouseHandler.xpos(), client.mouseHandler.ypos());
	}

	/** What a left-click would do with the armed command, or what a plain right-click would do otherwise. */
	public static RtsOrderHint.Hint currentHint(RtsSelectionClient.Hover hover) {
		RtsCommands.RtsCommand command = STATE.armed();
		if (command != null && command.action() instanceof RtsCommands.Armed armed) {
			return armed.hint().apply(hover);
		}
		if (command != null && command.action() instanceof RtsCommands.Area area) {
			return area.hint().apply(hover);
		}
		return RtsOrderHintClient.rightClick(hover);
	}

	/** Disarms any armed command, when RTS mode ends or a screen opens. */
	public static void clear() {
		DRAG.cancel();
		STATE.disarm();
		RtsBlueprintsClient.clear();
	}

	private static RtsCommands.@Nullable RtsCommand buttonAt(double x, double y) {
		Window window = Minecraft.getInstance().getWindow();
		double scale = window.getGuiScale();
		List<RtsCommands.RtsCommand> onPage = RtsCommands.PAGES.commands(page());
		List<RtsCommands.RtsCommand> globals = RtsCommands.PAGES.globals();
		RtsCommandBar.Hit hit = RtsCommandBar.pagedHitTest(onPage.size(), globals.size(), window.getGuiScaledWidth(),
				window.getGuiScaledHeight(), RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
		return switch (hit.kind()) {
			case PAGE -> onPage.get(hit.index());
			case GLOBAL -> globals.get(hit.index());
			case NONE -> null;
		};
	}

	private static @Nullable RtsCommandPage tabAt(double x, double y) {
		Minecraft client = Minecraft.getInstance();
		Window window = client.getWindow();
		double scale = window.getGuiScale();
		int tab = RtsCommandBar.tabHitTest(tabWidths(client.font), window.getGuiScaledWidth(),
				window.getGuiScaledHeight(), RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
		return tab < 0 ? null : RtsCommands.PAGES.visiblePages().get(tab);
	}
}
