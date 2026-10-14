package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: asks for a read-only snapshot of the container at {@code pos}. The server runs the same
 * checks as orders and answers with {@link RtsContentsPayload}, or with {@link RtsOrderFeedbackPayload} on
 * rejection. See {@code RtsOrders.handleContents}.
 */
public record RtsContentsRequestPayload(BlockPos pos) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsContentsRequestPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_contents_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsContentsRequestPayload> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, RtsContentsRequestPayload::pos,
			RtsContentsRequestPayload::new);

	@Override
	public CustomPacketPayload.Type<RtsContentsRequestPayload> type() {
		return TYPE;
	}
}
