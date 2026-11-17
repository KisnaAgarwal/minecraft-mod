package com.kisna.harvestguardian;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Real stacks shared by the menu, farming, baking, collection and food delivery. */
public final class GuardianBackpack extends SimpleContainer {
    public static final int SIZE = 27;
    private static final Set<Item> SUPPLIES = Set.of(Items.WHEAT_SEEDS, Items.WHEAT, Items.BREAD,
            Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BEETROOT_SEEDS, Items.BONE_MEAL,
            Items.PUMPKIN_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN, Items.MELON_SLICE);

    public GuardianBackpack() { super(SIZE); }
    public static boolean isSupply(ItemStack stack) { return SUPPLIES.contains(stack.getItem()); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return isSupply(stack); }

    public boolean consume(Item item, int count) {
        if (count <= 0 || countItem(item) < count) return false;
        removeItemType(item, count);
        setChanged();
        return true;
    }

    private SimpleContainer copyInventory() {
        SimpleContainer copy = new SimpleContainer(SIZE);
        for (int i = 0; i < SIZE; i++) copy.setItem(i, getItem(i).copy());
        return copy;
    }

    private void commit(SimpleContainer copy) {
        for (int i = 0; i < SIZE; i++) setItem(i, copy.getItem(i).copy());
        setChanged();
    }

    public boolean bakeBread() {
        if (countItem(Items.WHEAT) < 3) return false;
        SimpleContainer copy = copyInventory();
        copy.removeItemType(Items.WHEAT, 3);
        if (!copy.addItem(new ItemStack(Items.BREAD)).isEmpty()) return false;
        commit(copy);
        return true;
    }

    /** Reserve one real seed, using harvest drops if necessary; never mutate on planning failure. */
    public HarvestPlan planHarvest(List<ItemStack> drops) {
        SimpleContainer copy = copyInventory();
        List<ItemStack> remaining = drops.stream().map(ItemStack::copy).toList();
        if (copy.countItem(Items.WHEAT_SEEDS) > 0) {
            copy.removeItemType(Items.WHEAT_SEEDS, 1);
        } else {
            ItemStack seed = remaining.stream().filter(s -> s.is(Items.WHEAT_SEEDS) && !s.isEmpty()).findFirst().orElse(null);
            if (seed == null) return null;
            seed.shrink(1);
        }
        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack stack : remaining) {
            ItemStack rest = copy.addItem(stack);
            if (!rest.isEmpty()) overflow.add(rest);
        }
        return new HarvestPlan(copy, List.copyOf(overflow));
    }

    public void commitHarvest(HarvestPlan plan) { commit(plan.inventory()); }
    public record HarvestPlan(SimpleContainer inventory, List<ItemStack> overflow) {}

    public void save(ValueOutput output) { ContainerHelper.saveAllItems(output, getItems()); }
    public void load(ValueInput input) {
        clearContent();
        ContainerHelper.loadAllItems(input, getItems());
        setChanged();
    }
}
