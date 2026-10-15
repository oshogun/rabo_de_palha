package io.github.oshogun.rabodepalha.client.rts;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import io.github.oshogun.rabodepalha.client.render.WorldOverlay;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.worker.task.HarvestBlocks;

/**
 * Draws the ripe plants in the field being marked for Harvest in RTS mode: each ripe plant is boxed in
 * green (a stalk from its base to its top), and unripe plants are not marked. A field over the cap is drawn
 * as one red box. The field marked for Tend, while dragged or waiting for its container, is drawn the same way
 * plus its outline. The ripe plants are cached per area and refreshed every {@link #REFRESH_TICKS} ticks;
 * terrain rendering is untouched, so this works on Sodium.
 */
public final class RtsHarvestOverlay {
	private static final int RIPE_LINE = 0xFF40D040;
	private static final int RIPE_FILL = 0x3340D040;
	private static final int INVALID_LINE = 0xFFFF4A4A;
	private static final int INVALID_FILL = 0x33FF4A4A;
	private static final int FIELD_LINE = 0xFFE0E0E0;
	private static final int REFRESH_TICKS = 10;

	private static @Nullable RtsDigArea cachedArea;
	private static long cachedAt;
	private static List<AABB> marks = List.of();

	private RtsHarvestOverlay() {
	}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(RtsHarvestOverlay::submit);
	}

	private static void submit(LevelRenderContext ctx) {
		if (!RtsClient.isActive()) {
			return;
		}
		RtsDigArea area = RtsCommandClient.harvestArea();
		boolean tend = false;
		if (area == null) {
			area = RtsCommandClient.tendArea();
			if (area == null) {
				area = RtsCommandClient.pendingTendArea();
			}
			tend = area != null;
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (area == null || level == null) {
			cachedArea = null;
			return;
		}
		RtsField field = RtsField.of(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ());
		AABB box = new AABB(area.minX(), area.minY(), area.minZ(), area.maxX() + 1, area.maxY() + 1, area.maxZ() + 1);
		if (field.exceeds()) {
			WorldOverlay.box(ctx, box, INVALID_LINE, INVALID_FILL);
			return;
		}
		if (tend) {
			// Tend keeps working the whole field, so its outline is drawn as well as the ripe plants.
			WorldOverlay.box(ctx, box, FIELD_LINE, 0);
		}
		long now = level.getGameTime();
		if (!area.equals(cachedArea) || now - cachedAt >= REFRESH_TICKS || now < cachedAt) {
			marks = scan(level, field);
			cachedArea = area;
			cachedAt = now;
		}
		for (AABB mark : marks) {
			WorldOverlay.box(ctx, mark, RIPE_LINE, RIPE_FILL);
		}
	}

	private static List<AABB> scan(ClientLevel level, RtsField field) {
		List<HarvestBlocks.Target> ripe = new ArrayList<>();
		for (int[] column : field.columnOrder()) {
			HarvestBlocks.scan(level, field, column[0], column[1], ripe);
		}
		List<AABB> result = new ArrayList<>(ripe.size());
		for (HarvestBlocks.Target target : ripe) {
			BlockPos pos = target.pos();
			BlockPos top = pos;
			if (target.rule() == HarvestBlocks.Rule.STALK) {
				Block stalk = level.getBlockState(pos).getBlock();
				while (top.getY() < level.getMaxY() && level.getBlockState(top.above()).is(stalk)) {
					top = top.above();
				}
			}
			result.add(new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, top.getY() + 1, pos.getZ() + 1));
		}
		return result;
	}
}
