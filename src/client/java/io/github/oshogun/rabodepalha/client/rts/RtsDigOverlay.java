package io.github.oshogun.rabodepalha.client.rts;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.world.phys.AABB;

import io.github.oshogun.rabodepalha.client.render.WorldOverlay;
import io.github.oshogun.rabodepalha.rts.RtsDigArea;

/**
 * Draws the area being marked for Dig in RTS mode, while dragging and in the depth step: blue when the
 * area can be ordered, red when {@link RtsOrdersClient#digProblem} finds a problem with it.
 */
public final class RtsDigOverlay {
	private static final int VALID_LINE = 0xFF4AA8FF;
	private static final int VALID_FILL = 0x334AA8FF;
	private static final int INVALID_LINE = 0xFFFF4A4A;
	private static final int INVALID_FILL = 0x33FF4A4A;

	private RtsDigOverlay() {
	}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(RtsDigOverlay::submit);
	}

	private static void submit(LevelRenderContext ctx) {
		if (!RtsClient.isActive()) {
			return;
		}
		RtsDigArea area = RtsCommandClient.digArea();
		if (area == null) {
			return;
		}
		// Unlike the hover highlight, the area stays drawn while the cursor is over the command bar.
		boolean valid = RtsOrdersClient.digProblem(area) == null;
		AABB box = new AABB(area.minX(), area.minY(), area.minZ(), area.maxX() + 1, area.maxY() + 1, area.maxZ() + 1);
		WorldOverlay.box(ctx, box, valid ? VALID_LINE : INVALID_LINE, valid ? VALID_FILL : INVALID_FILL);
	}
}
