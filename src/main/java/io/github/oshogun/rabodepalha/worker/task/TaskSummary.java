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
 * Components so block and item names are translated on the client.
 */
public record TaskSummary(String key, List<Component> args, List<BlockPos> targets) {
	public static final int MAX_ARGS = 4;
	public static final int MAX_TARGETS = 2;
	public static final int MAX_KEY = 64;

	public static final StreamCodec<RegistryFriendlyByteBuf, TaskSummary> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_KEY), TaskSummary::key,
			ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ARGS)), TaskSummary::args,
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TARGETS)), TaskSummary::targets,
			TaskSummary::new);

	public TaskSummary {
		args = List.copyOf(args.size() > MAX_ARGS ? args.subList(0, MAX_ARGS) : args);
		targets = List.copyOf(targets.size() > MAX_TARGETS ? targets.subList(0, MAX_TARGETS) : targets);
	}

	public Component toComponent() {
		return Component.translatable(key, args.toArray());
	}
}
