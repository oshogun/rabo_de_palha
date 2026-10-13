package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the outcome of an RTS order, shown on the HUD (green when {@code ok}, red otherwise). */
public record RtsOrderFeedbackPayload(boolean ok, Component message) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsOrderFeedbackPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_order_feedback"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsOrderFeedbackPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, RtsOrderFeedbackPayload::ok,
			ComponentSerialization.TRUSTED_STREAM_CODEC, RtsOrderFeedbackPayload::message,
			RtsOrderFeedbackPayload::new);

	@Override
	public CustomPacketPayload.Type<RtsOrderFeedbackPayload> type() {
		return TYPE;
	}
}
