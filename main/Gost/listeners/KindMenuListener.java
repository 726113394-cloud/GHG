package io.Sriptirc_wp_1258.gost.listeners;

import io.Sriptirc_wp_1258.gost.GHG;
import io.Sriptirc_wp_1258.gost.managers.MotherKind;
import io.Sriptirc_wp_1258.gost.managers.ServerCompat;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 母体种类选择菜单。
 *
 * <p>原来只能敲 {@code /ghga hunter kind <种类>}，基岩版玩家长命令很别扭。这里给一个能点的箱子菜单，
 * 准备阶段自动弹给玩家母体。</p>
 */
public class KindMenuListener implements Listener {

    /** 标记用的 Holder：只用来认「这是我们的菜单」 */
    public static final class Holder implements InventoryHolder {
        private final UUID ownerId;
        /** 选完才允许关掉菜单 */
        private boolean selectionMade;

        public Holder(UUID ownerId) {
            this.ownerId = ownerId;
        }

        public UUID getOwnerId() {
            return ownerId;
        }

        public boolean isSelectionMade() {
            return selectionMade;
        }

        public void setSelectionMade(boolean selectionMade) {
            this.selectionMade = selectionMade;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final GHG plugin;

    public KindMenuListener(GHG plugin) {
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

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        MotherKind kind = plugin.getHunterGameManager().kindForSlot(slot);
        if (kind == null) return;

        if (!plugin.getHunterGameManager().selectMotherKind(kind)) {
            plugin.getLanguageManager().sendMessage(player, "hunter.cmd.kind-too-late");
            return;
        }
        // 选成功了才放行关闭
        holder.setSelectionMade(true);
        player.closeInventory();
    }

    /**
     * 没选就不能关：按 E（或 ESC）关掉的话，下一 tick 原样弹回来。
     *
     * <p>只剩「必须选一个」这一个出口——不然玩家母体一键关掉菜单就带着默认种类开局了。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) return;
        Holder holder = (Holder) event.getInventory().getHolder();
        if (holder.isSelectionMade()) return;
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        if (holder.getOwnerId() == null || !holder.getOwnerId().equals(player.getUniqueId())) return;
        // 准备阶段结束（开打/收局）就不该再拦人了
        if (!plugin.getHunterGameManager().isPreparing()) return;

        plugin.getLanguageManager().sendMessage(player, "hunter.kindmenu.must-select");
        ServerCompat.runLater(plugin, () -> {
            if (!player.isOnline()) return;
            plugin.getHunterGameManager().openKindMenu(player);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
