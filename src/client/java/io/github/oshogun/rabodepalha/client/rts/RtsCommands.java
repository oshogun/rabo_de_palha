package io.github.oshogun.rabodepalha.client.rts;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;

/**
 * The RTS command bar's commands, in bar order. This list is the one place to add a command: the key mappings,
 * the bar, its tooltips and the click handling all read it. A new command needs an entry here and its lang keys
 * ({@code key.rabodepalha.rts_command.<id>} and {@code rabodepalha.rts.command.<id>}), plus a payload if the
 * server has to carry it out.
 */
public final class RtsCommands {
	/** What a command does when its button or hotkey is used. */
	public sealed interface Action permits Instant, Armed, Area {
	}

	/** Runs at once, with no target. */
	public record Instant(Runnable run) implements Action {
	}

	/**
	 * Arms a mode; the next valid left-click in the world fires it.
	 *
	 * @param hint whether and why the mode can be carried out on a hover
	 * @param fire carries the mode out on a hover; the flag is Shift, which queues the order
	 */
	public record Armed(Function<RtsSelectionClient.Hover, RtsOrderHint.Hint> hint,
			BiConsumer<RtsSelectionClient.Hover, Boolean> fire) implements Action {
	}

	/**
	 * Arms a mode that marks an area: drag a rectangle in the world, set the depth with the scroll wheel, then
	 * left-click to fire it.
	 *
	 * @param hint whether and why an area can be started on a hover
	 * @param fire carries the mode out on the marked area; the flag is Shift, which queues the order
	 */
	public record Area(Function<RtsSelectionClient.Hover, RtsOrderHint.Hint> hint,
			BiConsumer<RtsDigArea, Boolean> fire) implements Action {
	}

	/**
	 * A command bar button.
	 *
	 * @param id the command's id, used in its lang keys
	 * @param icon the item drawn on the button
	 * @param defaultKey the default hotkey, an {@link InputConstants} key code
	 */
	public record RtsCommand(String id, Supplier<ItemStack> icon, int defaultKey, Action action) {
		/** The name of the command's key mapping. */
		public String keyMappingName() {
			return "key.rabodepalha.rts_command." + id;
		}

		/** The command's display name, for the tooltip and the hint line. */
		public Component displayName() {
			return Component.translatable("rabodepalha.rts.command." + id);
		}
	}

	public static final List<RtsCommand> COMMANDS = List.of(
			order("move", () -> new ItemStack(Items.FEATHER), InputConstants.KEY_M, RtsOrderKind.MOVE),
			order("mine", () -> new ItemStack(Items.IRON_PICKAXE), InputConstants.KEY_B, RtsOrderKind.MINE),
			order("place", () -> new ItemStack(Items.STONE), InputConstants.KEY_V, RtsOrderKind.PLACE),
			new RtsCommand("dig", () -> new ItemStack(Items.IRON_SHOVEL), InputConstants.KEY_C,
					new Area(RtsOrderHintClient::dig, RtsOrdersClient::sendDig)),
			new RtsCommand("stop", () -> new ItemStack(Items.BARRIER), InputConstants.KEY_G,
					new Instant(RtsOrdersClient::sendStop)),
			new RtsCommand("spawn", () -> new ItemStack(Items.VILLAGER_SPAWN_EGG), InputConstants.KEY_N,
					new Armed(RtsOrderHintClient::spawn, (hover, shift) -> RtsOrdersClient.sendSpawn(hover))));

	private RtsCommands() {
	}

	/** An armed command that gives an order of {@code kind} to the selected workers. */
	private static RtsCommand order(String id, Supplier<ItemStack> icon, int defaultKey, RtsOrderKind kind) {
		return new RtsCommand(id, icon, defaultKey, new Armed(
				hover -> RtsOrderHintClient.current(hover, kind),
				(hover, shift) -> RtsOrdersClient.sendOrder(kind, hover, RtsOrderHintClient.current(hover, kind),
						shift)));
	}
}
