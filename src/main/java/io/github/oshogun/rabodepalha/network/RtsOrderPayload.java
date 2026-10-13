package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: an order for the selected workers. The client only sends what was clicked; the server
 * derives the target, checks everything and answers with {@link RtsOrderFeedbackPayload}. See {@code RtsOrders.handle}.
 */
public record RtsOrderPayload(List<Integer> workerIds, RtsOrderKind kind, BlockPos clicked, Direction face, int hotbarSlot, boolean append)
		implements CustomPacketPayload {
	/** Upper bound on the ids in one order, enforced by the codec so a packet cannot grow without limit. */
	public static final int MAX_IDS = 256;

	public static final CustomPacketPayload.Type<RtsOrderPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_order"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsOrderPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_IDS)), RtsOrderPayload::workerIds,
			RtsOrderKind.STREAM_CODEC, RtsOrderPayload::kind,
			BlockPos.STREAM_CODEC, RtsOrderPayload::clicked,
			Direction.STREAM_CODEC, RtsOrderPayload::face,
			ByteBufCodecs.VAR_INT, RtsOrderPayload::hotbarSlot,
			ByteBufCodecs.BOOL, RtsOrderPayload::append,
			RtsOrderPayload::new);

	public RtsOrderPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsOrderPayload> type() {
		return TYPE;
	}
}
