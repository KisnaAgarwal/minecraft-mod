package com.kisna.harvestguardian;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.Locale;

public final class HarvestGuardianCommands {
    private HarvestGuardianCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = Commands.literal("harvestguardian")
                    .requires(HarvestGuardianCommands::canDemonstrate)
                    .executes(ctx -> help(ctx.getSource()))
                    .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                    .then(Commands.literal("summon").executes(ctx -> summon(ctx.getSource())))
                    .then(Commands.literal("status").executes(ctx -> {
                        var guardian = nearest(ctx.getSource());
                        return success(ctx.getSource(), String.format(Locale.ROOT,
                                "Guardian #%d: %.0f/%.0f health, %d bread. Commands select the nearest living guardian within 32 blocks.",
                                guardian.getId(), guardian.getHealth(), guardian.getMaxHealth(), guardian.getBreadSupply()));
                    }))
                    .then(Commands.literal("showcase").executes(ctx -> {
                        nearest(ctx.getSource()).startShowcase();
                        return success(ctx.getSource(), "15-second preview: idle, walk, planting, bread, slam, aggro. No crops, items or damage are produced. /hg stop ends it.");
                    }))
                    .then(Commands.literal("stop").executes(ctx -> {
                        nearest(ctx.getSource()).stopPreview();
                        return success(ctx.getSource(), "Preview stopped; normal AI resumed.");
                    }))
                    .then(Commands.literal("refill").executes(ctx -> {
                        nearest(ctx.getSource()).refillBread();
                        return success(ctx.getSource(), "Guardian's bread supply set to 64.");
                    }))
                    .then(Commands.literal("plant").executes(ctx -> {
                        var guardian = nearest(ctx.getSource());
                        guardian.stopPreview();
                        if (!guardian.demonstratePlanting()) throw failure("No mature wheat or empty farmland within 3 blocks of the guardian. Prepare farmland at its feet, then retry; /hg animation plant_seed previews the animation anywhere.");
                        return success(ctx.getSource(), "Guardian planted wheat (or harvested and replanted a mature wheat crop).");
                    }))
                    .then(Commands.literal("bread").executes(ctx -> {
                        var guardian = nearest(ctx.getSource());
                        if (guardian.getBreadSupply() == 0) throw failure("No bread left. Run /hg refill first.");
                        guardian.stopPreview();
                        if (!guardian.giveBread()) throw failure("No living villager within 5 blocks of the guardian. Bring one closer, or use /hg animation give_bread for a preview.");
                        return success(ctx.getSource(), "Dropped one loaf beside the nearest villager. Supply remaining: " + guardian.getBreadSupply() + ". Villager pickup follows Minecraft's normal rules.");
                    }))
                    .then(Commands.literal("slam").executes(ctx -> {
                        var guardian = nearest(ctx.getSource());
                        guardian.stopPreview();
                        int hits = guardian.demonstrateSlam();
                        return success(ctx.getSource(), "Slam hit " + hits + " nearby monster(s). Players and villagers are excluded.");
                    }))
                    .then(Commands.literal("aggro").executes(ctx -> {
                        nearest(ctx.getSource()).startPreview("player_agro");
                        return success(ctx.getSource(), "Playing the player-aggro animation and sound; this preview does not attack you.");
                    }));

            var animations = Commands.literal("animation");
            for (String clip : HarvestGuardianEntity.DEMO_CLIPS) {
                animations.then(Commands.literal(clip).executes(ctx -> {
                    nearest(ctx.getSource()).startPreview(clip);
                    return success(ctx.getSource(), "Previewing " + clip + ". Normal AI resumes in 2 seconds.");
                }));
            }
            root.then(animations);
            var node = dispatcher.register(root);
            dispatcher.register(Commands.literal("hg").requires(HarvestGuardianCommands::canDemonstrate)
                    .executes(ctx -> help(ctx.getSource())).redirect(node));
        });
    }

    private static boolean canDemonstrate(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static HarvestGuardianEntity nearest(CommandSourceStack source) throws CommandSyntaxException {
        var position = source.getPosition();
        return source.getLevel().getEntitiesOfClass(HarvestGuardianEntity.class,
                        new AABB(position.x - 32, position.y - 32, position.z - 32,
                                position.x + 32, position.y + 32, position.z + 32),
                        entity -> entity.isAlive() && entity.distanceToSqr(position) <= 32 * 32)
                .stream().min(Comparator.comparingDouble(entity -> entity.distanceToSqr(position)))
                .orElseThrow(() -> failure("No Harvest Guardian within 32 blocks in this dimension. Run /hg summon or move closer."));
    }

    private static int summon(CommandSourceStack source) throws CommandSyntaxException {
        var guardian = new HarvestGuardianEntity(HarvestGuardianMod.HARVEST_GUARDIAN, source.getLevel());
        guardian.snapTo(source.getPosition().add(3, 0, 0));
        guardian.setPersistenceRequired();
        if (!source.getLevel().noCollision(guardian)) throw failure("Not enough space 3 blocks east of you. Move to an open area (guardian is 1.35 blocks tall).");
        if (!source.getLevel().addFreshEntity(guardian)) throw failure("Could not spawn the guardian.");
        return success(source, "Summoned a Harvest Guardian 3 blocks east of you. Try /hg showcase.");
    }

    private static int help(CommandSourceStack source) {
        return success(source, "Harvest Guardian commands (nearest within 32 blocks):\n"
                + "/hg summon | status | refill\n"
                + "/hg showcase | stop — preview all six animations\n"
                + "/hg animation <idle|walk|plant_seed|give_bread|attack_slam|player_agro>\n"
                + "/hg plant — work on nearby farmland/wheat\n"
                + "/hg bread — deliver bread to a villager within 5 blocks\n"
                + "/hg slam — damage nearby monsters\n"
                + "/hg aggro — preview aggro without targeting players\n"
                + "Requires cheats or operator permission. /harvestguardian also works.");
    }

    private static int success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static CommandSyntaxException failure(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }
}
