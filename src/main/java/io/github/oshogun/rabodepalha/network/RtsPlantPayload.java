package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: till and plant the field between the picked blocks {@code from} and {@code to} with
 * {@code seed}, using the selected workers, like {@code /rdp plant <from> <to> <seed> [workers]}. With
 * {@code append} the task is queued after the current ones instead of replacing them. The server checks
 * everything and answers with {@link RtsOrderFeedbackPayload}. See {@code RtsOrders.handlePlant}.
 */
public record RtsPlantPayload(List<Integer> workerIds, BlockPos from, BlockPos to, Identifier seed, boolean append)
		implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsPlantPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_plant"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsPlantPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsOrderPayload.MAX_IDS)), RtsPlantPayload::workerIds,
			BlockPos.STREAM_CODEC, RtsPlantPayload::from,
			BlockPos.STREAM_CODEC, RtsPlantPayload::to,
			Identifier.STREAM_CODEC, RtsPlantPayload::seed,
			ByteBufCodecs.BOOL, RtsPlantPayload::append,
			RtsPlantPayload::new);

	public RtsPlantPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsPlantPayload> type() {
		return TYPE;
	}
}
