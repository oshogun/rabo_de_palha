package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlueprintFootprintTest {
	private static final BlockPos ORIGIN = new BlockPos(10, 64, -7);
	private static final List<Vec3i> SIZES = List.of(new Vec3i(3, 2, 5), new Vec3i(1, 1, 1), new Vec3i(4, 3, 2));

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void boxCoversEveryTransformedBlock() {
		for (Rotation rotation : Rotation.values()) {
			for (Vec3i size : SIZES) {
				int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
				int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
				for (BlockPos local : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
					BlockPos world = BlueprintFootprint.toWorld(ORIGIN, local, rotation);
					assertEquals(ORIGIN.offset(StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO)), world);
					minX = Math.min(minX, world.getX());
					minY = Math.min(minY, world.getY());
					minZ = Math.min(minZ, world.getZ());
					maxX = Math.max(maxX, world.getX());
					maxY = Math.max(maxY, world.getY());
					maxZ = Math.max(maxZ, world.getZ());
				}
				assertEquals(new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ),
						BlueprintFootprint.box(ORIGIN, size, rotation), rotation + " " + size);
			}
		}
	}

	@Test
	void clockwise90ExtendsTowardNegativeX() {
		BoundingBox box = BlueprintFootprint.box(ORIGIN, new Vec3i(3, 2, 5), Rotation.CLOCKWISE_90);

		assertEquals(new BoundingBox(ORIGIN.getX() - 4, ORIGIN.getY(), ORIGIN.getZ(), ORIGIN.getX(), ORIGIN.getY() + 1, ORIGIN.getZ() + 2), box);
	}

	@Test
	void emptySizeIsOneBlock() {
		assertEquals(BoundingBox.fromCorners(ORIGIN, ORIGIN), BlueprintFootprint.box(ORIGIN, Vec3i.ZERO, Rotation.CLOCKWISE_180));
	}

	@Test
	void originForKeepsTheMinCornerOnTheAnchor() {
		for (Rotation rotation : Rotation.values()) {
			for (Vec3i size : SIZES) {
				BoundingBox box = BlueprintFootprint.box(BlueprintFootprint.originFor(ORIGIN, size, rotation), size, rotation);
				String label = rotation + " " + size;
				assertEquals(ORIGIN.getX(), box.minX(), label);
				assertEquals(ORIGIN.getY(), box.minY(), label);
				assertEquals(ORIGIN.getZ(), box.minZ(), label);
				boolean quarter = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
				assertEquals(quarter ? size.getZ() : size.getX(), box.getXSpan(), label);
				assertEquals(size.getY(), box.getYSpan(), label);
				assertEquals(quarter ? size.getX() : size.getZ(), box.getZSpan(), label);
			}
		}
	}

	@Test
	void nextCyclesThroughAllRotations() {
		assertEquals(Rotation.CLOCKWISE_90, BlueprintFootprint.next(Rotation.NONE));
		assertEquals(Rotation.CLOCKWISE_180, BlueprintFootprint.next(Rotation.CLOCKWISE_90));
		assertEquals(Rotation.COUNTERCLOCKWISE_90, BlueprintFootprint.next(Rotation.CLOCKWISE_180));
		assertEquals(Rotation.NONE, BlueprintFootprint.next(Rotation.COUNTERCLOCKWISE_90));
	}
}
