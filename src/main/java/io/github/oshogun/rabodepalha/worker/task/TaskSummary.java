package io.github.oshogun.rabodepalha.worker.task;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A short translatable description of a task, plus the positions it targets. Arguments are
 * Components so block and item names are translated on the client. A build targets its origin, then
 * its footprint's min and max corners when the blueprint is known; the origin is always first.
 * {@code repeating} marks an order that runs until stopped (see {@link io.github.oshogun.rabodepalha.task.Task#repeating}),
 * so the panel and markers can show it as repeating rather than stuck.
 */
public record TaskSummary(String key, List<Component> args, List<BlockPos> targets, boolean repeating) {
	public static final int MAX_ARGS = 4;
	public static final int MAX_TARGETS = 3;
	public static final int MAX_KEY = 64;

	public static final StreamCodec<RegistryFriendlyByteBuf, TaskSummary> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_KEY), TaskSummary::key,
			ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ARGS)), TaskSummary::args,
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TARGETS)), TaskSummary::targets,
			ByteBufCodecs.BOOL, TaskSummary::repeating,
			TaskSummary::new);

	public TaskSummary {
		args = List.copyOf(args.size() > MAX_ARGS ? args.subList(0, MAX_ARGS) : args);
		targets = List.copyOf(targets.size() > MAX_TARGETS ? targets.subList(0, MAX_TARGETS) : targets);
	}

	/** A one-shot order's summary. */
	public TaskSummary(String key, List<Component> args, List<BlockPos> targets) {
		this(key, args, targets, false);
	}

	/** This summary with {@code repeating} set as given. */
	public TaskSummary withRepeating(boolean repeating) {
		return repeating == this.repeating ? this : new TaskSummary(key, args, targets, repeating);
	}

	public Component toComponent() {
		return Component.translatable(key, args.toArray());
	}
}
