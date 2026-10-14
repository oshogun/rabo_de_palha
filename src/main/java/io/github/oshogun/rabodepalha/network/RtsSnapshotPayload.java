package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.rts.WorkerSnapshot;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: fresh snapshots of watched workers that changed, and the ids of watched workers that are
 * gone (dead, unloaded or never existed) so the client drops them.
 */
public record RtsSnapshotPayload(List<WorkerSnapshot> snapshots, List<Integer> gone) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_snapshot"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsSnapshotPayload> STREAM_CODEC = StreamCodec.composite(
			WorkerSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list(RtsWatchPayload.MAX_WATCHED)), RtsSnapshotPayload::snapshots,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsWatchPayload.MAX_WATCHED)), RtsSnapshotPayload::gone,
			RtsSnapshotPayload::new);

	public RtsSnapshotPayload {
		snapshots = List.copyOf(snapshots);
		gone = List.copyOf(gone);
	}

	@Override
	public CustomPacketPayload.Type<RtsSnapshotPayload> type() {
		return TYPE;
	}
}
