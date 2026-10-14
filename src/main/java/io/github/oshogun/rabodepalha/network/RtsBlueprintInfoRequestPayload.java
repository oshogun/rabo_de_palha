package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: asks for one blueprint's size and materials. The server runs the same checks as orders
 * and answers with {@link RtsBlueprintInfoPayload}, or with {@link RtsOrderFeedbackPayload} on rejection
 * (including unknown ids). See {@code RtsOrders.handleBlueprintInfo}.
 */
public record RtsBlueprintInfoRequestPayload(Identifier id) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsBlueprintInfoRequestPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_blueprint_info_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsBlueprintInfoRequestPayload> STREAM_CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, RtsBlueprintInfoRequestPayload::id,
			RtsBlueprintInfoRequestPayload::new);

	@Override
	public CustomPacketPayload.Type<RtsBlueprintInfoRequestPayload> type() {
		return TYPE;
	}
}
