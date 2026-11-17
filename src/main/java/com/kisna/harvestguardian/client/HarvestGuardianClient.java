package com.kisna.harvestguardian.client;

import com.kisna.harvestguardian.HarvestGuardianMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public final class HarvestGuardianClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(HarvestGuardianMod.HARVEST_GUARDIAN, HarvestGuardianRenderer::new);
    }
}
