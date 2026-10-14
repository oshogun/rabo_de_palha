package io.github.oshogun.rabodepalha.client.rts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.worker.WorkerSpawner;

/**
 * Client glue for {@link RtsOrderHint}: fills in the {@link RtsOrderHint.Target} from the cursor hover, the
 * selection and the held item, and turns the resulting hint into text. The order targets the block hit, so a
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
				heldItem(client).getItem() instanceof BlockItem, distSqr);
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
				heldItem(client).getItem() instanceof BlockItem, distSqr);
		return RtsOrderHint.evaluateSpawn(target, roomFree);
	}

	/**
	 * The hint's text: the block name for mine, the held item name for place, the reason when invalid. A spawn
	 * hint has no kind and uses its key as is.
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
				return Component.translatable(hint.key(), heldItem(client).getHoverName());
			}
		}
		return Component.translatable(hint.key());
	}

	private static ItemStack heldItem(Minecraft client) {
		if (client.player == null) {
			return ItemStack.EMPTY;
		}
		return client.player.getInventory().getItem(client.player.getInventory().getSelectedSlot());
	}
}
