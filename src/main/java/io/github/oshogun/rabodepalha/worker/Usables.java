package io.github.oshogun.rabodepalha.worker;

import java.util.Optional;

import io.github.oshogun.rabodepalha.mixin.TrapDoorBlockAccessor;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluids;

/**
 * Which blocks a worker can use, and what using them does: a lever toggles, a button presses, and a door,
 * trapdoor or fence gate that opens by hand toggles open or closed. Iron doors and iron trapdoors don't count.
 * The classification reads only the block state, so the client can use it for the RTS hint.
 */
public final class Usables {
	private Usables() {
	}

	public enum Kind {
		LEVER,
		BUTTON,
		DOOR,
		TRAPDOOR,
		GATE
	}

	/** What using a block did. */
	public enum Result {
		USED,
		/** A button that was already pressed; it is left alone. */
		ALREADY_PRESSED,
		NOT_USABLE
	}

	/** What kind of usable block {@code state} is, or empty when a worker can't use it. */
	public static Optional<Kind> kind(BlockState state) {
		if (state.getBlock() instanceof LeverBlock) {
			return Optional.of(Kind.LEVER);
		}
		if (state.getBlock() instanceof ButtonBlock) {
			return Optional.of(Kind.BUTTON);
		}
		if (Openables.isOpenableDoor(state)) {
			return Optional.of(Kind.DOOR);
		}
		if (state.getBlock() instanceof TrapDoorBlock trapdoor && trapdoorType(trapdoor).canOpenByHand()) {
			return Optional.of(Kind.TRAPDOOR);
		}
		if (Openables.isOpenableGate(state)) {
			return Optional.of(Kind.GATE);
		}
		return Optional.empty();
	}

	public static boolean isUsable(BlockState state) {
		return kind(state).isPresent();
	}

	/** Levers and buttons, which a plain right-click in RTS mode uses instead of moving onto. */
	public static boolean isSwitch(BlockState state) {
		return kind(state).filter(kind -> kind == Kind.LEVER || kind == Kind.BUTTON).isPresent();
	}

	/** Where to use the block at {@code pos}: a door's lower half, otherwise {@code pos} itself. */
	public static BlockPos canonicalPos(BlockGetter level, BlockPos pos) {
		return Openables.canonicalPos(level.getBlockState(pos), pos).immutable();
	}

	/**
	 * Uses the block at {@code pos} as {@code worker}, with its sound and game event. Does not swing the
	 * worker's arm. A door is used through its lower half.
	 */
	public static Result use(Level level, WorkerEntity worker, BlockPos pos) {
		BlockPos at = canonicalPos(level, pos);
		BlockState state = level.getBlockState(at);
		Optional<Kind> kind = kind(state);
		if (kind.isEmpty()) {
			return Result.NOT_USABLE;
		}
		switch (kind.get()) {
			case LEVER -> ((LeverBlock) state.getBlock()).pull(state, level, at, null);
			case BUTTON -> {
				if (state.getValue(ButtonBlock.POWERED)) {
					return Result.ALREADY_PRESSED;
				}
				((ButtonBlock) state.getBlock()).press(state, level, at, null);
			}
			case DOOR, GATE -> Openables.setOpen(level, worker, at, !state.getValue(BlockStateProperties.OPEN));
			case TRAPDOOR -> toggleTrapdoor(level, worker, at, state);
		}
		return Result.USED;
	}

	/** Vanilla's private TrapDoorBlock#toggle, with the worker as the game event's source. */
	private static void toggleTrapdoor(Level level, WorkerEntity worker, BlockPos pos, BlockState state) {
		BlockState updated = state.cycle(TrapDoorBlock.OPEN);
		level.setBlock(pos, updated, 2);
		if (updated.getValue(TrapDoorBlock.WATERLOGGED)) {
			level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
		}
		boolean open = updated.getValue(TrapDoorBlock.OPEN);
		BlockSetType type = trapdoorType((TrapDoorBlock) state.getBlock());
		level.playSound(null, pos, open ? type.trapdoorOpen() : type.trapdoorClose(), SoundSource.BLOCKS,
				1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
		level.gameEvent(worker, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
	}

	private static BlockSetType trapdoorType(TrapDoorBlock trapdoor) {
		return ((TrapDoorBlockAccessor) trapdoor).rabodepalha$type();
	}
}
