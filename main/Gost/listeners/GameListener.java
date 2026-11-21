package io.Sriptirc_wp_1258.gost.listeners;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public class GameListener implements Listener {
    
    private final GHG plugin;
    
    public GameListener(GHG plugin) {
        this.plugin = plugin;
    }
    
    /**
     * 猎场保护：不在对局里、又没有管理权限的人，不能在猎场圈定的范围内挖方块。
     *
     * <p>原来只有「对局中的玩家」会被拦，路人（甚至没进对局的 OP 之外的人）可以随手把
     * 法阵地砖、工程师留下的痕迹全挖了。这里补一层场地保护。</p>
     *
     * @return true 表示已被拦下
     */
    private boolean blockedByAreaProtection(Player player, org.bukkit.Location location) {
        if (player == null || location == null) return false;
        if (!plugin.getConfigManager().isAreaProtectionEnabled()) return false;
        if (player.hasPermission("ghg.admin")) return false;
        if (plugin.getPlayerManager().getAllPlayers().contains(player.getUniqueId())) return false;
        if (!plugin.getAreaManager().isInsideAnyArea(location)) return false;
        player.sendMessage(plugin.getLanguageManager().getMessage(player, "area.protected"));
        return true;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        if (blockedByAreaProtection(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        
        // 如果玩家在游戏中，禁止破坏方块
        if (plugin.getPlayerManager().getAllPlayers().contains(player.getUniqueId())) {
            // GHG：道具箱本身就是可破坏方块，破箱判定交给 HunterGameManager
            if (plugin.getModeManager().isHunter()) {
                // 工程师放的栅栏：母体与工程师能一瞬间拆掉，其他人拆不动
                if (plugin.getHunterGameManager().isFenceBlock(event.getBlock().getLocation())) {
                    event.setCancelled(true);
                    // 提示与次数都在 breakFence 里处理（母体/工程师要打好几下才塌）
                    plugin.getHunterGameManager().breakFence(player, event.getBlock());
                }
                return;
            }
            event.setCancelled(true);
            player.sendMessage(plugin.getLanguageManager().getMessage(player, "game.block-break"));
        }
    }
    
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        if (blockedByAreaProtection(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        
        // 如果玩家在游戏中，禁止放置方块
        if (plugin.getPlayerManager().getAllPlayers().contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLanguageManager().getMessage(player, "game.block-place"));
        }
    }
    
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        
        // 如果玩家在游戏中，检查是否离开游戏区域
        if (plugin.getPlayerManager().getAllPlayers().contains(player.getUniqueId())) {
            // 检查游戏区域
            io.Sriptirc_wp_1258.gost.managers.AreaManager.GameArea selectedArea = plugin.getAreaManager().getSelectedArea();
            if (selectedArea != null) {
                if (!selectedArea.contains(player.getLocation())) {
                    // 将玩家传送回区域中心
                    plugin.getAreaManager().teleportPlayerToArea(player, selectedArea);
                    player.sendMessage(plugin.getLanguageManager().getMessage(player, "game.leave-area"));
                }
            }
        }
    }
    
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        
        // 如果玩家在游戏中，检查物品使用
        if (plugin.getPlayerManager().getAllPlayers().contains(player.getUniqueId())) {
            // 物品使用由ItemListener处理
            return;
        }
    }
    
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // 这个事件由InfectionListener处理
        return;
    }
    
}