package io.Sriptirc_wp_1258.gost.listeners;

import io.Sriptirc_wp_1258.gost.GHG;
import io.Sriptirc_wp_1258.gost.managers.MenuManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * {@code /ghg} 的图形菜单点击处理。
 *
 * <p>只做「认界面 → 拦掉原版移动 → 交给 {@link MenuManager}」。菜单里的每个按钮做什么、
 * 打开哪个下一级，全在 MenuManager 里，这里不做业务判断。</p>
 */
public class GhgMenuListener implements Listener {

    private final GHG plugin;

    public GhgMenuListener(GHG plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuManager.Holder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        MenuManager.Holder holder = (MenuManager.Holder) event.getInventory().getHolder();
        if (holder.getOwnerId() == null || !holder.getOwnerId().equals(player.getUniqueId())) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        plugin.getMenuManager().handleClick(player, holder, slot, event.getClick());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuManager.Holder) {
            event.setCancelled(true);
        }
    }
}
