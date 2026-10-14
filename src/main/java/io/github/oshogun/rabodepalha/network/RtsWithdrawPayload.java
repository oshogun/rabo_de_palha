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
 * Client to server: each selected worker takes {@code count} of {@code item} from the container at {@code pos},
 * like {@code /rdp withdraw <pos> <item> [count]}. The count is per worker. With {@code append} the task is
 * queued after the current ones instead of replacing them. The server checks everything and answers with
 * {@link RtsOrderFeedbackPayload}. See {@code RtsOrders.handleWithdraw}.
 */
public record RtsWithdrawPayload(List<Integer> workerIds, BlockPos pos, Identifier item, int count, boolean append)
		implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsWithdrawPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_withdraw"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsWithdrawPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsOrderPayload.MAX_IDS)), RtsWithdrawPayload::workerIds,
			BlockPos.STREAM_CODEC, RtsWithdrawPayload::pos,
			Identifier.STREAM_CODEC, RtsWithdrawPayload::item,
			ByteBufCodecs.VAR_INT, RtsWithdrawPayload::count,
			ByteBufCodecs.BOOL, RtsWithdrawPayload::append,
			RtsWithdrawPayload::new);

	public RtsWithdrawPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsWithdrawPayload> type() {
		return TYPE;
	}
}
