package io.github.oshogun.rabodepalha.client.rts;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import io.github.oshogun.rabodepalha.client.render.WorldOverlay;
import io.github.oshogun.rabodepalha.rts.BlueprintFootprint;

/**
 * Draws the armed blueprint's footprint at the cursor in RTS mode: an outline of the anchor block, and once
 * the blueprint's info has arrived, the turned box built from {@link RtsOrdersClient#buildOrigin}, blue when it
 * can be ordered and red when {@link RtsOrdersClient#buildProblem} finds a problem. Ghost blocks are not drawn.
 */
public final class RtsBlueprintOverlay {
	private static final int VALID_LINE = 0xFF4AA8FF;
	private static final int VALID_FILL = 0x334AA8FF;
	private static final int INVALID_LINE = 0xFFFF4A4A;
	private static final int INVALID_FILL = 0x33FF4A4A;

	private RtsBlueprintOverlay() {
	}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(RtsBlueprintOverlay::submit);
	}

	private static void submit(LevelRenderContext ctx) {
		if (!RtsClient.isActive() || !RtsCommandClient.buildArmed()) {
			return;
		}
		// RtsHoverOverlay registers first, so this frame's pick is already up to date.
		RtsSelectionClient.Hover hover = RtsSelectionClient.hover();
		if (hover == null) {
			return;
		}
		BlockHitResult block = hover.block();
		if (block == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		double x = client.mouseHandler.xpos();
		double y = client.mouseHandler.ypos();
		// A click on the bar or the picker uses them, so preview nothing under them.
		if (RtsCommandClient.overBar(x, y) || RtsBlueprintsClient.over(x, y)) {
			return;
		}
		WorldOverlay.outlineBlock(ctx, RtsOrdersClient.anchor(block), VALID_LINE);
		RtsBlueprintsClient.Info info = RtsBlueprintsClient.armedInfo();
		BlockPos origin = RtsOrdersClient.buildOrigin(hover);
		if (info == null || origin == null) {
			return;
		}
		BoundingBox box = BlueprintFootprint.box(origin, info.size(), RtsBlueprintsClient.rotation());
		boolean valid = RtsOrdersClient.buildProblem(box) == null;
		AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
		WorldOverlay.box(ctx, aabb, valid ? VALID_LINE : INVALID_LINE, valid ? VALID_FILL : INVALID_FILL);
	}
}
