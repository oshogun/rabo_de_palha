package io.github.oshogun.rabodepalha.client.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;

import org.jspecify.annotations.Nullable;

import io.github.oshogun.rabodepalha.client.cutaway.CutawaySolidity;
import io.github.oshogun.rabodepalha.rts.CutawayRooms;
import io.github.oshogun.rabodepalha.rts.CutawayShape;
import io.github.oshogun.rabodepalha.rts.RtsCutawayFocus;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

/**
 * The local cutaways of the RTS camera (#29), each switched on and off with its own key: the see-through
 * bubble, roofs off over the room under the cursor or the focused worker, and the walls of that room that
 * face the camera. Each tick {@link #shapes} turns them into {@link CutawayShape}s for {@link RtsClient} to
 * union with the view level. The cursor and the focused worker (the first selected) each get their own
 * shapes. Anchors, the facing and rooms are debounced, so the shapes, and the re-meshing, change rarely.
 */
public final class RtsCutawayModes {
	/** How often, in game ticks, a cached room is detected again, so block edits show up. */
	private static final int ROOM_REFRESH_TICKS = 20;

	private static boolean bubble;
	private static boolean roofs = true;
	private static boolean walls;

	private static RtsCutawayFocus.@Nullable Anchor cursorAnchor;
	private static RtsCutawayFocus.@Nullable Anchor workerAnchor;
	private static int facing = -1;
	private static final RoomTracker CURSOR_ROOM = new RoomTracker();
	private static final RoomTracker WORKER_ROOM = new RoomTracker();
	private static long lastRefresh = Long.MIN_VALUE;

	private RtsCutawayModes() {
	}

	public static boolean bubble() {
		return bubble;
	}

	public static boolean roofs() {
		return roofs;
	}

	public static boolean walls() {
		return walls;
	}

	public static boolean toggleBubble() {
		bubble = !bubble;
		cursorAnchor = null;
		workerAnchor = null;
		return bubble;
	}

	public static boolean toggleRoofs() {
		roofs = !roofs;
		return roofs;
	}

	public static boolean toggleWalls() {
		walls = !walls;
		return walls;
	}

	/** Drops the anchors and cached rooms, as when RTS mode starts or stops. */
	public static void clearState() {
		cursorAnchor = null;
		workerAnchor = null;
		facing = -1;
		CURSOR_ROOM.clear();
		WORKER_ROOM.clear();
		lastRefresh = Long.MIN_VALUE;
	}

	/** Back to the defaults, for a new session: roofs on, bubble and walls off. */
	public static void reset() {
		bubble = false;
		roofs = true;
		walls = false;
		clearState();
	}

	/** This tick's local cut shapes; empty when every mode is off or nothing is focused. Once per tick. */
	public static List<CutawayShape> shapes(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null || (!bubble && !roofs && !walls)) {
			clearState();
			return List.of();
		}
		Camera camera = client.gameRenderer.mainCamera();
		facing = RtsCutawayFocus.quantizeYaw(facing, camera.yRot());
		long now = level.getGameTime();
		boolean refresh = now - lastRefresh >= ROOM_REFRESH_TICKS || now < lastRefresh;
		if (refresh) {
			lastRefresh = now;
		}

		RtsSelectionClient.Hover hover = RtsSelectionClient.hover();
		WorkerEntity hoveredWorker = hover == null ? null : hover.worker();
		BlockHitResult hoveredBlock = hover == null ? null : hover.block();
		List<WorkerEntity> selected = RtsSelectionClient.selectedWorkers();
		WorkerEntity focus = selected.isEmpty() ? null : selected.get(0);

		Set<CutawayShape> shapes = new LinkedHashSet<>();
		if (bubble) {
			double slope = RtsCutawayFocus.quantizeSlope(camera.xRot());
			if (hoveredWorker != null) {
				cursorAnchor = RtsCutawayFocus.anchor(cursorAnchor, Mth.floor(hoveredWorker.getX()),
						Mth.floor(hoveredWorker.getY()), Mth.floor(hoveredWorker.getZ()));
			} else if (hoveredBlock != null) {
				// The bubble starts in the cell above the hovered block, so that block stays drawn and clickable.
				BlockPos pos = hoveredBlock.getBlockPos();
				cursorAnchor = RtsCutawayFocus.anchor(cursorAnchor, pos.getX(), pos.getY() + 1, pos.getZ());
			}
			workerAnchor = focus == null ? null
					: RtsCutawayFocus.anchor(workerAnchor, Mth.floor(focus.getX()), Mth.floor(focus.getY()),
							Mth.floor(focus.getZ()));
			if (cursorAnchor != null) {
				shapes.add(RtsCutawayFocus.bubbleFor(cursorAnchor, facing, slope));
			}
			if (workerAnchor != null) {
				shapes.add(RtsCutawayFocus.bubbleFor(workerAnchor, facing, slope));
			}
		}
		if (roofs || walls) {
			CutawaySolidity world = new CutawaySolidity(level);
			if (hoveredWorker != null) {
				CURSOR_ROOM.update(world, feetCell(hoveredWorker), refresh, null);
			} else if (hoveredBlock != null) {
				CURSOR_ROOM.update(world, null, refresh, hoveredBlock);
			}
			WORKER_ROOM.update(world, focus == null ? null : feetCell(focus), refresh, null);
			for (CutawayRooms.Room room : new CutawayRooms.Room[] {CURSOR_ROOM.room, WORKER_ROOM.room}) {
				if (room == null) {
					continue;
				}
				if (roofs) {
					shapes.add(CutawayRooms.roofBox(room));
				}
				if (walls) {
					shapes.addAll(CutawayRooms.nearWalls(room, facing));
				}
			}
		}
		return new ArrayList<>(shapes);
	}

	private static CutawayRooms.Cell feetCell(Entity entity) {
		return new CutawayRooms.Cell(Mth.floor(entity.getX()), Mth.floor(entity.getY()), Mth.floor(entity.getZ()));
	}

	/** One focus's room, detected again only when its start cell changes or on the periodic refresh. */
	private static final class RoomTracker {
		private CutawayRooms.@Nullable Cell start;
		private CutawayRooms.@Nullable Room room;
		private boolean known;

		void clear() {
			start = null;
			room = null;
			known = false;
		}

		/**
		 * Follows the room at {@code feet}, or, when it is null, under the hovered block {@code hit}. A hovered
		 * block still near the current room keeps it, so the cursor dropping through the hidden roof onto the
		 * floor doesn't switch rooms.
		 */
		void update(CutawayRooms.Solidity world, CutawayRooms.@Nullable Cell feet, boolean refresh,
				@Nullable BlockHitResult hit) {
			if (hit != null && room != null && !refresh) {
				BlockPos pos = hit.getBlockPos();
				if (CutawayRooms.near(room, pos.getX(), pos.getY(), pos.getZ())) {
					return;
				}
			}
			CutawayRooms.Cell next = feet;
			if (hit != null) {
				BlockPos pos = hit.getBlockPos();
				Direction face = hit.getDirection();
				next = CutawayRooms.start(world, pos.getX(), pos.getY(), pos.getZ(), face.getStepX(), face.getStepY(),
						face.getStepZ());
				if (next == null && room != null && CutawayRooms.near(room, pos.getX(), pos.getY(), pos.getZ())) {
					// On the periodic refresh the cursor may sit on a wall stub with no start cell: keep the room.
					next = start;
				}
			}
			if (known && !refresh && Objects.equals(next, start)) {
				return;
			}
			start = next;
			room = next == null ? null : CutawayRooms.detect(world, next.x(), next.y(), next.z());
			known = true;
		}
	}
}
