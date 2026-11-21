package io.Sriptirc_wp_1258.gost.listeners;

import io.Sriptirc_wp_1258.gost.GHG;
import io.Sriptirc_wp_1258.gost.managers.HunterRole;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * 逃生者职业选择菜单。
 *
 * <p>职业原本是纯随机的，抽到冰球手和抽到工程师完全看脸，还要按职业收入场券——
 * 这里给一个能点的箱子菜单（准备阶段自动弹，也能用 {@code /ghg role} 打开），
 * 换职业时按差价补/退职业券。</p>
 */
public class RoleMenuListener implements Listener {

    public static final class Holder implements InventoryHolder {
        private final UUID ownerId;

        public Holder(UUID ownerId) {
            this.ownerId = ownerId;
        }

        public UUID getOwnerId() {
            return ownerId;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final GHG plugin;

    public RoleMenuListener(GHG plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Holder holder = (Holder) event.getInventory().getHolder();
        if (holder.getOwnerId() == null || !holder.getOwnerId().equals(player.getUniqueId())) return;

        HunterRole role = plugin.getHunterGameManager().roleForSlot(event.getRawSlot());
        if (role == null) return;
        if (plugin.getHunterGameManager().selectRole(player, role)) {
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) return;
        event.setCancelled(true);
    }
}
