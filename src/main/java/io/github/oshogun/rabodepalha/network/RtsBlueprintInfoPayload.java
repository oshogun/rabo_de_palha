package io.github.oshogun.rabodepalha.network;

import java.util.List;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Server to client: one blueprint's unrotated size and the items it needs (an upper bound: blocks already in
 * place are not subtracted), most needed first and capped at {@link #MAX_MATERIALS}.
 */
public record RtsBlueprintInfoPayload(Identifier id, Vec3i size, List<Material> materials) implements CustomPacketPayload {
	/** The most material rows one info sends. */
	public static final int MAX_MATERIALS = 64;

	/** An item and how many of it the blueprint needs. */
	public record Material(Item item, int count) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Material> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.registry(Registries.ITEM), Material::item,
				ByteBufCodecs.VAR_INT, Material::count,
				Material::new);
	}

	public static final CustomPacketPayload.Type<RtsBlueprintInfoPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_blueprint_info"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsBlueprintInfoPayload> STREAM_CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, RtsBlueprintInfoPayload::id,
			Vec3i.STREAM_CODEC, RtsBlueprintInfoPayload::size,
			Material.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_MATERIALS)), RtsBlueprintInfoPayload::materials,
			RtsBlueprintInfoPayload::new);

	public RtsBlueprintInfoPayload {
		materials = List.copyOf(materials);
	}

	@Override
	public CustomPacketPayload.Type<RtsBlueprintInfoPayload> type() {
		return TYPE;
	}
}
