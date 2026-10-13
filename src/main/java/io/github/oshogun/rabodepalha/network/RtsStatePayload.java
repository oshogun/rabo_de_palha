package io.github.oshogun.rabodepalha.network;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: whether the player is now in RTS mode. The client only switches its camera on this. */
public record RtsStatePayload(boolean active) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RtsStatePayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsStatePayload> STREAM_CODEC = ByteBufCodecs.BOOL
			.map(RtsStatePayload::new, RtsStatePayload::active)
			.cast();

	@Override
	public CustomPacketPayload.Type<RtsStatePayload> type() {
		return TYPE;
	}
}
