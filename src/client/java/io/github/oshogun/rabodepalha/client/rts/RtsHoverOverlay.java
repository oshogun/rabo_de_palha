package io.github.oshogun.rabodepalha.client.rts;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.client.render.WorldOverlay;
import io.github.oshogun.rabodepalha.rts.RtsOrderHint;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

/**
 * Highlights what the cursor is over in RTS mode: a box around the hovered worker, or else an outline of
 * the hovered block with the hit face filled. Picks the cursor once per frame through
 * {@link RtsSelectionClient#updateHover}, and hides vanilla's crosshair outline while RTS is active.
 */
public final class RtsHoverOverlay {
	private static final double WORKER_INFLATE = 0.05;
	private static final int WORKER_LINE = 0xFFFFD84A;
	private static final int WORKER_FILL = 0x33FFD84A;
	private static final int BLOCK_LINE = 0x99000000;
	private static final int FACE_FILL = 0x40FFFFFF;

	private RtsHoverOverlay() {
	}

	public static void init() {
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outlineRenderState) -> !RtsClient.isActive());
		LevelRenderEvents.COLLECT_SUBMITS.register(RtsHoverOverlay::submit);
	}

	private static void submit(LevelRenderContext ctx) {
		Minecraft client = Minecraft.getInstance();
		RtsSelectionClient.updateHover(client);
		RtsSelectionClient.Hover hover = RtsSelectionClient.hover();
		if (hover == null) {
			return;
		}
		WorkerEntity worker = hover.worker();
		BlockHitResult block = hover.block();
		// Only the hover flags matter to hoverKind; the rest of the target is for the order hint.
		RtsOrderHint.Target target = new RtsOrderHint.Target(worker != null, block != null, false, false, 0.0);
		switch (RtsOrderHint.hoverKind(target)) {
			case WORKER -> {
				float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
				Vec3 offset = worker.getPosition(partialTick).subtract(worker.position());
				AABB box = worker.getBoundingBox().move(offset).inflate(WORKER_INFLATE);
				WorldOverlay.box(ctx, box, WORKER_LINE, WORKER_FILL);
			}
			case BLOCK -> {
				WorldOverlay.outlineBlock(ctx, block.getBlockPos(), BLOCK_LINE);
				WorldOverlay.face(ctx, block.getBlockPos(), block.getDirection(), FACE_FILL);
			}
			case NONE -> {
			}
		}
	}
}
