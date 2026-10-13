package io.github.oshogun.rabodepalha.command;

import io.github.oshogun.rabodepalha.mixin.BlockInputAccessor;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.DepositTask;
import io.github.oshogun.rabodepalha.worker.task.MineTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;
import io.github.oshogun.rabodepalha.worker.task.PlaceTask;
import io.github.oshogun.rabodepalha.worker.task.WithdrawTask;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** {@code /rdp} debug commands for spawning workers and handing them tasks. Requires permission level 2. */
public final class WorkerCommands {
	private static final double NEAREST_RANGE = 64.0;
	private static final SimpleCommandExceptionType NO_NEARBY_WORKER =
			new SimpleCommandExceptionType(Component.literal("No worker within " + (int) NEAREST_RANGE + " blocks"));
	private static final SimpleCommandExceptionType NO_WORKERS_SELECTED =
			new SimpleCommandExceptionType(Component.literal("No workers selected"));
	private static final SimpleCommandExceptionType BLOCK_NBT_NOT_SUPPORTED = new SimpleCommandExceptionType(Component.literal(
			"Block entity data ({...}) is not supported by /rdp place; give only the block and its properties"));
	private static final SimpleCommandExceptionType ITEM_COMPONENTS_NOT_SUPPORTED = new SimpleCommandExceptionType(Component.literal(
			"Item components ([...]) are not supported; deposit and withdraw match by item only"));

	private WorkerCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher, buildContext));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("rdp")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("spawn")
						.executes(ctx -> spawn(ctx.getSource(), ctx.getSource().getPosition()))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(ctx -> spawn(ctx.getSource(),
										Vec3.atBottomCenterOf(BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))))
				.then(Commands.literal("list")
						.executes(ctx -> list(ctx.getSource())))
				.then(Commands.literal("goto")
						.then(withWorkers(Commands.argument("pos", BlockPosArgument.blockPos()),
								(ctx, workers) -> enqueue(ctx, workers, () -> new MoveToTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))))
				.then(Commands.literal("mine")
						.then(withWorkers(Commands.argument("pos", BlockPosArgument.blockPos()),
								(ctx, workers) -> enqueue(ctx, workers, () -> new MineTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))))
				.then(Commands.literal("place")
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.then(withWorkers(Commands.argument("block", BlockStateArgument.block(buildContext)),
										(ctx, workers) -> {
											BlockInput input = BlockStateArgument.getBlock(ctx, "block");
											// PlaceTask places only the state, so NBT would be dropped without a word.
											if (((BlockInputAccessor) input).rabodepalha$getTag() != null) {
												throw BLOCK_NBT_NOT_SUPPORTED.create();
											}
											BlockState state = input.getState();
											BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
											return enqueue(ctx, workers, () -> new PlaceTask(pos, state));
										}))))
				// A word that parses as an item id is read as the item filter, even if a player has that name:
				// a bare name in "workers" only ever matches a player, never a worker, so nothing is lost.
				// Name workers with a selector (@e[type=rabodepalha:worker,name=...]) or a UUID.
				.then(Commands.literal("deposit")
						.then(withWorkers(Commands.argument("pos", BlockPosArgument.blockPos())
										.then(withWorkers(Commands.argument("item", ItemArgument.item(buildContext)),
												(ctx, workers) -> deposit(ctx, workers, Optional.of(itemId(ctx))))),
								(ctx, workers) -> deposit(ctx, workers, Optional.empty()))))
				// As for "give" below, "count" goes before "workers".
				.then(Commands.literal("withdraw")
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.then(withWorkers(Commands.argument("item", ItemArgument.item(buildContext))
												.then(withWorkers(Commands.argument("count", IntegerArgumentType.integer(1)),
														(ctx, workers) -> withdraw(ctx, workers, IntegerArgumentType.getInteger(ctx, "count")))),
										(ctx, workers) -> withdraw(ctx, workers, 1)))))
				// "count" must be added before withWorkers adds "workers": a bare number also parses as a
				// player name, and brigadier prefers the first-registered child when both consume the token.
				.then(Commands.literal("give")
						.then(withWorkers(Commands.argument("item", ItemArgument.item(buildContext))
										.then(withWorkers(Commands.argument("count", IntegerArgumentType.integer(1, 64 * WorkerEntity.INVENTORY_SIZE)),
												(ctx, workers) -> give(ctx, workers, IntegerArgumentType.getInteger(ctx, "count")))),
								(ctx, workers) -> give(ctx, workers, 1))))
				.then(withWorkers(Commands.literal("queue"), WorkerCommands::queue))
				.then(withWorkers(Commands.literal("clear"), WorkerCommands::clear)));
	}

	/**
	 * Makes {@code node} run {@code action} on the nearest worker, and adds an optional
	 * {@code workers} entity-selector child that runs it on the selected workers instead.
	 */
	private static <T extends ArgumentBuilder<CommandSourceStack, T>> T withWorkers(T node, WorkerAction action) {
		return node
				.executes(ctx -> action.run(ctx, nearestWorker(ctx.getSource())))
				.then(Commands.argument("workers", EntityArgument.entities())
						.executes(ctx -> action.run(ctx, selectedWorkers(ctx))));
	}

	@FunctionalInterface
	private interface WorkerAction {
		int run(CommandContext<CommandSourceStack> ctx, List<WorkerEntity> workers) throws CommandSyntaxException;
	}

	@FunctionalInterface
	private interface TaskFactory {
		Task<WorkerEntity> create() throws CommandSyntaxException;
	}

	private static List<WorkerEntity> nearestWorker(CommandSourceStack source) throws CommandSyntaxException {
		Vec3 pos = source.getPosition();
		double size = NEAREST_RANGE * 2;
		return source.getLevel()
				.getEntitiesOfClass(WorkerEntity.class, AABB.ofSize(pos, size, size, size),
						w -> w.isAlive() && w.position().distanceTo(pos) <= NEAREST_RANGE)
				.stream()
				.min(Comparator.comparingDouble(w -> w.position().distanceToSqr(pos)))
				.map(List::of)
				.orElseThrow(NO_NEARBY_WORKER::create);
	}

	private static List<WorkerEntity> selectedWorkers(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		List<WorkerEntity> workers = EntityArgument.getEntities(ctx, "workers").stream()
				.filter(WorkerEntity.class::isInstance)
				.map(WorkerEntity.class::cast)
				.toList();
		if (workers.isEmpty()) {
			throw NO_WORKERS_SELECTED.create();
		}
		return workers;
	}

	private static void rememberCommander(CommandSourceStack source, List<WorkerEntity> workers) {
		ServerPlayer player = source.getPlayer();
		if (player != null) {
			workers.forEach(w -> w.addCommander(player.getUUID()));
		}
	}

	private static String label(WorkerEntity worker) {
		BlockPos pos = worker.blockPosition();
		return worker.getName().getString() + " " + worker.getId()
				+ " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static int spawn(CommandSourceStack source, Vec3 pos) {
		ServerLevel level = source.getLevel();
		WorkerEntity worker = ModEntities.WORKER.create(level, EntitySpawnReason.COMMAND);
		if (worker == null) {
			source.sendFailure(Component.literal("Could not create worker"));
			return 0;
		}
		worker.snapTo(pos.x, pos.y, pos.z, source.getRotation().y, 0.0F);
		if (!level.addFreshEntity(worker)) {
			source.sendFailure(Component.literal("Could not spawn worker"));
			return 0;
		}
		rememberCommander(source, List.of(worker));
		source.sendSuccess(() -> Component.literal("Spawned " + label(worker)), false);
		return 1;
	}

	private static int list(CommandSourceStack source) {
		List<? extends WorkerEntity> workers = source.getLevel().getEntities(ModEntities.WORKER, WorkerEntity::isAlive);
		if (workers.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No workers in this dimension"), false);
			return 0;
		}
		StringBuilder text = new StringBuilder(workers.size() + " worker(s):");
		for (WorkerEntity worker : workers) {
			text.append("\n  ").append(label(worker)).append(" - ")
					.append(worker.tasks().current().map(Task::describe).orElse("idle"))
					.append(", ").append(worker.tasks().pending().size()).append(" queued");
		}
		source.sendSuccess(() -> Component.literal(text.toString()), false);
		return workers.size();
	}

	private static int enqueue(CommandContext<CommandSourceStack> ctx, List<WorkerEntity> workers, TaskFactory factory)
			throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		rememberCommander(source, workers);
		String described = null;
		for (WorkerEntity worker : workers) {
			Task<WorkerEntity> task = factory.create();
			worker.tasks().enqueue(task);
			described = task.describe();
		}
		String message = "Queued " + described + " for " + workers.size() + " worker(s)";
		source.sendSuccess(() -> Component.literal(message), false);
		return workers.size();
	}

	/** The item of the "item" argument. Components are rejected: deposit and withdraw match by item only. */
	private static Identifier itemId(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ItemInput input = ItemArgument.getItem(ctx, "item");
		if (!input.components().isEmpty()) {
			throw ITEM_COMPONENTS_NOT_SUPPORTED.create();
		}
		return BuiltInRegistries.ITEM.getKey(input.item().value());
	}

	private static int deposit(CommandContext<CommandSourceStack> ctx, List<WorkerEntity> workers, Optional<Identifier> item)
			throws CommandSyntaxException {
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
		return enqueue(ctx, workers, () -> new DepositTask(pos, item));
	}

	private static int withdraw(CommandContext<CommandSourceStack> ctx, List<WorkerEntity> workers, int count)
			throws CommandSyntaxException {
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
		Identifier item = itemId(ctx);
		return enqueue(ctx, workers, () -> new WithdrawTask(pos, item, count));
	}

	private static int give(CommandContext<CommandSourceStack> ctx, List<WorkerEntity> workers, int count)
			throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		int dropped = 0;
		ItemStack sample = ItemStack.EMPTY;
		for (WorkerEntity worker : workers) {
			ItemStack stack = ItemArgument.getItem(ctx, "item").createItemStack(count);
			sample = stack.copy();
			dropped += worker.insertOrDrop(stack).getCount();
		}
		String message = "Gave " + count + " " + sample.getHoverName().getString() + " to " + workers.size()
				+ " worker(s)" + (dropped > 0 ? ", " + dropped + " dropped (inventory full)" : "");
		source.sendSuccess(() -> Component.literal(message), false);
		return workers.size();
	}

	private static int queue(CommandContext<CommandSourceStack> ctx, List<WorkerEntity> workers) {
		StringBuilder text = new StringBuilder();
		for (WorkerEntity worker : workers) {
			if (!text.isEmpty()) {
				text.append('\n');
			}
			text.append(label(worker)).append(':');
			List<Task<WorkerEntity>> all = worker.tasks().snapshot();
			if (all.isEmpty()) {
				text.append(" idle");
			}
			boolean running = worker.tasks().current().isPresent();
			for (int i = 0; i < all.size(); i++) {
				text.append("\n  ").append(i == 0 && running ? "> " : (i + 1) + ". ").append(all.get(i).describe());
			}
		}
		ctx.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
		return workers.size();
	}

	private static int clear(CommandContext<CommandSourceStack> ctx, List<WorkerEntity> workers) {
		rememberCommander(ctx.getSource(), workers);
		workers.forEach(w -> w.tasks().clear(w));
		ctx.getSource().sendSuccess(() -> Component.literal("Cleared tasks of " + workers.size() + " worker(s)"), false);
		return workers.size();
	}
}
