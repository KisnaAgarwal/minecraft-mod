package com.kisna.harvestguardian;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class HarvestGuardianMod implements ModInitializer {
    public static final String MOD_ID = "harvestguardian";
    public static final ResourceKey<EntityType<?>> GUARDIAN_KEY = ResourceKey.create(
            BuiltInRegistries.ENTITY_TYPE.key(), Identifier.fromNamespaceAndPath(MOD_ID, "harvest_guardian"));
    public static final EntityType<HarvestGuardianEntity> HARVEST_GUARDIAN = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            GUARDIAN_KEY,
            EntityType.Builder.<HarvestGuardianEntity>of(HarvestGuardianEntity::new, MobCategory.CREATURE)
                    // The exported model is ~21 pixels tall (16 model pixels = one block).
                    .sized(0.6f, 1.35f)
                    .eyeHeight(1.0f)
                    .clientTrackingRange(10)
                    .build(GUARDIAN_KEY));

    @Override
    public void onInitialize() {
        // Entity type is registered above; FabricDefaultAttributeRegistry is bound here.
        net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(
                HARVEST_GUARDIAN, HarvestGuardianEntity.createAttributes());
        HarvestGuardianCommands.register();
    }
}
