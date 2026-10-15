package io.github.oshogun.rabodepalha.client.rts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import io.github.oshogun.rabodepalha.client.render.WorldOverlay;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.rts.RtsIrrigation;
import io.github.oshogun.rabodepalha.worker.task.IrrigationBlocks;

/**
 * Draws the plot or field being marked for Irrigate in RTS mode: each planned hole is outlined and filled in
 * opaque blue, the columns it (or water already there) keeps wet get a light blue tint, and columns left dry
 * are dimmed. A click is drawn as the 9×9 plot around it; a field over the cap is drawn as one red box. The
 * plan is cached per field and refreshed every {@link #REFRESH_TICKS} ticks; terrain rendering is untouched,
 * so this works on Sodium.
 */
public final class RtsIrrigateOverlay {
	private static final int HOLE_LINE = 0xFF2060FF;
	private static final int HOLE_FILL = 0xCC2060FF;
	private static final int WET = 0x3360A0FF;
	private static final int DRY = 0x22808080;
	private static final int INVALID_LINE = 0xFFFF4A4A;
	private static final int INVALID_FILL = 0x33FF4A4A;
	private static final int REFRESH_TICKS = 10;

	private record Mark(BlockPos pos, int argb) {
	}

	private static @Nullable RtsField cachedField;
	private static long cachedAt;
	private static List<BlockPos> holes = List.of();
	private static List<Mark> marks = List.of();

	private RtsIrrigateOverlay() {
	}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(RtsIrrigateOverlay::submit);
	}

	private static void submit(LevelRenderContext ctx) {
		if (!RtsClient.isActive()) {
			return;
		}
		RtsDigArea area = RtsCommandClient.irrigateArea();
		ClientLevel level = Minecraft.getInstance().level;
		if (area == null || level == null) {
			cachedField = null;
			return;
		}
		RtsField field = RtsOrdersClient.irrigateField(area);
		if (field.exceeds()) {
			AABB box = new AABB(area.minX(), area.minY(), area.minZ(), area.maxX() + 1, area.maxY() + 1, area.maxZ() + 1);
			WorldOverlay.box(ctx, box, INVALID_LINE, INVALID_FILL);
			return;
		}
		long now = level.getGameTime();
		if (!field.equals(cachedField) || now - cachedAt >= REFRESH_TICKS || now < cachedAt) {
			scan(level, field);
			cachedField = field;
			cachedAt = now;
		}
		for (Mark mark : marks) {
			WorldOverlay.face(ctx, mark.pos(), Direction.UP, mark.argb());
		}
		for (BlockPos hole : holes) {
			WorldOverlay.face(ctx, hole, Direction.UP, HOLE_FILL);
			WorldOverlay.outlineBlock(ctx, hole, HOLE_LINE);
		}
	}

	private static void scan(ClientLevel level, RtsField field) {
		List<RtsIrrigation.Column> columns = IrrigationBlocks.columns(level, field);
		RtsIrrigation.Plan plan = RtsIrrigation.plan(field, columns, IrrigationBlocks.existingWater(level, field));
		List<BlockPos> holeBlocks = new ArrayList<>();
		Set<Long> holeKeys = new HashSet<>();
		for (RtsIrrigation.Column hole : plan.holes()) {
			holeBlocks.add(new BlockPos(hole.x(), hole.y(), hole.z()));
			holeKeys.add(hole.key());
		}
		List<Mark> result = new ArrayList<>();
		for (RtsIrrigation.Column column : columns) {
			long key = column.key();
			if (holeKeys.contains(key)) {
				continue;
			}
			BlockPos ground = new BlockPos(column.x(), column.y(), column.z());
			if (plan.wet().contains(key)) {
				result.add(new Mark(ground, WET));
			} else if (plan.dry().contains(key)) {
				result.add(new Mark(ground, DRY));
			}
		}
		holes = holeBlocks;
		marks = result;
	}
}
