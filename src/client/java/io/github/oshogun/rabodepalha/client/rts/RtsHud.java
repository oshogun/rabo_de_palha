package io.github.oshogun.rabodepalha.client.rts;

import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.platform.Window;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.rts.RtsAreaDrag;
import io.github.oshogun.rabodepalha.rts.RtsCommandBar;
import io.github.oshogun.rabodepalha.rts.RtsContainerPanel;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsItemPicker;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsPanel;
import io.github.oshogun.rabodepalha.rts.RtsPickMath;
import io.github.oshogun.rabodepalha.rts.RtsScreenRect;
import io.github.oshogun.rabodepalha.rts.WorkerSnapshot;
import io.github.oshogun.rabodepalha.worker.task.TaskSummary;

/**
 * RTS mode HUD: the number of selected workers, the outcome of the last order, the block a place order
 * would use and what a deposit would put away, what the armed command or a right-click would do at the cursor,
 * the selection box while dragging, the side panel with the watched workers, the withdraw view, and the command
 * bar with its tooltips.
 */
public final class RtsHud {
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int BOX_FILL_COLOR = 0x3380FF80;
	private static final int BOX_BORDER_COLOR = 0xFF80FF80;
	private static final int MARGIN = 4;
	private static final int BUTTON_COLOR = 0xB0000000;
	private static final int BUTTON_HOVER_COLOR = 0xB0505050;
	private static final int BUTTON_ARMED_COLOR = 0xFFFFD84A;
	private static final int PANEL_COLOR = 0x90000000;
	private static final int PANEL_BUTTON_COLOR = 0xB0303030;
	private static final int SLOT_BORDER_COLOR = 0x60FFFFFF;
	private static final int TASK_COLOR = 0xFFFFFFA0;
	private static final int LABEL_COLOR = 0xFFA0A0A0;
	private static final int PICKED_COLOR = 0x80FFD84A;

	/** How wide lines at the left margin may be this frame; narrower while the panel is on screen. */
	private static int leftMaxWidth = Integer.MAX_VALUE;

	private RtsHud() {
	}

	private static final int OK_COLOR = 0xFF55FF55;
	private static final int ERROR_COLOR = 0xFFFF5555;
	private static final int HINT_INVALID_COLOR = 0xFF808080;
	/** How long an order feedback line stays on screen (about 60 ticks). */
	private static final long FEEDBACK_MILLIS = 3000L;

	private static boolean feedbackOk;
	private static Component feedback;
	private static long feedbackExpiresAt;

	/** Shows the outcome of an RTS order for a few seconds. */
	public static void showFeedback(boolean ok, Component message) {
		feedbackOk = ok;
		feedback = message;
		feedbackExpiresAt = System.currentTimeMillis() + FEEDBACK_MILLIS;
	}

	public static void init() {
		// Not attached to HOTBAR: RTS mode puts the player in spectator, which never draws the item hotbar,
		// so the HOTBAR root (and anything attached to it) never runs. CHAT is drawn in every game mode, and
		// attaching before it keeps chat, the player list and subtitles on top of the RTS HUD.
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, RaboDePalha.id("rts_selection"),
				RtsHud::extract);
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!RtsClient.isActive()) {
			// Leaving RTS mode drops any pending order feedback.
			feedback = null;
			return;
		}
		Minecraft client = Minecraft.getInstance();
		RtsPanel.Layout panel = RtsPanelClient.layout();
		// With the panel on screen, lines at the left margin stop short of it.
		leftMaxWidth = panel == null ? Integer.MAX_VALUE
				: RtsPanel.leftTextMaxWidth(client.getWindow().getGuiScaledWidth());
		int lineHeight = client.font.lineHeight + 2;
		int y = MARGIN;
		int count = RtsSelectionClient.count();
		if (count > 0) {
			leftText(graphics, client, Component.translatable("rabodepalha.rts.selected", count), y, TEXT_COLOR);
			y += lineHeight;
		}
		if (feedback != null) {
			if (System.currentTimeMillis() < feedbackExpiresAt) {
				leftText(graphics, client, feedback, y, feedbackOk ? OK_COLOR : ERROR_COLOR);
				y += lineHeight;
			} else {
				feedback = null;
			}
		}
		Optional<Component> pickedName = RtsOrderHintClient.pickedName();
		if (RtsOrderHintClient.pickedBlock() && pickedName.isPresent()) {
			leftText(graphics, client, Component.translatable("rabodepalha.rts.place_block", pickedName.get()), y,
					TEXT_COLOR);
			y += lineHeight;
		}
		RtsCommands.RtsCommand armedCommand = RtsCommandClient.armed();
		if (armedCommand != null && armedCommand.id().equals("deposit")) {
			Component what = pickedName
					.<Component>map(name -> Component.translatable("rabodepalha.rts.deposit_item", name))
					.orElseGet(() -> Component.translatable("rabodepalha.rts.deposit_all"));
			leftText(graphics, client, what, y, TEXT_COLOR);
			y += lineHeight;
		}
		int hovered = RtsCommandClient.hoveredButton();
		RtsPanel.Hit panelHit = RtsPanelClient.hovered();
		RtsContainerPanel.Layout view = RtsContainerViewClient.layout();
		RtsContainerPanel.Hit viewHit = hoveredView(client, view);
		RtsSelectionClient.Hover hover = RtsSelectionClient.hover();
		RtsDigArea digArea = RtsCommandClient.digArea();
		if (digArea != null) {
			extractDigArea(graphics, client, digArea, y);
		} else if (hover != null && hovered < 0 && panelHit instanceof RtsPanel.Hit.None
				&& viewHit instanceof RtsContainerPanel.Hit.None) {
			// What a left-click with the armed command, or else a right-click, would do; an invalid hint's key
			// already states the reason.
			RtsOrderHint.Hint hint = RtsCommandClient.currentHint(hover);
			Component text = RtsOrderHintClient.text(hint, hover);
			RtsCommands.RtsCommand armed = RtsCommandClient.armed();
			if (armed != null) {
				text = Component.empty().append(armed.displayName()).append(": ").append(text);
			}
			leftText(graphics, client, text, y, hint.valid() ? OK_COLOR : HINT_INVALID_COLOR);
		}

		RtsScreenRect rect = RtsSelectionClient.currentDragRect();
		if (rect != null) {
			// The box is in window pixels; the HUD draws in GUI-scaled coordinates.
			Window window = client.getWindow();
			double scale = window.getGuiScale();
			int x0 = (int) Math.floor(rect.minX() / scale);
			int y0 = (int) Math.floor(rect.minY() / scale);
			int x1 = (int) Math.ceil(rect.maxX() / scale);
			int y1 = (int) Math.ceil(rect.maxY() / scale);
			graphics.fill(x0, y0, x1, y1, BOX_FILL_COLOR);
			graphics.outline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), BOX_BORDER_COLOR);
		}

		if (panel != null) {
			extractPanel(graphics, client, panel, panelHit);
		}
		if (view != null) {
			extractContainerView(graphics, client, view, viewHit);
		}
		extractCommandBar(graphics, client, hovered);
	}

	/** Draws {@code text} at the left margin, cut to {@link #leftMaxWidth}. */
	private static void leftText(GuiGraphicsExtractor graphics, Minecraft client, Component text, int y, int color) {
		graphics.text(client.font, clip(client.font, text, leftMaxWidth), MARGIN, y, color);
	}

	/** {@code text} cut to at most {@code width} GUI pixels. */
	private static FormattedCharSequence clip(Font font, Component text, int width) {
		if (font.width(text) <= width) {
			return text.getVisualOrderText();
		}
		return Language.getInstance().getVisualOrder(font.substrByWidth(text, Math.max(0, width)));
	}

	/**
	 * Draws the side panel: with one worker watched, its name and chunk status, a Clear button, the current task,
	 * the queued tasks with a Remove button each and the inventory; with several, one row per worker with its
	 * current task. Workers whose snapshot hasn't arrived yet show a loading line.
	 */
	private static void extractPanel(GuiGraphicsExtractor graphics, Minecraft client, RtsPanel.Layout layout,
			RtsPanel.Hit hit) {
		Font font = client.font;
		RtsCommandBar.Rect bounds = layout.bounds();
		graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.w(), bounds.y() + bounds.h(), PANEL_COLOR);
		List<Integer> watched = RtsSnapshotsClient.watched();
		if (layout.mode() == RtsPanel.Mode.MULTI) {
			for (int i = 0; i < layout.rows().size() && i < watched.size(); i++) {
				RtsCommandBar.Rect row = layout.rows().get(i);
				if (hit instanceof RtsPanel.Hit.Row r && r.index() == i) {
					graphics.fill(row.x(), row.y(), row.x() + row.w(), row.y() + row.h(), BUTTON_HOVER_COLOR);
				}
				WorkerSnapshot snapshot = RtsSnapshotsClient.get(watched.get(i));
				Component text = snapshot == null ? Component.translatable("rabodepalha.rts.panel.loading")
						: Component.translatable("rabodepalha.rts.panel.row", snapshot.name(), currentTask(snapshot));
				rowText(graphics, font, text, row, row.w(), TEXT_COLOR);
			}
			moreRow(graphics, font, layout, layout.hidden());
			List<RtsItemPicker.Entry> combined = RtsItemPickClient.combined();
			for (int i = 0; i < layout.items().size() && i < combined.size(); i++) {
				RtsItemPicker.Entry entry = combined.get(i);
				entryCell(graphics, font, layout.items().get(i), entry,
						isPicked(entry.item()) || hit instanceof RtsPanel.Hit.Item h && h.index() == i);
			}
			return;
		}

		WorkerSnapshot snapshot = watched.isEmpty() ? null : RtsSnapshotsClient.get(watched.get(0));
		RtsCommandBar.Rect header = layout.header();
		RtsCommandBar.Rect clear = layout.clear();
		if (snapshot == null) {
			if (header.w() > 0) {
				rowText(graphics, font, Component.translatable("rabodepalha.rts.panel.loading"), header, header.w(),
						TEXT_COLOR);
			}
			return;
		}
		if (header.w() > 0) {
			Component title = Component.empty().append(snapshot.name()).append(" ")
					.append(chunkStatus(snapshot.chunks()));
			rowText(graphics, font, title, header, header.w() - clear.w() - 2, TEXT_COLOR);
			button(graphics, font, clear, Component.translatable("rabodepalha.rts.panel.clear"),
					hit instanceof RtsPanel.Hit.Clear);
		}
		RtsCommandBar.Rect current = layout.current();
		if (current.w() > 0) {
			rowText(graphics, font, currentTask(snapshot), current, current.w(), TASK_COLOR);
		}
		RtsCommandBar.Rect queuedLabel = layout.queuedLabel();
		if (queuedLabel.w() > 0) {
			rowText(graphics, font, Component.translatable("rabodepalha.rts.panel.queued", snapshot.queuedTotal()),
					queuedLabel, queuedLabel.w(), LABEL_COLOR);
		}
		for (int i = 0; i < layout.queued().size() && i < snapshot.queued().size(); i++) {
			RtsCommandBar.Rect row = layout.queued().get(i);
			RtsCommandBar.Rect remove = layout.remove().get(i);
			rowText(graphics, font, snapshot.queued().get(i).summary().toComponent(), row, row.w() - remove.w() - 2,
					TEXT_COLOR);
			button(graphics, font, remove, Component.translatable("rabodepalha.rts.panel.remove"),
					hit instanceof RtsPanel.Hit.Remove r && r.index() == i);
		}
		// Queued tasks the snapshot itself left out count as hidden too.
		moreRow(graphics, font, layout, layout.hidden() + snapshot.queuedTotal() - snapshot.queued().size());
		for (int i = 0; i < layout.slots().size() && i < snapshot.inventory().size(); i++) {
			RtsCommandBar.Rect cell = layout.slots().get(i);
			ItemStack stack = snapshot.inventory().get(i);
			if (!stack.isEmpty() && (isPicked(BuiltInRegistries.ITEM.getKey(stack.getItem()))
					|| hit instanceof RtsPanel.Hit.Item h && h.index() == i)) {
				graphics.fill(cell.x(), cell.y(), cell.x() + cell.w(), cell.y() + cell.h(), PICKED_COLOR);
			}
			graphics.outline(cell.x(), cell.y(), cell.w(), cell.h(), SLOT_BORDER_COLOR);
			if (!stack.isEmpty()) {
				graphics.item(stack, cell.x() + 1, cell.y() + 1);
				graphics.itemDecorations(font, stack, cell.x() + 1, cell.y() + 1);
			}
		}
	}

	private static boolean isPicked(Identifier item) {
		return RtsItemPickClient.picked().filter(item::equals).isPresent();
	}

	/**
	 * Draws an entry's cell: a 1-count icon and the total as text, since totals may exceed a stack. With
	 * {@code highlight} the cell is filled.
	 */
	private static void entryCell(GuiGraphicsExtractor graphics, Font font, RtsCommandBar.Rect cell,
			RtsItemPicker.Entry entry, boolean highlight) {
		if (highlight) {
			graphics.fill(cell.x(), cell.y(), cell.x() + cell.w(), cell.y() + cell.h(), PICKED_COLOR);
		}
		graphics.outline(cell.x(), cell.y(), cell.w(), cell.h(), SLOT_BORDER_COLOR);
		BuiltInRegistries.ITEM.getOptional(entry.item())
				.ifPresent(item -> graphics.item(new ItemStack(item, 1), cell.x() + 1, cell.y() + 1));
		if (entry.count() != 1) {
			// Where itemDecorations puts a stack count: right-aligned at the bottom of the 16x16 icon.
			String amount = Integer.toString(entry.count());
			graphics.text(font, amount, cell.x() + 18 - font.width(amount), cell.y() + 10, TEXT_COLOR, true);
		}
	}

	/** What the mouse cursor is on in the withdraw view. */
	private static RtsContainerPanel.Hit hoveredView(Minecraft client, RtsContainerPanel.@Nullable Layout view) {
		if (view == null || client.mouseHandler == null) {
			return RtsContainerPanel.Hit.NONE;
		}
		return RtsContainerViewClient.hitAt(client.mouseHandler.xpos(), client.mouseHandler.ypos());
	}

	/**
	 * Draws the withdraw view: the title with a Close button, the container's entries with the picked one
	 * highlighted, then Minus, the count ("N each" with several workers selected), Plus and Confirm.
	 */
	private static void extractContainerView(GuiGraphicsExtractor graphics, Minecraft client,
			RtsContainerPanel.Layout layout, RtsContainerPanel.Hit hit) {
		Font font = client.font;
		RtsCommandBar.Rect bounds = layout.bounds();
		graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.w(), bounds.y() + bounds.h(), PANEL_COLOR);
		RtsCommandBar.Rect header = layout.header();
		RtsCommandBar.Rect close = layout.close();
		if (header.w() > 0) {
			Component title = layout.hidden() > 0
					? Component.empty().append(Component.translatable("rabodepalha.rts.withdraw.title")).append(" ")
							.append(Component.translatable("rabodepalha.rts.panel.more", layout.hidden()))
					: Component.translatable("rabodepalha.rts.withdraw.title");
			rowText(graphics, font, title, header, header.w() - close.w() - 2, TEXT_COLOR);
			button(graphics, font, close, Component.translatable("rabodepalha.rts.withdraw.close"),
					hit instanceof RtsContainerPanel.Hit.Close);
		}
		List<RtsItemPicker.Entry> entries = RtsContainerViewClient.entries();
		int selected = RtsContainerViewClient.selected();
		for (int i = 0; i < layout.items().size() && i < entries.size(); i++) {
			entryCell(graphics, font, layout.items().get(i), entries.get(i),
					i == selected || hit instanceof RtsContainerPanel.Hit.Item h && h.index() == i);
		}
		button(graphics, font, layout.minus(), Component.literal("-"), hit instanceof RtsContainerPanel.Hit.Minus);
		button(graphics, font, layout.plus(), Component.literal("+"), hit instanceof RtsContainerPanel.Hit.Plus);
		RtsCommandBar.Rect count = layout.count();
		if (selected >= 0 && count.w() > 0) {
			Component text = RtsContainerViewClient.countText(RtsSelectionClient.count());
			graphics.centeredText(font, clip(font, text, count.w()), count.x() + count.w() / 2,
					count.y() + (count.h() - font.lineHeight) / 2 + 1, TEXT_COLOR);
		}
		button(graphics, font, layout.confirm(), Component.translatable("rabodepalha.rts.withdraw.confirm"),
				hit instanceof RtsContainerPanel.Hit.Confirm);
	}

	private static Component currentTask(WorkerSnapshot snapshot) {
		return snapshot.current().map(TaskSummary::toComponent)
				.orElseGet(() -> Component.translatable("rabodepalha.rts.panel.idle"));
	}

	private static Component chunkStatus(WorkerSnapshot.ChunkStatus status) {
		return switch (status) {
			case LOADED -> Component.translatable("rabodepalha.rts.panel.chunks_loaded").withColor(OK_COLOR);
			case CAP_REACHED -> Component.translatable("rabodepalha.rts.panel.chunks_cap").withColor(ERROR_COLOR);
			case NOT_LOADED -> Component.translatable("rabodepalha.rts.panel.chunks_not_loaded")
					.withColor(HINT_INVALID_COLOR);
		};
	}

	/** Draws {@code text} in {@code row}, vertically centred and cut to {@code width}. */
	private static void rowText(GuiGraphicsExtractor graphics, Font font, Component text, RtsCommandBar.Rect row,
			int width, int color) {
		graphics.text(font, clip(font, text, width), row.x() + 1, row.y() + (row.h() - font.lineHeight) / 2 + 1,
				color);
	}

	private static void button(GuiGraphicsExtractor graphics, Font font, RtsCommandBar.Rect rect, Component label,
			boolean hovered) {
		if (rect.w() <= 0) {
			return;
		}
		graphics.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(),
				hovered ? BUTTON_HOVER_COLOR : PANEL_BUTTON_COLOR);
		graphics.centeredText(font, clip(font, label, rect.w()), rect.x() + rect.w() / 2,
				rect.y() + (rect.h() - font.lineHeight) / 2 + 1, TEXT_COLOR);
	}

	private static void moreRow(GuiGraphicsExtractor graphics, Font font, RtsPanel.Layout layout, int hidden) {
		RtsCommandBar.Rect more = layout.more();
		if (more.w() > 0 && hidden > 0) {
			rowText(graphics, font, Component.translatable("rabodepalha.rts.panel.more", hidden), more, more.w(),
					LABEL_COLOR);
		}
	}

	/**
	 * While a dig area is being marked: the hint for the current phase on the hint line, and the area's size
	 * and volume above the top centre of its box, or on the hint line when that point is off-camera. An
	 * invalid area adds the reason, in the error colour.
	 */
	private static void extractDigArea(GuiGraphicsExtractor graphics, Minecraft client, RtsDigArea area, int y) {
		String hintKey = RtsCommandClient.digPhase() == RtsAreaDrag.Phase.DEPTH ? "rabodepalha.rts.dig.hint.depth"
				: "rabodepalha.rts.dig.hint.drag";
		Component hint = Component.translatable(hintKey);
		RtsCommands.RtsCommand armed = RtsCommandClient.armed();
		if (armed != null) {
			hint = Component.empty().append(armed.displayName()).append(": ").append(hint);
		}
		Component problem = RtsOrdersClient.digProblem(area);
		Component label = Component.literal(area.label());
		if (problem != null) {
			label = Component.empty().append(label).append(" - ").append(problem);
		}
		int labelColor = problem == null ? OK_COLOR : ERROR_COLOR;

		Camera camera = client.gameRenderer.mainCamera();
		Window window = client.getWindow();
		Vec3 eye = camera.position();
		RtsPickMath.Projected p = RtsPickMath.project(area.centerX() - eye.x, area.maxY() + 1 - eye.y,
				area.centerZ() - eye.z, window.getScreenWidth(), window.getScreenHeight(), camera.getFov(),
				camera.yRot(), camera.xRot());
		leftText(graphics, client, hint, y, TEXT_COLOR);
		if (p == null) {
			leftText(graphics, client, label, y + client.font.lineHeight + 2, labelColor);
			return;
		}
		// The projection is in window pixels; the HUD draws in GUI-scaled coordinates. Centred just above the point.
		double scale = window.getGuiScale();
		int x = (int) Math.round(p.x() / scale) - client.font.width(label) / 2;
		int labelY = (int) Math.round(p.y() / scale) - client.font.lineHeight - 2;
		graphics.text(client.font, label, x, labelY, labelColor);
	}

	/** Draws the command bar at the bottom centre, and the hovered button's tooltip on top of it. */
	private static void extractCommandBar(GuiGraphicsExtractor graphics, Minecraft client, int hovered) {
		List<RtsCommands.RtsCommand> commands = RtsCommands.COMMANDS;
		Window window = client.getWindow();
		List<RtsCommandBar.Rect> buttons = RtsCommandBar.layout(commands.size(), window.getGuiScaledWidth(),
				window.getGuiScaledHeight());
		RtsCommands.RtsCommand armed = RtsCommandClient.armed();
		for (int i = 0; i < buttons.size(); i++) {
			RtsCommandBar.Rect button = buttons.get(i);
			RtsCommands.RtsCommand command = commands.get(i);
			graphics.fill(button.x(), button.y(), button.x() + button.w(), button.y() + button.h(),
					i == hovered ? BUTTON_HOVER_COLOR : BUTTON_COLOR);
			if (command == armed) {
				graphics.outline(button.x(), button.y(), button.w(), button.h(), BUTTON_ARMED_COLOR);
			}
			// Item icons are 16x16.
			graphics.item(command.icon().get(), button.x() + (button.w() - 16) / 2, button.y() + (button.h() - 16) / 2);
		}
		if (hovered >= 0 && hovered < RtsClient.commandKeys.size()) {
			// Tooltips set "for next frame" are only drawn by screens, so the HUD draws its own, above the icons.
			graphics.nextStratum();
			Component tooltip = Component.empty().append(commands.get(hovered).displayName()).append(" (")
					.append(RtsClient.commandKeys.get(hovered).getTranslatedKeyMessage()).append(")");
			double scale = window.getGuiScale();
			int mouseX = (int) RtsCommandBar.toGui(client.mouseHandler.xpos(), scale);
			int mouseY = (int) RtsCommandBar.toGui(client.mouseHandler.ypos(), scale);
			graphics.tooltip(client.font, List.of(ClientTooltipComponent.create(tooltip.getVisualOrderText())), mouseX,
					mouseY, DefaultTooltipPositioner.INSTANCE, null, false);
		}
	}
}
