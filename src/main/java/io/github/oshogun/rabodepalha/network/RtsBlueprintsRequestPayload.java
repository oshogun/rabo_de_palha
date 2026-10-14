package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: asks for the blueprint ids the RTS picker offers, including vanilla templates only with
 * {@code includeVanilla}. The server runs the same checks as orders and answers with
 * {@link RtsBlueprintsPayload}, or with {@link RtsOrderFeedbackPayload} on rejection. See
 * {@code RtsOrders.handleBlueprints}.
 */
public record RtsBlueprintsRequestPayload(boolean includeVanilla) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsBlueprintsRequestPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_blueprints_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsBlueprintsRequestPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, RtsBlueprintsRequestPayload::includeVanilla,
			RtsBlueprintsRequestPayload::new);

	@Override
	public CustomPacketPayload.Type<RtsBlueprintsRequestPayload> type() {
		return TYPE;
	}
}
