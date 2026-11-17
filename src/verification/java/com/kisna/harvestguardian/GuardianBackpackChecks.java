package com.kisna.harvestguardian;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.List;

public final class GuardianBackpackChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static GuardianBackpack fullBag() {
        var bag = new GuardianBackpack();
        for (int i = 0; i < GuardianBackpack.SIZE; i++) bag.setItem(i, new ItemStack(Items.CARROT, 64));
        return bag;
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = net.minecraft.data.registries.VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pending -> pending.apply());
        var bag = new GuardianBackpack();
        check(!bag.bakeBread(), "Empty bag cannot create bread");
        bag.addItem(new ItemStack(Items.WHEAT, 7));
        check(bag.bakeBread() && bag.bakeBread(), "Two loaves from seven wheat");
        check(bag.countItem(Items.WHEAT) == 1 && bag.countItem(Items.BREAD) == 2, "3:1 crafting conservation");
        check(!bag.bakeBread(), "Cannot bake with one wheat");
        check(!bag.consume(Items.BREAD, 3) && bag.countItem(Items.BREAD) == 2, "Failed consumption changes nothing");
        check(bag.consume(Items.BREAD, 1) && bag.countItem(Items.BREAD) == 1, "Food delivery consumes one loaf");

        bag = fullBag();
        bag.setItem(0, new ItemStack(Items.WHEAT, 64));
        check(!bag.bakeBread(), "A full bag with no bread space must refuse crafting");
        check(bag.countItem(Items.WHEAT) == 64 && bag.countItem(Items.CARROT) == 26 * 64, "Failed craft preserves all supplies");
        bag.setItem(0, new ItemStack(Items.WHEAT, 3));
        check(bag.bakeBread() && bag.countItem(Items.BREAD) == 1 && bag.countItem(Items.WHEAT) == 0,
                "Crafting can reuse the slot emptied by its ingredients");

        bag = new GuardianBackpack();
        var wheat = new ItemStack(Items.WHEAT);
        var seeds = new ItemStack(Items.WHEAT_SEEDS, 3);
        var plan = bag.planHarvest(List.of(wheat, seeds));
        check(plan != null && bag.isEmpty(), "Planning harvest must not change inventory before the block update");
        bag.commitHarvest(plan);
        check(bag.countItem(Items.WHEAT) == 1 && bag.countItem(Items.WHEAT_SEEDS) == 2, "Harvest reserves one real seed for replanting");
        check(wheat.getCount() == 1 && seeds.getCount() == 3, "Loot inputs are not mutated");
        check(bag.consume(Items.WHEAT_SEEDS, 1) && bag.countItem(Items.WHEAT_SEEDS) == 1, "Planting consumes stored seeds");
        var noSeeds = new GuardianBackpack();
        check(noSeeds.planHarvest(List.of(wheat)) == null && noSeeds.isEmpty(), "Cannot replant without a seed");

        bag = fullBag();
        bag.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 2));
        plan = bag.planHarvest(List.of(wheat, seeds));
        check(plan != null && plan.overflow().size() == 1 && plan.overflow().getFirst().is(Items.WHEAT), "Overflow harvest items must be retained for world drops");
        bag.commitHarvest(plan);
        check(bag.countItem(Items.WHEAT_SEEDS) == 4 && bag.countItem(Items.CARROT) == 26 * 64, "Full-inventory harvest conserves stored contents");

        bag = new GuardianBackpack();
        bag.setItem(0, new ItemStack(Items.WHEAT, 3));
        bag.setItem(26, new ItemStack(Items.BONE_MEAL, 7));
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        bag.save(output);
        var loaded = new GuardianBackpack();
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, output.buildResult()));
        check(loaded.getItem(0).is(Items.WHEAT) && loaded.getItem(0).getCount() == 3, "First slot survives save/load");
        check(loaded.getItem(26).is(Items.BONE_MEAL) && loaded.getItem(26).getCount() == 7, "Last slot survives save/load");
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, output.buildResult()));
        check(loaded.countItem(Items.BONE_MEAL) == 7, "Repeated loading must not duplicate items");
        check(GuardianBackpack.isSupply(new ItemStack(Items.WHEAT_SEEDS)) && !GuardianBackpack.isSupply(new ItemStack(Items.DIAMOND)), "Only supplies enter backpack slots");
        check(GuardianMode.fromId(-1) == GuardianMode.PATROL && GuardianMode.fromId(99) == GuardianMode.PATROL, "Invalid saved mode safely defaults to patrol");
        System.out.println("PASS: " + checks + " inventory, crafting, replanting, overflow and persistence checks.");
    }
}
