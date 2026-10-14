package io.github.oshogun.rabodepalha.client.rts;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.rts.BlueprintFootprint;
import io.github.oshogun.rabodepalha.rts.RtsItemPicker;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.worker.WorkerSpawner;

/**
 * Client glue for {@link RtsOrderHint}: fills in the {@link RtsOrderHint.Target} from the cursor hover, the
 * selection and the item picked in the panel, and turns the resulting hint into text. The order targets the block hit, so a
 * worker under the cursor does not change the hint.
 */
public final class RtsOrderHintClient {
	private RtsOrderHintClient() {
	}

	/** The order of the given kind for {@code hover} with the current selection. */
	public static RtsOrderHint.Hint current(RtsSelectionClient.Hover hover, RtsOrderKind kind) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		BlockHitResult block = hover.block();
		boolean blockHit = block != null && player != null && level != null;
		boolean blockIsAir = false;
		double distSqr = 0;
		if (blockHit) {
			blockIsAir = level.getBlockState(block.getBlockPos()).isAir();
			// In RTS the player is the spectator camera, so this is the distance from what is on screen.
			BlockPos target = RtsOrders.target(kind, block.getBlockPos(), block.getDirection());
			distSqr = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target));
		}
		RtsOrderHint.Target target = new RtsOrderHint.Target(hover.worker() != null, blockHit, blockIsAir,
				pickedBlock(), distSqr);
		return RtsOrderHint.evaluate(RtsSelectionClient.count(), kind, target);
	}

	/**
	 * Spawning a worker on top of the hovered block. The distance is measured to the spawn spot, and the room
	 * check uses the client's loaded block states; the server repeats both checks.
	 */
	public static RtsOrderHint.Hint spawn(RtsSelectionClient.Hover hover) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		BlockHitResult block = hover.block();
		boolean blockHit = block != null && player != null && level != null;
		boolean blockIsAir = false;
		double distSqr = 0;
		boolean roomFree = false;
		if (blockHit) {
			blockIsAir = level.getBlockState(block.getBlockPos()).isAir();
			BlockPos spot = block.getBlockPos().above();
			distSqr = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(spot));
			roomFree = WorkerSpawner.hasRoom(level, WorkerSpawner.feetOn(spot));
		}
		RtsOrderHint.Target target = new RtsOrderHint.Target(hover.worker() != null, blockHit, blockIsAir,
				pickedBlock(), distSqr);
		return RtsOrderHint.evaluateSpawn(target, roomFree);
	}

	/**
	 * Dig armed and no area started yet: it needs a selection and a block under the cursor to start the drag.
	 * The area itself is checked by {@link RtsOrdersClient#digProblem} once it exists. The hint has no kind and
	 * uses its key as is.
	 */
	public static RtsOrderHint.Hint dig(RtsSelectionClient.Hover hover) {
		if (RtsSelectionClient.count() <= 0) {
			return new RtsOrderHint.Hint("rabodepalha.rts.order.nothing_selected", false, null);
		}
		if (hover.block() == null) {
			return new RtsOrderHint.Hint("rabodepalha.rts.order.no_target", false, null);
		}
		return new RtsOrderHint.Hint("rabodepalha.rts.dig.hint.start", true, null);
	}

	/**
	 * Building the armed blueprint at the hovered block: it needs a selection, a picked blueprint whose info has
	 * arrived, and a footprint the server would accept. The hint has no kind and uses its key as is.
	 */
	public static RtsOrderHint.Hint build(RtsSelectionClient.Hover hover) {
		if (RtsSelectionClient.count() <= 0) {
			return new RtsOrderHint.Hint("rabodepalha.rts.order.nothing_selected", false, null);
		}
		if (!RtsBlueprintsClient.isArmed()) {
			return new RtsOrderHint.Hint("rabodepalha.rts.build.hint.pick", false, null);
		}
		RtsBlueprintsClient.Info info = RtsBlueprintsClient.armedInfo();
		if (info == null) {
			return new RtsOrderHint.Hint("rabodepalha.rts.build.hint.loading", false, null);
		}
		BlockPos origin = RtsOrdersClient.buildOrigin(hover);
		if (origin == null) {
			return new RtsOrderHint.Hint("rabodepalha.rts.order.no_target", false, null);
		}
		String problem = RtsOrdersClient.buildProblemKey(
				BlueprintFootprint.box(origin, info.size(), RtsBlueprintsClient.rotation()));
		if (problem != null) {
			return new RtsOrderHint.Hint(problem, false, null);
		}
		return new RtsOrderHint.Hint("rabodepalha.rts.build.hint.place", true, null);
	}

	/** Depositing into the hovered container: everything, or only the picked item. */
	public static RtsOrderHint.Hint deposit(RtsSelectionClient.Hover hover) {
		return RtsOrderHint.evaluateContainer(RtsSelectionClient.count(), isContainer(hover),
				"rabodepalha.rts.hint.deposit");
	}

	/** Withdrawing from the hovered container, which first opens its contents. */
	public static RtsOrderHint.Hint withdraw(RtsSelectionClient.Hover hover) {
		return RtsOrderHint.evaluateContainer(RtsSelectionClient.count(), isContainer(hover),
				"rabodepalha.rts.hint.withdraw");
	}

	/**
	 * Whether the hovered block holds a container, from the client's block entities. It can disagree with the
	 * server, which has the last word.
	 */
	private static boolean isContainer(RtsSelectionClient.Hover hover) {
		ClientLevel level = Minecraft.getInstance().level;
		BlockHitResult block = hover.block();
		return level != null && block != null && level.getBlockEntity(block.getBlockPos()) instanceof Container;
	}

	/**
	 * The hint's text: the block name for mine, the picked item name for place, the reason when invalid. A spawn
	 * or container hint has no kind and uses its key as is.
	 */
	public static Component text(RtsOrderHint.Hint hint, RtsSelectionClient.Hover hover) {
		if (hint.valid()) {
			Minecraft client = Minecraft.getInstance();
			BlockHitResult block = hover.block();
			if (hint.kind() == RtsOrderKind.MINE && block != null && client.level != null) {
				return Component.translatable(hint.key(),
						client.level.getBlockState(block.getBlockPos()).getBlock().getName());
			}
			if (hint.kind() == RtsOrderKind.PLACE) {
				return Component.translatable(hint.key(), pickedName().orElse(Component.empty()));
			}
		}
		return Component.translatable(hint.key());
	}

	/** Whether the item picked in the panel is a block. */
	public static boolean pickedBlock() {
		return RtsItemPickClient.picked().map(RtsItemPicker::isBlock).orElse(false);
	}

	/** The name of the item picked in the panel, if any. */
	public static Optional<Component> pickedName() {
		return RtsItemPickClient.picked().flatMap(RtsOrderHintClient::itemName);
	}

	/** The display name of the item {@code id}, if it is registered. */
	public static Optional<Component> itemName(Identifier id) {
		return BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item).getHoverName());
	}
}
