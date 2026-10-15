package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: harvest the ripe plants in the field between the picked blocks {@code from} and {@code to},
 * using the selected workers, like {@code /rdp harvest <from> <to> [workers]}. With {@code append} the task is
 * queued after the current ones instead of replacing them. The server checks everything and answers with
 * {@link RtsOrderFeedbackPayload}. See {@code RtsOrders.handleHarvest}.
 */
public record RtsHarvestPayload(List<Integer> workerIds, BlockPos from, BlockPos to, boolean append)
		implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsHarvestPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_harvest"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsHarvestPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsOrderPayload.MAX_IDS)), RtsHarvestPayload::workerIds,
			BlockPos.STREAM_CODEC, RtsHarvestPayload::from,
			BlockPos.STREAM_CODEC, RtsHarvestPayload::to,
			ByteBufCodecs.BOOL, RtsHarvestPayload::append,
			RtsHarvestPayload::new);

	public RtsHarvestPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsHarvestPayload> type() {
		return TYPE;
	}
}
