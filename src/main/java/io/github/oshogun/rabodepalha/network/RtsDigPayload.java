package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: dig the cuboid between {@code from} and {@code to} with the selected workers, like
 * {@code /rdp dig <from> <to> [workers]}. The client sends the cuboid's min and max corners; the server
 * normalises them anyway. With {@code append} the task is queued after the current ones instead of replacing
 * them. The server checks everything and answers with {@link RtsOrderFeedbackPayload}. See
 * {@code RtsOrders.handleDig}.
 */
public record RtsDigPayload(List<Integer> workerIds, BlockPos from, BlockPos to, boolean append)
		implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsDigPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_dig"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsDigPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsOrderPayload.MAX_IDS)), RtsDigPayload::workerIds,
			BlockPos.STREAM_CODEC, RtsDigPayload::from,
			BlockPos.STREAM_CODEC, RtsDigPayload::to,
			ByteBufCodecs.BOOL, RtsDigPayload::append,
			RtsDigPayload::new);

	public RtsDigPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsDigPayload> type() {
		return TYPE;
	}
}
