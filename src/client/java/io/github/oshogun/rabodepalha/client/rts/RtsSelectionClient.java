package io.github.oshogun.rabodepalha.client.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.jspecify.annotations.Nullable;

import io.github.oshogun.rabodepalha.rts.RtsPickMath;
import io.github.oshogun.rabodepalha.rts.RtsScreenRect;
import io.github.oshogun.rabodepalha.rts.RtsSelection;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

/**
 * Client-side worker selection in RTS mode. Left-click picks the worker under the cursor, dragging
 * selects every worker inside the box, and shift adds to the selection. Picking casts a ray from the
 * cursor through the RTS camera, not from the crosshair.
 *
 * <p>Orders read the selection through {@link #selectedIds()}, {@link #selectedWorkers()} and
 * {@link #count()}. Cursor positions are window pixels, as in {@code MouseHandler.xpos()}.
 */
public final class RtsSelectionClient {
	/** How far, in blocks, a click or box can select workers from the camera. */
	public static final double PICK_RANGE = 128.0;
	/** Extra margin around a worker's bounding box for click picking, in blocks. */
	private static final double PICK_INFLATE = 0.1;

	private static final RtsSelection SELECTION = new RtsSelection();

	private static boolean dragging;
	private static double dragStartX;
	private static double dragStartY;
	private static double dragX;
	private static double dragY;

	private RtsSelectionClient() {
	}

	/** The selected entity ids, in selection order. */
	public static List<Integer> selectedIds() {
		return SELECTION.ids();
	}

	/** The selected workers that still exist in the client level. */
	public static List<WorkerEntity> selectedWorkers() {
		ClientLevel level = Minecraft.getInstance().level;
		List<WorkerEntity> workers = new ArrayList<>();
		if (level == null) {
			return workers;
		}
		for (int id : SELECTION.ids()) {
			WorkerEntity worker = liveWorker(level, id);
			if (worker != null) {
				workers.add(worker);
			}
		}
		return workers;
	}

	public static boolean isSelected(Entity entity) {
		return entity instanceof WorkerEntity && SELECTION.contains(entity.getId());
	}

	public static int count() {
		return SELECTION.size();
	}

	public static boolean isEmpty() {
		return SELECTION.isEmpty();
	}

	/** Clears the selection and drops any drag in progress. */
	public static void clear() {
		SELECTION.clear();
		cancelDrag();
	}

	public static boolean isDragging() {
		return dragging;
	}

	public static void cancelDrag() {
		dragging = false;
	}

	/** The box being dragged, in window pixels, or {@code null} while the press is still a click. */
	public static @Nullable RtsScreenRect currentDragRect() {
		if (!dragging || !RtsScreenRect.isDrag(dragStartX, dragStartY, dragX, dragY)) {
			return null;
		}
		return RtsScreenRect.fromCorners(dragStartX, dragStartY, dragX, dragY);
	}

	public static void onLeftPress(double x, double y) {
		dragging = true;
		dragStartX = x;
		dragStartY = y;
		dragX = x;
		dragY = y;
	}

	public static void onDragMove(double x, double y) {
		if (dragging) {
			dragX = x;
			dragY = y;
		}
	}

	/** Ends the press: a box select if the cursor moved past the drag threshold, a click pick otherwise. */
	public static void onLeftRelease(double x, double y, boolean shift) {
		if (!dragging) {
			return;
		}
		dragging = false;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		if (RtsScreenRect.isDrag(dragStartX, dragStartY, x, y)) {
			SELECTION.boxSelect(boxPick(client, RtsScreenRect.fromCorners(dragStartX, dragStartY, x, y)), shift);
		} else {
			SELECTION.clickSelect(clickPick(client, x, y), shift);
		}
	}

	/** Prunes workers that are gone and drops a drag that can no longer finish. Runs every client tick. */
	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			clear();
			return;
		}
		if (!RtsClient.isActive() || client.gui.screen() != null) {
			cancelDrag();
		}
		SELECTION.retainIf(id -> liveWorker(level, id) != null);
	}

	private static @Nullable WorkerEntity liveWorker(ClientLevel level, int id) {
		return level.getEntity(id) instanceof WorkerEntity worker && worker.isAlive() && !worker.isRemoved()
				? worker
				: null;
	}

	/** The cursor ray through the RTS camera, from the camera position out to {@link #PICK_RANGE}. */
	private record Ray(Vec3 start, Vec3 end) {
	}

	private static Ray cursorRay(Minecraft client, double x, double y) {
		Camera camera = client.gameRenderer.mainCamera();
		Window window = client.getWindow();
		double[] dir = RtsPickMath.cursorRayDir(x, y, window.getScreenWidth(), window.getScreenHeight(),
				camera.getFov(), camera.yRot(), camera.xRot());
		Vec3 start = camera.position();
		return new Ray(start, start.add(dir[0] * PICK_RANGE, dir[1] * PICK_RANGE, dir[2] * PICK_RANGE));
	}

	private static @Nullable BlockHitResult clipBlock(ClientLevel level, Ray ray) {
		BlockHitResult hit = level.clip(new ClipContext(ray.start(), ray.end(), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS ? null : hit;
	}

	/** The block under the cursor within {@link #PICK_RANGE}, or {@code null} if the ray hits nothing. */
	static @Nullable BlockHitResult pickBlock(double x, double y) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return null;
		}
		return clipBlock(client.level, cursorRay(client, x, y));
	}

	/** The id of the nearest worker under the cursor that is not hidden behind blocks, or {@code null}. */
	private static @Nullable Integer clickPick(Minecraft client, double x, double y) {
		Ray ray = cursorRay(client, x, y);
		Vec3 start = ray.start();
		Vec3 end = ray.end();

		BlockHitResult blockHit = clipBlock(client.level, ray);
		double maxDistSqr = blockHit == null
				? PICK_RANGE * PICK_RANGE
				: blockHit.getLocation().distanceToSqr(start);

		Integer best = null;
		double bestDistSqr = maxDistSqr;
		for (WorkerEntity worker : client.level.getEntitiesOfClass(WorkerEntity.class, new AABB(start, end).inflate(1.0),
				Entity::isAlive)) {
			Optional<Vec3> hit = worker.getBoundingBox().inflate(PICK_INFLATE).clip(start, end);
			if (hit.isPresent()) {
				double distSqr = hit.get().distanceToSqr(start);
				if (distSqr < bestDistSqr) {
					bestDistSqr = distSqr;
					best = worker.getId();
				}
			}
		}
		return best;
	}

	/** The ids of the workers whose bounding-box centre projects inside {@code rect}; blocks are ignored. */
	private static List<Integer> boxPick(Minecraft client, RtsScreenRect rect) {
		Camera camera = client.gameRenderer.mainCamera();
		Window window = client.getWindow();
		Vec3 eye = camera.position();
		List<Integer> hits = new ArrayList<>();
		for (WorkerEntity worker : client.level.getEntitiesOfClass(WorkerEntity.class,
				AABB.ofSize(eye, PICK_RANGE * 2, PICK_RANGE * 2, PICK_RANGE * 2), Entity::isAlive)) {
			Vec3 centre = worker.getBoundingBox().getCenter().subtract(eye);
			RtsPickMath.Projected p = RtsPickMath.project(centre.x, centre.y, centre.z, window.getScreenWidth(),
					window.getScreenHeight(), camera.getFov(), camera.yRot(), camera.xRot());
			if (p != null && rect.contains(p.x(), p.y())) {
				hits.add(worker.getId());
			}
		}
		return hits;
	}
}
