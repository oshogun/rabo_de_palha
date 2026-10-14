package io.github.oshogun.rabodepalha.worker.task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Task types known to the worker, keyed on {@link Task#typeId()}. Only task parameters are
 * serialized; runtime state is rebuilt when the task starts. The codec works with any ops (NBT, JSON),
 * but {@link #PLACE} looks blocks up in the static block registry, so the game must be bootstrapped.
 * Each type also has a {@link TaskSummary} for the RTS panel; {@link Task#describe()} stays the log text.
 */
public final class TaskTypes {
	private static final String KEY_PREFIX = "rabodepalha.task.";
	private static final Map<String, Type<?>> TYPES = new LinkedHashMap<>();

	public static final MapCodec<MoveToTask> MOVE_TO = register(MoveToTask.TYPE_ID, MoveToTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(MoveToTask::pos),
					Codec.INT.optionalFieldOf("timeout", Approach.DEFAULT_TIMEOUT).forGetter(MoveToTask::timeoutTicks)
			).apply(instance, MoveToTask::new)),
			task -> new TaskSummary(KEY_PREFIX + "move_to", List.of(pos(task.pos())), List.of(task.pos())));

	public static final MapCodec<MineTask> MINE = register(MineTask.TYPE_ID, MineTask.class,
			BlockPos.CODEC.fieldOf("pos").xmap(MineTask::new, MineTask::pos),
			task -> summary("mine", List.of(pos(task.pos()))));

	/**
	 * A full block state ({@code {Name, Properties}}). Saves from before states were stored hold a bare
	 * block id, which still decodes, as the default state. An unknown id is a decode error, which drops
	 * only that queue entry on load.
	 */
	private static final Codec<BlockState> PLACE_STATE_CODEC = Codec.withAlternative(BlockState.CODEC,
			BuiltInRegistries.BLOCK.byNameCodec().xmap(Block::defaultBlockState, BlockState::getBlock));

	public static final MapCodec<PlaceTask> PLACE = register(PlaceTask.TYPE_ID, PlaceTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(PlaceTask::pos),
					PLACE_STATE_CODEC.fieldOf("block").forGetter(PlaceTask::state)
			).apply(instance, PlaceTask::new)),
			task -> summary("place", List.of(task.state().getBlock().getName(), pos(task.pos()))));

	public static final MapCodec<DepositTask> DEPOSIT = register(DepositTask.TYPE_ID, DepositTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(DepositTask::pos),
					Identifier.CODEC.optionalFieldOf("item").forGetter(DepositTask::itemId)
			).apply(instance, DepositTask::new)),
			task -> task.itemId()
					.map(id -> summary("deposit_item", List.of(itemName(id), pos(task.pos()))))
					.orElseGet(() -> summary("deposit", List.of(pos(task.pos())))));

	public static final MapCodec<WithdrawTask> WITHDRAW = register(WithdrawTask.TYPE_ID, WithdrawTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("pos").forGetter(WithdrawTask::pos),
					Identifier.CODEC.fieldOf("item").forGetter(WithdrawTask::itemId),
					Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(WithdrawTask::count)
			).apply(instance, WithdrawTask::new)),
			task -> summary("withdraw", List.of(Component.literal(String.valueOf(task.count())), itemName(task.itemId()),
					pos(task.pos()))));

	/** Only the corners: progress is not saved, a loaded task scans the area again. */
	public static final MapCodec<DigAreaTask> DIG_AREA = register(DigAreaTask.TYPE_ID, DigAreaTask.class,
			RecordCodecBuilder.<DigAreaTask>mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("from").forGetter(DigAreaTask::from),
					BlockPos.CODEC.fieldOf("to").forGetter(DigAreaTask::to)
			).apply(instance, DigAreaTask::new)).validate(task -> task.volume() <= DigAreaTask.MAX_VOLUME
					? DataResult.success(task)
					: DataResult.error(() -> "Dig area of " + task.volume() + " blocks exceeds " + DigAreaTask.MAX_VOLUME)),
			task -> new TaskSummary(KEY_PREFIX + "dig_area", List.of(pos(task.from()), pos(task.to())),
					List.of(task.from(), task.to())));

	/** Only the parameters: progress is not saved, a loaded task plans the build again. */
	public static final MapCodec<BuildTask> BUILD = register(BuildTask.TYPE_ID, BuildTask.class,
			RecordCodecBuilder.mapCodec(instance -> instance.group(
					BlockPos.CODEC.fieldOf("origin").forGetter(BuildTask::origin),
					Identifier.CODEC.fieldOf("blueprint").forGetter(BuildTask::blueprint),
					Rotation.CODEC.optionalFieldOf("rotation", Rotation.NONE).forGetter(BuildTask::rotation)
			).apply(instance, BuildTask::new)),
			task -> new TaskSummary(KEY_PREFIX + "build", List.of(Component.literal(task.blueprint().toString()),
					pos(task.origin()), Component.literal(task.rotation().getSerializedName())), List.of(task.origin())));

	/** Dispatches on a {@code type} field; an unknown type decodes to a {@link DataResult} error. */
	public static final Codec<Task<WorkerEntity>> CODEC = Codec.STRING.partialDispatch("type",
			task -> DataResult.success(task.typeId()), TaskTypes::codecFor);

	private TaskTypes() {
	}

	/** Forces class initialization so all types are registered. */
	public static void bootstrap() {
	}

	public static DataResult<MapCodec<? extends Task<WorkerEntity>>> codecFor(String typeId) {
		Type<?> type = TYPES.get(typeId);
		return type != null ? DataResult.success(type.codec()) : DataResult.error(() -> "Unknown task type: " + typeId);
	}

	/** A translatable description of the task; an unregistered type gets {@code rabodepalha.task.unknown}. */
	public static TaskSummary summarize(Task<WorkerEntity> task) {
		Type<?> type = TYPES.get(task.typeId());
		if (type == null || !type.taskClass().isInstance(task)) {
			return summary("unknown", List.of(Component.literal(task.typeId())));
		}
		return type.summarize(task);
	}

	private static <T extends Task<WorkerEntity>> MapCodec<T> register(String typeId, Class<T> taskClass, MapCodec<T> codec,
			Function<T, TaskSummary> summary) {
		if (TYPES.putIfAbsent(typeId, new Type<>(taskClass, codec, summary)) != null) {
			throw new IllegalStateException("Duplicate task type " + typeId);
		}
		return codec;
	}

	private static TaskSummary summary(String key, List<Component> args) {
		return new TaskSummary(KEY_PREFIX + key, args, List.of());
	}

	private static Component pos(BlockPos pos) {
		return Component.literal(pos.toShortString());
	}

	/** The item's translatable name, or the raw id when it is unknown or air. */
	private static Component itemName(Identifier id) {
		Item item = BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : Items.AIR;
		return item == Items.AIR ? Component.literal(id.toString()) : Component.translatable(item.getDescriptionId());
	}

	private record Type<T extends Task<WorkerEntity>>(Class<T> taskClass, MapCodec<T> codec, Function<T, TaskSummary> summary) {
		TaskSummary summarize(Task<WorkerEntity> task) {
			return summary.apply(taskClass.cast(task));
		}
	}
}
