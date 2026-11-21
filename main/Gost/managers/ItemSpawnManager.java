package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ItemSpawnManager {
    
    private final GHG plugin;
    private ServerCompat.Cancellable spawnTask;
    private final Map<UUID, Integer> playerItemCounts = new ConcurrentHashMap<>();
    private final Random random = new Random();
    
    // 随机道具配置
    private final List<RandomItemConfig> randomItems = new ArrayList<>();
    
    public ItemSpawnManager(GHG plugin) {
        this.plugin = plugin;
        loadRandomItems();
    }
    
    /**
     * 加载随机道具配置
     */
    private void loadRandomItems() {
        randomItems.clear();
        
        // 臭牛排 - 速度2效果14秒 + 发光效果10秒 + 冷却30秒
        randomItems.add(new RandomItemConfig(
            "stinky-steak",
            Material.COOKED_BEEF,
            "&a臭牛排",
            Arrays.asList("&7食用后获得速度I效果和发光效果", "&7速度持续时间: 14秒", "&7发光持续时间: 10秒", "&7冷却时间: 30秒"),
            15,
            Arrays.asList(
                new PotionEffect(PotionEffectType.SPEED, 120, 0), // 6 秒速度Ⅰ
                new PotionEffect(PotionEffectType.GLOWING, 200, 0) // 10秒 * 20 = 200 ticks
            )
        ));
        
        // 传送珍珠 - 双方可用
        randomItems.add(new RandomItemConfig(
            "teleport-pearl",
            Material.ENDER_PEARL,
            "&5传送珍珠",
            Arrays.asList("&7右键投掷传送", "&7冷却时间: 20秒"),
            20,
            null // 传送珍珠没有药水效果，只有传送功能
        ));
        
        // 灵魂探测器 - 母体专属道具
        randomItems.add(new RandomItemConfig(
            "soul-detector",
            Material.COMPASS,
            "&d灵魂探测器",
            Arrays.asList("&7使用后所有玩家发光25秒", "&7冷却时间: 35秒", "&7母体专属道具"),
            12,
            Collections.singletonList(new PotionEffect(PotionEffectType.GLOWING, 500, 0)), // 25秒 * 20 = 500 ticks
            false, true // ghostOnly
        ));
        
        // 第二次机会 - 人类专属道具
        randomItems.add(new RandomItemConfig(
            "second-chance",
            Material.TOTEM_OF_UNDYING,
            "&6第二次机会",
            Arrays.asList(
                "&7可抵挡一次鬼的感染",
                "&6被动触发道具",
                "&a仅限人类使用",
                "&c[唯一道具]",
                "",
                "&7效果:",
                "&7• 人类: 速度II 10秒",
                "&7• 人类: 高亮 10秒",
                "&7• 鬼: 缓慢I 7秒",
                "",
                "&7冷却时间: 180秒"
            ),
            10,
            null, // 被动触发，没有直接药水效果
            true, false // humanOnly
        ));
    }
    
    /** 自动投放池里有多少种随机道具（启动报告用） */
    public int getRandomItemCount() {
        return randomItems.size();
    }
    
    /**
     * 开始道具刷新任务
     */
    
    /**
     * 刷新道具
     */
    
    /**
     * 给予玩家随机道具
     */
    
    /**
     * 随机选择道具（根据权重）
     */
    
    /**
     * 创建随机道具
     */
    private ItemStack createRandomItem(RandomItemConfig config) {
        ItemStack item = new ItemStack(config.material);
        
        if (item.getItemMeta() instanceof PotionMeta) {
            PotionMeta meta = (PotionMeta) item.getItemMeta();
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', config.name));
            meta.setLore(config.lore.stream()
                .map(lore -> ChatColor.translateAlternateColorCodes('&', lore))
                .toList());
            
            // 添加药水效果
            for (PotionEffect effect : config.effects) {
                meta.addCustomEffect(effect, true);
            }
            
            item.setItemMeta(meta);
        } else {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', config.name));
                meta.setLore(config.lore.stream()
                    .map(lore -> ChatColor.translateAlternateColorCodes('&', lore))
                    .toList());
                item.setItemMeta(meta);
            }
        }
        
        return ItemIds.tag(item, config.id);
    }
    
    /**
     * 创建传送珍珠（公开方法，供ItemManager调用）
     */
    public ItemStack createTeleportPearl() {
        // 查找传送珍珠配置
        for (RandomItemConfig config : randomItems) {
            if (config.id.equals("teleport-pearl")) {
                return createRandomItem(config);
            }
        }
        
        // 如果找不到配置，创建默认的传送珍珠
        ItemStack teleportPearl = new ItemStack(Material.ENDER_PEARL);
        ItemMeta meta = teleportPearl.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "传送珍珠");
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键投掷传送",
                ChatColor.GRAY + "冷却时间: 10秒"
            ));
            teleportPearl.setItemMeta(meta);
        }
        return ItemIds.tag(teleportPearl, ItemIds.TELEPORT_PEARL);
    }
    
    /**
     * 创建臭牛排（公开方法）
     */
    public ItemStack createStinkySteak() {
        /* 说明按**当前配置**生成，不再走 randomItems 里那套写死的 14 秒 / 10 秒
           （那份文案早就不对了，玩家看到的和实际效果对不上）。 */
        int speedSeconds = plugin.getConfigManager().getStinkySteakSpeedDuration();
        int glowSeconds = plugin.getConfigManager().getStinkySteakGlowingDuration();
        ItemStack steak = new ItemStack(Material.COOKED_BEEF);
        ItemMeta meta = steak.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN
                + plugin.getLanguageManager().getMessage("item-name.stinky-steak"));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "按住右键吃掉（有进食动作，中途松手会打断）",
                ChatColor.GRAY + "吃完获得速度 " + speedSeconds + " 秒，",
                ChatColor.GRAY + "然后自己发光 " + glowSeconds + " 秒 —— 发光会暴露位置",
                ChatColor.DARK_GRAY + "不补饱食度：它只负责让你跑得快一阵"));
            steak.setItemMeta(meta);
        }
        return ItemIds.tag(steak, ItemIds.STINKY_STEAK);
    }
    
    /**
     * 创建灵魂探测器（公开方法）
     */
    public ItemStack createSoulDetector() {
        for (RandomItemConfig config : randomItems) {
            if (config.id.equals("soul-detector")) {
                return createRandomItem(config);
            }
        }
        ItemStack detector = new ItemStack(Material.COMPASS);
        ItemMeta meta = detector.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "灵魂探测器");
            meta.setLore(Arrays.asList(ChatColor.GRAY + "使用后所有玩家发光25秒"));
            detector.setItemMeta(meta);
        }
        return ItemIds.tag(detector, ItemIds.SOUL_DETECTOR);
    }
    
    /**
     * 停止道具刷新
     */
    public void stopSpawning() {
        if (spawnTask != null && !spawnTask.isCancelled()) {
            spawnTask.cancel();
            spawnTask = null;
        }
        
        playerItemCounts.clear();
        plugin.getLogger().info("道具刷新系统已停止");
    }
    
    /**
     * 清理玩家数据
     */
    public void cleanupPlayer(UUID playerId) {
        playerItemCounts.remove(playerId);
    }
    
    /**
     * 清理所有数据
     */
    public void cleanup() {
        playerItemCounts.clear();
        if (spawnTask != null && !spawnTask.isCancelled()) {
            spawnTask.cancel();
        }
        spawnTask = null;
    }
    
    /**
     * 随机道具配置类
     */
    private static class RandomItemConfig {
        final String id;
        final Material material;
        final String name;
        final List<String> lore;
        final int weight;
        final List<PotionEffect> effects;
        final boolean humanOnly;
        final boolean ghostOnly;
        
        RandomItemConfig(String id, Material material, String name, List<String> lore, 
                        int weight, List<PotionEffect> effects) {
            this(id, material, name, lore, weight, effects, false, false);
        }
        
        RandomItemConfig(String id, Material material, String name, List<String> lore, 
                        int weight, List<PotionEffect> effects, boolean humanOnly, boolean ghostOnly) {
            this.id = id;
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.weight = weight;
            this.effects = effects;
            this.humanOnly = humanOnly;
            this.ghostOnly = ghostOnly;
        }
    }
}