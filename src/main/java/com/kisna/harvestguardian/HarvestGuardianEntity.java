package com.kisna.harvestguardian;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import com.geckolib.constant.DataTickets;
import com.geckolib.util.GeckoLibUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.EnumSet;
import java.util.List;

public final class HarvestGuardianEntity extends PathfinderMob implements GeoEntity {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.walk");
    private static final String ACTIONS = "actions";
    public static final List<String> DEMO_CLIPS = List.of("idle", "walk", "plant_seed", "give_bread", "attack_slam", "player_agro");
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int bread = 8;
    private int giftTimer = 200;
    private int poseTicks;
    // Transient: unloading/reloading always returns the guardian to its normal AI.
    private int previewTicks;
    private boolean showcasing;

    public HarvestGuardianEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.xpReward = 15;
        getNavigation().setCanFloat(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 100.0)
                .add(Attributes.MOVEMENT_SPEED, 0.18)
                .add(Attributes.ATTACK_DAMAGE, 15.0)
                .add(Attributes.ARMOR, 10.0)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)); }
            @Override public boolean canUse() { return previewTicks > 0 || poseTicks > 0; }
            @Override public boolean canContinueToUse() { return canUse(); }
            @Override public void start() { getNavigation().stop(); }
        });
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, false));
        this.goalSelector.addGoal(3, new DeliverBreadGoal());
        this.goalSelector.addGoal(4, new TendWheatGoal());
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.85));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) return;
        if (previewTicks > 0) {
            getNavigation().stop();
            setTarget(null);
            if (--previewTicks == 0) {
                stopPreview();
            } else if (showcasing && previewTicks % 50 == 0) {
                playAction(DEMO_CLIPS.get((300 - previewTicks) / 50));
            }
            return;
        }
        if (poseTicks > 0) { poseTicks--; getNavigation().stop(); }
        if (this.giftTimer > 0) this.giftTimer--;
    }

    private boolean tendNearbyCrop() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return false;
        BlockPos origin = this.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-3, -1, -3), origin.offset(3, 1, 3))) {
            if (harvestAt(serverLevel, pos)) return true;
        }
        return false;
    }

    private boolean harvestAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.WHEAT) || !((CropBlock) state.getBlock()).isMaxAge(state)
                || !level.getBlockState(pos.below()).is(Blocks.FARMLAND)) return false;
        level.destroyBlock(pos, true, this);
        level.setBlock(pos, Blocks.WHEAT.defaultBlockState(), 3);
        getLookControl().setLookAt(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        playAction("plant_seed");
        return true;
    }

    private boolean canWork() {
        return previewTicks == 0 && poseTicks == 0 && getTarget() == null;
    }

    private final class TendWheatGoal extends MoveToBlockGoal {
        TendWheatGoal() { super(HarvestGuardianEntity.this, 0.85, 8, 2); }
        @Override public boolean canUse() { return canWork() && super.canUse(); }
        @Override public boolean canContinueToUse() { return canWork() && super.canContinueToUse(); }
        @Override public double acceptedDistance() { return 1.6; }
        @Override protected boolean isValidTarget(LevelReader level, BlockPos pos) {
            BlockState crop = level.getBlockState(pos.above());
            return level.getBlockState(pos).is(Blocks.FARMLAND) && crop.is(Blocks.WHEAT)
                    && ((CropBlock) crop.getBlock()).isMaxAge(crop);
        }
        @Override public void tick() {
            super.tick();
            if (isReachedTarget() && level() instanceof ServerLevel serverLevel) {
                harvestAt(serverLevel, blockPos.above());
                nextStartTick = 100;
            }
        }
        @Override public void stop() { getNavigation().stop(); super.stop(); }
    }

    private final class DeliverBreadGoal extends Goal {
        private Villager recipient;
        private int deadline;
        private int nextSearch;
        DeliverBreadGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            if (!canWork() || bread <= 0 || giftTimer > 0 || tickCount < nextSearch) return false;
            nextSearch = tickCount + 100;
            recipient = level().getEntitiesOfClass(Villager.class, getBoundingBox().inflate(12))
                    .stream().filter(v -> v.isAlive() && v.wantsMoreFood())
                    .min(java.util.Comparator.comparingDouble(HarvestGuardianEntity.this::distanceToSqr))
                    .orElse(null);
            return recipient != null;
        }
        @Override public void start() {
            deadline = tickCount + 200;
            if (!getNavigation().moveTo(recipient, 0.85)) giftTimer = 100;
        }
        @Override public boolean canContinueToUse() {
            return canWork() && giftTimer == 0 && bread > 0 && recipient != null && recipient.isAlive()
                    && tickCount < deadline && distanceToSqr(recipient) < 16 * 16;
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            getLookControl().setLookAt(recipient, 20, 20);
            if (distanceToSqr(recipient) <= 2 * 2) {
                giveBreadTo(recipient);
            } else if (tickCount % 20 == 0 && !getNavigation().moveTo(recipient, 0.85)) {
                giftTimer = 100;
            }
        }
        @Override public void stop() { recipient = null; getNavigation().stop(); }
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, net.minecraft.world.entity.Entity target) {
        if (previewTicks > 0) return false;
        playAction("attack_slam");
        boolean hit = super.doHurtTarget(level, target);
        if (hit && target instanceof LivingEntity living) {
            // A modest ground-slam pulse gives the attack its heavy golem feel.
            for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(blockPosition()).inflate(2.5, 1.0, 2.5), e -> e != this && e != living && e instanceof Monster)) {
                nearby.hurtServer(level, damageSources().mobAttack(this), 5.0f);
                nearby.knockback(0.8, getX() - nearby.getX(), getZ() - nearby.getZ());
            }
        }
        return hit;
    }

    @Override
    public boolean hurtServer(ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt && source.getEntity() instanceof Player) {
            stopPreview();
            playAction("player_agro");
        }
        return hurt;
    }

    public int getBreadSupply() { return bread; }

    public void refillBread() { bread = 64; }

    public boolean giveBread() {
        if (!(level() instanceof ServerLevel) || bread <= 0) return false;
        Villager villager = level().getEntitiesOfClass(Villager.class, getBoundingBox().inflate(5.0))
                .stream().filter(Villager::isAlive)
                .min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
        if (villager == null) return false;
        return giveBreadTo(villager);
    }

    private boolean giveBreadTo(Villager villager) {
        var loaf = new net.minecraft.world.entity.item.ItemEntity(level(),
                villager.getX(), villager.getY() + 0.4, villager.getZ(), new ItemStack(Items.BREAD));
        if (!level().addFreshEntity(loaf)) return false;
        bread--;
        giftTimer = 1200;
        getLookControl().setLookAt(villager, 30, 30);
        playAction("give_bread");
        return true;
    }

    /** Operator demonstration: replants mature wheat, or supplies a seed to empty farmland. */
    public boolean demonstratePlanting() {
        if (tendNearbyCrop()) return true;
        if (!(level() instanceof ServerLevel serverLevel)) return false;
        BlockPos origin = blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-3, -1, -3), origin.offset(3, 1, 3))) {
            if (serverLevel.getBlockState(pos).isAir() && serverLevel.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
                if (serverLevel.setBlock(pos, Blocks.WHEAT.defaultBlockState(), 3)) {
                    playAction("plant_seed");
                    return true;
                }
            }
        }
        return false;
    }

    /** The command damages nearby monsters only; players and villagers are excluded. */
    public int demonstrateSlam() {
        if (!(level() instanceof ServerLevel serverLevel)) return 0;
        playAction("attack_slam");
        int hits = 0;
        for (Monster monster : serverLevel.getEntitiesOfClass(Monster.class,
                new AABB(blockPosition()).inflate(2.5, 1.0, 2.5), LivingEntity::isAlive)) {
            if (monster.hurtServer(serverLevel, damageSources().mobAttack(this), 15.0f)) {
                monster.knockback(0.8, getX() - monster.getX(), getZ() - monster.getZ());
                hits++;
            }
        }
        return hits;
    }

    public void startPreview(String clip) {
        if (!DEMO_CLIPS.contains(clip)) throw new IllegalArgumentException("Unknown animation: " + clip);
        stopPreview();
        previewTicks = 40;
        getNavigation().stop();
        setTarget(null);
        playAction(clip);
    }

    public void startShowcase() {
        startPreview("idle");
        showcasing = true;
        previewTicks = 300;
    }

    public void stopPreview() {
        previewTicks = 0;
        poseTicks = 0;
        showcasing = false;
        stopTriggeredAnim(ACTIONS, null);
    }

    private void playAction(String clip) {
        if (clip.equals("plant_seed") || clip.equals("give_bread")) {
            poseTicks = 20;
            getNavigation().stop();
        }
        triggerAnim(ACTIONS, clip);
        switch (clip) {
            case "idle" -> playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.4f, 0.7f);
            case "walk" -> playSound(SoundEvents.IRON_GOLEM_STEP, 0.8f, 0.8f);
            case "plant_seed" -> playSound(SoundEvents.GRASS_PLACE, 0.8f, 0.9f);
            case "give_bread" -> playSound(SoundEvents.ITEM_PICKUP, 0.6f, 0.8f);
            case "attack_slam" -> playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.2f, 0.65f);
            case "player_agro" -> playSound(SoundEvents.WARDEN_ANGRY, 0.6f, 1.2f);
            default -> { }
        }
    }

    @Override protected SoundEvent getAmbientSound() { return SoundEvents.AZALEA_LEAVES_STEP; }
    @Override public int getAmbientSoundInterval() { return 240; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.IRON_GOLEM_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.IRON_GOLEM_DEATH; }
    @Override protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.IRON_GOLEM_STEP, 0.8f, 0.8f);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("GuardianBread", this.bread);
        tag.putInt("GuardianMovementRevision", 1);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput tag) {
        super.readAdditionalSaveData(tag);
        this.bread = Math.max(0, tag.getIntOr("GuardianBread", 8));
        // Upgrade the speed saved on guardians spawned before the size correction.
        if (tag.getIntOr("GuardianMovementRevision", 0) < 1) {
            getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.18);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>("movement", 5, state -> {
            double speed = state.getDataOrDefault(DataTickets.VELOCITY, Vec3.ZERO).horizontalDistance();
            state.setControllerSpeed(state.isMoving() ? Mth.clamp((float) (speed / 0.06), 0.6f, 2.5f) : 1.0f);
            state.setAnimation(state.isMoving() ? WALK : IDLE);
            return PlayState.CONTINUE;
        }));
        controllers.add(new AnimationController<>(ACTIONS, 0, state -> PlayState.STOP)
                .triggerableAnim("idle", RawAnimation.begin().thenPlay("animation.idle"))
                .triggerableAnim("walk", RawAnimation.begin().thenPlay("animation.walk"))
                .triggerableAnim("plant_seed", RawAnimation.begin().thenPlay("animation.plant_seed"))
                .triggerableAnim("give_bread", RawAnimation.begin().thenPlay("animation.give_bread"))
                .triggerableAnim("attack_slam", RawAnimation.begin().thenPlay("animation.attack_slam"))
                .triggerableAnim("player_agro", RawAnimation.begin().thenPlay("animation.player_agro")));
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return this.cache; }
}
