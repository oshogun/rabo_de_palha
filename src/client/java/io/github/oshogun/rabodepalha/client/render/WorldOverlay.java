package io.github.oshogun.rabodepalha.client.render;

import java.util.List;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Draws simple world-space markers: block outlines, a highlighted block face, axis-aligned boxes and
 * line segments. Every call takes its own ARGB colour.
 *
 * <p>Call these methods only from a {@code LevelRenderEvents.COLLECT_SUBMITS} listener, passing that
 * listener's context. The geometry is queued on {@link LevelRenderContext#submitNodeCollector()} and drawn
 * in the same frame. Nothing is retained, so submit again every frame for as long as the marker should
 * show. Later features (dig area, blueprint placement, queued-order markers) register their own listener:
 *
 * <pre>{@code
 * LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
 *     if (area != null) {
 *         WorldOverlay.box(ctx, area, 0xFFFFFFFF, 0x30FFFFFF);
 *     }
 * });
 * }</pre>
 *
 * <p>Coordinates are in world space; each call translates them relative to the camera. Do not draw
 * from the terrain-stage events ({@code AFTER_OPAQUE_TERRAIN}, {@code BEFORE_TRANSLUCENT_TERRAIN},
 * {@code AFTER_TRANSLUCENT_TERRAIN}): Sodium replaces terrain rendering and may not run them as vanilla
 * does. {@code COLLECT_SUBMITS} runs while entities are submitted, outside the terrain path.
 */
public final class WorldOverlay {
	/** How far a face highlight sits outside the block, in blocks, to avoid z-fighting. */
	private static final double FACE_OFFSET = 0.002;

	/** A line segment in world coordinates. */
	public record Line(Vec3 from, Vec3 to, int argb) {
	}

	private WorldOverlay() {
	}

	/**
	 * Outlines a voxel shape, given relative to {@code pos}, the way vanilla outlines the crosshair block.
	 */
	public static void outlineShape(LevelRenderContext ctx, BlockPos pos, VoxelShape shape, int argb) {
		PoseStack poseStack = ctx.poseStack();
		poseStack.pushPose();
		translate(ctx, poseStack, pos.getX(), pos.getY(), pos.getZ());
		submitOutline(ctx, poseStack, shape, argb);
		poseStack.popPose();
	}

	/** Outlines the block at {@code pos} using its shape, or a full cube when the shape is empty. */
	public static void outlineBlock(LevelRenderContext ctx, BlockPos pos, int argb) {
		VoxelShape shape = Shapes.block();
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			VoxelShape blockShape = level.getBlockState(pos).getShape(level, pos, CollisionContext.empty());
			if (!blockShape.isEmpty()) {
				shape = blockShape;
			}
		}
		outlineShape(ctx, pos, shape, argb);
	}

	/**
	 * Fills one face of the full cube at {@code pos} with a translucent quad, drawn slightly outside the
	 * block and visible from both sides.
	 */
	public static void face(LevelRenderContext ctx, BlockPos pos, Direction face, int argb) {
		PoseStack poseStack = ctx.poseStack();
		poseStack.pushPose();
		translate(ctx, poseStack, pos.getX(), pos.getY(), pos.getZ());
		AABB cube = new AABB(0, 0, 0, 1, 1, 1).inflate(FACE_OFFSET);
		submitQuads(ctx, poseStack, RenderTypes.debugQuads(), cube, new Direction[] {face}, argb);
		poseStack.popPose();
	}

	/**
	 * Draws a box in world coordinates: a wireframe in {@code lineArgb} and a translucent fill in
	 * {@code fillArgb}. A fill with zero alpha is skipped. The fill is culled, so it is not visible from
	 * inside the box.
	 */
	public static void box(LevelRenderContext ctx, AABB box, int lineArgb, int fillArgb) {
		PoseStack poseStack = ctx.poseStack();
		poseStack.pushPose();
		// Translate to the box corner so the vertices stay small and precise far from the origin.
		translate(ctx, poseStack, box.minX, box.minY, box.minZ);
		AABB local = box.move(-box.minX, -box.minY, -box.minZ);
		submitOutline(ctx, poseStack, Shapes.create(local), lineArgb);
		if (ARGB.alpha(fillArgb) != 0) {
			submitQuads(ctx, poseStack, RenderTypes.debugFilledBox(), local, Direction.values(), fillArgb);
		}
		poseStack.popPose();
	}

	/** Draws a line segment in world coordinates. A zero-length or fully transparent line is skipped. */
	public static void line(LevelRenderContext ctx, Vec3 from, Vec3 to, int argb) {
		lines(ctx, List.of(new Line(from, to, argb)));
	}

	/**
	 * Draws many line segments in one submission, so a long chain costs no more submissions than one line.
	 * Zero-length and fully transparent lines are skipped.
	 */
	public static void lines(LevelRenderContext ctx, List<Line> lines) {
		if (lines.stream().noneMatch(WorldOverlay::visible)) {
			return;
		}
		// Vertices are relative to the camera, computed in doubles, so they stay precise far from the origin.
		Vec3 camera = ctx.levelState().cameraRenderState.pos;
		float width = ctx.gameRenderer().gameRenderState().windowRenderState.appropriateLineWidth;
		PoseStack poseStack = ctx.poseStack();
		poseStack.pushPose();
		ctx.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.linesTranslucent(), (pose, buffer) -> {
			Vector3f normal = new Vector3f();
			for (Line line : lines) {
				if (!visible(line)) {
					continue;
				}
				Vec3 a = line.from().subtract(camera);
				Vec3 b = line.to().subtract(camera);
				// The same vertex layout as vanilla's shape outlines on this render type.
				normal.set((float) (b.x - a.x), (float) (b.y - a.y), (float) (b.z - a.z)).normalize();
				buffer.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(line.argb()).setNormal(pose, normal)
						.setLineWidth(width);
				buffer.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(line.argb()).setNormal(pose, normal)
						.setLineWidth(width);
			}
		});
		poseStack.popPose();
	}

	private static boolean visible(Line line) {
		return ARGB.alpha(line.argb()) != 0 && line.from().distanceToSqr(line.to()) > 1.0E-8;
	}

	private static void translate(LevelRenderContext ctx, PoseStack poseStack, double x, double y, double z) {
		Vec3 camera = ctx.levelState().cameraRenderState.pos;
		poseStack.translate(x - camera.x, y - camera.y, z - camera.z);
	}

	private static void submitOutline(LevelRenderContext ctx, PoseStack poseStack, VoxelShape shape, int argb) {
		float width = ctx.gameRenderer().gameRenderState().windowRenderState.appropriateLineWidth;
		ctx.submitNodeCollector().submitShapeOutline(poseStack, shape, RenderTypes.linesTranslucent(), argb, width,
				false);
	}

	/** Submits one quad per face of {@code box}, each wound counter-clockwise seen from outside. */
	private static void submitQuads(LevelRenderContext ctx, PoseStack poseStack, RenderType renderType, AABB box,
			Direction[] faces, int argb) {
		float x0 = (float) box.minX;
		float y0 = (float) box.minY;
		float z0 = (float) box.minZ;
		float x1 = (float) box.maxX;
		float y1 = (float) box.maxY;
		float z1 = (float) box.maxZ;
		ctx.submitNodeCollector().submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
			for (Direction face : faces) {
				switch (face) {
					case DOWN -> quad(buffer, pose, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
					case UP -> quad(buffer, pose, argb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
					case NORTH -> quad(buffer, pose, argb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
					case SOUTH -> quad(buffer, pose, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
					case WEST -> quad(buffer, pose, argb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
					case EAST -> quad(buffer, pose, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
				}
			}
		});
	}

	private static void quad(VertexConsumer buffer, PoseStack.Pose pose, int argb, float ax, float ay, float az,
			float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
		buffer.addVertex(pose, ax, ay, az).setColor(argb);
		buffer.addVertex(pose, bx, by, bz).setColor(argb);
		buffer.addVertex(pose, cx, cy, cz).setColor(argb);
		buffer.addVertex(pose, dx, dy, dz).setColor(argb);
	}
}
