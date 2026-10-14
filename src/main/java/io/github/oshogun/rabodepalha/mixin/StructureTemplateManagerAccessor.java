package io.github.oshogun.rabodepalha.mixin;

import java.util.Map;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Exposes the structure manager's loaded-template cache, which vanilla keeps private, so the blueprint list can include
 * templates that exist only in memory (created with getOrCreate and not saved yet). Reading its keys loads nothing.
 */
@Mixin(StructureTemplateManager.class)
public interface StructureTemplateManagerAccessor {
	@Accessor("structureRepository")
	Map<Identifier, Optional<StructureTemplate>> rabodepalha$getRepository();
}
