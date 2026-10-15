package io.github.oshogun.rabodepalha.network;

import java.util.List;
import java.util.Optional;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: tend the field between the picked blocks {@code from} and {@code to} until stopped, using the
 * selected workers, like {@code /rdp tend <from> <to> [container] [workers]}. The optional {@code container} is
 * where the produce goes. With {@code append} the task is queued after the current ones instead of replacing them.
 * The server checks everything and answers with {@link RtsOrderFeedbackPayload}. See {@code RtsOrders.handleTend}.
 */
public record RtsTendPayload(List<Integer> workerIds, BlockPos from, BlockPos to, Optional<BlockPos> container,
		boolean append) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsTendPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_tend"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsTendPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsOrderPayload.MAX_IDS)), RtsTendPayload::workerIds,
			BlockPos.STREAM_CODEC, RtsTendPayload::from,
			BlockPos.STREAM_CODEC, RtsTendPayload::to,
			ByteBufCodecs.optional(BlockPos.STREAM_CODEC), RtsTendPayload::container,
			ByteBufCodecs.BOOL, RtsTendPayload::append,
			RtsTendPayload::new);

	public RtsTendPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsTendPayload> type() {
		return TYPE;
	}
}
