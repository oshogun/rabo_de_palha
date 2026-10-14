package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: spawn a worker on top of the clicked block, like {@code /rdp spawn <pos>}. The server
 * checks everything and answers with {@link RtsOrderFeedbackPayload}. See {@code RtsOrders.handleSpawn}.
 */
public record RtsSpawnPayload(BlockPos clicked) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsSpawnPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_spawn"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsSpawnPayload> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, RtsSpawnPayload::clicked,
			RtsSpawnPayload::new);

	@Override
	public CustomPacketPayload.Type<RtsSpawnPayload> type() {
		return TYPE;
	}
}
