package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 一次道具使用的上下文：谁、用的哪件、点的哪个键、原版行为要不要放行。
 *
 * <p>默认拦截原版行为（避免药水被真的喝掉、钟被真的摇），
 * 需要原版行为继续的（投掷凝冰球、扔末影珍珠）由效果自己调
 * {@link #allowVanillaAction()}。</p>
 */
public final class ItemUseContext {

    private final GHG plugin;
    private final Player player;
    private final ItemStack item;
    private final boolean rightClick;
    private final boolean leftClick;
    private boolean cancelVanilla = true;

    public ItemUseContext(GHG plugin, Player player, ItemStack item,
                          boolean rightClick, boolean leftClick) {
        this.plugin = plugin;
        this.player = player;
        this.item = item;
        this.rightClick = rightClick;
        this.leftClick = leftClick;
    }

    public GHG plugin() { return plugin; }
    public Player player() { return player; }
    public ItemStack item() { return item; }
    public boolean isRightClick() { return rightClick; }
    public boolean isLeftClick() { return leftClick; }

    /** 放行原版行为（投掷类道具用） */
    public void allowVanillaAction() {
        cancelVanilla = false;
    }

    /** 原版行为是否应该被拦截 */
    public boolean shouldCancelVanilla() {
        return cancelVanilla;
    }

    /**
     * 消耗**一件**。
     *
     * <p>⚠️ 原来用的是 {@code inventory.remove(item)}：Bukkit 的 {@code remove(ItemStack)}
     * 会把这个玩家身上**所有相同堆**整摞删掉 —— 而 GHG 的道具默认「不可叠加、同种不能有两件」，
     * 所以两瓶漂浮药水其实是两个独立格子，用一次就会**两瓶一起消失**。现在按 id 找到第一个格子、
     * 只减一件。</p>
     */
    public void consume() {
        if (item == null || player == null) return;
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        String usedId = ItemIds.idOf(item);
        org.bukkit.Material usedType = item.getType();
        org.bukkit.inventory.ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            org.bukkit.inventory.ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) continue;
            boolean match = usedId != null ? usedId.equals(ItemIds.idOf(stack))
                : stack.getType() == usedType;
            if (!match) continue;
            if (stack.getAmount() > 1) {
                stack.setAmount(stack.getAmount() - 1);
                inv.setItem(slot, stack);
            } else {
                inv.setItem(slot, null);
            }
            player.updateInventory();
            return;
        }
        player.updateInventory();
    }
}
