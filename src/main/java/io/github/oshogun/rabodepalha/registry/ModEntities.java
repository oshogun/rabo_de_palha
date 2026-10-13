package io.github.oshogun.rabodepalha.registry;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final ResourceKey<EntityType<?>> WORKER_KEY = ResourceKey.create(Registries.ENTITY_TYPE, RaboDePalha.id("worker"));

	public static final EntityType<WorkerEntity> WORKER = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			WORKER_KEY,
			EntityType.Builder.<WorkerEntity>of(WorkerEntity::new, MobCategory.MISC)
					.sized(0.6F, 1.95F)
					.clientTrackingRange(10)
					.build(WORKER_KEY)
	);

	private ModEntities() {
	}

	/** Forces class loading (and so registration) and registers default attributes. */
	public static void init() {
		FabricDefaultAttributeRegistry.register(WORKER, WorkerEntity.createAttributes());
	}
}
