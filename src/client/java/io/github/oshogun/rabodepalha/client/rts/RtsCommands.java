package io.github.oshogun.rabodepalha.client.rts;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.oshogun.rabodepalha.rts.RtsCommandPage;
import io.github.oshogun.rabodepalha.rts.RtsCommandPages;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;
import io.github.oshogun.rabodepalha.rts.RtsTaskKind;

/**
 * The RTS command bar's commands, in bar order within each page. This list is the one place to add a command: the
 * key mappings, the bar, its pages, its tooltips and the click handling all read it. A new command needs an entry
 * here and its lang keys ({@code key.rabodepalha.rts_command.<id>} and {@code rabodepalha.rts.command.<id>}), plus a
 * payload if the server has to carry it out.
 *
 * <p>A new command is one {@link #COMMANDS} entry naming its {@link RtsCommandPage}. It gets
 * {@code defaultKey} {@code InputConstants.UNKNOWN.getValue()}: players reach it through its page slot (keys 1-9)
 * and can bind a letter in Controls. Only the original commands keep default letters. A page holds at most
 * {@value RtsCommandPage#MAX_SLOTS} commands; add a page in {@link RtsCommandPage} if one is full.
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
	 * @param staysArmed whether the mode stays armed after firing, for a mode that something else disarms
	 * @param onArm runs each time the mode gets armed
	 */
	public record Armed(Function<RtsSelectionClient.Hover, RtsOrderHint.Hint> hint,
			BiConsumer<RtsSelectionClient.Hover, Boolean> fire, boolean staysArmed, Runnable onArm) implements Action {
		/** A mode that does nothing extra when armed. */
		public Armed(Function<RtsSelectionClient.Hover, RtsOrderHint.Hint> hint,
				BiConsumer<RtsSelectionClient.Hover, Boolean> fire, boolean staysArmed) {
			this(hint, fire, staysArmed, () -> {
			});
		}

		/** A mode that disarms once fired, unless Shift is held. */
		public Armed(Function<RtsSelectionClient.Hover, RtsOrderHint.Hint> hint,
				BiConsumer<RtsSelectionClient.Hover, Boolean> fire) {
			this(hint, fire, false);
		}
	}

	/**
	 * Arms a mode that marks an area: drag a rectangle in the world, set the depth with the scroll wheel, then
	 * left-click to fire it. A flat area has no depth step and fires when the drag is released.
	 *
	 * @param hint whether and why an area can be started on a hover
	 * @param fire carries the mode out on the marked area; the flag is Shift, which queues the order
	 * @param problem why the server would reject the marked area, if the client sees a reason
	 * @param flat whether the area is the rectangle between the two picked blocks, with no depth step
	 * @param then a container step after the area: a flat area then waits for a click on a container, or Enter to
	 *     skip it, and fires with that instead of {@code fire}
	 */
	public record Area(Function<RtsSelectionClient.Hover, RtsOrderHint.Hint> hint,
			BiConsumer<RtsDigArea, Boolean> fire, Function<RtsDigArea, Optional<Component>> problem,
			boolean flat, Optional<ContainerStep> then) implements Action {
		/** An area that fires as soon as it is marked. */
		public Area(Function<RtsSelectionClient.Hover, RtsOrderHint.Hint> hint,
				BiConsumer<RtsDigArea, Boolean> fire, Function<RtsDigArea, Optional<Component>> problem,
				boolean flat) {
			this(hint, fire, problem, flat, Optional.empty());
		}

		/** Whether a container is picked after the area is marked. */
		public boolean pickContainer() {
			return then.isPresent();
		}
	}

	/**
	 * The container step of an {@link Area}.
	 *
	 * @param hint whether the hovered block can be picked as the container
	 * @param fire carries the order out on the marked area and the picked container, empty when skipped; the flag
	 *     is Shift, held when the area was marked
	 */
	public record ContainerStep(Function<RtsSelectionClient.Hover, RtsOrderHint.Hint> hint, ContainerFire fire) {
	}

	/** Carries out an area order with an optional container. */
	@FunctionalInterface
	public interface ContainerFire {
		void fire(RtsDigArea area, Optional<BlockPos> container, boolean shift);
	}

	/**
	 * A command bar button.
	 *
	 * @param id the command's id, used in its lang keys
	 * @param icon the item drawn on the button
	 * @param defaultKey the default hotkey, an {@link InputConstants} key code; {@code InputConstants.UNKNOWN} for none
	 * @param page the bar page the command is on, or {@link RtsCommandPage#GLOBAL} for every page
	 * @param kind the kind of task the command queues, whose colour the bar shows under the button
	 */
	public record RtsCommand(String id, Supplier<ItemStack> icon, int defaultKey, RtsCommandPage page, Action action,
			Optional<RtsTaskKind> kind) {
		/** A command that queues no task of its own kind. */
		public RtsCommand(String id, Supplier<ItemStack> icon, int defaultKey, RtsCommandPage page, Action action) {
			this(id, icon, defaultKey, page, action, Optional.empty());
		}

		/** The name of the command's key mapping. */
		public String keyMappingName() {
			return "key.rabodepalha.rts_command." + id;
		}

		/** The command's display name, for the tooltip and the hint line. */
		public Component displayName() {
			return Component.translatable("rabodepalha.rts.command." + id);
		}
	}

	/** The build command's id. */
	public static final String BUILD = "build";
	/** The place command's id. */
	public static final String PLACE = "place";
	/** The plant command's id. */
	public static final String PLANT = "plant";
	/** The harvest command's id. */
	public static final String HARVEST = "harvest";
	/** The tend command's id. */
	public static final String TEND = "tend";
	/** The irrigate command's id. */
	public static final String IRRIGATE = "irrigate";

	public static final List<RtsCommand> COMMANDS = List.of(
			order("move", () -> new ItemStack(Items.FEATHER), InputConstants.KEY_M, RtsOrderKind.MOVE),
			order("mine", () -> new ItemStack(Items.IRON_PICKAXE), InputConstants.KEY_B, RtsOrderKind.MINE),
			order(PLACE, () -> new ItemStack(Items.STONE), InputConstants.KEY_V, RtsOrderKind.PLACE),
			new RtsCommand("dig", () -> new ItemStack(Items.IRON_SHOVEL), InputConstants.KEY_C, RtsCommandPage.ORDERS,
					new Area(RtsOrderHintClient::dig, RtsOrdersClient::sendDig,
							area -> Optional.ofNullable(RtsOrdersClient.digProblem(area)), false),
					Optional.of(RtsTaskKind.DIG)),
			new RtsCommand("stop", () -> new ItemStack(Items.BARRIER), InputConstants.KEY_G, RtsCommandPage.GLOBAL,
					new Instant(RtsOrdersClient::sendStop)),
			new RtsCommand("spawn", () -> new ItemStack(Items.VILLAGER_SPAWN_EGG), InputConstants.KEY_N,
					RtsCommandPage.ORDERS,
					new Armed(RtsOrderHintClient::spawn, (hover, shift) -> RtsOrdersClient.sendSpawn(hover))),
			new RtsCommand("deposit", () -> new ItemStack(Items.CHEST), InputConstants.KEY_J, RtsCommandPage.HAUL_BUILD,
					new Armed(RtsOrderHintClient::deposit, RtsOrdersClient::sendDeposit), Optional.of(RtsTaskKind.DEPOSIT)),
			// Withdraw stays armed while the contents view is open; confirming or closing the view disarms it.
			new RtsCommand("withdraw", () -> new ItemStack(Items.HOPPER), InputConstants.KEY_K, RtsCommandPage.HAUL_BUILD,
					new Armed(RtsOrderHintClient::withdraw, (hover, shift) -> RtsOrdersClient.requestContents(hover),
							true), Optional.of(RtsTaskKind.WITHDRAW)),
			// Build opens the blueprint picker when armed and stays armed for more copies; the picked blueprint is
			// dropped when the mode disarms.
			new RtsCommand(BUILD, () -> new ItemStack(Items.BRICKS), InputConstants.KEY_Y, RtsCommandPage.HAUL_BUILD,
					new Armed(RtsOrderHintClient::build, RtsOrdersClient::sendBuild, true, () -> {
						RtsBlueprintsClient.open();
						RtsOrdersClient.requestBlueprints(RtsBlueprintsClient.includeVanilla());
					}), Optional.of(RtsTaskKind.BUILD)),
			// No default key: a plain right-click already uses levers and buttons.
			order("use", () -> new ItemStack(Items.LEVER), InputConstants.UNKNOWN.getValue(), RtsCommandPage.GATHER,
					RtsOrderKind.USE),
			// A flat drag over the field; the seed is the item picked in the panel.
			new RtsCommand(PLANT, () -> new ItemStack(Items.WHEAT_SEEDS), InputConstants.UNKNOWN.getValue(),
					RtsCommandPage.FARM, new Area(RtsOrderHintClient::plant, RtsOrdersClient::sendPlant,
							area -> Optional.ofNullable(RtsOrdersClient.plantProblem(area)), true),
					Optional.of(RtsTaskKind.PLANT)),
			// A flat drag over the field, like Plant; only ripe plants are harvested.
			new RtsCommand(HARVEST, () -> new ItemStack(Items.WHEAT), InputConstants.UNKNOWN.getValue(),
					RtsCommandPage.FARM, new Area(RtsOrderHintClient::harvest, RtsOrdersClient::sendHarvest,
							area -> Optional.ofNullable(RtsOrdersClient.harvestProblem(area)), true),
					Optional.of(RtsTaskKind.HARVEST)),
			// A flat drag over the field, then a click on the chest for the produce, or Enter for none.
			new RtsCommand(TEND, () -> new ItemStack(Items.IRON_HOE), InputConstants.UNKNOWN.getValue(),
					RtsCommandPage.FARM, new Area(RtsOrderHintClient::tend,
							(area, shift) -> RtsOrdersClient.sendTend(area, Optional.empty(), shift),
							area -> Optional.ofNullable(RtsOrdersClient.tendProblem(area)), true,
							Optional.of(new ContainerStep(RtsOrderHintClient::tendContainer, RtsOrdersClient::sendTend))),
					Optional.of(RtsTaskKind.TEND)),
			// A click for the 9×9 plot around a block, or a flat drag for a field; the seed is picked in the panel.
			new RtsCommand(IRRIGATE, () -> new ItemStack(Items.WATER_BUCKET), InputConstants.UNKNOWN.getValue(),
					RtsCommandPage.FARM, new Area(RtsOrderHintClient::irrigate, RtsOrdersClient::sendIrrigate,
							area -> Optional.ofNullable(RtsOrdersClient.irrigateProblem(area)), true),
					Optional.of(RtsTaskKind.IRRIGATE)));

	/** {@link #COMMANDS} sorted into bar pages; fails at class load if a page is overfull. */
	public static final RtsCommandPages<RtsCommand> PAGES = new RtsCommandPages<>(COMMANDS, RtsCommand::page);

	private RtsCommands() {
	}

	/** A page's tab name. */
	public static Component pageName(RtsCommandPage page) {
		return Component.translatable(page.langKey());
	}

	/** An armed command on the Orders page that gives an order of {@code kind} to the selected workers. */
	private static RtsCommand order(String id, Supplier<ItemStack> icon, int defaultKey, RtsOrderKind kind) {
		return order(id, icon, defaultKey, RtsCommandPage.ORDERS, kind);
	}

	/** An armed command on {@code page} that gives an order of {@code kind} to the selected workers. */
	private static RtsCommand order(String id, Supplier<ItemStack> icon, int defaultKey, RtsCommandPage page,
			RtsOrderKind kind) {
		return new RtsCommand(id, icon, defaultKey, page, new Armed(
				hover -> RtsOrderHintClient.current(hover, kind),
				(hover, shift) -> RtsOrdersClient.sendOrder(kind, hover, RtsOrderHintClient.current(hover, kind),
						shift)), Optional.of(RtsTaskKind.from(kind)));
	}
}
