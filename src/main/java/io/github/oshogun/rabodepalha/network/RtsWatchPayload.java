package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the workers the RTS panel is showing. Replaces the previous watch; the server answers
 * with {@link RtsSnapshotPayload} while the player stays in RTS mode. See {@code RtsOrders.handleWatch}.
 */
public record RtsWatchPayload(List<Integer> workerIds) implements CustomPacketPayload {
	/** Upper bound on watched workers, enforced by the codec. */
	public static final int MAX_WATCHED = 16;

	public static final CustomPacketPayload.Type<RtsWatchPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_watch"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsWatchPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_WATCHED)), RtsWatchPayload::workerIds,
			RtsWatchPayload::new);

	public RtsWatchPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsWatchPayload> type() {
		return TYPE;
	}
}
