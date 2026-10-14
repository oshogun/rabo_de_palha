package io.github.oshogun.rabodepalha.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.oshogun.rabodepalha.mixin.StructureTemplateManagerAccessor;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The blueprints the RTS picker offers, listed by id and source without loading any template. It reads the
 * same sources {@link StructureTemplateManager} loads from, in the same order: the world's {@code generated}
 * directory (what a structure block saves, often in the {@code minecraft} namespace), then data and mod
 * packs, then the vanilla packs. Templates that exist only in memory (getOrCreate, not saved yet) come
 * from the manager's cache and count as pack templates.
 */
public final class BlueprintCatalog {
	/** The most entries one listing sends; the rest are counted in {@link Listing#more()}. */
	public static final int MAX_BLUEPRINTS = 1024;

	private static final FileToIdConverter RESOURCE_LISTER = new FileToIdConverter("structure", ".nbt");

	/** Where a blueprint comes from, in the order the picker shows them. */
	public enum Source {
		WORLD, PACK, VANILLA
	}

	public record Entry(Identifier id, Source source) {
	}

	/** The entries to show, and how many more were left out by the cap. */
	public record Listing(List<Entry> entries, int more) {
		public Listing {
			entries = List.copyOf(entries);
		}
	}

	private BlueprintCatalog() {
	}

	/** Lists the server's blueprints, hiding vanilla ones unless {@code includeVanilla}. Loads no template. */
	public static Listing list(MinecraftServer server, boolean includeVanilla) {
		return select(classify(server), includeVanilla, MAX_BLUEPRINTS);
	}

	/** Every known blueprint id with its source. A world save wins over a pack, and a pack over vanilla. */
	public static Map<Identifier, Source> classify(MinecraftServer server) {
		Map<Identifier, Source> classified = new HashMap<>();
		for (Identifier id : worldIds(server)) {
			classified.put(id, Source.WORLD);
		}
		for (Map.Entry<Identifier, Resource> entry : RESOURCE_LISTER.listMatchingResources(server.getResourceManager()).entrySet()) {
			boolean vanilla = entry.getValue().knownPackInfo().map(KnownPack::isVanilla).orElse(false);
			classified.putIfAbsent(RESOURCE_LISTER.fileToId(entry.getKey()), vanilla ? Source.VANILLA : Source.PACK);
		}
		Map<Identifier, Optional<StructureTemplate>> repository =
				((StructureTemplateManagerAccessor) server.getStructureTemplateManager()).rabodepalha$getRepository();
		for (Map.Entry<Identifier, Optional<StructureTemplate>> entry : repository.entrySet()) {
			if (entry.getValue().isPresent()) {
				classified.putIfAbsent(entry.getKey(), Source.PACK);
			}
		}
		return classified;
	}

	/** The ids saved in the world's {@code generated} directory, in any namespace, listed the way vanilla does. */
	public static Set<Identifier> worldIds(MinecraftServer server) {
		Path dir = server.getWorldPath(LevelResource.GENERATED_DIR).normalize();
		Set<Identifier> ids = new HashSet<>();
		if (!Files.isDirectory(dir)) {
			return ids;
		}
		FileToIdConverter lister = StructureTemplateManager.WORLD_STRUCTURE_LISTER;
		for (String namespace : PathPackResources.getNamespaces(dir)) {
			PathPackResources.listResources(dir, namespace, lister.prefix(), (file, stream) -> {
				if (lister.extensionMatches(file)) {
					ids.add(lister.fileToId(file));
				}
			});
		}
		return ids;
	}

	/**
	 * Orders world blueprints first, then pack ones, then vanilla ones, each sorted by id. Vanilla ones are
	 * dropped unless {@code includeVanilla}. The cap applies after that filter, so hidden vanilla entries
	 * never push out the player's own.
	 */
	public static Listing select(Map<Identifier, Source> classified, boolean includeVanilla, int cap) {
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<Identifier, Source> entry : classified.entrySet()) {
			if (includeVanilla || entry.getValue() != Source.VANILLA) {
				entries.add(new Entry(entry.getKey(), entry.getValue()));
			}
		}
		entries.sort(Comparator.comparing(Entry::source).thenComparing(Entry::id));
		int shown = Math.min(entries.size(), Math.max(0, cap));
		return new Listing(entries.subList(0, shown), entries.size() - shown);
	}
}
