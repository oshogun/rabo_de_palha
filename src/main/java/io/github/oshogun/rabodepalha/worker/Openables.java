package io.github.oshogun.rabodepalha.worker;

import io.github.oshogun.rabodepalha.mixin.FenceGateBlockAccessor;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Which blocks a worker opens on its way, and how it opens and closes them.
 *
 * <p>Doors that open by hand ({@link DoorBlock#isWoodenDoor}) and fence gates count; iron doors and
 * trapdoors don't. Not gated by {@code mobGriefing}: workers act for the player who ordered them.
 */
public final class Openables {
	private Openables() {
	}

	public static boolean isOpenableDoor(BlockState state) {
		return DoorBlock.isWoodenDoor(state);
	}

	public static boolean isOpenableGate(BlockState state) {
		return state.getBlock() instanceof FenceGateBlock;
	}

	public static boolean isOpenable(BlockState state) {
		return isOpenableDoor(state) || isOpenableGate(state);
	}

	public static boolean isClosedOpenable(BlockState state) {
		return isOpenable(state) && !state.getValue(BlockStateProperties.OPEN);
	}

	/** The position one door is tracked under: its lower half. Gates and lower halves map to themselves. */
	public static BlockPos canonicalPos(BlockState state, BlockPos pos) {
		if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
			return pos.below();
		}
		return pos;
	}

	/**
	 * Opens or closes the door or gate at {@code pos} with its sound, game event and an arm swing.
	 * Does nothing when the block there is not openable.
	 *
	 * <p>A gate keeps its facing: an open gate is passable from both sides, and
	 * {@link FenceGateBlock#useWithoutItem} would read a player's direction to turn it.
	 */
	public static void open(Level level, WorkerEntity worker, BlockPos pos, boolean open) {
		BlockState state = level.getBlockState(pos);
		if (isOpenableDoor(state)) {
			((DoorBlock) state.getBlock()).setOpen(worker, level, state, pos, open);
		} else if (isOpenableGate(state)) {
			if (state.getValue(FenceGateBlock.OPEN) != open) {
				level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, open), 10);
				WoodType type = ((FenceGateBlockAccessor) state.getBlock()).rabodepalha$type();
				level.playSound(null, pos, open ? type.fenceGateOpen() : type.fenceGateClose(), SoundSource.BLOCKS,
						1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
				level.gameEvent(worker, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
			}
		} else {
			return;
		}
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
	}
}
