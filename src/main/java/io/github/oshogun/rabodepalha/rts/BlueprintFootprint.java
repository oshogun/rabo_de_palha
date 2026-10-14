package io.github.oshogun.rabodepalha.rts;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Where a blueprint lands in the world: the same transform {@link io.github.oshogun.rabodepalha.worker.task.BuildTask}
 * places blocks with. The template turns around its local {@code (0,0,0)} corner, which sits on the
 * origin, so with a rotation the box can extend on the negative side of the origin. Rotation never
 * moves Y. It needs no world, so it can be unit-tested.
 */
public final class BlueprintFootprint {
	private BlueprintFootprint() {
	}

	/** The world position of the template's {@code local} position, built at {@code origin} turned by {@code rotation}. */
	public static BlockPos toWorld(BlockPos origin, BlockPos local, Rotation rotation) {
		return origin.offset(StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO));
	}

	/**
	 * The blocks a template of {@code size} covers when built at {@code origin} turned by {@code rotation}.
	 * Each size component is clamped to at least 1, so an empty template still gives a single block.
	 */
	public static BoundingBox box(BlockPos origin, Vec3i size, Rotation rotation) {
		BlockPos far = new BlockPos(Math.max(1, size.getX()) - 1, Math.max(1, size.getY()) - 1, Math.max(1, size.getZ()) - 1);
		BlockPos a = toWorld(origin, BlockPos.ZERO, rotation);
		BlockPos b = toWorld(origin, far, rotation);
		return BoundingBox.fromCorners(a, b);
	}

	/** The next rotation clockwise: 0, 90, 180, 270, then 0 again. */
	public static Rotation next(Rotation rotation) {
		return switch (rotation) {
			case NONE -> Rotation.CLOCKWISE_90;
			case CLOCKWISE_90 -> Rotation.CLOCKWISE_180;
			case CLOCKWISE_180 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.NONE;
		};
	}

	/**
	 * The origin to build at so the turned box's minimum corner on X and Z sits on {@code anchor}, at
	 * {@code anchor}'s Y, whatever the rotation. This keeps a preview under the cursor while rotating.
	 */
	public static BlockPos originFor(BlockPos anchor, Vec3i size, Rotation rotation) {
		BoundingBox box0 = box(BlockPos.ZERO, size, rotation);
		return anchor.offset(-box0.minX(), 0, -box0.minZ());
	}
}
