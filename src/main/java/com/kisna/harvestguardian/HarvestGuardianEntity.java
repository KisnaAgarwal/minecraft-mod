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
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public final class HarvestGuardianEntity extends PathfinderMob implements GeoEntity {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.walk");
    private static final String ACTIONS = "actions";
    private static final double SLAM_RANGE = 3.2;
    private static final ColorParticleOption LEAVES = ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, .45f, .65f, .20f);
    public static final List<String> DEMO_CLIPS = List.of("idle", "walk", "plant_seed", "give_bread", "attack_slam", "player_agro");
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final GuardianBackpack backpack = new GuardianBackpack();
    private GuardianMode mode = GuardianMode.PATROL;
    private GlobalPos home;
    private UUID owner;
    private UUID followPlayer;
    private int patrolRadius = 24;
    private int giftTimer = 200;
    private int poseTicks;
    private int previewTicks;
    private boolean showcasing;
    private int slamWindup;
    private int slamCooldown;
    private LivingEntity slamTarget;

    public HarvestGuardianEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        xpReward = 15;
        getNavigation().setCanFloat(true);
        setPersistenceRequired();
        backpack.addItem(new ItemStack(Items.BREAD, 8));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 100)
                .add(Attributes.MOVEMENT_SPEED, .18).add(Attributes.ATTACK_DAMAGE, 15)
                .add(Attributes.ARMOR, 10).add(Attributes.FOLLOW_RANGE, 24)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1);
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
            @Override public boolean canUse() { return previewTicks > 0 || poseTicks > 0 || slamWindup > 0; }
            @Override public boolean canContinueToUse() { return canUse(); }
            @Override public void start() { getNavigation().stop(); }
        });
        goalSelector.addGoal(2, new SlamCombatGoal());
        goalSelector.addGoal(3, new FollowKeeperGoal());
        goalSelector.addGoal(3, new ReturnHomeGoal());
        goalSelector.addGoal(4, new TendWheatGoal());
        goalSelector.addGoal(5, new DeliverBreadGoal());
        goalSelector.addGoal(6, new CollectSuppliesGoal());
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, .85) {
            @Override public boolean canUse() { return mode == GuardianMode.PATROL && canWork() && super.canUse(); }
            @Override public boolean canContinueToUse() { return mode == GuardianMode.PATROL && canWork() && super.canContinueToUse(); }
            @Override protected Vec3 getPosition() {
                Vec3 position = super.getPosition();
                return position != null && withinHome(position) ? position : null;
            }
        });
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 12));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true,
                (target, server) -> allowedCombatTarget(target)));
    }

    @Override public void tick() {
        super.tick();
        if (level().isClientSide()) { spawnLeafParticles(); return; }
        if (!isAlive()) { cancelSlam(); return; }
        if (home == null) assignHome(blockPosition());
        if (getTarget() != null && !allowedCombatTarget(getTarget())) setTarget(null);
        // Explicit server-side acquisition also covers guardians loaded with old AI state.
        // Check once a second, so quiet guardians react when a hostile enters their area.
        if (previewTicks == 0 && getTarget() == null && tickCount % 20 == 0) {
            Monster threat = level().getEntitiesOfClass(Monster.class,
                    getBoundingBox().inflate(getAttributeValue(Attributes.FOLLOW_RANGE)),
                    mob -> allowedCombatTarget(mob) && hasLineOfSight(mob))
                    .stream().min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
            if (threat != null) {
                setTarget(threat);
                getNavigation().stop();
            }
        }
        setAggressive(getTarget() != null);
        if (slamCooldown > 0) slamCooldown--;
        if (slamWindup > 0 && --slamWindup == 0) impactSlam((ServerLevel) level());
        if (previewTicks > 0) {
            getNavigation().stop();
            setTarget(null);
            if (--previewTicks == 0) stopPreview();
            else if (showcasing && previewTicks % 50 == 0) playAction(DEMO_CLIPS.get((300 - previewTicks) / 50));
            return;
        }
        if (poseTicks > 0) { poseTicks--; getNavigation().stop(); }
        if (giftTimer > 0) giftTimer--;
        if (canWork() && (mode == GuardianMode.FARM || mode == GuardianMode.PATROL)) {
            if (tickCount % 10 == 0) collectNearbySupplies();
            if (tickCount % 100 == 0) backpack.bakeBread();
        }
    }

    public GuardianBackpack getBackpack() { return backpack; }
    public GuardianMode getMode() { return mode; }
    public int getPatrolRadius() { return patrolRadius; }
    public int getBreadSupply() { return backpack.countItem(Items.BREAD); }
    public boolean hasHomeHere() { return home != null && home.dimension().equals(level().dimension()); }
    public String homeDescription() {
        return home == null ? "unassigned" : home.pos().toShortString() + " (" + home.dimension().identifier() + ")";
    }
    public void assignHome(BlockPos pos) {
        home = GlobalPos.of(level().dimension(), pos.immutable());
        getNavigation().stop();
        setTarget(null);
    }
    public void setPatrolRadius(int radius) { patrolRadius = Mth.clamp(radius, 8, 64); getNavigation().stop(); }
    public boolean bindNearbyBell(BlockPos center) {
        BlockPos best = null;
        double bestDistance = 16 * 16;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -4, -16), center.offset(16, 4, 16))) {
            double distance = pos.distSqr(center);
            if (distance <= bestDistance && level().hasChunkAt(pos) && level().getBlockState(pos).is(Blocks.BELL)) {
                best = pos.immutable(); bestDistance = distance;
            }
        }
        if (best == null) return false;
        assignHome(best);
        return true;
    }
    private boolean withinHome(Vec3 pos) {
        if (!hasHomeHere()) return false;
        double dx = pos.x - home.pos().getX() - .5, dz = pos.z - home.pos().getZ() - .5;
        return dx * dx + dz * dz <= patrolRadius * patrolRadius && Math.abs(pos.y - home.pos().getY()) <= 16;
    }
    private boolean withinHome(BlockPos pos) { return withinHome(Vec3.atCenterOf(pos)); }
    private Player keeper() { return followPlayer == null ? null : level().getPlayerByUUID(followPlayer); }
    public boolean canManage(Player player) {
        return owner == null || owner.equals(player.getUUID()) || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
    public void setMode(GuardianMode next, Player player) {
        if (!canManage(player)) return;
        if (owner == null) owner = player.getUUID();
        mode = next;
        if (mode == GuardianMode.FOLLOW) followPlayer = player.getUUID();
        stopPreview(); cancelSlam(); setTarget(null); getNavigation().stop();
    }
    public void showControls(Player player) {
        if (!canManage(player)) {
            player.sendSystemMessage(Component.literal("This Guardian belongs to another player."));
            return;
        }
        if (owner == null) owner = player.getUUID();
        player.sendSystemMessage(Component.literal("Harvest Guardian: " + mode.label + " | Home radius: " + patrolRadius
                + " | Seeds: " + backpack.countItem(Items.WHEAT_SEEDS) + " | Wheat: " + backpack.countItem(Items.WHEAT)
                + " | Bread: " + getBreadSupply()));
        var controls = Component.literal("Choose: ");
        for (GuardianMode choice : GuardianMode.values()) {
            controls.append(controlButton(choice.label, choice.name().toLowerCase(java.util.Locale.ROOT))).append(" ");
        }
        player.sendSystemMessage(controls);
        player.sendSystemMessage(Component.literal("Home: ").append(controlButton("Set here", "home")).append(" ")
                .append(controlButton("Bind bell", "bell")).append(" ")
                .append(controlButton("Radius -", "radius " + Math.max(8, patrolRadius - 8))).append(" ")
                .append(controlButton("Radius +", "radius " + Math.min(64, patrolRadius + 8))));
        player.sendSystemMessage(Component.literal("Use /hg feed with seeds to stock farming; sneak while running it to store crops instead of healing."));
    }
    private Component controlButton(String label, String action) {
        return Component.literal("[" + label + "]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand("/guardian " + getUUID() + " " + action)));
    }
    public void feedFromHand(Player player) {
        if (level().isClientSide()) return;
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        float recovery = held.is(Items.BONE_MEAL) ? 10 : held.is(Items.BREAD) ? 6
                : held.is(Items.WHEAT) || held.is(Items.CARROT) || held.is(Items.POTATO) || held.is(Items.BEETROOT) ? 3 : 0;
        if (!player.isShiftKeyDown() && recovery > 0 && getHealth() < getMaxHealth()) {
            heal(recovery);
            if (!player.getAbilities().instabuild) held.shrink(1);
            playSound(SoundEvents.IRON_GOLEM_REPAIR, 1, 1);
            ((ServerLevel) level()).sendParticles(ParticleTypes.HEART, getX(), getY() + getBbHeight() * .65, getZ(), 5, .6, .5, .6, .01);
        } else if (!held.isEmpty() && GuardianBackpack.isSupply(held) && canManage(player)) {
            if (owner == null) owner = player.getUUID();
            int amount = player.isShiftKeyDown() ? held.getCount() : 1;
            ItemStack offering = held.copyWithCount(amount);
            int accepted = amount - backpack.addItem(offering).getCount();
            if (!player.getAbilities().instabuild) held.shrink(accepted);
            player.sendSystemMessage(Component.literal(accepted > 0 ? "Stored " + accepted + " supplies for farming and food delivery." : "Supplies are full."));
            if (accepted > 0) playSound(SoundEvents.ITEM_PICKUP, .6f, .8f);
        } else player.sendSystemMessage(Component.literal("Hold seeds, crops, bread or bone meal, then run /hg feed. Sneak to store the held stack instead of healing."));
    }

    private boolean canWork() {
        return isAlive() && previewTicks == 0 && poseTicks == 0 && slamWindup == 0
                && getTarget() == null && hasHomeHere() && withinHome(position());
    }
    private boolean canFarm() {
        return canWork() && mode == GuardianMode.FARM && level() instanceof ServerLevel server
                && server.getGameRules().get(GameRules.MOB_GRIEFING);
    }
    private boolean farmingAllowed(ServerLevel level, BlockPos pos) {
        return withinHome(pos) && level.getGameRules().get(GameRules.MOB_GRIEFING)
                && level.getBlockState(pos.below()).is(Blocks.FARMLAND);
    }
    private boolean workCrop(ServerLevel level, BlockPos pos) {
        if (!farmingAllowed(level, pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            if (backpack.countItem(Items.WHEAT_SEEDS) == 0 || !level.setBlock(pos, Blocks.WHEAT.defaultBlockState(), 3)) return false;
            backpack.consume(Items.WHEAT_SEEDS, 1);
        } else {
            if (!state.is(Blocks.WHEAT) || !((CropBlock) state.getBlock()).isMaxAge(state)) return false;
            var plan = backpack.planHarvest(Block.getDrops(state, level, pos, null, this, ItemStack.EMPTY));
            if (plan == null || !level.setBlock(pos, Blocks.WHEAT.defaultBlockState(), 3)) return false;
            backpack.commitHarvest(plan);
            for (ItemStack overflow : plan.overflow()) {
                level.addFreshEntity(new ItemEntity(level, pos.getX() + .5, pos.getY() + .4, pos.getZ() + .5, overflow));
            }
            level.levelEvent(2001, pos, Block.getId(state));
        }
        getLookControl().setLookAt(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        playAction("plant_seed");
        return true;
    }
    public boolean demonstratePlanting() {
        if (!(level() instanceof ServerLevel server)) return false;
        BlockPos origin = blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-3, -1, -3), origin.offset(3, 1, 3))) {
            if (workCrop(server, pos)) return true;
        }
        return false;
    }
    public int refillBread() {
        backpack.removeItemType(Items.BREAD, backpack.countItem(Items.BREAD));
        backpack.addItem(new ItemStack(Items.BREAD, 64));
        return getBreadSupply();
    }
    public boolean giveBread() {
        if (!(level() instanceof ServerLevel) || getBreadSupply() == 0) return false;
        Villager villager = level().getEntitiesOfClass(Villager.class, getBoundingBox().inflate(5))
                .stream().filter(v -> v.isAlive() && withinHome(v.position()))
                .min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
        return villager != null && giveBreadTo(villager);
    }
    private boolean giveBreadTo(Villager villager) {
        if (getBreadSupply() <= 0) return false;
        var loaf = new ItemEntity(level(), villager.getX(), villager.getY() + .4, villager.getZ(), new ItemStack(Items.BREAD));
        if (!level().addFreshEntity(loaf)) return false;
        backpack.consume(Items.BREAD, 1);
        giftTimer = 1200;
        getLookControl().setLookAt(villager, 30, 30);
        playAction("give_bread");
        return true;
    }
    private boolean canCollect(ItemEntity item) {
        return item.isAlive() && !item.hasPickUpDelay() && withinHome(item.position())
                && GuardianBackpack.isSupply(item.getItem()) && backpack.canAddItem(item.getItem());
    }
    private void collectNearbySupplies() {
        for (ItemEntity item : level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(.75), this::canCollect)) {
            ItemStack remaining = backpack.addItem(item.getItem().copy());
            if (remaining.isEmpty()) item.discard(); else item.setItem(remaining);
        }
    }

    private final class TendWheatGoal extends MoveToBlockGoal {
        TendWheatGoal() { super(HarvestGuardianEntity.this, .85, 12, 2); }
        @Override public boolean canUse() { return canFarm() && super.canUse(); }
        @Override public boolean canContinueToUse() { return canFarm() && super.canContinueToUse(); }
        @Override public double acceptedDistance() { return 2; }
        @Override protected boolean isValidTarget(LevelReader level, BlockPos pos) {
            if (!withinHome(pos) || !level.getBlockState(pos).is(Blocks.FARMLAND)) return false;
            BlockState crop = level.getBlockState(pos.above());
            return (crop.isAir() && backpack.countItem(Items.WHEAT_SEEDS) > 0)
                    || (crop.is(Blocks.WHEAT) && ((CropBlock) crop.getBlock()).isMaxAge(crop));
        }
        @Override public void tick() {
            super.tick();
            if (isReachedTarget() && level() instanceof ServerLevel server) {
                if (!workCrop(server, blockPos.above())) tryTicks = 1201;
                nextStartTick = 60;
            }
        }
        @Override public void stop() { getNavigation().stop(); super.stop(); }
    }

    private final class DeliverBreadGoal extends Goal {
        private Villager recipient;
        private int deadline, nextSearch;
        DeliverBreadGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        private boolean enabled() { return canWork() && (mode == GuardianMode.PATROL || mode == GuardianMode.FARM) && getBreadSupply() > 0 && giftTimer == 0; }
        @Override public boolean canUse() {
            if (!enabled() || tickCount < nextSearch) return false;
            nextSearch = tickCount + 100;
            recipient = level().getEntitiesOfClass(Villager.class, getBoundingBox().inflate(12))
                    .stream().filter(v -> v.isAlive() && v.wantsMoreFood() && withinHome(v.position()))
                    .min(Comparator.comparingDouble(HarvestGuardianEntity.this::distanceToSqr)).orElse(null);
            return recipient != null;
        }
        @Override public void start() {
            deadline = tickCount + 200;
            if (distanceToSqr(recipient) > 4 && !getNavigation().moveTo(recipient, .85)) giftTimer = 100;
        }
        @Override public boolean canContinueToUse() {
            return enabled() && recipient != null && recipient.isAlive() && withinHome(recipient.position()) && tickCount < deadline;
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            getLookControl().setLookAt(recipient, 20, 20);
            if (distanceToSqr(recipient) <= 4) giveBreadTo(recipient);
            else if (tickCount % 20 == 0 && !getNavigation().moveTo(recipient, .85)) giftTimer = 100;
        }
        @Override public void stop() { recipient = null; getNavigation().stop(); }
    }

    private final class CollectSuppliesGoal extends Goal {
        private ItemEntity item;
        private int nextSearch, deadline;
        CollectSuppliesGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() {
            if (!canWork() || (mode != GuardianMode.FARM && mode != GuardianMode.PATROL) || tickCount < nextSearch) return false;
            nextSearch = tickCount + 40;
            item = level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(10), HarvestGuardianEntity.this::canCollect)
                    .stream().min(Comparator.comparingDouble(HarvestGuardianEntity.this::distanceToSqr)).orElse(null);
            return item != null;
        }
        @Override public void start() { deadline = tickCount + 160; getNavigation().moveTo(item, .85); }
        @Override public boolean canContinueToUse() {
            return canWork() && (mode == GuardianMode.FARM || mode == GuardianMode.PATROL)
                    && item != null && canCollect(item) && tickCount < deadline;
        }
        @Override public void tick() { if (tickCount % 20 == 0) getNavigation().moveTo(item, .85); }
        @Override public void stop() { item = null; getNavigation().stop(); }
    }

    private final class FollowKeeperGoal extends Goal {
        FollowKeeperGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            Player player = keeper();
            return mode == GuardianMode.FOLLOW && getTarget() == null && player != null && player.isAlive() && distanceToSqr(player) > 36;
        }
        @Override public boolean canContinueToUse() {
            Player player = keeper();
            return mode == GuardianMode.FOLLOW && getTarget() == null && player != null && player.isAlive() && distanceToSqr(player) > 16;
        }
        @Override public void start() { move(); }
        private void move() { Player player = keeper(); if (player != null) { getLookControl().setLookAt(player, 20, 20); getNavigation().moveTo(player, 1); } }
        @Override public void tick() { if (tickCount % 10 == 0) move(); }
        @Override public void stop() { getNavigation().stop(); }
    }

    private final class ReturnHomeGoal extends Goal {
        ReturnHomeGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() {
            return (mode == GuardianMode.PATROL || mode == GuardianMode.FARM) && hasHomeHere()
                    && getTarget() == null && home.pos().distToCenterSqr(position()) > Math.pow(Math.max(4, patrolRadius - 4), 2);
        }
        @Override public boolean canContinueToUse() {
            return (mode == GuardianMode.PATROL || mode == GuardianMode.FARM) && hasHomeHere()
                    && getTarget() == null && home.pos().distToCenterSqr(position()) > 16;
        }
        private void move() { getNavigation().moveTo(getNavigation().createPath(home.pos(), 3), .9); }
        @Override public void start() { move(); }
        @Override public void tick() { if (tickCount % 40 == 0) move(); }
        @Override public void stop() { getNavigation().stop(); }
    }

    private boolean allowedCombatTarget(LivingEntity target) {
        if (target == null || !target.isAlive() || target == this || isAlliedTo(target)) return false;
        if (owner != null && owner.equals(target.getUUID())) return false;
        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        if (mode == GuardianMode.STAY) return distanceToSqr(target) <= SLAM_RANGE * SLAM_RANGE;
        if (mode == GuardianMode.FOLLOW) {
            Player player = keeper();
            return player != null && player.distanceToSqr(target) <= 16 * 16;
        }
        return withinHome(target.position()) && withinHome(position());
    }
    private final class SlamCombatGoal extends Goal {
        SlamCombatGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return allowedCombatTarget(getTarget()); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            LivingEntity target = getTarget();
            if (target == null) return;
            getLookControl().setLookAt(target, 30, 30);
            if (distanceToSqr(target) <= SLAM_RANGE * SLAM_RANGE && hasLineOfSight(target)) {
                getNavigation().stop();
                beginSlam(target);
            } else if (mode != GuardianMode.STAY && tickCount % 10 == 0) getNavigation().moveTo(target, 1);
        }
        @Override public void stop() { getNavigation().stop(); }
    }
    private boolean beginSlam(LivingEntity primary) {
        if (!isAlive() || slamCooldown > 0 || slamWindup > 0 || previewTicks > 0) return false;
        slamTarget = primary;
        slamWindup = 14;
        slamCooldown = 74;
        getNavigation().stop();
        playAction("attack_slam");
        return true;
    }
    private void cancelSlam() { slamWindup = 0; slamTarget = null; }
    private void impactSlam(ServerLevel level) {
        if (!isAlive()) { cancelSlam(); return; }
        BlockState floor = level.getBlockState(blockPosition().below());
        if (floor.isAir()) floor = Blocks.DIRT.defaultBlockState();
        for (int i = 0; i < 16; i++) {
            double angle = Math.PI * 2 * i / 16;
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, floor), getX() + Math.cos(angle) * 2,
                    getY() + .15, getZ() + Math.sin(angle) * 2, 3, .2, .15, .2, .08);
        }
        level.sendParticles(ParticleTypes.POOF, getX(), getY() + .2, getZ(), 12, 1, .1, 1, .04);
        playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.4f, .6f);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(getX()-SLAM_RANGE, getY()-1, getZ()-SLAM_RANGE, getX()+SLAM_RANGE, getY()+2, getZ()+SLAM_RANGE))) {
            if (!(victim instanceof Monster) && victim != slamTarget) continue;
            if (!allowedCombatTarget(victim) || distanceToSqr(victim) > SLAM_RANGE * SLAM_RANGE || !hasLineOfSight(victim)) continue;
            if (victim.hurtServer(level, damageSources().mobAttack(this), 15)) {
                victim.knockback(1.2, getX() - victim.getX(), getZ() - victim.getZ());
            }
        }
        slamTarget = null;
        poseTicks = 8;
    }
    @Override public boolean doHurtTarget(ServerLevel level, Entity target) {
        return target instanceof LivingEntity living && allowedCombatTarget(living) && beginSlam(living);
    }
    /** Queue the same timed attack as natural combat; the preview command remains harmless. */
    public boolean demonstrateSlam() { return level() instanceof ServerLevel && beginSlam(null); }
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt && (previewTicks > 0 || showcasing)) stopPreview();
        if (hurt && source.getEntity() instanceof Player player && (owner == null || !owner.equals(player.getUUID()))
                && slamWindup == 0 && slamCooldown == 0) playAction("player_agro");
        return hurt;
    }
    @Override protected void dropEquipment(ServerLevel level) {
        super.dropEquipment(level);
        for (ItemStack stack : backpack.removeAllItems()) if (!stack.isEmpty()) spawnAtLocation(level, stack);
    }

    public void startPreview(String clip) {
        if (!DEMO_CLIPS.contains(clip)) throw new IllegalArgumentException("Unknown animation: " + clip);
        stopPreview(); cancelSlam(); previewTicks = 40;
        getNavigation().stop(); setTarget(null); playAction(clip);
    }
    public void startShowcase() { startPreview("idle"); showcasing = true; previewTicks = 300; }
    public void stopPreview() { previewTicks = 0; poseTicks = 0; showcasing = false; stopTriggeredAnim(ACTIONS, null); }
    private void playAction(String clip) {
        if (clip.equals("plant_seed") || clip.equals("give_bread")) { poseTicks = 20; getNavigation().stop(); }
        triggerAnim(ACTIONS, clip);
        switch (clip) {
            case "idle" -> playSound(SoundEvents.AMETHYST_BLOCK_CHIME, .4f, .7f);
            case "walk" -> playSound(SoundEvents.IRON_GOLEM_STEP, .8f, .8f);
            case "plant_seed" -> playSound(SoundEvents.GRASS_PLACE, .8f, .9f);
            case "give_bread" -> playSound(SoundEvents.ITEM_PICKUP, .6f, .8f);
            case "attack_slam" -> playSound(SoundEvents.IRON_GOLEM_STEP, .8f, .5f);
            case "player_agro" -> playSound(SoundEvents.WARDEN_ANGRY, .6f, 1.2f);
            default -> { }
        }
    }
    private void spawnLeafParticles() {
        if (!isAlive() || isInvisible() || (tickCount + getId()) % 6 != 0) return;
        double angle = random.nextDouble() * Math.PI * 2, radius = getBbWidth() * (.45 + random.nextDouble() * .25);
        level().addParticle(LEAVES, getX() + Math.cos(angle)*radius, getY()+getBbHeight()*(.45+random.nextDouble()*.5),
                getZ()+Math.sin(angle)*radius, 0, 0, 0);
    }
    @Override protected SoundEvent getAmbientSound() { return SoundEvents.AZALEA_LEAVES_STEP; }
    @Override public int getAmbientSoundInterval() { return 240; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.IRON_GOLEM_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.IRON_GOLEM_DEATH; }
    @Override protected void playStepSound(BlockPos pos, BlockState state) { playSound(SoundEvents.IRON_GOLEM_STEP, .8f, .8f); }

    @Override protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        backpack.save(output.child("GuardianBackpack"));
        output.putInt("GuardianMode", mode.ordinal());
        output.putInt("GuardianPatrolRadius", patrolRadius);
        output.storeNullable("GuardianHome", GlobalPos.CODEC, home);
        output.storeNullable("GuardianOwner", UUIDUtil.CODEC, owner);
        output.storeNullable("GuardianFollowPlayer", UUIDUtil.CODEC, followPlayer);
        output.putInt("GuardianGiftTimer", giftTimer);
        output.putInt("GuardianMovementRevision", 1);
    }
    @Override protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        if (input.child("GuardianBackpack").isPresent()) backpack.load(input.childOrEmpty("GuardianBackpack"));
        else {
            backpack.clearContent();
            int oldBread = Mth.clamp(input.getIntOr("GuardianBread", 8), 0, GuardianBackpack.SIZE * 64);
            while (oldBread > 0) { int amount = Math.min(oldBread, 64); backpack.addItem(new ItemStack(Items.BREAD, amount)); oldBread -= amount; }
        }
        mode = GuardianMode.fromId(input.getIntOr("GuardianMode", GuardianMode.PATROL.ordinal()));
        patrolRadius = Mth.clamp(input.getIntOr("GuardianPatrolRadius", 24), 8, 64);
        home = input.read("GuardianHome", GlobalPos.CODEC).orElse(null);
        owner = input.read("GuardianOwner", UUIDUtil.CODEC).orElse(null);
        followPlayer = input.read("GuardianFollowPlayer", UUIDUtil.CODEC).orElse(null);
        giftTimer = Mth.clamp(input.getIntOr("GuardianGiftTimer", 200), 0, 1200);
        if (input.getIntOr("GuardianMovementRevision", 0) < 1) getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.18);
    }
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>("movement", 5, state -> {
            double speed = state.getDataOrDefault(DataTickets.VELOCITY, Vec3.ZERO).horizontalDistance();
            state.setControllerSpeed(state.isMoving() ? Mth.clamp((float)(speed/.06), .6f, 2.5f)/HarvestGuardianMod.MODEL_SCALE : 1);
            state.setAnimation(state.isMoving() ? WALK : IDLE);
            return PlayState.CONTINUE;
        }));
        var actions = new AnimationController<>(ACTIONS, 0, state -> PlayState.STOP);
        for (String clip : DEMO_CLIPS) actions.triggerableAnim(clip, RawAnimation.begin().thenPlay("animation." + clip));
        controllers.add(actions);
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
