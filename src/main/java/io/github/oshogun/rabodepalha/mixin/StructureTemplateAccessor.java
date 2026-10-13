package io.github.oshogun.rabodepalha.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Exposes a structure template's block palettes, which vanilla keeps private, so a build can read the blueprint. */
@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor {
	@Accessor("palettes")
	List<StructureTemplate.Palette> rabodepalha$getPalettes();
}
