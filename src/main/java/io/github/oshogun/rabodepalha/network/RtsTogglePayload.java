package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: asks to enter or leave RTS mode. The server decides; see {@code RtsMode.requestToggle}. */
public record RtsTogglePayload() implements CustomPacketPayload {
	public static final RtsTogglePayload INSTANCE = new RtsTogglePayload();
	public static final CustomPacketPayload.Type<RtsTogglePayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_toggle"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsTogglePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public CustomPacketPayload.Type<RtsTogglePayload> type() {
		return TYPE;
	}
}
