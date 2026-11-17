package com.kisna.harvestguardian.client;

import com.geckolib.model.DefaultedEntityGeoModel;
import com.kisna.harvestguardian.HarvestGuardianMod;
import net.minecraft.resources.Identifier;

final class HarvestGuardianModModel {
    private HarvestGuardianModModel() {}

    static DefaultedEntityGeoModel<com.kisna.harvestguardian.HarvestGuardianEntity> create() {
        return new DefaultedEntityGeoModel<>(Identifier.fromNamespaceAndPath(
                HarvestGuardianMod.MOD_ID, "harvest_guardian"));
    }
}
