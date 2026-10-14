package io.github.oshogun.rabodepalha.network;

import java.util.List;
import java.util.Optional;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: the selected workers put their items into the container at {@code pos}, like
 * {@code /rdp deposit <pos> [item]}. With no {@code item} they deposit everything; otherwise only that item.
 * With {@code append} the task is queued after the current ones instead of replacing them. The server checks
 * everything and answers with {@link RtsOrderFeedbackPayload}. See {@code RtsOrders.handleDeposit}.
 */
public record RtsDepositPayload(List<Integer> workerIds, BlockPos pos, Optional<Identifier> item, boolean append)
		implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsDepositPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_deposit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsDepositPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsOrderPayload.MAX_IDS)), RtsDepositPayload::workerIds,
			BlockPos.STREAM_CODEC, RtsDepositPayload::pos,
			ByteBufCodecs.optional(Identifier.STREAM_CODEC), RtsDepositPayload::item,
			ByteBufCodecs.BOOL, RtsDepositPayload::append,
			RtsDepositPayload::new);

	public RtsDepositPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsDepositPayload> type() {
		return TYPE;
	}
}
