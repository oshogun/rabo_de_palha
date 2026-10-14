package io.github.oshogun.rabodepalha.client.rts;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import io.github.oshogun.rabodepalha.client.render.WorldOverlay;
import io.github.oshogun.rabodepalha.rts.QueuedOrderMarkers;
import io.github.oshogun.rabodepalha.rts.RtsOrderColors;
import io.github.oshogun.rabodepalha.rts.WorkerSnapshot;

/**
 * Draws the selected workers' current and queued orders in RTS mode, from their latest snapshots: the
 * markers {@link QueuedOrderMarkers} builds, and lines from each worker through its targets in queue order.
 * The current task is filled; queued ones are outlined in a dimmed colour. Nothing is cached, so a finished
 * task's marker goes when the next snapshot arrives. Only watched workers have snapshots, so at most the
 * first {@link io.github.oshogun.rabodepalha.network.RtsWatchPayload#MAX_WATCHED} selected workers show
 * markers.
 */
public final class RtsQueuedOrdersOverlay {
	private RtsQueuedOrdersOverlay() {
	}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(RtsQueuedOrdersOverlay::submit);
	}

	private static void submit(LevelRenderContext ctx) {
		if (!RtsClient.isActive() || RtsSelectionClient.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null) {
			return;
		}
		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		List<QueuedOrderMarkers.Worker> workers = new ArrayList<>();
		for (int id : RtsSelectionClient.selectedIds()) {
			WorkerSnapshot snapshot = RtsSnapshotsClient.get(id);
			Entity entity = level.getEntity(id);
			if (snapshot == null || entity == null) {
				continue;
			}
			// Lines start at the worker's middle, where it is drawn this frame.
			Vec3 position = entity.getPosition(partialTick).add(0, entity.getBbHeight() / 2, 0);
			workers.add(new QueuedOrderMarkers.Worker(position, snapshot));
		}
		if (workers.isEmpty()) {
			return;
		}
		Vec3 camera = ctx.levelState().cameraRenderState.pos;
		QueuedOrderMarkers.Markers markers = QueuedOrderMarkers.build(workers, camera, QueuedOrderMarkers.Limits.DEFAULT);
		for (QueuedOrderMarkers.Marker marker : markers.markers()) {
			int fill = marker.current() ? RtsOrderColors.fill(marker.kind()) : 0;
			switch (marker.shape()) {
				case COLUMN, BOX -> WorldOverlay.box(ctx, marker.bounds(), marker.argb(), fill);
				case BLOCK -> {
					if (marker.current()) {
						WorldOverlay.box(ctx, marker.bounds(), marker.argb(), fill);
					} else {
						WorldOverlay.outlineBlock(ctx, BlockPos.containing(marker.anchor()), marker.argb());
					}
				}
			}
		}
		List<WorldOverlay.Line> lines = new ArrayList<>(markers.segments().size());
		for (QueuedOrderMarkers.Segment segment : markers.segments()) {
			lines.add(new WorldOverlay.Line(segment.from(), segment.to(), segment.argb()));
		}
		WorldOverlay.lines(ctx, lines);
	}
}
