package com.kisna.harvestguardian.client;

import com.geckolib.renderer.GeoEntityRenderer;
import com.kisna.harvestguardian.HarvestGuardianEntity;
import com.kisna.harvestguardian.HarvestGuardianMod;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

public final class HarvestGuardianRenderer extends GeoEntityRenderer<HarvestGuardianEntity, EntityRenderState> {
    public HarvestGuardianRenderer(EntityRendererProvider.Context context) {
        super(context, HarvestGuardianModModel.create());
        withScale(HarvestGuardianMod.MODEL_SCALE);
        this.shadowRadius = 0.3f * HarvestGuardianMod.MODEL_SCALE;
    }
}
