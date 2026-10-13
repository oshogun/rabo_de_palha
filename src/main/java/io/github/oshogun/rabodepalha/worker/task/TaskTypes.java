package io.github.oshogun.rabodepalha.worker.task;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Task types known to the worker, keyed on {@link Task#typeId()}. Only task parameters are
 * serialized; runtime state is rebuilt when the task starts. The codec works with any ops (NBT, JSON),
 * but {@link #PLACE} looks blocks up in the static block registry, so the game must be bootstrapped.
 */
public final class TaskTypes {
	private static final Map<String, MapCodec<? extends Task<WorkerEntity>>> TYPES = new LinkedHashMap<>();

	public static final MapCodec<MoveToTask> MOVE_TO = register(MoveToTask.TYPE_ID,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(MoveToTask::pos),
					Codec.INT.optionalFieldOf("timeout", Approach.DEFAULT_TIMEOUT).forGetter(MoveToTask::timeoutTicks)
			).apply(instance, MoveToTask::new)));

	public static final MapCodec<MineTask> MINE = register(MineTask.TYPE_ID,
			BlockPos.CODEC.fieldOf("pos").xmap(MineTask::new, MineTask::pos));

	/**
	 * A full block state ({@code {Name, Properties}}). Saves from before states were stored hold a bare
	 * block id, which still decodes, as the default state. An unknown id is a decode error, which drops
	 * only that queue entry on load.
	 */
	private static final Codec<BlockState> PLACE_STATE_CODEC = Codec.withAlternative(BlockState.CODEC,
			BuiltInRegistries.BLOCK.byNameCodec().xmap(Block::defaultBlockState, BlockState::getBlock));

	public static final MapCodec<PlaceTask> PLACE = register(PlaceTask.TYPE_ID,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(PlaceTask::pos),
					PLACE_STATE_CODEC.fieldOf("block").forGetter(PlaceTask::state)
			).apply(instance, PlaceTask::new)));

	public static final MapCodec<DepositTask> DEPOSIT = register(DepositTask.TYPE_ID,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(DepositTask::pos),
					Identifier.CODEC.optionalFieldOf("item").forGetter(DepositTask::itemId)
			).apply(instance, DepositTask::new)));

	public static final MapCodec<WithdrawTask> WITHDRAW = register(WithdrawTask.TYPE_ID,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(WithdrawTask::pos),
					Identifier.CODEC.fieldOf("item").forGetter(WithdrawTask::itemId),
					Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(WithdrawTask::count)
			).apply(instance, WithdrawTask::new)));

	/** Dispatches on a {@code type} field; an unknown type decodes to a {@link DataResult} error. */
	public static final Codec<Task<WorkerEntity>> CODEC = Codec.STRING.partialDispatch("type",
			task -> DataResult.success(task.typeId()), TaskTypes::codecFor);

	private TaskTypes() {
	}

	/** Forces class initialization so all types are registered. */
	public static void bootstrap() {
	}

	public static DataResult<MapCodec<? extends Task<WorkerEntity>>> codecFor(String typeId) {
		MapCodec<? extends Task<WorkerEntity>> codec = TYPES.get(typeId);
		return codec != null ? DataResult.success(codec) : DataResult.error(() -> "Unknown task type: " + typeId);
	}

	private static <T extends Task<WorkerEntity>> MapCodec<T> register(String typeId, MapCodec<T> codec) {
		if (TYPES.putIfAbsent(typeId, codec) != null) {
			throw new IllegalStateException("Duplicate task type " + typeId);
		}
		return codec;
	}
}
