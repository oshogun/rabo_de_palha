package io.github.oshogun.rabodepalha.client.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import io.github.oshogun.rabodepalha.client.render.WorldOverlay;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;
import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.worker.task.FieldBlocks;

/**
 * Draws the field being marked for Plant in RTS mode: each column's field block is tinted amber when it
 * will be tilled or has cover to clear first, green when it will be planted as is, and red when it will
 * be skipped. A field over the cap is drawn as one red box. The per-block plan is cached per (area, seed)
 * and refreshed every {@link #REFRESH_TICKS} ticks; terrain rendering is untouched, so this works on Sodium.
 */
public final class RtsPlantOverlay {
	private static final int TILL = 0x66FFB000;
	private static final int PLANT = 0x6640D040;
	private static final int SKIP = 0x66FF4A4A;
	private static final int INVALID_LINE = 0xFFFF4A4A;
	private static final int INVALID_FILL = 0x33FF4A4A;
	private static final int REFRESH_TICKS = 10;

	private record Mark(BlockPos pos, int argb) {
	}

	private static @Nullable RtsDigArea cachedArea;
	private static @Nullable Identifier cachedSeed;
	private static long cachedAt;
	private static List<Mark> marks = List.of();

	private RtsPlantOverlay() {
	}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(RtsPlantOverlay::submit);
	}

	private static void submit(LevelRenderContext ctx) {
		if (!RtsClient.isActive()) {
			return;
		}
		RtsDigArea area = RtsCommandClient.plantArea();
		ClientLevel level = Minecraft.getInstance().level;
		if (area == null || level == null) {
			cachedArea = null;
			return;
		}
		RtsField field = RtsField.of(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ());
		if (field.exceeds()) {
			AABB box = new AABB(area.minX(), area.minY(), area.minZ(), area.maxX() + 1, area.maxY() + 1, area.maxZ() + 1);
			WorldOverlay.box(ctx, box, INVALID_LINE, INVALID_FILL);
			return;
		}
		Identifier seed = RtsItemPickClient.picked().orElse(null);
		long now = level.getGameTime();
		if (!area.equals(cachedArea) || !Objects.equals(seed, cachedSeed) || now - cachedAt >= REFRESH_TICKS
				|| now < cachedAt) {
			marks = scan(level, field, seed);
			cachedArea = area;
			cachedSeed = seed;
			cachedAt = now;
		}
		for (Mark mark : marks) {
			WorldOverlay.face(ctx, mark.pos(), Direction.UP, mark.argb());
		}
	}

	private static List<Mark> scan(ClientLevel level, RtsField field, @Nullable Identifier seedId) {
		Item seed = seedId == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(seedId).orElse(Items.AIR);
		List<Mark> result = new ArrayList<>();
		for (int[] column : field.columnOrder()) {
			BlockPos ground = FieldBlocks.surface(level, field, column[0], column[1]);
			if (ground == null) {
				continue;
			}
			FieldBlocks.Plan plan = FieldBlocks.plan(level, ground, seed);
			int argb = switch (plan.action()) {
				case TILL -> TILL;
				case PLANT -> plan.clearFirst() ? TILL : PLANT;
				case SKIP -> SKIP;
			};
			result.add(new Mark(ground, argb));
		}
		return result;
	}
}
