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
 * Client to server: dig and fill the water holes that keep the field between {@code from} and {@code to} wet,
 * then till and plant it with {@code seed}, using the selected workers, like {@code /rdp irrigate <from> <to>
 * <seed> [workers]}. When {@code from} equals {@code to} it is a plot: the 9×9 square around that block, like
 * {@code /rdp irrigate <pos> <seed> [workers]}. With {@code append} the task is queued after the current ones
 * instead of replacing them. The server checks everything and answers with {@link RtsOrderFeedbackPayload}. See
 * {@code RtsOrders.handleIrrigate}.
 */
public record RtsIrrigatePayload(List<Integer> workerIds, BlockPos from, BlockPos to, Identifier seed, boolean append)
		implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsIrrigatePayload> TYPE =
			new CustomPacketPayload.Type<>(RaboDePalha.id("rts_irrigate"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsIrrigatePayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsOrderPayload.MAX_IDS)), RtsIrrigatePayload::workerIds,
			BlockPos.STREAM_CODEC, RtsIrrigatePayload::from,
			BlockPos.STREAM_CODEC, RtsIrrigatePayload::to,
			Identifier.STREAM_CODEC, RtsIrrigatePayload::seed,
			ByteBufCodecs.BOOL, RtsIrrigatePayload::append,
			RtsIrrigatePayload::new);

	public RtsIrrigatePayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsIrrigatePayload> type() {
		return TYPE;
	}
}
