package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: drop one queued task of a worker, by the runtime id from its {@code WorkerSnapshot}.
 * The server checks everything and answers with {@link RtsOrderFeedbackPayload}. See {@code RtsOrders.handleRemoveTask}.
 */
public record RtsRemoveTaskPayload(int workerId, long taskId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsRemoveTaskPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_remove_task"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsRemoveTaskPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RtsRemoveTaskPayload::workerId,
			ByteBufCodecs.VAR_LONG, RtsRemoveTaskPayload::taskId,
			RtsRemoveTaskPayload::new);

	@Override
	public CustomPacketPayload.Type<RtsRemoveTaskPayload> type() {
		return TYPE;
	}
}
