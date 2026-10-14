package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: clear the queues and current tasks of the selected workers, like {@code /rdp clear}.
 * The server checks everything and answers with {@link RtsOrderFeedbackPayload}. See {@code RtsOrders.handleStop}.
 */
public record RtsStopPayload(List<Integer> workerIds) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsStopPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_stop"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsStopPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsOrderPayload.MAX_IDS)), RtsStopPayload::workerIds,
			RtsStopPayload::new);

	public RtsStopPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsStopPayload> type() {
		return TYPE;
	}
}
