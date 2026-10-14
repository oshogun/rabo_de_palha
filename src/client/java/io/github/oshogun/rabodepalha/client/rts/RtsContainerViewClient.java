package io.github.oshogun.rabodepalha.client.rts;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import io.github.oshogun.rabodepalha.rts.RtsCommandBar;
import io.github.oshogun.rabodepalha.rts.RtsContainerPanel;
import io.github.oshogun.rabodepalha.rts.RtsItemPicker;

/**
 * Client state of the RTS withdraw view: the read-only contents of a clicked container, the entry picked in it and
 * the per-worker count. A contents request records its position; only the reply for that position opens the view,
 * so stale replies are ignored. Confirming queues the withdraw and disarms; the view closes on Close, Esc, leaving
 * RTS mode, or when the selection empties.
 */
public final class RtsContainerViewClient {
	/** How far Shift moves the count per Minus or Plus press. */
	public static final int SHIFT_STEP = 16;

	private static @Nullable BlockPos pendingPos;
	private static @Nullable BlockPos pos;
	private static List<RtsItemPicker.Entry> entries = List.of();
	private static int selected = -1;
	private static int count;

	private RtsContainerViewClient() {
	}

	/** Records a contents request for {@code requested}; only its reply will open the view. */
	public static void request(BlockPos requested) {
		pendingPos = requested.immutable();
	}

	/** Opens the view on a contents reply, unless it answers an older or cancelled request. */
	public static void open(BlockPos replyPos, List<RtsItemPicker.Entry> replyEntries) {
		if (pendingPos == null || !pendingPos.equals(replyPos)) {
			return;
		}
		pendingPos = null;
		pos = replyPos.immutable();
		entries = List.copyOf(replyEntries);
		select(entries.isEmpty() ? -1 : 0);
	}

	/** Whether the view is open. */
	public static boolean isOpen() {
		return pos != null;
	}

	/** The container the view shows, or null when closed. */
	public static @Nullable BlockPos pos() {
		return pos;
	}

	/** The container's entries, empty when closed. */
	public static List<RtsItemPicker.Entry> entries() {
		return entries;
	}

	/** The index of the picked entry, or -1. */
	public static int selected() {
		return selected;
	}

	/** The per-worker count of the picked entry. */
	public static int count() {
		return count;
	}

	/** The count label: plain N for one worker, "N each" for several. */
	public static Component countText(int selectionCount) {
		return selectionCount > 1 ? Component.translatable("rabodepalha.rts.withdraw.count_each", count)
				: Component.literal(Integer.toString(count));
	}

	/** Picks entry {@code index}; the count starts at a stack or everything there is, whichever is less. */
	public static void select(int index) {
		if (index < 0 || index >= entries.size()) {
			selected = -1;
			count = 0;
			return;
		}
		selected = index;
		RtsItemPicker.Entry entry = entries.get(index);
		int maxStack = BuiltInRegistries.ITEM.getOptional(entry.item())
				.map(item -> new ItemStack(item).getMaxStackSize())
				.orElse(1);
		count = Math.min(maxStack, entry.count());
	}

	/** Moves the count by {@code delta}, by {@link #SHIFT_STEP} with Shift, within 1 and the entry's total. */
	public static void step(int delta, boolean shift) {
		if (selected < 0) {
			return;
		}
		int available = entries.get(selected).count();
		count = Mth.clamp(count + delta * (shift ? SHIFT_STEP : 1), 1, available);
	}

	/** Queues the withdraw of the picked entry on the selected workers, then closes the view and disarms. */
	public static void confirm(boolean shift) {
		BlockPos at = pos;
		if (at == null || selected < 0) {
			return;
		}
		RtsOrdersClient.sendWithdraw(at, entries.get(selected).item(), count, shift);
		close();
	}

	/** Closes the view, drops any pending request and disarms. */
	public static void close() {
		boolean wasOpen = isOpen() || pendingPos != null;
		pendingPos = null;
		pos = null;
		entries = List.of();
		selected = -1;
		count = 0;
		if (wasOpen) {
			RtsCommandClient.clear();
		}
	}

	/** Closes the view once the selection empties. */
	public static void tick() {
		if (isOpen() && RtsSelectionClient.isEmpty()) {
			close();
		}
	}

	/** Handles Esc. Returns true if it closed the view, which consumes the key. */
	public static boolean onEscape() {
		if (!isOpen()) {
			return false;
		}
		close();
		return true;
	}

	/** The view's layout on the current window, or null when closed. */
	public static RtsContainerPanel.@Nullable Layout layout() {
		if (!isOpen()) {
			return null;
		}
		Window window = Minecraft.getInstance().getWindow();
		return RtsContainerPanel.layout(window.getGuiScaledWidth(), window.getGuiScaledHeight(), entries.size());
	}

	/** What the window pixel ({@code x}, {@code y}) falls on in the view. */
	public static RtsContainerPanel.Hit hitAt(double x, double y) {
		RtsContainerPanel.Layout layout = layout();
		if (layout == null) {
			return RtsContainerPanel.Hit.NONE;
		}
		double scale = Minecraft.getInstance().getWindow().getGuiScale();
		return RtsContainerPanel.hitTest(layout, RtsCommandBar.toGui(x, scale), RtsCommandBar.toGui(y, scale));
	}

	/** Whether the window pixel ({@code x}, {@code y}) is on the view. */
	public static boolean over(double x, double y) {
		return !(hitAt(x, y) instanceof RtsContainerPanel.Hit.None);
	}

	/** Handles a left press. Returns true when it fell on the view, which consumes it. */
	public static boolean onLeftPress(double x, double y, boolean shift) {
		switch (hitAt(x, y)) {
			case RtsContainerPanel.Hit.None none -> {
				return false;
			}
			case RtsContainerPanel.Hit.Item item -> select(item.index());
			case RtsContainerPanel.Hit.Minus minus -> step(-1, shift);
			case RtsContainerPanel.Hit.Plus plus -> step(1, shift);
			case RtsContainerPanel.Hit.Confirm confirm -> confirm(shift);
			case RtsContainerPanel.Hit.Close closeHit -> close();
			case RtsContainerPanel.Hit.Body body -> {
				// Consumed: a press on the view's background must not select or order anything.
			}
		}
		return true;
	}
}
