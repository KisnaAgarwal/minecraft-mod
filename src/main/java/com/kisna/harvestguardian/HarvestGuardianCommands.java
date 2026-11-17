package com.kisna.harvestguardian;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.arguments.UuidArgument;
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
                                "Guardian #%d: %.0f/%.0f health, %d bread, %s mode, radius %d. Home: %s",
                                guardian.getId(), guardian.getHealth(), guardian.getMaxHealth(), guardian.getBreadSupply(),
                                guardian.getMode().label, guardian.getPatrolRadius(), guardian.homeDescription()));
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
                        int stock = nearest(ctx.getSource()).refillBread();
                        return success(ctx.getSource(), "Backpack now holds " + stock + " bread. Free a slot if it could not fit 64.");
                    }))
                    .then(Commands.literal("plant").executes(ctx -> {
                        var guardian = nearest(ctx.getSource());
                        guardian.stopPreview();
                        if (!guardian.demonstratePlanting()) throw failure("Need nearby farmland inside the home area and real seeds in the backpack (or in harvest drops). Mob griefing must be enabled. /hg animation plant_seed previews without supplies.");
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
                        if (!guardian.demonstrateSlam()) throw failure("Slam is cooling down. Wait a few seconds and retry.");
                        return success(ctx.getSource(), "Slam winding up: nearby monsters take damage at impact. Players and villagers are excluded from this demo.");
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
            for (GuardianMode mode : GuardianMode.values()) {
                root.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
                    nearest(ctx.getSource()).setMode(mode, ctx.getSource().getPlayerOrException());
                    return success(ctx.getSource(), "Mode set to " + mode.label + ".");
                }));
            }
            root.then(Commands.literal("feed").executes(ctx -> {
                nearest(ctx.getSource()).feedFromHand(ctx.getSource().getPlayerOrException());
                return 1;
            }));
            root.then(Commands.literal("controls").executes(ctx -> {
                nearest(ctx.getSource()).showControls(ctx.getSource().getPlayerOrException());
                return 1;
            }));
            root.then(Commands.literal("home").executes(ctx -> {
                nearest(ctx.getSource()).assignHome(net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()));
                return success(ctx.getSource(), "Home set at your position.");
            }));
            root.then(Commands.literal("bell").executes(ctx -> {
                if (!nearest(ctx.getSource()).bindNearbyBell(net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition())))
                    throw failure("No bell within 16 blocks of your position.");
                return success(ctx.getSource(), "Home bound to the nearest village bell.");
            }));
            root.then(Commands.literal("radius").then(Commands.argument("blocks", IntegerArgumentType.integer(8, 64)).executes(ctx -> {
                int radius = IntegerArgumentType.getInteger(ctx, "blocks");
                nearest(ctx.getSource()).setPatrolRadius(radius);
                return success(ctx.getSource(), "Home radius set to " + radius + " blocks.");
            })));
            var node = dispatcher.register(root);
            dispatcher.register(Commands.literal("hg").requires(HarvestGuardianCommands::canDemonstrate)
                    .executes(ctx -> help(ctx.getSource())).redirect(node));

            // Survival-mode interaction buttons: authorize every action on the server.
            var controlled = Commands.argument("id", UuidArgument.uuid());
            for (GuardianMode mode : GuardianMode.values()) {
                controlled.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
                    var guardian = managed(ctx);
                    guardian.setMode(mode, ctx.getSource().getPlayerOrException());
                    return success(ctx.getSource(), "Guardian mode: " + mode.label);
                }));
            }
            controlled.then(Commands.literal("home").executes(ctx -> {
                managed(ctx).assignHome(ctx.getSource().getPlayerOrException().blockPosition());
                return success(ctx.getSource(), "Home set here.");
            }));
            controlled.then(Commands.literal("bell").executes(ctx -> {
                if (!managed(ctx).bindNearbyBell(ctx.getSource().getPlayerOrException().blockPosition()))
                    throw failure("No bell within 16 blocks. Move closer to the village bell.");
                return success(ctx.getSource(), "Home bound to the nearest bell.");
            }));
            controlled.then(Commands.literal("radius").then(Commands.argument("blocks", IntegerArgumentType.integer(8, 64)).executes(ctx -> {
                int radius = IntegerArgumentType.getInteger(ctx, "blocks");
                managed(ctx).setPatrolRadius(radius);
                return success(ctx.getSource(), "Home radius: " + radius + " blocks.");
            })));
            dispatcher.register(Commands.literal("guardian").then(controlled));
        });
    }

    private static HarvestGuardianEntity managed(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrException();
        var entity = ctx.getSource().getLevel().getEntity(UuidArgument.getUuid(ctx, "id"));
        if (!(entity instanceof HarvestGuardianEntity guardian) || !guardian.isAlive()
                || guardian.distanceToSqr(player) > 100 || !guardian.canManage(player))
            throw failure("You must be within 10 blocks of your Guardian to use these controls.");
        return guardian;
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
        if (!source.getLevel().noCollision(guardian)) throw failure("Not enough space 3 blocks east of you. Move to an open area (guardian needs about 2.1 blocks of width and 4.73 blocks of height).");
        if (!source.getLevel().addFreshEntity(guardian)) throw failure("Could not spawn the guardian.");
        return success(source, "Summoned a Harvest Guardian 3 blocks east of you. Try /hg showcase.");
    }

    private static int help(CommandSourceStack source) {
        return success(source, "Harvest Guardian commands (nearest within 32 blocks):\n"
                + "/hg summon | status | refill\n"
                + "/hg feed | controls | follow | stay | patrol | farm\n"
                + "/hg home | bell | radius <8-64>\n"
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
