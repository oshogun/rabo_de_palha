package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;

/**
 * Client to server: build {@code blueprint} at {@code origin} with {@code rotation} using the selected
 * workers, like {@code /rdp build <origin> <blueprint> [rotation]}. The origin is the template's (0,0,0)
 * corner, used verbatim. With {@code append} the task is queued after the current ones instead of replacing
 * them. The server checks everything and answers with {@link RtsOrderFeedbackPayload}. See
 * {@code RtsOrders.handleBuild}.
 */
public record RtsBuildPayload(List<Integer> workerIds, BlockPos origin, Identifier blueprint, Rotation rotation,
		boolean append) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsBuildPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_build"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsBuildPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(RtsOrderPayload.MAX_IDS)), RtsBuildPayload::workerIds,
			BlockPos.STREAM_CODEC, RtsBuildPayload::origin,
			Identifier.STREAM_CODEC, RtsBuildPayload::blueprint,
			Rotation.STREAM_CODEC, RtsBuildPayload::rotation,
			ByteBufCodecs.BOOL, RtsBuildPayload::append,
			RtsBuildPayload::new);

	public RtsBuildPayload {
		workerIds = List.copyOf(workerIds);
	}

	@Override
	public CustomPacketPayload.Type<RtsBuildPayload> type() {
		return TYPE;
	}
}
