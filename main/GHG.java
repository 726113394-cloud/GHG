package io.Sriptirc_wp_1258.gost;

import io.Sriptirc_wp_1258.gost.commands.GhgCommand;
import io.Sriptirc_wp_1258.gost.commands.GhgAdminCommand;
import io.Sriptirc_wp_1258.gost.listeners.*;
import io.Sriptirc_wp_1258.gost.managers.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class GHG extends JavaPlugin {
    
    private static GHG instance;
    
    /**
     * bStats 插件 ID。为 0 时不上报任何数据；
     * 在 bstats.org 注册后填入真实 ID 即自动生效。
     */
    private static final int BSTATS_PLUGIN_ID = 0;
    
    private boolean spearRushEnabled = false;
    /** 冲刺矛用的外观（长矛 / 三叉戟），由服务器版本决定 */
    private String spearVisualName = "长矛";
    
    private ConfigManager configManager;
    private ModeManager modeManager;
    private HunterGameManager hunterGameManager;
    private HunterStatsManager hunterStatsManager;
    private PlayerManager playerManager;
    private ItemManager itemManager;
    private ItemEffectRegistry itemEffectRegistry;
    private TeamManager teamManager;
    private AreaManager areaManager;
    private SelectionManager selectionManager;
    private EconomyManager economyManager;
    private ActionBarManager actionBarManager;
    private LanguageManager languageManager;
    private ItemSpawnManager itemSpawnManager;
    private QueueManager queueManager;
    private MenuManager menuManager;
    
    @Override
    public void onEnable() {
        instance = this;
        
        checkSpearRushSupport();
        
        try {
            configManager = new ConfigManager(this);
            modeManager = new ModeManager(this);

            // LanguageManager 只需要 ConfigManager，提前创建：
            // ItemManager 建道具时要用它取道具显示名（item-name.*）
            try {
                languageManager = new LanguageManager(this);
            } catch (NoClassDefFoundError | Exception e) {
                getLogger().warning("无法初始化 LanguageManager: " + e.getMessage());
                getLogger().warning("语言功能将不可用，但插件会继续运行");
                languageManager = null;
            }

            economyManager = new EconomyManager(this);
            areaManager = new AreaManager(this);
            selectionManager = new SelectionManager(this);
            teamManager = new TeamManager(this);
            itemManager = new ItemManager(this);
            itemEffectRegistry = new ItemEffectRegistry(this);
            ItemEffects.registerAll(itemEffectRegistry);
            playerManager = new PlayerManager(this);
            hunterGameManager = new HunterGameManager(this);
            hunterStatsManager = new HunterStatsManager(this);
            actionBarManager = new ActionBarManager(this);
            
            itemSpawnManager = new ItemSpawnManager(this);
            queueManager = new QueueManager(this);
            menuManager = new MenuManager(this);
        } catch (Exception e) {
            getLogger().severe("初始化管理器时发生错误: " + e.getMessage());
            e.printStackTrace();
            return;
        }
        
        try {
            getCommand("ghg").setExecutor(new GhgCommand(this));
            getCommand("ghga").setExecutor(new GhgAdminCommand(this));
            getCommand("ghga").setTabCompleter(new GhgAdminCommand(this));
            
            getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
            getServer().getPluginManager().registerEvents(new GameListener(this), this);
            getServer().getPluginManager().registerEvents(new ItemListener(this), this);
            getServer().getPluginManager().registerEvents(new SelectionListener(this), this);
            getServer().getPluginManager().registerEvents(new HunterListener(this), this);
            getServer().getPluginManager().registerEvents(new KindMenuListener(this), this);
            getServer().getPluginManager().registerEvents(new RoleMenuListener(this), this);
            getServer().getPluginManager().registerEvents(new GhgMenuListener(this), this);
            
            // 启动信息只由这一处输出，各管理器不再各打一行「已加载 N 个…」
            io.Sriptirc_wp_1258.gost.managers.StartupReport.print(this);
            
            io.Sriptirc_wp_1258.gost.managers.ServerCompat.runLater(this, () -> {
                configManager.printMigrationReport();
            }, 20L);

            enableMetrics();

            if (getConfig().getBoolean("update-check.enabled", true)) {
                UpdateChecker.checkAsync(this);
            }
        } catch (Exception e) {
            getLogger().severe("注册命令和监听器时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @Override
    public void onDisable() {
        if (modeManager != null) {
            modeManager.reset();
        }
        if (queueManager != null) {
            queueManager.clear();
        }
        if (hunterGameManager != null && hunterGameManager.isRunning()) {
            hunterGameManager.forceStop();
        }
        if (teamManager != null) {
            teamManager.cleanup();
        }
        getLogger().info("GHG 插件已禁用");
    }
    
    /**
     * bStats 匿名统计（可选）。
     *
     * <p>ID 为 0 时不上报任何数据 —— 在 <a href="https://bstats.org">bstats.org</a>
     * 注册插件后把真实 ID 填到 {@link #BSTATS_PLUGIN_ID} 即可生效，无需改动其他代码。</p>
     *
     * <p>服务器主可通过 bStats 自身的 {@code plugins/bStats/config.yml} 全局关闭，
     * 因此本插件不再额外提供开关。</p>
     */
    private void enableMetrics() {
        if (BSTATS_PLUGIN_ID <= 0) {
            return; // 未配置 ID 就静默，启动报告里也不占一行
        }
        try {
            new org.bstats.bukkit.Metrics(this, BSTATS_PLUGIN_ID);
            getLogger().info("bStats 统计已启用（ID " + BSTATS_PLUGIN_ID + "）");
        } catch (Throwable t) {
            getLogger().warning("bStats 初始化失败: " + t.getMessage());
        }
    }

    public static GHG getInstance() {
        return instance;
    }
    
    public ConfigManager getConfigManager() { return configManager; }
    public ModeManager getModeManager() { return modeManager; }
    public HunterGameManager getHunterGameManager() { return hunterGameManager; }
    public HunterStatsManager getHunterStatsManager() { return hunterStatsManager; }
    public PlayerManager getPlayerManager() { return playerManager; }
    public ItemManager getItemManager() { return itemManager; }
    public ItemEffectRegistry getItemEffectRegistry() { return itemEffectRegistry; }
    public TeamManager getTeamManager() { return teamManager; }
    public AreaManager getAreaManager() { return areaManager; }
    public SelectionManager getSelectionManager() { return selectionManager; }
    public EconomyManager getEconomyManager() { return economyManager; }
    public ActionBarManager getActionBarManager() { return actionBarManager; }
    public LanguageManager getLanguageManager() {
        if (languageManager == null) {
            languageManager = new LanguageManager(this);
        }
        return languageManager;
    }
    public ItemSpawnManager getItemSpawnManager() { return itemSpawnManager; }
    public QueueManager getQueueManager() { return queueManager; }
    public MenuManager getMenuManager() { return menuManager; }
    
    public boolean isSpearRushEnabled() {
        return spearRushEnabled;
    }

    /** 冲刺矛外观名（长矛 / 三叉戟），启动报告用 */
    public String getSpearVisualName() {
        return spearVisualName;
    }
    
    private void checkSpearRushSupport() {
        String version = Bukkit.getBukkitVersion();
        try {
            String verNum = version.split("-")[0];
            String[] parts = verNum.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = (parts.length > 2) ? Integer.parseInt(parts[2]) : 0;
            spearRushEnabled = true;
            if (major > 1 || (major == 1 && minor > 21) || (major == 1 && minor == 21 && patch >= 4)) {
                // ≥1.21.4：矛（GOLDEN_SPEAR）存在，使用矛外观
                spearVisualName = "长矛";
            } else {
                // <1.21.4：矛不存在，使用三叉戟外观（TRIDENT），功能不变
                spearVisualName = "三叉戟";
            }
        } catch (Exception e) {
            spearRushEnabled = false;
            getLogger().warning("无法解析服务器版本，冲刺矛功能已禁用: " + e.getMessage());
        }
    }
}
