package io.github.oshogun.rabodepalha.network;

import java.util.List;
import java.util.function.IntFunction;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.rts.BlueprintCatalog;
import io.netty.buffer.ByteBuf;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ByIdMap;

/**
 * Server to client: the blueprints the RTS picker offers, as ids and sources only (no sizes or materials,
 * which come per blueprint through {@link RtsBlueprintInfoPayload}). {@code more} counts the entries left out
 * by {@link BlueprintCatalog#MAX_BLUEPRINTS}; {@code includeVanilla} echoes the request.
 */
public record RtsBlueprintsPayload(List<BlueprintCatalog.Entry> entries, int more, boolean includeVanilla)
		implements CustomPacketPayload {
	private static final IntFunction<BlueprintCatalog.Source> SOURCE_BY_ID = ByIdMap.continuous(BlueprintCatalog.Source::ordinal,
			BlueprintCatalog.Source.values(), ByIdMap.OutOfBoundsStrategy.ZERO);
	private static final StreamCodec<ByteBuf, BlueprintCatalog.Source> SOURCE_CODEC = ByteBufCodecs.idMapper(SOURCE_BY_ID,
			BlueprintCatalog.Source::ordinal);
	private static final StreamCodec<RegistryFriendlyByteBuf, BlueprintCatalog.Entry> ENTRY_CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, BlueprintCatalog.Entry::id,
			SOURCE_CODEC, BlueprintCatalog.Entry::source,
			BlueprintCatalog.Entry::new);

	public static final CustomPacketPayload.Type<RtsBlueprintsPayload> TYPE = new CustomPacketPayload.Type<>(RaboDePalha.id("rts_blueprints"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RtsBlueprintsPayload> STREAM_CODEC = StreamCodec.composite(
			ENTRY_CODEC.apply(ByteBufCodecs.list(BlueprintCatalog.MAX_BLUEPRINTS)), RtsBlueprintsPayload::entries,
			ByteBufCodecs.VAR_INT, RtsBlueprintsPayload::more,
			ByteBufCodecs.BOOL, RtsBlueprintsPayload::includeVanilla,
			RtsBlueprintsPayload::new);

	public RtsBlueprintsPayload {
		entries = List.copyOf(entries);
	}

	/** Builds the payload for a catalog listing. */
	public static RtsBlueprintsPayload of(BlueprintCatalog.Listing listing, boolean includeVanilla) {
		return new RtsBlueprintsPayload(listing.entries(), listing.more(), includeVanilla);
	}

	@Override
	public CustomPacketPayload.Type<RtsBlueprintsPayload> type() {
		return TYPE;
	}
}
