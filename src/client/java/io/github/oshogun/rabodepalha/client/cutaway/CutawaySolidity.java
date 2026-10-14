package io.github.oshogun.rabodepalha.client.cutaway;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import io.github.oshogun.rabodepalha.rts.CutawayRooms;

/**
 * Room detection's view of the real level, not the cut one. Walls and floors are the blocks the
 * no-leaves motion heightmap counts; any non-replaceable block covers the cells below it, so leaves and
 * glass panes make a ceiling. Main thread only: it reuses one mutable position.
 */
public final class CutawaySolidity implements CutawayRooms.Solidity {
	private final BlockGetter level;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	public CutawaySolidity(BlockGetter level) {
		this.level = level;
	}

	@Override
	public boolean solid(int x, int y, int z) {
		BlockState state = this.level.getBlockState(this.pos.set(x, y, z));
		return !state.isAir() && state.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP_NO_LEAVES);
	}

	@Override
	public boolean ceiling(int x, int y, int z) {
		BlockState state = this.level.getBlockState(this.pos.set(x, y, z));
		return !state.isAir() && !state.canBeReplaced();
	}
}
