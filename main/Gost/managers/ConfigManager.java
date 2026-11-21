package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    
    private final GHG plugin;
    private FileConfiguration config;
    
    /**
     * GHG 自己的配置版本，从 1 开始 —— 和 Gost 的版本号（当时 34+）无关。
     * 只要改了 config.yml 的结构就 +1；读到的版本不一致会自动迁移并备份。
     */
    private static final int CURRENT_CONFIG_VERSION = 31;

    /** 配置版本号的主键（GHG 自己的） */
    private static final String VERSION_KEY = "ghg-config-version";
    /** 历史键名：从 Gost/ScriptIrc 继承来的，只读一次做迁移 */
    private static final String LEGACY_VERSION_KEY = "ScriptIrc-config-version";

    /** 当前代码期望的配置版本（自检用） */
    public int getCurrentConfigVersion() {
        return CURRENT_CONFIG_VERSION;
    }
    
    // 记录迁移数据，用于服务器启动后打印详情
    private boolean configMigrated = false;
    private int oldConfigVersion = 0;
    private java.util.Map<String, Object> migratedValues = new java.util.LinkedHashMap<>();
    
    public ConfigManager(GHG plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    /**
     * 把配置文件里「内置 config.yml 没有的键」删掉。
     *
     * <p>GHG 生成的 config 只含本插件真正读取的段，但老存档是从 Gost 迁过来的，
     * 里面躺着一堆经典局的死键（感染 / 猎魔人 / 鬼粒子 …）。这里以 jar 里的
     * config.yml 当白名单做一次清理；<b>旧文件已经备份成 config_old_v&lt;版本&gt;.yml</b>，
     * 想找回任何一个键都能从备份里抄。</p>
     *
     * @return 删掉了多少个键
     */
    private int pruneUnknownKeys() {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        try (java.io.InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return 0;
            org.bukkit.configuration.file.YamlConfiguration template =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            allowed.addAll(template.getKeys(true));
        } catch (Throwable t) {
            plugin.getLogger().warning("读取内置 config 模板失败，跳过配置瘦身: " + t.getMessage());
            return 0;
        }
        if (allowed.isEmpty()) return 0;

        int removed = 0;
        for (String key : new java.util.ArrayList<>(config.getKeys(true))) {
            if (VERSION_KEY.equals(key) || LEGACY_VERSION_KEY.equals(key)) continue;
            if (allowed.contains(key)) continue;
            config.set(key, null);
            removed++;
        }
        return removed;
    }

    private void ensureConfigLoaded() {
        if (config == null) {
            loadConfig();
        }
    }

    /** 某个整数键还等于旧默认值就换成新默认值；返回改动条数（0 或 1） */
    private int retuneInt(String key, int oldDefault, int newDefault) {
        if (config == null || !config.contains(key)) return 0;
        if (config.getInt(key, oldDefault) != oldDefault) return 0;
        config.set(key, newDefault);
        return 1;
    }

    /** 布尔键：值仍是旧默认值就换成新默认值 */
    private int retuneBoolean(String key, boolean oldDefault, boolean newDefault) {
        if (config == null || !config.contains(key)) return 0;
        if (config.getBoolean(key, oldDefault) != oldDefault) return 0;
        config.set(key, newDefault);
        return 1;
    }

    /** 同上，小数版 */
    private int retuneDouble(String key, double oldDefault, double newDefault) {
        if (config == null || !config.contains(key)) return 0;
        if (Math.abs(config.getDouble(key, oldDefault) - oldDefault) > 1e-9) return 0;
        config.set(key, newDefault);
        return 1;
    }
    
    private void loadConfig() {
        if (config != null) {
            return; // 已经加载
        }
        plugin.saveDefaultConfig();
        config = plugin.getConfig();
        
        // 检查配置版本，如果不一致则自动迁移旧配置数据
        int savedVersion = config.getInt(VERSION_KEY, config.getInt(LEGACY_VERSION_KEY, 0));
        if (savedVersion != CURRENT_CONFIG_VERSION) {
            plugin.getLogger().info("配置版本 v" + savedVersion + " → v" + CURRENT_CONFIG_VERSION
                + "，开始迁移（旧键会全部保留）");
            
            // 保存旧配置的所有值
            java.util.Map<String, Object> oldValues = new java.util.LinkedHashMap<>();
            if (savedVersion > 0) {
                for (String key : config.getKeys(true)) {
                    // 跳过版本号本身
                    if (key.equals(VERSION_KEY) || key.equals(LEGACY_VERSION_KEY)) continue;
                    Object value = config.get(key);
                    if (value != null) {
                        oldValues.put(key, value);
                    }
                }
                plugin.getLogger().info("读取旧配置 " + oldValues.size() + " 项");
            }
            
            // 备份旧配置文件
            java.io.File configFile = new java.io.File(plugin.getDataFolder(), "config.yml");
            if (configFile.exists()) {
                java.io.File backupFile = new java.io.File(plugin.getDataFolder(), "config_old_v" + savedVersion + ".yml");
                try {
                    java.nio.file.Files.copy(configFile.toPath(), backupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    plugin.getLogger().info("旧配置已备份 config_old_v" + savedVersion + ".yml");
                } catch (java.io.IOException e) {
                    plugin.getLogger().warning("备份旧配置文件失败: " + e.getMessage());
                }
            }
            
            // 重新生成新配置
            plugin.getDataFolder().mkdirs();
            plugin.saveResource("config.yml", true);
            plugin.reloadConfig();
            config = plugin.getConfig();
            
            // 将旧配置中的值**全部**写回新配置。
            // 这里刻意不做「死键清理」：新配置里没有、甚至当前代码根本用不到的旧键，
            // 也原样搬过去，方便以后复用或改回去。丢掉用户手改过的键是不可接受的。
            // 例外：ConfigurationSection 本身不搬（它的子键会单独列出，搬节对象只会写坏 YAML）。
            int migratedCount = 0;
            migratedValues.clear();
            for (java.util.Map.Entry<String, Object> entry : oldValues.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof org.bukkit.configuration.ConfigurationSection) continue;
                config.set(key, value);
                migratedValues.put(key, value);
                migratedCount++;
            }
            
            // 确保版本号正确；旧版本键保留（同样不做清理，留着无害）
            config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
            plugin.saveConfig();
            
            // 记录迁移状态，等服务器完全启动后再打印详情
            configMigrated = true;
            oldConfigVersion = savedVersion;
            
            plugin.getLogger().info("配置迁移完成：" + migratedCount + " 项已写入新配置，明细在启动报告后打印");
        }

        // v2 调参升级：把**仍然是旧默认值**的手感键换成新默认值。
        // 只在从 v1 升上来时做一次——之后管理员就算把值改回旧默认值，也不会再被覆盖。
        if (savedVersion < 2) {
            int retuned = 0;
            retuned += retuneInt("hunter.duration", 480, 1200);
            retuned += retuneInt("hunter.downed.bleedout-seconds", 30, 60);
            retuned += retuneDouble("hunter.heartbeat.range", 32.0, 48.0);
            if (retuned > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v2）：" + retuned
                    + " 项仍是旧默认值，已换成新默认值（对局 15 分钟 / 放血 60 秒 / 心跳半径 48 格）");
            }
        }
        
        // v3 调参升级：同样是「只改仍是旧默认值的键」
        if (savedVersion < 3) {
            int retuned3 = 0;
            retuned3 += retuneInt("hunter.duration", 900, 1200);
            // 开箱件数在 v4 里回退成 2，v3 这里不再动它
            retuned3 += retuneInt("hunter.mother.attack-cooldown-seconds", 5, 4);
            retuned3 += retuneInt("hunter.downed.hit-burst-seconds", 3, 2);
            retuned3 += retuneInt("hunter.chest.wave-interval-seconds", 60, 40);
            if (retuned3 > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v3）：" + retuned3
                    + " 项仍是旧默认值，已换成新默认值（对局 20 分钟 / 箱子 3 件 / 斧子 4 秒 / 挣脱加速 2 秒）");
            }
        }

        // v4：开箱件数回退成 2 件（第一件仍是应急口粮，剩 1 件随机）
        if (savedVersion < 4) {
            if (retuneInt("hunter.chest.drops", 3, 2) > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v4）：道具箱回复成 2 件（第一件仍是应急口粮）");
            }
        }

        // v5：票价整体下调（入场费 100→50、保底 3000→500、母体种类门票除以 4）+ 行动分抬高一档
        if (savedVersion < 5) {
            int retuned5 = 0;
            retuned5 += retuneDouble("hunter.reward.entry-fee", 100.0, 50.0);
            retuned5 += retuneDouble("hunter.reward.server-bonus", 3000.0, 500.0);
            retuned5 += retuneDouble("hunter.mother.kind-cost.CHARGER", 400.0, 100.0);
            retuned5 += retuneDouble("hunter.mother.kind-cost.ARCHER", 500.0, 120.0);
            retuned5 += retuneDouble("hunter.mother.kind-cost.ALCHEMIST", 400.0, 100.0);
            retuned5 += retuneDouble("hunter.mother.kind-cost.WALKER", 600.0, 150.0);
            retuned5 += retuneDouble("hunter.mother.kind-cost.WATCHER", 700.0, 180.0);
            retuned5 += retuneDouble("hunter.mother.kind-cost.WEAVER", 500.0, 120.0);
            retuned5 += retuneDouble("hunter.reward.actions.altar-second", 1.0, 2.0);
            retuned5 += retuneDouble("hunter.reward.actions.rescue", 8.0, 15.0);
            retuned5 += retuneDouble("hunter.reward.actions.escape", 12.0, 25.0);
            retuned5 += retuneDouble("hunter.reward.actions.mother-hit", 3.0, 5.0);
            retuned5 += retuneDouble("hunter.reward.actions.mother-down", 6.0, 10.0);
            retuned5 += retuneDouble("hunter.reward.actions.mother-eliminate", 25.0, 40.0);
            if (retuned5 > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v5）：" + retuned5
                    + " 项经济数值已按新价位调整（入场 50 / 保底 500 / 门票下调 / 行动分上调）");
            }
        }

        // v6：手感受众调软后的数值回收 + 准备时间 30 秒
        if (savedVersion < 6) {
            int retuned6 = 0;
            retuned6 += retuneInt("hunter.downed.recover-seconds", 25, 15);
            retuned6 += retuneInt("hunter.downed.rescue-protect-seconds", 5, 3);
            retuned6 += retuneInt("hunter.downed.hit-burst-seconds", 2, 1);
            retuned6 += retuneInt("hunter.preparation-seconds", 20, 30);
            if (retuned6 > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v6）：" + retuned6
                    + " 项已更新（自愈 15s / 保护 3s / 挣脱 1s / 准备 30s）");
            }
        }

        // v7：受击链重做 + 准备 25 秒 + 自愈 20 秒 + 法阵上限 8
        if (savedVersion < 7) {
            int retuned7 = 0;
            retuned7 += retuneInt("hunter.preparation-seconds", 30, 25);
            retuned7 += retuneInt("hunter.heal.self-seconds", 16, 20);
            retuned7 += retuneInt("hunter.altar.max", 4, 8);
            retuned7 += retuneInt("hunter.downed.hit-slow-seconds", 2, 0);
            retuned7 += retuneInt("hunter.mother.recover-seconds", 3, 2);
            retuned7 += retuneInt("hunter.mother.recover-level", 2, 4);
            if (retuned7 > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v7）：" + retuned7
                    + " 项已更新（准备 25s / 自愈 20s / 法阵上限 8 / 受击不再减速）");
            }
        }

        // v8：道具平衡（牛排速度等级语义修正 + 时长 8→6）
        if (savedVersion < 8) {
            int retuned8 = 0;
            retuned8 += retuneInt("items.stinky-steak.speed-duration", 8, 6);
            retuned8 += retuneInt("items.stinky-steak.speed-duration", 14, 6);
            if (retuned8 > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v8）：臭牛排速度时长收到 6 秒（速度等级按 1 = 速度Ⅰ 解释）");
            }
        }

        // v9：配置瘦身——按内置模板删掉 Gost 遗留键（旧值已备份在 config_old_v*.yml）
        if (savedVersion < 9) {
            int pruned = pruneUnknownKeys();
            config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
            plugin.saveConfig();
            if (pruned > 0) {
                plugin.getLogger().info("配置瘦身（v9）：删掉 " + pruned
                    + " 个 Gost 遗留键（旧文件已备份为 config_old_v" + savedVersion + ".yml）");
            }
        }

        // v10：母体基础移速 0.21 → 0.25（人类道具变多后追不上）
        if (savedVersion < 10) {
            if (retuneDouble("hunter.mother.speed", 0.21, 0.25) > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v10）：母体基础移速 0.21 → 0.25");
            }
        }

        // v11：测试局不再默认跳过技能冷却（技能冷却任何模式都生效）
        if (savedVersion < 11) {
            if (retuneBoolean("hunter.test.ignore-skill-cooldown", true, false) > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v11）：测试局的「忽略技能冷却」已关闭，技能在任何模式都吃冷却");
            }
        }

        // v12：技能冷却拉长 / 冲刺更远 / 面包一次性 / 法阵默认 4 个
        if (savedVersion < 12) {
            int retuned12 = 0;
            retuned12 += retuneInt("hunter.mother.skills.sprint.cooldown", 14, 20);
            retuned12 += retuneInt("hunter.mother.skills.dash.cooldown", 10, 16);
            retuned12 += retuneInt("hunter.mother.skills.arrow.cooldown", 8, 16);
            retuned12 += retuneInt("hunter.mother.skills.potion.cooldown", 12, 18);
            retuned12 += retuneInt("hunter.mother.skills.teleport.cooldown", 20, 28);
            retuned12 += retuneInt("hunter.mother.skills.vanish.cooldown", 20, 28);
            retuned12 += retuneInt("hunter.mother.skills.snare.cooldown", 12, 18);
            retuned12 += retuneDouble("hunter.mother.skills.dash.power", 1.6, 2.6);
            retuned12 += retuneInt("hunter.mother.skills.dash.stun-ticks", 30, 40);
            retuned12 += retuneInt("hunter.mother.skills.dash.stun-window-ticks", 24, 32);
            retuned12 += retuneInt("hunter.items.ration.bites", 3, 1);
            retuned12 += retuneInt("hunter.altar.count", 2, 4);
            if (retuned12 > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v12）：技能冷却拉长 / 冲刺更远 / 面包一次性（" + retuned12 + " 项）");
            }
        }

        // v13：拉长破阵进度（共享进度条后 4%/s 太快）+ 法阵数量修正
        if (savedVersion < 13) {
            int retuned13 = 0;
            retuned13 += retuneDouble("hunter.altar.progress-per-second", 4.0, 1.0);
            retuned13 += retuneDouble("hunter.altar.decay-per-second", 2.0, 0.6);
            retuned13 += retuneDouble("hunter.items.ritual-knife.progress", 25.0, 10.0);
            retuned13 += retuneInt("hunter.altar.count", 2, 4);
            retuned13 += retuneInt("hunter.altar.max", 4, 8);
            if (retuned13 > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v13）：破阵速度 4.0→1.0 %/s、守机衰减 2.0→0.6、"
                    + "破阵刀 25→10%、法阵数量 2→4、上限 4→8（" + retuned13 + " 项）");
            }
        }

        // v14：涨速封顶为「单人 0.9 倍」，守机衰减回到能推负（0.6 → 1.5）
        if (savedVersion < 14) {
            if (retuneDouble("hunter.altar.decay-per-second", 0.6, 1.5) > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v14）：守机衰减 0.6 → 1.5（进度可以被推成负数）");
            }
        }

        // v18：母体基础移速 0.25 → 0.26（微微再快一点；只改仍是旧默认值的配置）
        if (savedVersion < 18) {
            if (retuneDouble("hunter.mother.speed", 0.25, 0.26) > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v18）：母体基础移速 0.25 → 0.26");
            }
        }

        // v19：移除「卡住就瞬移到逃生者身边」——那是作弊；换成原地挤出去（stuck-escape: nudge）
        if (savedVersion < 19) {
            config.set("hunter.mother.stuck-teleport", null);
            config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
            plugin.saveConfig();
            plugin.getLogger().info("配置升级（v19）：已移除 stuck-teleport（不再瞬移到逃生者身边），改用 stuck-escape: nudge");
        }

        // v23：加固要打 16 次 + 加固后 60 秒冷却 + 斧头 3 秒回收（只改仍是旧默认值的配置）
        if (savedVersion < 23) {
            int t = 0;
            t += retuneInt("hunter.altar.harden-hits", 7, 16);
            t += retuneInt("hunter.altar.harden-cooldown-seconds", 5, 60);
            t += retuneInt("hunter.mother.axe-recover-seconds", 4, 3);
            if (t > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v23）：加固 16 次 / 加固冷却 60 秒 / 斧头 3 秒回收");
            }
        }

        // v26：法阵推满更快；应急口粮不再必出；漂浮药水更强（只改仍是旧默认值的配置）
        if (savedVersion < 26) {
            int t = 0;
            t += retuneDouble("hunter.altar.progress-per-second", 1.0, 1.3);
            t += retuneBoolean("hunter.items.ration.guaranteed-in-chest", true, false);
            t += retuneInt("items.levitation.duration-ticks", 90, 120);
            if (t > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v26）：法阵基准涨速 1.0 → 1.3 / 口粮不再必出 / 漂浮 4.5s → 6s");
            }
        }

        // v27：破阵时间缩短（1.3 → 1.8 %/s）—— 4 座阵 + 瘫痪 60 秒时一局推不完
        if (savedVersion < 27) {
            if (retuneDouble("hunter.altar.progress-per-second", 1.3, 1.8) > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v27）：法阵基准涨速 1.3 → 1.8 %/s（单人推满一座约 62 秒）");
            }
        }

        // v28：冲刺矛冲得更远（1.1 → 1.6 格/刻；1.1 只有 4~5 格，用户反馈太短）
        if (savedVersion < 28) {
            if (retuneDouble("items.spear-rush.velocity", 1.1, 1.6) > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v28）：冲刺矛初速 1.1 → 1.6（约 7~8 格）");
            }
        }

        // v29：新增观战席位与「淘汰后提前结算」相关键（没有旧值要迁，只把新键写进配置文件）
        if (savedVersion < 29) {
            config.options().copyDefaults(true);
            config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
            plugin.saveConfig();
            plugin.getLogger().info("配置升级（v29）：新增 hunter.spectate.*（观战席位）与 hunter.reward.early-exit-settle");
        }

        // v30：母体卡住判定从 6 秒收紧到 3 秒（6 秒太钝，看起来就像在发呆）
        if (savedVersion < 30) {
            if (retuneInt("hunter.mother.stuck-seconds", 6, 3) > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v30）：母体卡住判定 6 → 3 秒");
            }
        }

        // v31：跑者后遗缓慢 2 → 1 秒；新增假人体力/速度配置（只改仍是旧默认值的）
        if (savedVersion < 31) {
            int t = 0;
            t += retuneInt("hunter.items.adrenaline.after-slow-seconds", 2, 1);
            if (t > 0) {
                config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
                plugin.saveConfig();
                plugin.getLogger().info("调参升级（v31）：跑者后遗缓慢 2 → 1 秒");
            }
            config.options().copyDefaults(true);
            config.set(VERSION_KEY, CURRENT_CONFIG_VERSION);
            plugin.saveConfig();
        }

        // 设置默认值
        config.addDefault(VERSION_KEY, CURRENT_CONFIG_VERSION);
        config.addDefault("update-check.enabled", true); // 启动时查一次 Modrinth 是否有新版本
        config.addDefault("game.duration", 420); // 7分钟，单位秒
        config.addDefault("game.preparation-time", 20); // 准备时间，单位秒
        config.addDefault("game.queue-time", 60); // 队列等待时间，单位秒
        config.addDefault("game.min-players", 2); // 最小玩家数
        config.addDefault("game.max-players", 16); // 最大玩家数
        config.addDefault("game.max-games", 1); // 最大同时游戏数
        config.addDefault("game.match-queue-time", 30); // 匹配队列时间，队列满员后等待多久开始游戏
        
        // 经济设置
        config.addDefault("economy.entry-fee", 100.0); // 入场费
        config.addDefault("economy.server-bonus", 5000.0); // 服务器奖金
        
        // 道具设置
        config.addDefault("items.adrenaline.duration", 10); // 肾上腺素持续时间，单位秒
        config.addDefault("items.adrenaline.speed-level", 2); // 肾上腺素速度等级
        config.addDefault("items.frenzy.duration", 10); // 狂暴持续时间，单位秒
        config.addDefault("items.frenzy.speed-level", 2); // 狂暴速度等级
        config.addDefault("items.ice-ball.slow-duration", 4); // 凝冰球减速持续时间，单位秒
        config.addDefault("items.ice-ball.slow-level", 4); // 凝冰球减速等级
        config.addDefault("items.soul-control.freeze-duration", 6); // 控魂术冻结持续时间，单位秒
        config.addDefault("items.soul-control.cooldown", 18); // 控魂术冷却时间，单位秒
        config.addDefault("items.teleport-pearl.cooldown", 20); // 传送珍珠冷却时间，单位秒
        config.addDefault("items.stinky-steak.speed-duration", 14); // 臭牛排速度效果持续时间，单位秒
        config.addDefault("items.stinky-steak.speed-level", 2); // 臭牛排速度效果等级（1=速度II）
        config.addDefault("items.stinky-steak.glowing-duration", 10); // 臭牛排发光效果持续时间，单位秒
        config.addDefault("items.stinky-steak.cooldown", 30); // 臭牛排冷却时间，单位秒
        config.addDefault("items.soul-detector.duration", 25); // 灵魂探测器暴露持续时间，单位秒
        config.addDefault("items.soul-detector.cooldown", 35); // 灵魂探测器冷却时间，单位秒
        config.addDefault("items.second-chance.cooldown", 180); // 一次机会冷却时间，单位秒
        config.addDefault("items.second-chance.human-speed-duration", 10); // 人类玩家速度效果持续时间，单位秒
        config.addDefault("items.second-chance.human-speed-level", 2); // 人类玩家速度效果等级
        config.addDefault("items.second-chance.human-glowing-duration", 10); // 人类玩家高亮效果持续时间，单位秒
        config.addDefault("items.second-chance.ghost-slow-duration", 7); // 鬼玩家缓慢效果持续时间，单位秒
        config.addDefault("items.second-chance.ghost-slow-level", 1); // 鬼玩家缓慢效果等级
        
        // 效果设置
        config.addDefault("effects.mother-ghost-blindness-duration", 20); // 母体失明持续时间，单位秒
        config.addDefault("effects.ghost-immobilize-duration", 20); // 母体鬼固定时间，单位秒
        config.addDefault("effects.ghost-sense-duration", 5); // 幽灵感知高亮持续时间，单位秒
        config.addDefault("effects.infection-lightning", true); // 感染时是否显示闪电
        config.addDefault("effects.infection-sound", true); // 感染时是否播放音效
        config.addDefault("effects.minute-glowing.enabled", true); // 是否启用每分钟高亮效果
        config.addDefault("effects.minute-glowing.duration", 5); // 高亮持续时间，单位秒
        config.addDefault("effects.minute-glowing.interval", 60); // 触发间隔时间，单位秒
        
        // 游戏血量设置
        config.addDefault("health.max-health", 10.0); // 游戏期间玩家的最大生命值（默认10颗心，即20点生命值）
        
        // 鬼转人类功能设置
        config.addDefault("ghost-to-human.enabled", false); // 是否启用鬼转人类功能
        config.addDefault("ghost-to-human.remaining-time", 180); // 剩余多少秒时触发（默认3分钟）
        config.addDefault("ghost-to-human.count", 1); // 转换数量
        
        // 黑暗效果设置
        config.addDefault("dark-effect.enabled", true); // 是否启用黑暗效果（默认开启）
        config.addDefault("dark-effect.duration", 999999); // 黑暗效果持续时间（秒）
        config.addDefault("dark-effect.amplifier", 0); // 黑暗效果等级
        
        // 心跳声设置
        config.addDefault("heartbeat.enabled", true); // 是否启用心跳声
        config.addDefault("heartbeat.interval", 10); // 心跳声播放间隔（秒）
        
        // 局内饱食度设置（v2.3.2）
        config.addDefault("food.enabled", true); // 是否启用局内饱食度恢复
        config.addDefault("food.regenerate-per-second", 1); // 人类：按住蹲下每秒恢复的饱食度
        config.addDefault("food.ghost-regenerate-interval", 0.8); // 鬼：按住蹲下每N秒恢复1点饱食度
        
        // 转化功能设置
        config.addDefault("conversion.enabled", false); // 是否启用转化功能
        config.addDefault("conversion.activate-time", 120); // 转化激活时间（游戏剩余时间，秒）
        config.addDefault("conversion.cooldown", 30); // 转化冷却时间（秒）
        config.addDefault("conversion.cost", 15); // 转化消耗宝石数量
        
        // 道具刷新设置
        config.addDefault("item-spawn.enabled", true); // 是否启用道具刷新
        config.addDefault("item-spawn.interval", 60); // 刷新间隔（秒）
        config.addDefault("item-spawn.max-per-refresh", 3); // 每次刷新数量上限
        config.addDefault("item-spawn.max-per-player", 1); // 每位玩家最多获得数量
        config.addDefault("item-spawn.max-item-types-per-player", 6); // 玩家最多拥有的道具种类数量
        

        
        // 服务器模组设置（预留）
        
        // 区域选择设置
        config.addDefault("area.selection-tool", "MAGMA_CREAM"); // 选区工具物品
        config.addDefault("area.max-areas", 20); // 最大存档区域数量
        config.addDefault("area.auto-teleport", true); // 是否自动传送到区域
        config.addDefault("area.random-each-game", true); // 每局从已启用区域中随机选图
        config.addDefault("death-state.mode", "invisible"); // invisible 隐身(默认) / spectator 旁观
        
        // 语言设置
        config.addDefault("language.default", "zh_CN"); // 默认语言: zh_CN/en_US/ja_JP/es_ES/fr_FR/de_DE
        config.addDefault("language.auto-detect", true); // 是否自动检测玩家语言
        
        // 神圣守护系统设置（v2.2.2重构）
        config.addDefault("divine-guardian.enabled", true); // 是否启用神圣守护系统
        config.addDefault("divine-guardian.trigger-human-count", 2); // 触发神圣守护的人类玩家数量（最后N位人类）
        config.addDefault("divine-guardian.broadcast", true); // 是否广播神圣守护触发消息
        
        // 神圣守护效果设置
        config.addDefault("divine-guardian.holy-guardian.teleport-attacker", true); // 随机传送尝试感染的鬼
        config.addDefault("divine-guardian.holy-guardian.teleport-radius", 10.0); // 随机传送半径（方块）
        config.addDefault("divine-guardian.holy-guardian.effect-duration", 30); // 神圣守护效果持续时间（秒），0表示永久直到猎魔人阶段
        config.addDefault("divine-guardian.holy-guardian.defense-charges", 3); // 神圣守护可抵挡的攻击次数
        
        // 猎魔人阶段设置
        config.addDefault("divine-guardian.demon-hunter.phase-start-time", 90); // 猎魔人阶段开始时间（游戏剩余秒数）
        config.addDefault("divine-guardian.demon-hunter.max-uses", 2); // 神之救赎道具最大使用次数（保持不变）
        config.addDefault("divine-guardian.demon-hunter.holy-redemption-cooldown", 10); // 神之救赎冷却时间（秒）
        
        // 收割者道具设置
        config.addDefault("divine-guardian.demon-hunter.reaper-weapon.damage-per-hit", 1.0); // 每次攻击伤害
        config.addDefault("divine-guardian.demon-hunter.reaper-weapon.hits-to-kill", 2); // 击杀所需攻击次数
        config.addDefault("divine-guardian.demon-hunter.reaper-weapon.attack-cooldown", 2.0); // 攻击冷却时间（秒）
        config.addDefault("divine-guardian.demon-hunter.reaper-weapon.enchant-glow", true); // 是否显示附魔光效
        
        // 击杀奖励设置
        config.addDefault("divine-guardian.demon-hunter.kill-rewards.demon-hunter-kill-reward", 0.3); // 猎魔人击杀鬼获得的奖金比例（从人类奖池分配）
        config.addDefault("divine-guardian.demon-hunter.kill-rewards.mother-kill-demon-hunter-reward", 0.5); // 母体击杀猎魔人获得的奖金比例（高于感染奖励）
        
        // 复活机制设置
        config.addDefault("divine-guardian.demon-hunter.respawn.enabled", true); // 是否启用鬼玩家复活机制
        config.addDefault("divine-guardian.demon-hunter.respawn.respawn-time", 15); // 复活时间（秒）
        
        // 血量设置（猎魔人阶段）
        config.addDefault("divine-guardian.demon-hunter.health.ghost-normal", 2.0); // 普通鬼血量（猎魔人阶段）
        config.addDefault("divine-guardian.demon-hunter.health.ghost-mother", 4.0); // 母体鬼血量（猎魔人阶段）
        config.addDefault("divine-guardian.demon-hunter.health.demon-hunter", 2.0); // 猎魔人血量（猎魔人阶段）
        config.addDefault("divine-guardian.demon-hunter.health.no-healing", true); // 猎魔人阶段禁止回血
        
        // 母体新增设置
        config.addDefault("divine-guardian.demon-hunter.additional-mother.enabled", true); // 是否在猎魔人阶段新增母体
        config.addDefault("divine-guardian.demon-hunter.additional-mother.player-threshold", 8); // 触发新增母体的玩家总数阈值
        config.addDefault("divine-guardian.demon-hunter.additional-mother.only-in-demon-hunter-phase", true); // 是否只在猎魔人阶段新增
        
        // 鬼玩家粒子效果设置
        config.addDefault("ghost-particle.enabled", true); // 是否启用鬼玩家粒子效果
        config.addDefault("ghost-particle.type", "DUST"); // 粒子类型：DUST, FLAME, SOUL_FIRE_FLAME, DRAGON_BREATH, PORTAL, DUST_COLOR_TRANSITION, SPELL_MOB, SPELL_WITCH, ENCHANT, SWEEP_ATTACK, HEART, NOTE, VILLAGER_ANGRY, VILLAGER_HAPPY, TOTEM, COMPOSTER, SQUID_INK, DRIPPING_OBSIDIAN_TEAR, FALLING_OBSIDIAN_TEAR, LANDING_OBSIDIAN_TEAR
        config.addDefault("ghost-particle.count", 5); // 每次生成粒子数量
        config.addDefault("ghost-particle.interval", 15); // 粒子生成间隔（刻，20刻=1秒）
        config.addDefault("ghost-particle.mother-color", "255,0,0"); // 母体鬼粒子颜色（RGB格式：红,绿,蓝）
        config.addDefault("ghost-particle.normal-color", "0,255,0"); // 普通鬼粒子颜色（RGB格式：红,绿,蓝）
        config.addDefault("ghost-particle.size", 1.0); // 粒子大小
        config.addDefault("ghost-particle.show-in-preparation", true); // 准备阶段是否显示粒子
        
        // 生物清理设置
        config.addDefault("entity-clear.enabled", true); // 游戏开始时是否清除区域内的生物
        config.addDefault("entity-clear.prevent-spawn", true); // 是否禁止游戏区域内生成任何生物
        
        // 救赎者常驻设置
        config.addDefault("redeemer.enabled", true); // 救赎者常驻（每局最多2名）
        config.addDefault("redeemer.max-count", 2); // 最大救赎者数量
        config.addDefault("redeemer.max-uses", 2); // 神之救赎使用次数
        
        // 收割者技能设置
        config.addDefault("reaper.harvest-cooldown", 10); // 收割技能冷却（秒）
        config.addDefault("reaper.harvest-range", 4); // 收割技能范围（方块）
        
        // 额外奖励设置（结算时由服务器发放，与奖池分离）
        config.addDefault("reward.redeem-ghost", 100); // 成功救赎一名鬼玩家奖励游戏币
        config.addDefault("reward.kill-normal", 50); // 击杀一名普通鬼奖励游戏币
        config.addDefault("reward.kill-mother", 100); // 击杀一名母体奖励游戏币
        
        // 背包限制设置
        config.addDefault("inventory.max-item-types", 9); // 对局期间最多道具种类
        config.addDefault("inventory.max-items", 9); // 对局期间最多道具数量
        config.addDefault("inventory.allow-duplicate-universal", true); // 通用道具是否允许重复
        config.addDefault("inventory.force-first-slot-items", "收割者,神之救赎"); // 强制第一格道具
        
        // ===== v2.3.3 母体进化水晶 =====
        config.addDefault("mother-emerald.enabled", true); // 是否启用母体进化水晶
        config.addDefault("mother-emerald.spawn-phase", "holy-guardian"); // 出现阶段: holy-guardian / demon-hunter / both
        config.addDefault("mother-emerald.player-threshold", 5); // 参与人数达到该值才出现
        config.addDefault("mother-emerald.location-mode", "center"); // 坐标模式: fixed(固定坐标) / center(区域中心) / random(区域随机)
        config.addDefault("mother-emerald.fixed.world", ""); // 固定坐标世界名（留空用选区世界）
        config.addDefault("mother-emerald.fixed.x", 0.0);
        config.addDefault("mother-emerald.fixed.y", 64.0);
        config.addDefault("mother-emerald.fixed.z", 0.0);
        config.addDefault("mother-emerald.pickup-delay-ticks", 40); // 拾取延迟（刻）
        config.addDefault("mother-emerald.glow-interval-ticks", 20); // 发光粒子刷新间隔（刻）
        config.addDefault("mother-emerald.item-name", "母体升级宝石");
        config.addDefault("mother-emerald.entity-name", "母体绿宝石");
        
        // ===== v2.3.3 猎魔人死亡 → 普通鬼 =====
        config.addDefault("demon-hunter.death.convert-to-normal-ghost", true); // 被母体击杀后转为普通鬼
        config.addDefault("demon-hunter.death.respawn-as-ghost", true); // 复活时以普通鬼身份复活
        
        // ===== 道具细节（补全可配置） =====
        config.addDefault("items.adrenaline.duration", 10);
        config.addDefault("items.adrenaline.speed-level", 2);
        config.addDefault("items.frenzy.duration", 10);
        config.addDefault("items.frenzy.speed-level", 2);
        config.addDefault("items.ice-ball.slow-duration", 4);
        config.addDefault("items.ice-ball.slow-level", 4);
        config.addDefault("items.soul-control.freeze-duration", 6);
        config.addDefault("items.soul-control.cooldown", 18);
        config.addDefault("items.teleport-pearl.cooldown", 20);
        config.addDefault("items.stinky-steak.speed-duration", 14);
        config.addDefault("items.stinky-steak.speed-level", 2);
        config.addDefault("items.stinky-steak.glowing-duration", 10);
        config.addDefault("items.stinky-steak.cooldown", 30);
        config.addDefault("items.stinky-steak.eat-time-ticks", 20); // 食用耗时（刻）
        config.addDefault("items.soul-detector.duration", 25);
        config.addDefault("items.soul-detector.cooldown", 35);
        config.addDefault("items.second-chance.cooldown", 180);
        config.addDefault("items.levitation.duration-ticks", 90); // 漂浮 4.5 秒
        config.addDefault("items.spear-rush.distance", 5.5); // 冲刺距离（米）
        config.addDefault("items.spear-rush.consume-on-use", true);
        config.addDefault("items.holy-redemption.uses", 1);
        config.addDefault("items.holy-redemption.teleport-on-use", true);
        config.addDefault("items.reaper.attack-cooldown", 2.0);
        config.addDefault("items.reaper.damage-per-hit", 1.0);
        config.addDefault("items.reaper.harvest-cooldown", 10);
        config.addDefault("items.reaper.harvest-range", 4.0);
        config.addDefault("items.reaper.sound-on-harvest", "ENTITY_WITHER_DEATH");
        
        // ===== 音效开关 =====
        config.addDefault("sounds.enabled", true);
        config.addDefault("sounds.item-give", true);
        config.addDefault("sounds.item-use", true);
        config.addDefault("sounds.phase-change", true);
        config.addDefault("sounds.kill-feedback", true);
        config.addDefault("sounds.respawn", true);
        
        // ===== 字幕/广播 =====
        config.addDefault("titles.enabled", true);
        config.addDefault("broadcast.item-give", true);
        config.addDefault("broadcast.kill", true);
        config.addDefault("broadcast.respawn", true);
        config.addDefault("broadcast.mother-emerald", true);
        
        // ===== 粒子 =====
        config.addDefault("particles.kill-explosion", true);
        config.addDefault("particles.harvest-shockwave", true);
        config.addDefault("particles.respawn-totem", true);
        config.addDefault("particles.mother-emerald-glow", true);
        
        // ===== 复活细节 =====
        config.addDefault("divine-guardian.demon-hunter.respawn.random-location", true);
        config.addDefault("divine-guardian.demon-hunter.respawn.restore-health-on-respawn", true);
        config.addDefault("divine-guardian.demon-hunter.respawn.boundary-check", true);
        config.addDefault("divine-guardian.demon-hunter.respawn.boundary-radius", 50);
        
        // ===== 母体攻击猎魔人 =====
        config.addDefault("divine-guardian.demon-hunter.mother.hits-to-infect-hunter", 4);
        config.addDefault("divine-guardian.demon-hunter.mother.hits-to-break-guardian", 3);
        
        // ===== 击杀烟花 =====
        config.addDefault("effects.kill-firework.enabled", true);
        config.addDefault("effects.kill-firework.type", "BURST");
        config.addDefault("effects.kill-firework.colors", "RED,YELLOW");
        
        // ===== 第二次机会 =====
        config.addDefault("items.second-chance.consume-on-trigger", false); // 触发后是否消耗
        config.addDefault("items.second-chance.teleport-on-trigger", true);
        config.addDefault("items.second-chance.human-speed-duration", 10);
        config.addDefault("items.second-chance.human-speed-level", 2);
        config.addDefault("items.second-chance.human-glowing-duration", 10);
        config.addDefault("items.second-chance.ghost-slow-duration", 7);
        config.addDefault("items.second-chance.ghost-slow-level", 1);
        
        config.options().copyDefaults(true);
        plugin.saveConfig();
    }
    
    public void reloadConfig() {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    // ---------------------------------------------------------------- 菜单调参

    /**
     * /ghg 管理员板块用：写一个键并落盘。
     *
     * <p>本类和 {@code plugin.getConfig()} 共用同一个 FileConfiguration 实例，
     * 所以 {@link #setValue} 之后所有 getter **立刻**读到新值——不用 reload，
     * 也不会触发配置迁移那一套。</p>
     */
    public void setValue(String key, Object value) {
        if (key == null || key.isEmpty()) return;
        ensureConfigLoaded();
        config.set(key, value);
        plugin.saveConfig();
    }

    /** 读一个数值键（菜单显示当前值用） */
    public double getValue(String key, double fallback) {
        ensureConfigLoaded();
        return config.getDouble(key, fallback);
    }
    
    /**
     * 服务器完全启动后调用，在控制台打印配置迁移详情
     */
    public void printMigrationReport() {
        if (!configMigrated || migratedValues.isEmpty()) {
            return;
        }
        
        // 控制台不吃 § 颜色码，这里全部用纯文本，跟启动报告同一个风格
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append("--------------------------------------------------").append('\n');
        sb.append(" 配置自动迁移报告\n");
        sb.append("--------------------------------------------------").append('\n');
        sb.append(" 旧版本   v").append(oldConfigVersion).append('\n');
        sb.append(" 新版本   v").append(CURRENT_CONFIG_VERSION).append('\n');
        sb.append(" 迁移项   ").append(migratedValues.size()).append(" 条（旧键全部保留）\n");
        sb.append(" 备份     config_old_v").append(oldConfigVersion)
          .append(".yml —— 要恢复就把它改名成 config.yml 后重启\n");
        sb.append("--------------------------------------------------\n");
        
        int index = 1;
        for (java.util.Map.Entry<String, Object> entry : migratedValues.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String valueStr = (value != null) ? value.toString() : "null";
            // 截断过长的值
            if (valueStr.length() > 60) {
                valueStr = valueStr.substring(0, 57) + "...";
            }
            sb.append("  ").append(index).append(". ").append(key).append(" = ").append(valueStr).append('\n');
            index++;
        }
        sb.append("--------------------------------------------------");
        plugin.getLogger().info(sb.toString());
        
        // 标记已打印，避免重复
        configMigrated = false;
    }
    
    // 获取配置值的方法
    public int getConfigVersion() {
        return config.getInt(VERSION_KEY, config.getInt(LEGACY_VERSION_KEY, 2));
    }
    
    // ==================== GHG（HunterGame）配置 ====================

    public int getHunterDuration() {
        ensureConfigLoaded();
        // v3 起默认 20 分钟（1200 秒）
        return config.getInt("hunter.duration", 1200);
    }

    /** 法阵数量：1-5 为指定值，0 表示随人数自动 {@code ceil(人数/2)} */
    public int getHunterAltarCount() {
        ensureConfigLoaded();
        return Math.max(0, Math.min(5, config.getInt("hunter.altar.count", 2)));
    }

    /** 自动法阵数的上限 */
    public int getHunterAltarMax() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.altar.max", 4));
    }

    /** 多人同时站进同一个法阵时，每多一人额外加的进度倍率 */
    public double getHunterAltarGroupBonus() {
        ensureConfigLoaded();
        return config.getDouble("hunter.altar.group-bonus", 0.5);
    }

    /** 候选点位是否每局随机抽（false = 固定九宫格矩阵，方便调试） */
    public boolean isHunterRandomizePoints() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.points.randomize", true);
    }

    /** 多层猎场：法阵/逃脱点可以落在任意一层（二层、平台），不再只跟发起者那一层 */
    public boolean isHunterPointsMultiFloor() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.points.multi-floor", true);
    }

    /** 候选点与发起者站位的最大高度差（0 = 不限） */
    public int getHunterPointsMaxHeightDiff() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.points.max-height-diff", 0));
    }

    /** 单局逃生者人数上限：单只 AI 母体压不住更多人 */
    public int getHunterMaxSurvivors() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.max-survivors", 4));
    }

    // ---- 排队开局（/ghg join）----

    public boolean isHunterQueueEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.queue.enabled", true);
    }

    /** 凑够几个逃生者就开局 */
    public int getHunterQueueMinPlayers() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.queue.min-players", 2));
    }

    /** 队列人数上限（还会再和 hunter.max-survivors 取小的那个） */
    public int getHunterQueueMaxPlayers() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.queue.max-players", 4));
    }

    /** 凑人阶段最长等多久，到点人数不够就取消队列 */
    public int getHunterQueueWaitSeconds() {
        ensureConfigLoaded();
        return Math.max(5, config.getInt("hunter.queue.wait-seconds", 90));
    }

    /** 凑够人之后还有多少秒开局 */
    public int getHunterQueueCountdownSeconds() {
        ensureConfigLoaded();
        return Math.max(3, config.getInt("hunter.queue.countdown-seconds", 15));
    }

    /** AI = 队列只排逃生者、开 AI 母体；PLAYER = 从队列里随机抽一人当母体 */
    public String getHunterQueueMother() {
        ensureConfigLoaded();
        String value = config.getString("hunter.queue.mother", "AI");
        if (value == null) return "AI";
        value = value.trim().toUpperCase(java.util.Locale.ROOT);
        return "PLAYER".equals(value) ? "PLAYER" : "AI";
    }

    /**
     * 切换「谁来当母体」：{@code AI} = 队列只排逃生者、开局刷 AI 母体；
     * {@code PLAYER} = 从队列里随机抽一人当母体（不看入队顺序）。
     *
     * <p>直接写进 config.yml 并存盘 —— 这是服主的设置，重启之后还要生效。
     * {@code QueueManager} 每次判定都现读配置，所以调用完就立刻生效，不用 reload。</p>
     */
    public void setHunterQueueMother(String value) {
        ensureConfigLoaded();
        config.set("hunter.queue.mother",
            "PLAYER".equalsIgnoreCase(value) ? "PLAYER" : "AI");
        plugin.saveConfig();
    }

    public int getHunterAltarSize() {
        ensureConfigLoaded();
        int size = config.getInt("hunter.altar.size", 1);
        return size >= 2 ? 2 : 1;
    }

    public double getHunterAltarProgressPerSecond() {
        ensureConfigLoaded();
        return config.getDouble("hunter.altar.progress-per-second", 1.8);
    }

    /** 蓄力时的滴答声间隔（刻）；0 = 关 */
    public int getHunterAltarChargeTickTicks() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.altar.charge-tick-ticks", 10));
    }

    public double getHunterAltarDecayPerSecond() {
        ensureConfigLoaded();
        return config.getDouble("hunter.altar.decay-per-second", 1.5);
    }

    public double getHunterAltarDecayRadius() {
        ensureConfigLoaded();
        return config.getDouble("hunter.altar.decay-radius", 6.0);
    }

    public int getHunterEscapeHoldSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.escape.hold-seconds", 4));
    }

    public double getHunterEscapeRadius() {
        ensureConfigLoaded();
        return config.getDouble("hunter.escape.radius", 3.0);
    }

    public int getHunterChestCount() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.chest.count", 3));
    }

    public String getHunterChestMaterial() {
        ensureConfigLoaded();
        return config.getString("hunter.chest.material", "MELON");
    }

    public int getHunterChestHitsRequired() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.chest.hits-required", 5));
    }

    public int getHunterChestDrops() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.chest.drops", 2));
    }

    public int getHunterHitsToDown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.downed.hits-to-down", 2));
    }

    public int getHunterGlowSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.downed.glow-seconds", 4));
    }

    public int getHunterRescueLimit() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.downed.rescue-limit", 1));
    }

    public double getHunterRescueRadius() {
        ensureConfigLoaded();
        return config.getDouble("hunter.downed.rescue-radius", 2.5);
    }

    public String getHunterMotherType() {
        ensureConfigLoaded();
        return config.getString("hunter.mother.type", "AUTO");
    }

    /** 母体种类：决定技能与默认外观（BRUTE / STALKER / WEAVER） */
    public String getHunterMotherKind() {
        ensureConfigLoaded();
        return config.getString("hunter.mother.kind", "BRUTE");
    }

    /** 是否允许玩家扮演母体（/ghga hunter mother <玩家>） */
    public boolean isHunterPlayerMotherEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.mother.player-mode.enabled", true);
    }

    /** 玩家母体的速度等级（0 = 不加成） */
    public int getHunterPlayerMotherSpeedLevel() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.mother.player-mode.speed-level", 1));
    }

    /** 技能冷却；0 表示用各技能自带的默认值 */
    public int getHunterMotherSkillCooldown() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.mother.skill-cooldown", 0));
    }

    /** 技能命中的减速时长（秒） */
    public int getHunterSkillSlowSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.mother.skill-slow-seconds", 3));
    }

    /** 技能命中的减速等级 */
    public int getHunterSkillSlowLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skill-slow-level", 1));
    }

    /** 被母体打中后的减速时长（秒） */
    public int getHunterHitSlowSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.downed.hit-slow-seconds", 2));
    }

    /** 被母体打中后的减速等级 */
    public int getHunterHitSlowLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.downed.hit-slow-level", 1));
    }

    /** 被打中后的挣脱加速时长（秒）：减速结束才开始，形成逃跑窗口 */
    public int getHunterHitBurstSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.downed.hit-burst-seconds", 1));
    }

    /** 被打中后的挣脱加速等级 */
    public int getHunterHitBurstLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.downed.hit-burst-level", 2));
    }

    /**
     * 母体命中后自减速时长（秒）：追击后摇，给逃生者换时间的窗口。
     *
     * <p>GHG 自己的键是 {@code recover-seconds}。旧键 {@code hit-recover-seconds} 是 Gost
     * 时代的，值偏小（2 秒缓慢Ⅰ），后摇几乎看不出来，所以不再读取。</p>
     */
    public int getHunterMotherHitRecoverSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.mother.recover-seconds", 2));
    }

    /** 母体命中后自减速等级（1 = 缓慢Ⅰ，2 = 缓慢Ⅱ） */
    public int getHunterMotherHitRecoverLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.recover-level", 4));
    }

    /** 救援需要持续多少秒（读条） */
    public int getHunterRescueSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.downed.rescue-seconds", 3));
    }

    /** 是否启用心跳声 */
    public boolean isHunterHeartbeatEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.heartbeat.enabled", true);
    }

    /** 心跳声的生效半径（格） */
    public double getHunterHeartbeatRange() {
        ensureConfigLoaded();
        return config.getDouble("hunter.heartbeat.range", 48.0);
    }

    public double getHunterMotherSpeed() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.speed", 0.26);
    }

    public double getHunterMotherAttackRange() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.attack-range", 2.2);
    }

    /**
     * 母体攻击冷却（秒）。
     *
     * <p>GHG 自己的键是 {@code attack-cooldown-seconds}。旧键 {@code attack-cooldown} 只有 3 秒，
     * 追上之后几乎能连着两下打死人，所以不再读取它——想调就改新键。</p>
     */
    public int getHunterMotherAttackCooldown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.attack-cooldown-seconds", 4));
    }

    public double getHunterMotherSenseRange() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.sense-range", 60.0);
    }

    public double getHunterMotherSpawnDistance() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.spawn-distance", 12.0);
    }
    /** 准备阶段时长（秒）：人类可自由移动，法阵不可破，母体定身 */
    public int getHunterPreparationSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.preparation-seconds", 30));
    }

    /** 单人测试模式的准备阶段时长（秒）：测试时不值得等 20 秒 */
    public int getHunterTestPreparationSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.test.preparation-seconds", 10));
    }

    // ---- GHG 奖池 ----

    public boolean isHunterRewardEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.reward.enabled", true);
    }

    /** 每个逃生者的入场费（进奖池） */
    public double getHunterRewardEntryFee() {
        ensureConfigLoaded();
        return Math.max(0, config.getDouble("hunter.reward.entry-fee", 50));
    }

    /** 服务器保底奖金（进奖池，不占玩家的钱） */
    public double getHunterRewardServerBonus() {
        ensureConfigLoaded();
        return Math.max(0, config.getDouble("hunter.reward.server-bonus", 500));
    }

    /** 逃生者胜利时，奖池给逃生者的比例 */
    public double getHunterRewardSurvivorSplit() {
        ensureConfigLoaded();
        return Math.max(0, Math.min(1, config.getDouble("hunter.reward.survivor-split", 1.0)));
    }

    /** 母体胜利时，奖池给母体的比例 */
    public double getHunterRewardMotherSplit() {
        ensureConfigLoaded();
        return Math.max(0, Math.min(1, config.getDouble("hunter.reward.mother-split", 1.0)));
    }

    /** 贡献权重：破阵每秒 */
    public double getHunterRewardAltarWeight() {
        ensureConfigLoaded();
        return Math.max(0, config.getDouble("hunter.reward.contribution.altar", 0.3));
    }

    /** 贡献权重：每次救援 */
    public double getHunterRewardRescueWeight() {
        ensureConfigLoaded();
        return Math.max(0, config.getDouble("hunter.reward.contribution.rescue", 5.0));
    }

    /** 贡献权重：活到撤离 */
    public double getHunterRewardSurviveWeight() {
        ensureConfigLoaded();
        return Math.max(0, config.getDouble("hunter.reward.contribution.survive", 10.0));
    }

    /** 是否定时通报法阵位置 */
    public boolean isHunterAltarLocatorEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.altar.locator.enabled", true);
    }

    /** 每隔多少秒通报一次未完成法阵的坐标 */
    public int getHunterAltarLocatorIntervalSeconds() {
        ensureConfigLoaded();
        return Math.max(10, config.getInt("hunter.altar.locator.interval-seconds", 60));
    }

    /** 母体（玩家扮演）连跳几次才腿软：默认比人类多一次 */
    public int getHunterJumpFatigueMotherMax() {
        ensureConfigLoaded();
        int human = config.getInt("hunter.jump-fatigue.max-jumps", 3);
        return Math.max(1, config.getInt("hunter.jump-fatigue.mother-max-jumps", human + 1));
    }

    /** 母体腿软的持续秒数：默认比人类少一秒 */
    public int getHunterJumpFatigueMotherPenaltySeconds() {
        ensureConfigLoaded();
        int human = config.getInt("hunter.jump-fatigue.penalty-seconds", 2);
        return Math.max(1, config.getInt("hunter.jump-fatigue.mother-penalty-seconds", Math.max(1, human - 1)));
    }

    /** 全场同时存在的道具箱上限 */
    public int getHunterChestMax() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.chest.max", 10));
    }

    /** 每隔多少秒补一波道具箱 */
    public int getHunterChestWaveIntervalSeconds() {
        ensureConfigLoaded();
        return Math.max(5, config.getInt("hunter.chest.wave-interval-seconds", 40));
    }

    /** 一波补几个 */
    public int getHunterChestWavePerRound() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.chest.wave-per-round", 2));
    }

    /** 测试局里**玩家母体**是否忽略技能冷却（沙盒，方便连续试技能；AI 母体始终吃冷却） */
    /**
     * 测试局里玩家母体是否**跳过**技能冷却。
     *
     * <p>默认 <b>false</b>：技能冷却是最基本的平衡，连测试局也不该白送。
     * 想连续试技能的管理员可以自己把这项打开。</p>
     */
    public boolean isHunterTestIgnoreSkillCooldown() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.test.ignore-skill-cooldown", false);
    }

    public boolean isHunterParticlesEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.particles.enabled", true);
    }

    /** 法阵粒子圈的采样点数（越大越圆、越费） */
    public int getHunterAltarRingPoints() {
        ensureConfigLoaded();
        return Math.max(6, Math.min(48, config.getInt("hunter.particles.altar-ring-points", 16)));
    }

    /** 准备阶段是否给法阵打信标光柱 */
    public boolean isHunterAltarBeamInPreparation() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.particles.altar-beam-in-preparation", true);
    }

    /** 口粮一口恢复多少点饱食度 */
    public int getHunterRationRestoreFood() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(20, config.getInt("hunter.items.ration.restore-food", 6)));
    }

    /** 口粮一口恢复多少饱和度 */
    public double getHunterRationRestoreSaturation() {
        ensureConfigLoaded();
        return Math.max(0, config.getDouble("hunter.items.ration.restore-saturation", 4.0));
    }

    /** 一份口粮能吃几口 */
    public int getHunterRationBites() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(16, config.getInt("hunter.items.ration.bites", 1)));
    }

    /** 是否每个道具箱必出一份口粮 */
    public boolean isHunterRationGuaranteed() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.items.ration.guaranteed-in-chest", false);
    }

    /** 是否给逃生者加压饱食度消耗 */
    public boolean isHunterHungerEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.hunger.enabled", true);
    }

    /** 饥饿等级（0 = 饥饿Ⅰ，1 = 饥饿Ⅱ） */
    public int getHunterHungerAmplifier() {
        ensureConfigLoaded();
        return Math.max(0, Math.min(3, config.getInt("hunter.hunger.amplifier", 1)));
    }

    /** 每隔多少秒补一次饥饿效果 */
    public int getHunterHungerIntervalSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.hunger.interval-seconds", 2));
    }

    /** 食物见底时是否追加缓慢 */
    public boolean isHunterLowFoodSlowness() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.hunger.low-food-slowness", true);
    }

    /** 食物低于等于多少算「见底」 */
    public int getHunterLowFoodThreshold() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(19, config.getInt("hunter.hunger.low-food-threshold", 6)));
    }

    /** 母体头顶是否挂粒子标记 */
    public boolean isHunterMotherMarkerEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.particles.mother-marker", true);
    }

    /** 信标光柱的高度（格） */
    public int getHunterBeamHeight() {
        ensureConfigLoaded();
        return Math.max(6, Math.min(64, config.getInt("hunter.particles.beam-height", 24)));
    }

    /** 法阵进度条是否也给母体看 */
    public boolean isHunterBossBarForMother() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.bossbar.show-to-mother", true);
    }

    /** 是否给逃生者随机分配构筑（perk） */
    public boolean isHunterPerkEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.perk.enabled", true);
    }

    /** 是否用 BossBar 显示法阵破除进度 */
    public boolean isHunterAlterBarEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.bossbar.altars", true);
    }

    /** 母体测试模式生成几个测试假人 */
    public int getHunterTestDummyCount() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.test.dummy-count", 2));
    }

    /** 假人被打几下算打倒 */
    public int getHunterTestDummyHits() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.test.dummy-hits", 2));
    }

    /** 母体进到这个距离内，假人开始跑 */
    public double getHunterTestDummyFleeRange() {
        ensureConfigLoaded();
        return config.getDouble("hunter.test.dummy-flee-range", 14.0);
    }

    /** 假人一次往反方向跑多远 */
    public double getHunterTestDummyFleeDistance() {
        ensureConfigLoaded();
        return config.getDouble("hunter.test.dummy-flee-distance", 8.0);
    }

    /** 准备阶段最后多少秒锁定母体种类 */
    public int getHunterKindLockSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.kind-lock-seconds", 5));
    }

    /** 某个母体种类要多少入场费（疾行者一般 0） */
    public double getHunterKindCost(MotherKind kind) {
        if (kind == null) return 0;
        ensureConfigLoaded();
        return Math.max(0, config.getDouble("hunter.mother.kind-cost." + kind.name(), 0));
    }

    /** 冲撞命中的眩晕时长（刻） */
    public int getHunterDashStunTicks() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.dash.stun-ticks", 40));
    }

    /** 冲撞的撞击判定半径 */
    public double getHunterDashStunRadius() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.skills.dash.stun-radius", 2.2);
    }

    /** 冲撞后多少刻内还算「撞击窗口」 */
    public int getHunterDashStunWindowTicks() {
        ensureConfigLoaded();
        return Math.max(4, config.getInt("hunter.mother.skills.dash.stun-window-ticks", 32));
    }

    // ---- 技能细化参数 ----

    /** 射手骨矢的减速时长（比通用 skill-slow 更长） */
    public int getHunterSkillArrowSlowSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.arrow.slow-seconds", 6));
    }

    /** 射手骨矢的减速等级（失能型，默认缓慢Ⅱ） */
    public int getHunterSkillArrowSlowLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.arrow.slow-level", 2));
    }

    /** 炼金者弩箭喷溅的半径 */
    public double getHunterSkillPotionRadius() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.skills.potion.radius", 3.0);
    }

    /** 炼金者喷溅的减速时长（比射手短） */
    public int getHunterSkillPotionSlowSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.potion.slow-seconds", 3));
    }

    /** 炼金者喷溅的减速等级（比射手低） */
    public int getHunterSkillPotionSlowLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.potion.slow-level", 1));
    }

    /** 窥视者隐身时长 */
    public int getHunterSkillVanishSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.vanish.seconds", 7));
    }

    /** 窥视者隐身期间的加速等级 */
    public int getHunterSkillVanishSpeedLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.vanish.speed-level", 2));
    }

    public int getHunterSkillVanishCooldown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.vanish.cooldown", 28));
    }

    /** 所有母体的被动：站着不动多久后透视 */
    public int getHunterPassiveSightStationarySeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.passive-sight.stationary-seconds", 3));
    }

    public boolean isHunterPassiveSightEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.mother.passive-sight.enabled", true);
    }

    public double getHunterPassiveSightRange() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.passive-sight.range", 40.0);
    }

    /** 翻越点 */
    public boolean isHunterVaultEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.vault.enabled", true);
    }





    /** 用第二次机会自救要按住 Shift 多少秒 */
    public int getHunterSelfRescueSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("items.second-chance.self-rescue-seconds", 3));
    }

    /** 破阵刀一次给法阵加多少进度（0-100） */
    /** 破阵刀：当前法阵加速持续多少秒 */
    public int getRitualKnifeBoostSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("items.ritual-knife.boost-seconds", 20));
    }

    /** 破阵刀：加速倍率（2.0 = 破解速度翻倍） */
    public double getRitualKnifeBoostMultiplier() {
        ensureConfigLoaded();
        return Math.max(1.0, config.getDouble("items.ritual-knife.boost-multiplier", 2.0));
    }

    /** 破阵刀使用冷却（秒） */
    public int getRitualKnifeCooldown() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("items.ritual-knife.cooldown", 30));
    }

    /** 假人逃跑速度（每刻速度值，0.14 ≈ 2.8 格/秒） */
    public double getHunterTestDummyFleeSpeed() {
        ensureConfigLoaded();
        return Math.max(0.04, config.getDouble("hunter.test.dummy-flee-speed", 0.14));
    }

    /** 假人连续逃跑多少秒后体力耗尽（0 = 永不累） */
    public int getHunterTestDummyFleeSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.test.dummy-flee-seconds", 3));
    }

    /** 假人体力耗尽后站着喘多久（秒） */
    public int getHunterTestDummyTiredSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.test.dummy-tired-seconds", 2));
    }

    /** 是否允许观战（对局已开始时） */
    public boolean isHunterSpectateEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.spectate.enabled", true);
    }

    /** 观战者 / 已被淘汰的人允许离开猎场 X/Z 边界的格数 */
    public double getHunterSpectateMargin() {
        ensureConfigLoaded();
        return Math.max(0.0, config.getDouble("hunter.spectate.margin", 10.0));
    }

    /** 被淘汰的人提前退出时是否立即结算奖励 */
    public boolean isHunterEarlyExitSettleEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.reward.early-exit-settle", true);
    }

    /** 追击时是否用 Paper Pathfinder 走向预判点（关掉就退回原版直追） */
    public boolean isHunterMotherChasePathfinder() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.mother.chase.pathfinder", true);
    }

    /** 追击寻路的速度倍率（1.0 = 母体自身移速） */
    public double getHunterMotherChaseSpeed() {
        ensureConfigLoaded();
        return Math.max(0.5, config.getDouble("hunter.mother.chase.speed", 1.15));
    }

    /** 追击预判提前量（秒） */
    public double getHunterMotherChaseLeadSeconds() {
        ensureConfigLoaded();
        return Math.max(0.0, Math.min(1.5, config.getDouble("hunter.mother.chase.lead-seconds", 0.5)));
    }

    /** 臭牛排吃下去补多少点饱食度（"不多但有"） */
    public int getStinkySteakRestoreFood() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("items.stinky-steak.restore-food", 3));
    }

    /** 臭牛排顺带补多少饱和度 */
    public double getStinkySteakRestoreSaturation() {
        ensureConfigLoaded();
        return Math.max(0.0, config.getDouble("items.stinky-steak.restore-saturation", 1.0));
    }

    /** 应急口粮在道具箱随机池里的权重（1 = 等权，0 = 不进池） */
    public int getHunterRationChestWeight() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.items.ration.chest-weight", 4));
    }

    /** 漂浮药水在道具箱随机池里的权重（1 = 等权，0 = 不进池） */
    public int getLevitationChestWeight() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("items.levitation.chest-weight", 3));
    }

    /** 同一座法阵最多几个人一起破（0 = 不限） */
    public int getHunterAltarMaxChargers() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.altar.max-chargers", 2));
    }

    /** 眩晕药水的定身时长（刻） */
    public int getHunterStunPotionTicks() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("items.stun-potion.stun-ticks", 30));
    }

    /** 眩晕药水的喷溅半径 */
    public double getHunterStunPotionRadius() {
        ensureConfigLoaded();
        return Math.max(1.0, config.getDouble("items.stun-potion.radius", 3.0));
    }

    /** 黑衣人隐身时长（秒） */
    public int getShadowCloakSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.items.shadow-cloak.seconds", 6));
    }

    /** 受伤时的血量（显示用；6 = 3 颗心） */
    public double getHunterDamagedHealth() {
        ensureConfigLoaded();
        return Math.max(1.0, config.getDouble("hunter.downed.damaged-health", 6.0));
    }

    /** 倒地时的血量（显示用；2 = 1 颗心） */
    public double getHunterDownedHealth() {
        ensureConfigLoaded();
        return Math.max(1.0, config.getDouble("hunter.downed.downed-health", 2.0));
    }

    /** 珍珠女士：传送后自己缓慢的秒数 */
    public int getHunterSkillPearlSlowSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.mother.skills.pearl.slow-seconds", 4));
    }

    /** 珍珠女士：传送后自己缓慢的等级（1 = 缓慢Ⅰ） */
    public int getHunterSkillPearlSlowLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.pearl.slow-level", 1));
    }

    /** 珍珠女士：朝准星闪的最远格数 */
    public double getHunterSkillPearlDistance() {
        ensureConfigLoaded();
        return Math.max(2.0, config.getDouble("hunter.mother.skills.pearl.distance", 10.0));
    }

    /** 珍珠女士：技能冷却（秒） */
    public int getHunterSkillPearlCooldown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.pearl.cooldown", 24));
    }

    /** 医疗包是否直接治好（true = 立刻恢复健康） */
    public boolean isHunterMedkitInstant() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.items.medkit.instant", true);
    }

    /** 是否启用受伤/治疗机制 */
    public boolean isHunterHealEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.heal.enabled", true);
    }

    /** 队友把你治好要几秒 */
    public int getHunterHealOtherSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.heal.other-seconds", 8));
    }

    /** 自己按住 Shift 自愈要几秒 */
    public int getHunterHealSelfSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.heal.self-seconds", 20));
    }

    public double getHunterHealRadius() {
        ensureConfigLoaded();
        return config.getDouble("hunter.heal.radius", 2.5);
    }

    /** 中断后进度是否回退（false = 保持已积累的进度） */
    public boolean isHunterHealDecay() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.heal.decay", true);
    }

    /** 被救起后的状态：0 = 健康，1 = 受伤 */
    public int getHunterRescueHealth() {
        ensureConfigLoaded();
        return Math.max(0, Math.min(1, config.getInt("hunter.downed.rescue-health", 1)));
    }

    /** 是否定时把道具投放到地图里 */
    public boolean isHunterItemDropEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.item-drop.enabled", true);
    }

    public int getHunterItemDropIntervalSeconds() {
        ensureConfigLoaded();
        return Math.max(5, config.getInt("hunter.item-drop.interval-seconds", 45));
    }

    public int getHunterItemDropPerRound() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.item-drop.per-round", 2));
    }

    public int getHunterItemDropMaxOnGround() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.item-drop.max-on-ground", 6));
    }

    /** 是否显示抓痕与血迹 */
    public boolean isHunterTrailEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.trail.enabled", true);
    }

    /** 目标丢失后先去最后已知位置找多少秒 */
    public int getHunterMotherSearchSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.mother.search-seconds", 8));
    }

    /** 正在破阵的逃生者在目标评分里的优先权重（距离减多少格） */
    public double getHunterMotherAltarPriorityBonus() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.altar-priority-bonus", 12.0);
    }

    /** 没人可追时是否去守未完成的法阵 */
    public boolean isHunterMotherPatrolEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.mother.patrol.enabled", true);
    }

    /** 巡逻移动速度倍率 */
    // ---- AI 手感（2026-11-12）：卡视野 / 够不着就放弃 / 守机站位 ----

    public boolean isHunterMotherLineOfSight() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.mother.line-of-sight", true);
    }

    /** 这么近一定看得见，不再做视线判定 */
    public double getHunterMotherCloseRange() {
        ensureConfigLoaded();
        return Math.max(1.0, config.getDouble("hunter.mother.close-range", 6.0));
    }

    /** 破阵声音的感知半径（看不见也能被听见） */
    public double getHunterMotherHearRange() {
        ensureConfigLoaded();
        return Math.max(0.0, config.getDouble("hunter.mother.hear-range", 20.0));
    }

    /** 目标高差超过这么多就判为够不着 */
    public double getHunterMotherMaxClimb() {
        ensureConfigLoaded();
        return Math.max(0.0, config.getDouble("hunter.mother.max-climb", 3.0));
    }

    public boolean isHunterMotherPatrolPreferCharging() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.mother.patrol.prefer-charging", true);
    }

    public boolean isHunterMotherPatrolSkipHardened() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.mother.patrol.skip-hardened", true);
    }

    // ---- 绕后 / 假动作（2026-11-12）----

    public boolean isHunterMotherFlankEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.mother.flank.enabled", true);
    }

    /** 进入这个距离才考虑绕后 */
    public double getHunterMotherJukeRange() {
        ensureConfigLoaded();
        return Math.max(2.0, config.getDouble("hunter.mother.flank.range", 12.0));
    }

    /** 比这更近就直接砍，不绕 */
    public double getHunterMotherJukeMinRange() {
        ensureConfigLoaded();
        return Math.max(1.0, config.getDouble("hunter.mother.flank.min-range", 4.0));
    }

    /** 背对程度阈值：目标视线与「目标→母体」夹角越大越算背对 */
    public double getHunterMotherFlankDot() {
        ensureConfigLoaded();
        return Math.max(0.0, Math.min(1.0, config.getDouble("hunter.mother.flank.dot", 0.35)));
    }

    public double getHunterMotherFlankOffset() {
        ensureConfigLoaded();
        return Math.max(1.0, config.getDouble("hunter.mother.flank.offset", 4.0));
    }

    public int getHunterMotherFlankMaxSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.flank.max-seconds", 3));
    }

    public int getHunterMotherFlankCooldownSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.mother.flank.cooldown-seconds", 8));
    }

    public boolean isHunterMotherFeintEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.mother.feint.enabled", true);
    }

    public int getHunterMotherFeintChance() {
        ensureConfigLoaded();
        return Math.max(0, Math.min(100, config.getInt("hunter.mother.feint.chance", 35)));
    }

    public int getHunterMotherFeintSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.feint.seconds", 1));
    }

    public int getHunterMotherFeintCooldownSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.mother.feint.cooldown-seconds", 12));
    }

    /** 绕后 / 假动作时的移动速度倍率（Pathfinder speed） */
    public double getHunterMotherJukeSpeed() {
        ensureConfigLoaded();
        return Math.max(0.2, config.getDouble("hunter.mother.juke-speed", 1.0));
    }

    /** 守机时站到阵心外侧多少格（0 = 站阵心正中） */
    public double getHunterMotherPatrolStandOffset() {
        ensureConfigLoaded();
        return Math.max(0.0, config.getDouble("hunter.mother.patrol.stand-offset", 3.0));
    }

    public double getHunterMotherPatrolSpeed() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.patrol.speed", 1.0);
    }

    /** 被击倒后多少秒没人救就淘汰（进旁观） */
    public int getHunterBleedoutSeconds() {
        ensureConfigLoaded();
        // v2 起默认 60 秒：30 秒太短，队友往往刚看见人就来不及跑过去
        return Math.max(1, config.getInt("hunter.downed.bleedout-seconds", 60));
    }

    /** 凝冰球砸中母体时的眩晕时长（秒）：抱着人被打中会松手 */
    public int getHunterIceBallMotherStunSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.items.ice-ball.mother-stun-seconds", 4));
    }

    /** 救人之后被救者的受击保护时长（秒） */
    public int getHunterRescueProtectSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.downed.rescue-protect-seconds", 3));
    }

    // ---------------------------------------------------------------- 行动分（奖金按操作分）

    /** 逃生者每秒破阵算多少分 */
    public double getHunterRewardActionAltarSecond() {
        ensureConfigLoaded();
        return config.getDouble("hunter.reward.actions.altar-second", 2.0);
    }

    /** 每次成功救人算多少分 */
    public double getHunterRewardActionRescue() {
        ensureConfigLoaded();
        return config.getDouble("hunter.reward.actions.rescue", 15.0);
    }

    /** 成功撤离的额外分 */
    public double getHunterRewardActionEscape() {
        ensureConfigLoaded();
        return config.getDouble("hunter.reward.actions.escape", 25.0);
    }

    /** 母体每次命中算多少分 */
    public double getHunterRewardActionMotherHit() {
        ensureConfigLoaded();
        return config.getDouble("hunter.reward.actions.mother-hit", 5.0);
    }

    /** 母体每次击倒算多少分 */
    public double getHunterRewardActionMotherDown() {
        ensureConfigLoaded();
        return config.getDouble("hunter.reward.actions.mother-down", 10.0);
    }

    /** 母体每淘汰一人算多少分 */
    public double getHunterRewardActionMotherEliminate() {
        ensureConfigLoaded();
        return config.getDouble("hunter.reward.actions.mother-eliminate", 40.0);
    }

    /** AI 母体一次刷几个（默认 2 个，出生在场地不同位置） */
    public int getHunterAiMotherCount() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(3, config.getInt("hunter.mother.ai-count", 2)));
    }

    /**
     * AI 母体的跳跃提升等级（1 = 约 1.25 格，2 = 约 2 格，3 = 约 3 格，0 = 关闭）。
     *
     * <p>只给 **AI 母体**；玩家母体不给——真人玩家靠走位和技能，跳 3 格会让追逐完全失去意义。</p>
     */
    public int getHunterMotherJumpBoostLevel() {
        ensureConfigLoaded();
        return Math.max(0, Math.min(5, config.getInt("hunter.mother.jump-boost-level", 3)));
    }

    /** AI 母体卡住判定：活动范围不超过这么多格就算没动 */
    public double getHunterMotherStuckBox() {
        ensureConfigLoaded();
        return Math.max(1.5, config.getDouble("hunter.mother.stuck-box", 4.0));
    }

    /** AI 母体在卡住判定范围内待够多少秒 → 传送脱困 */
    public int getHunterMotherStuckSeconds() {
        ensureConfigLoaded();
        return Math.max(2, config.getInt("hunter.mother.stuck-seconds", 6));
    }

    /** 是否允许「卡住自动传送脱困」 */
    /**
     * 卡住怎么脱困：{@code nudge}（默认，原地挤出去；{@code off} = 不处理）。
     *
     * <p>以前是「卡住就把母体瞬移到逃生者旁边 6~8 格」—— 那是作弊，已彻底移除。</p>
     */
    public String getHunterMotherStuckEscape() {
        ensureConfigLoaded();
        String value = config.getString("hunter.mother.stuck-escape", "nudge");
        if (value == null) return "nudge";
        value = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "off".equals(value) ? "off" : "nudge";
    }

    /** 卡住后允许「挤出去」的最大位移（格）：只在原地附近，绝不朝逃生者跳 */
    public double getHunterMotherStuckEscapeRange() {
        ensureConfigLoaded();
        return Math.max(1.0, Math.min(4.0, config.getDouble("hunter.mother.stuck-escape-range", 2.6)));
    }

    /** 母体砸红石块后，法阵被加固多少秒（这段时间人类破不了） */
    /** 连续打碎地砖几次才加固（默认 7） */
    // ---- 法阵瘫痪（单座阵推到 at% 就停一分钟：不能继续推、也不能加固）----

    public boolean isHunterAltarParalyzeEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.altar.paralyze.enabled", true);
    }

    /** 第一次跨过这个百分比就瘫痪（0 或 >=100 = 不触发） */
    public int getHunterAltarParalyzeAt() {
        ensureConfigLoaded();
        return config.getInt("hunter.altar.paralyze.at", 20);
    }

    /** 瘫痪时长（秒） */
    public int getHunterAltarParalyzeSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.altar.paralyze.seconds", 60));
    }

    public int getHunterAltarHardenHits() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.altar.harden-hits", 16));
    }

    /** 中断多久算「不连续」，归零重数（0 = 不清零） */
    public int getHunterAltarHardenResetSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.altar.harden-reset-seconds", 10));
    }

    /** 加固成功之后的冷却（秒）：这段时间里打树叶不计数；0 = 不限 */
    public int getHunterAltarHardenCooldownSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.altar.harden-cooldown-seconds", 60));
    }

    public int getHunterAltarHardenSeconds() {
        ensureConfigLoaded();
        return Math.max(5, config.getInt("hunter.altar.harden-seconds", 50));
    }

    /** 栅栏要打几下才塌 */
    public int getHunterRoleFenceHits() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(10, config.getInt("hunter.role.fence-hits", 3)));
    }

    /** AI 母体把人钉上法阵后，在原地守多久（秒） */
    public int getHunterChairAiCampSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.chair.ai-camp-seconds", 10));
    }

    /**
     * 进对局时的初始饱和度。
     *
     * <p>坑在这里：饱和度是**先于饱食度**被消耗的（每 4 点消耗度掉 1 点饱和），
     * 原来进场直接 `setSaturation(20)`，等于先送了 80 点消耗度的缓冲——
     * 饥饿Ⅱ 每秒才 0.2 点，要 400 秒（6 分半）饱食度才会开始掉，实测就是「根本不掉」。</p>
     */
    public double getHunterHungerStartSaturation() {
        ensureConfigLoaded();
        return Math.max(0.0, Math.min(20.0, config.getDouble("hunter.hunger.start-saturation", 5.0)));
    }

    // ---------------------------------------------------------------- 受击链（v7）

    /** 被打中后获得加速多久（刻） */
    public int getHunterHitSpeedTicks() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.downed.hit-speed-ticks", 40));
    }

    /** 被打中后加速的等级 */
    public int getHunterHitSpeedLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.downed.hit-speed-level", 2));
    }

    /** 加速结束后接多少刻缓慢（力竭） */
    public int getHunterPostSlowTicks() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.downed.post-slow-ticks", 30));
    }

    /** 力竭缓慢的等级 */
    public int getHunterPostSlowLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.downed.post-slow-level", 1));
    }

    /** 母体斧头砍出去之后多久回到手上（秒） */
    public int getHunterAxeRecoverSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.axe-recover-seconds", 3));
    }

    /** 自愈最低饱食度：饿着治不了伤 */
    public int getHunterHealMinFood() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.heal.min-food", 8));
    }

    /** 被母体抱着时按住 Shift 挣扎，几秒能挣脱 */
    public int getHunterChairStruggleSeconds() {
        ensureConfigLoaded();
        return Math.max(2, config.getInt("hunter.chair.struggle-seconds", 8));
    }

    /** 第三次上椅的倒计时（秒）：到点直接放飞 */
    public int getHunterChairThirdSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.chair.third-seconds", 5));
    }

    // ---------------------------------------------------------------- 肾上腺素（GHG 弱化版）

    /** 肾上腺素加速持续几秒（GHG 弱化：默认 4 秒，不是 Gost 的 10 秒） */
    public int getHunterAdrenalineSeconds() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(15, config.getInt("hunter.items.adrenaline.duration-seconds", 4)));
    }

    /** 肾上腺素的速度等级（默认 1 = 速度Ⅰ） */
    public int getHunterAdrenalineSpeedLevel() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(3, config.getInt("hunter.items.adrenaline.speed-level", 1)));
    }

    /** 加速结束后的后遗症：缓慢几秒（0 = 没有） */
    public int getHunterAdrenalineAfterSlowSeconds() {
        ensureConfigLoaded();
        return Math.max(0, Math.min(10, config.getInt("hunter.items.adrenaline.after-slow-seconds", 1)));
    }

    /** 猎场保护：没管理权限的人在猎场里不能破坏/放置方块 */
    public boolean isAreaProtectionEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("area.protection", true);
    }

    // ---------------------------------------------------------------- 职业入场券

    /**
     * 某个职业的入场券（在基础入场费之外另收）。
     *
     * <p>冰球手最强（能打断搬运），所以最贵；工程师的栅栏是软控，最便宜。</p>
     */
    public double getHunterRoleFee(HunterRole role) {
        if (role == null) return 0;
        ensureConfigLoaded();
        double fallback = role == HunterRole.ICER ? 80.0
            : role == HunterRole.GUARDIAN ? 60.0
            : role == HunterRole.LANCER ? 60.0 : 40.0; // ENGINEER / RUNNER 都是 40
        return Math.max(0, config.getDouble("hunter.reward.role-fee." + role.id(), fallback));
    }

    // ---------------------------------------------------------------- 职业

    /** 职业系统总开关 */
    public boolean isHunterRoleEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.role.enabled", true);
    }

    /** 技能道具用掉之后隔多久重新发到背包（秒）：没单独配就用这个兜底 */
    public int getHunterRoleRefreshSeconds() {
        ensureConfigLoaded();
        return Math.max(5, config.getInt("hunter.role.refresh-seconds", 60));
    }

    /**
     * 某个职业的技能道具冷却（秒）。
     *
     * <p>每个职业强度不同，统一 60 秒太钝：跑者的肾上腺素本来就弱，40 秒更合理；
     * 冰球能打断搬运，给长一点。填 `hunter.role.refresh.<职业>` 单独覆盖。</p>
     */
    public int getHunterRoleRefreshSeconds(HunterRole role) {
        if (role == null) return getHunterRoleRefreshSeconds();
        ensureConfigLoaded();
        int fallback;
        switch (role) {
            case ICER: fallback = 55; break;
            case GUARDIAN: fallback = 45; break;
            case LANCER: fallback = 40; break;
            case ENGINEER: fallback = getHunterRoleFenceRefreshSeconds(); break;
            case RUNNER: fallback = 35; break;
            default: fallback = getHunterRoleRefreshSeconds(); break;
        }
        return Math.max(5, config.getInt("hunter.role.refresh." + role.id(), fallback));
    }

    /** 栅栏一次架几格宽 */
    public int getHunterRoleFenceWidth() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(5, config.getInt("hunter.role.fence-width", 3)));
    }

    /** 栅栏几格高 */
    public int getHunterRoleFenceHeight() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(3, config.getInt("hunter.role.fence-height", 2)));
    }

    /** 栅栏工具用掉之后隔多久补发（秒） */
    public int getHunterRoleFenceRefreshSeconds() {
        ensureConfigLoaded();
        return Math.max(5, config.getInt("hunter.role.fence-refresh-seconds", 45));
    }

    // ---------------------------------------------------------------- 救赎护符

    /** 护符生效期间救人读条的加速倍率 */
    public double getCharmSpeedMultiplier() {
        ensureConfigLoaded();
        return Math.max(1.0, Math.min(10.0, config.getDouble("items.redemption-charm.speed-multiplier", 3.0)));
    }

    /** 护符持续时间（秒） */
    public int getCharmDurationSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("items.redemption-charm.duration-seconds", 15));
    }

    /** 护符冷却（秒） */
    public int getCharmCooldownSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("items.redemption-charm.cooldown", 45));
    }

    // ---------------------------------------------------------------- 狂欢之椅（v3）

    /**
     * 倒地后多少秒没人处理就自己爬起来（回到「流血/受伤」状态）。
     *
     * <p>攻击链是 健康 → 流血 → 倒地：倒地那一刻流血就算结算掉了，所以「没人管」不是终点——
     * 母体 {@code recover-seconds} 秒内不来抱走/钉阵，人就自己爬回流血状态。填 0 关闭。</p>
     */
    public int getHunterDownedRecoverSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.downed.recover-seconds", 15));
    }

    /**
     * 狂欢之椅（直接钉在法阵上）是否启用。
     *
     * <p>关掉就退回「倒地放血淘汰」。旧键 {@code hunter.chair.count} 已不再读取——
     * 椅子不再单独生成，用的就是场上的法阵。</p>
     */
    public boolean isHunterChairEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.chair.enabled", true);
    }

    /** 第一次上椅的倒计时（秒） */
    public int getHunterChairFirstSeconds() {
        ensureConfigLoaded();
        return Math.max(5, config.getInt("hunter.chair.first-seconds", 60));
    }

    /** 第二次上椅的倒计时（秒） */
    public int getHunterChairSecondSeconds() {
        ensureConfigLoaded();
        return Math.max(5, config.getInt("hunter.chair.second-seconds", 30));
    }


    /** 从椅子上救下来需要按住 Shift 几秒 */
    public int getHunterChairRescueSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.chair.rescue-seconds", 4));
    }

    /** 玩家母体要按住 Shift 贴在倒地者身边多久才抱起来（秒，支持小数） */
    public double getHunterChairGrabSeconds() {
        ensureConfigLoaded();
        return Math.max(0.5, config.getDouble("hunter.chair.grab-seconds", 2.0));
    }

    /** 抱着人时母体的移速倍率 */
    public double getHunterChairCarrySpeed() {
        ensureConfigLoaded();
        return Math.max(0.3, Math.min(1.0, config.getDouble("hunter.chair.carry-speed", 0.75)));
    }

    /** 只剩 1 名逃生者时是否开放地窖 */
    public boolean isHunterHatchEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.hatch.enabled", true);
    }

    public double getHunterHatchRadius() {
        ensureConfigLoaded();
        return config.getDouble("hunter.hatch.radius", 2.5);
    }

    /** 法阵全破后是否开始终局倒计时 */
    public boolean isHunterEndgameEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.endgame.enabled", true);
    }

    public int getHunterEndgameSeconds() {
        ensureConfigLoaded();
        return Math.max(10, config.getInt("hunter.endgame.seconds", 90));
    }

    /** 是否启用跳跃疲劳（不禁止跳跃，超出配额给一段缓慢） */
    public boolean isHunterJumpFatigueEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.jump-fatigue.enabled", true);
    }

    /** 窗口内允许的连续跳跃次数 */
    public int getHunterJumpFatigueMax() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.jump-fatigue.max-jumps", 3));
    }

    /** 配额统计窗口（秒） */
    public int getHunterJumpFatigueWindowSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.jump-fatigue.window-seconds", 5));
    }

    /** 超出配额后的腿软时长（秒） */
    public int getHunterJumpFatiguePenaltySeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.jump-fatigue.penalty-seconds", 2));
    }

    /** 超出配额后的腿软等级 */
    public int getHunterJumpFatiguePenaltyLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.jump-fatigue.penalty-level", 1));
    }

    /** 是否给逃生者黑暗效果（母体不受影响） */
    public boolean isHunterDarknessEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.darkness.enabled", true);
    }

    /** 准备阶段是否也压黑暗（false = 准备阶段视野正常，方便踩点） */
    public boolean isHunterDarknessInPreparation() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.darkness.preparation", true);
    }

    /** 黑暗效果等级（0 = I 级） */
    public int getHunterDarknessAmplifier() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.darkness.amplifier", 0));
    }

    /** 对局中是否禁止所有玩家疾跑 */
    public boolean isHunterNoSprint() {
        ensureConfigLoaded();
        return config.getBoolean("hunter.no-sprint", true);
    }

    /** 允许出现的母体种类（AI 随机从这里抽） */
    public java.util.List<String> getHunterEnabledKinds() {
        ensureConfigLoaded();
        return config.getStringList("hunter.mother.enabled-kinds");
    }

    public int getHunterSkillSprintCooldown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.sprint.cooldown", 20));
    }

    public int getHunterSkillSprintSeconds() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.sprint.seconds", 6));
    }

    public int getHunterSkillSprintLevel() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.sprint.level", 1));
    }

    public int getHunterSkillDashCooldown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.dash.cooldown", 16));
    }

    public double getHunterSkillDashPower() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.skills.dash.power", 2.6);
    }

    public int getHunterSkillArrowCooldown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.arrow.cooldown", 16));
    }

    public int getHunterSkillPotionCooldown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.potion.cooldown", 18));
    }

    public int getHunterSkillTeleportCooldown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.teleport.cooldown", 28));
    }

    /** 传送后母体自己硬直多少秒（禁止移动与互动） */
    /** 玩家母体「朝准星闪」的最远距离（格） */
    public double getHunterSkillTeleportDistance() {
        ensureConfigLoaded();
        return Math.max(2.0, config.getDouble("hunter.mother.skills.teleport.distance", 8.0));
    }

    public int getHunterSkillTeleportFreezeSeconds() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("hunter.mother.skills.teleport.freeze-seconds", 2));
    }

    public int getHunterSkillSnareCooldown() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("hunter.mother.skills.snare.cooldown", 18));
    }

    /** 缚网的展开半径（以母体自身为中心） */
    public double getHunterSkillSnareRadius() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.skills.snare.radius", 7.0);
    }

    /** 视野透视的作用半径 */
    public double getHunterSkillSightRange() {
        ensureConfigLoaded();
        return config.getDouble("hunter.mother.skills.sight.range", 40.0);
    }

    public int getGameDuration() {
        ensureConfigLoaded();
        return config.getInt("game.duration", 420);
    }
    
    public int getPreparationTime() {
        return config.getInt("game.preparation-time", 20);
    }
    
    public int getQueueTime() {
        return config.getInt("game.queue-time", 60);
    }
    
    public int getMinPlayers() {
        return config.getInt("game.min-players", 2);
    }
    
    public int getMaxPlayers() {
        return config.getInt("game.max-players", 16);
    }
    
    public int getMaxGames() {
        return config.getInt("game.max-games", 1);
    }
    
    public double getEntryFee() {
        ensureConfigLoaded();
        return config.getDouble("economy.entry-fee", 100.0);
    }
    
    public void setEntryFee(double entryFee) {
        ensureConfigLoaded();
        config.set("economy.entry-fee", entryFee);
        plugin.saveConfig();
    }
    
    public double getServerBonus() {
        ensureConfigLoaded();
        return config.getDouble("economy.server-bonus", 5000.0);
    }
    
    public void setServerBonus(double serverBonus) {
        ensureConfigLoaded();
        config.set("economy.server-bonus", serverBonus);
        plugin.saveConfig();
    }
    
    public int getAdrenalineDuration() {
        ensureConfigLoaded();
        return config.getInt("items.adrenaline.duration", 10);
    }
    
    public int getAdrenalineSpeedLevel() {
        ensureConfigLoaded();
        return config.getInt("items.adrenaline.speed-level", 2);
    }
    
    public int getFrenzyDuration() {
        ensureConfigLoaded();
        return config.getInt("items.frenzy.duration", 10);
    }
    
    public int getFrenzySpeedLevel() {
        ensureConfigLoaded();
        return config.getInt("items.frenzy.speed-level", 2);
    }
    
    public int getIceBallSlowDuration() {
        return config.getInt("items.ice-ball.slow-duration", 4);
    }
    
    public int getIceBallSlowLevel() {
        return config.getInt("items.ice-ball.slow-level", 4);
    }
    
    public int getSoulControlFreezeDuration() {
        return config.getInt("items.soul-control.freeze-duration", 6);
    }
    
    public int getSoulControlCooldown() {
        return config.getInt("items.soul-control.cooldown", 18);
    }
    
    public int getMotherGhostBlindnessDuration() {
        return config.getInt("effects.mother-ghost-blindness-duration", 20);
    }
    
    public int getGhostSenseDuration() {
        return config.getInt("effects.ghost-sense-duration", 5);
    }
    
    public boolean isInfectionLightningEnabled() {
        return config.getBoolean("effects.infection-lightning", true);
    }
    
    public boolean isInfectionSoundEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("effects.infection-sound", true);
    }
    
    public boolean isAutoTeleportEnabled() {
        return config.getBoolean("area.auto-teleport", true);
    }
    
    /**
     * 圈地选区工具的材质。
     *
     * <p>GHG 自己的键是 {@code area.tool-material}（默认小麦）。旧键 {@code area.selection-tool}
     * 从 Gost 继承过来、值多半还是 MAGMA_CREAM，所以不再读取它——否则从旧配置迁移过来的服务器
     * 会一直拿到岩浆膏。旧键留在文件里不动，随时可以删。</p>
     */
    public String getSelectionTool() {
        ensureConfigLoaded();
        return config.getString("area.tool-material", "WHEAT");
    }
    
    public int getMaxAreas() {
        return config.getInt("area.max-areas", 20);
    }

    /** 每局是否从已启用区域中随机选图 */
    public boolean isRandomAreaEachGame() {
        ensureConfigLoaded();
        return config.getBoolean("area.random-each-game", true);
    }

    /**
     * 死亡状态模式：{@code invisible}（隐身，默认）或 {@code spectator}（旁观）。
     * 非法值一律回退为 invisible。
     */
    public String getDeathStateMode() {
        ensureConfigLoaded();
        String mode = config.getString("death-state.mode", "invisible");
        if (mode == null) return "invisible";
        mode = mode.trim().toLowerCase(java.util.Locale.ROOT);
        return "spectator".equals(mode) ? "spectator" : "invisible";
    }
    
    // 新功能配置方法
    
    public int getGhostImmobilizeDuration() {
        return config.getInt("effects.ghost-immobilize-duration", 20);
    }
    
    public boolean isConversionEnabled() {
        return config.getBoolean("conversion.enabled", false);
    }
    
    public int getConversionActivateTime() {
        return config.getInt("conversion.activate-time", 120);
    }
    
    public int getConversionCooldown() {
        return config.getInt("conversion.cooldown", 30);
    }
    
    public int getConversionCost() {
        return config.getInt("conversion.cost", 15);
    }
    
    public boolean isItemSpawnEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("item-spawn.enabled", true);
    }
    
    public int getItemSpawnInterval() {
        return config.getInt("item-spawn.interval", 60);
    }
    
    public int getItemSpawnMaxPerRefresh() {
        return config.getInt("item-spawn.max-per-refresh", 3);
    }
    
    public int getItemSpawnMaxPerPlayer() {
        return config.getInt("item-spawn.max-per-player", 1);
    }
    

    

    

    

    
    public int getMatchQueueTime() {
        return config.getInt("game.match-queue-time", 30);
    }
    
    public int getSoulDetectorDuration() {
        return config.getInt("items.soul-detector.duration", 25);
    }
    
    public int getSoulDetectorCooldown() {
        return config.getInt("items.soul-detector.cooldown", 30);
    }
    
    public int getTeleportPearlCooldown() {
        return config.getInt("items.teleport-pearl.cooldown", 10);
    }
    
    public int getStinkySteakSpeedDuration() {
        ensureConfigLoaded();
        return Math.max(1, config.getInt("items.stinky-steak.speed-duration", 6));
    }
    
    /** 臭牛排的速度等级（1 = 速度Ⅰ，2 = 速度Ⅱ）：真正生效时按 level-1 作为放大器 */
    public int getStinkySteakSpeedLevel() {
        ensureConfigLoaded();
        return Math.max(1, Math.min(3, config.getInt("items.stinky-steak.speed-level", 1)));
    }
    
    public int getStinkySteakGlowingDuration() {
        return config.getInt("items.stinky-steak.glowing-duration", 10);
    }
    
    public int getStinkySteakCooldown() {
        return config.getInt("items.stinky-steak.cooldown", 30);
    }
    
    public int getSecondChanceCooldown() {
        ensureConfigLoaded();
        return config.getInt("items.second-chance.cooldown", 60);
    }
    
    public int getSecondChanceHumanSpeedDuration() {
        ensureConfigLoaded();
        return config.getInt("items.second-chance.human-speed-duration", 10);
    }
    
    public int getSecondChanceHumanSpeedLevel() {
        ensureConfigLoaded();
        return config.getInt("items.second-chance.human-speed-level", 2);
    }
    
    public int getSecondChanceHumanGlowingDuration() {
        ensureConfigLoaded();
        return config.getInt("items.second-chance.human-glowing-duration", 10);
    }
    
    public int getSecondChanceGhostSlowDuration() {
        ensureConfigLoaded();
        return config.getInt("items.second-chance.ghost-slow-duration", 7);
    }
    
    public int getSecondChanceGhostSlowLevel() {
        ensureConfigLoaded();
        return config.getInt("items.second-chance.ghost-slow-level", 1);
    }
    
    public int getMaxItemTypesPerPlayer() {
        return config.getInt("item-spawn.max-item-types-per-player", 6);
    }
    
    public boolean isMinuteGlowingEnabled() {
        return config.getBoolean("effects.minute-glowing.enabled", true);
    }
    
    public int getMinuteGlowingDuration() {
        return config.getInt("effects.minute-glowing.duration", 5);
    }
    
    public int getMinuteGlowingInterval() {
        return config.getInt("effects.minute-glowing.interval", 60);
    }
    
    // 鬼转人类功能配置
    public boolean isGhostToHumanEnabled() {
        return config.getBoolean("ghost-to-human.enabled", false);
    }
    
    public int getGhostToHumanRemainingTime() {
        return config.getInt("ghost-to-human.remaining-time", 180);
    }
    
    public int getGhostToHumanCount() {
        return config.getInt("ghost-to-human.count", 1);
    }
    
    // 黑暗效果配置
    public boolean isDarkEffectEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("dark-effect.enabled", true);
    }
    
    public int getDarkEffectDuration() {
        ensureConfigLoaded();
        return config.getInt("dark-effect.duration", 999999);
    }
    
    public int getDarkEffectAmplifier() {
        ensureConfigLoaded();
        return config.getInt("dark-effect.amplifier", 0);
    }
    
    // 设置黑暗效果开关
    public void setDarkEffectEnabled(boolean enabled) {
        config.set("dark-effect.enabled", enabled);
        plugin.saveConfig();
    }
    
    // 心跳声配置
    public boolean isHeartbeatEnabled() {
        return config.getBoolean("heartbeat.enabled", true);
    }
    
    public int getHeartbeatInterval() {
        return config.getInt("heartbeat.interval", 10);
    }
    
    // 设置心跳声开关
    public void setHeartbeatEnabled(boolean enabled) {
        config.set("heartbeat.enabled", enabled);
        plugin.saveConfig();
    }
    
    // 局内饱食度配置（v2.3.2）
    public boolean isFoodSystemEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("food.enabled", true);
    }
    
    public int getFoodRegeneratePerSecond() {
        ensureConfigLoaded();
        int amount = config.getInt("food.regenerate-per-second", 1);
        return Math.max(1, Math.min(20, amount)); // 限制在 1-20 之间
    }
    
    public double getFoodGhostRegenerateInterval() {
        ensureConfigLoaded();
        double interval = config.getDouble("food.ghost-regenerate-interval", 0.8);
        return Math.max(0.2, Math.min(10.0, interval)); // 限制在 0.2-10 秒之间
    }
    
    // 语言配置
    public String getDefaultLanguage() {
        return config.getString("language.default", "zh_CN");
    }
    
    public boolean isAutoDetectLanguage() {
        return config.getBoolean("language.auto-detect", true);
    }
    
    public void setDefaultLanguage(String language) {
        config.set("language.default", language);
        plugin.saveConfig();
    }
    
    public void setAutoDetectLanguage(boolean autoDetect) {
        config.set("language.auto-detect", autoDetect);
        plugin.saveConfig();
    }
    
    // 神圣守护配置
    public boolean isDivineGuardianEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.enabled", false);
    }
    
    public int getDivineGuardianMaxCharges() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.max-charges", 3);
    }
    
    public int getDivineGuardianCooldown() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.cooldown", 5);
    }
    
    public int getDivineGuardianInvisibilityDuration() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.invisibility-duration", 10);
    }
    
    public void setDivineGuardianEnabled(boolean enabled) {
        config.set("divine-guardian.enabled", enabled);
        plugin.saveConfig();
    }
    
    public void setDivineGuardianMaxCharges(int maxCharges) {
        config.set("divine-guardian.max-charges", maxCharges);
        plugin.saveConfig();
    }
    
    public void setDivineGuardianCooldown(int cooldown) {
        config.set("divine-guardian.cooldown", cooldown);
        plugin.saveConfig();
    }
    
    public void setDivineGuardianBroadcastEnabled(boolean enabled) {
        config.set("divine-guardian.broadcast", enabled);
        plugin.saveConfig();
    }
    
    public String getDivineGuardianMode() {
        ensureConfigLoaded();
        return config.getString("divine-guardian.mode", "1");
    }
    
    public void setDivineGuardianMode(String mode) {
        config.set("divine-guardian.mode", mode);
        plugin.saveConfig();
    }
    
    // 救赎者配置（模式2）
    public int getRedeemerMaxUses() {
        ensureConfigLoaded();
        return config.getInt("redeemer.max-uses", 2);
    }
    
    public int getRedeemerSpeedLevel() {
        return config.getInt("redeemer.speed-level", 1);
    }
    
    public int getHolyRedemptionCooldown() {
        return config.getInt("redeemer.holy-redemption-cooldown", 10);
    }
    
    public int getConversionInvincibilityTime() {
        return config.getInt("redeemer.conversion-invincibility-time", 5);
    }
    
    public boolean isRedeemerBroadcastEnabled() {
        return config.getBoolean("redeemer.broadcast", true);
    }
    
    public void setRedeemerMaxUses(int maxUses) {
        config.set("redeemer.max-uses", maxUses);
        plugin.saveConfig();
    }
    
    public void setRedeemerSpeedLevel(int speedLevel) {
        config.set("redeemer.speed-level", speedLevel);
        plugin.saveConfig();
    }
    
    public void setHolyRedemptionCooldown(int cooldown) {
        config.set("redeemer.holy-redemption-cooldown", cooldown);
        plugin.saveConfig();
    }
    
    public void setConversionInvincibilityTime(int time) {
        config.set("redeemer.conversion-invincibility-time", time);
        plugin.saveConfig();
    }
    
    public void setRedeemerBroadcastEnabled(boolean enabled) {
        config.set("redeemer.broadcast", enabled);
        plugin.saveConfig();
    }
    
    // 鬼玩家粒子效果配置
    public boolean isGhostParticleEnabled() {
        return config.getBoolean("ghost-particle.enabled", true);
    }
    
    public String getGhostParticleType() {
        return config.getString("ghost-particle.type", "DUST");
    }
    
    public int getGhostParticleCount() {
        return config.getInt("ghost-particle.count", 5);
    }
    
    public int getGhostParticleInterval() {
        return config.getInt("ghost-particle.interval", 15);
    }
    
    public String getGhostParticleMotherColor() {
        return config.getString("ghost-particle.mother-color", "255,0,0");
    }
    
    public String getGhostParticleNormalColor() {
        return config.getString("ghost-particle.normal-color", "0,255,0");
    }
    
    public double getGhostParticleSize() {
        return config.getDouble("ghost-particle.size", 1.0);
    }
    
    public boolean isGhostParticleShowInPreparation() {
        return config.getBoolean("ghost-particle.show-in-preparation", true);
    }
    
    public void setGhostParticleEnabled(boolean enabled) {
        config.set("ghost-particle.enabled", enabled);
        plugin.saveConfig();
    }
    
    public void setGhostParticleType(String type) {
        config.set("ghost-particle.type", type);
        plugin.saveConfig();
    }
    
    public void setGhostParticleCount(int count) {
        config.set("ghost-particle.count", count);
        plugin.saveConfig();
    }
    
    public void setGhostParticleInterval(int interval) {
        config.set("ghost-particle.interval", interval);
        plugin.saveConfig();
    }
    
    public void setGhostParticleMotherColor(String color) {
        config.set("ghost-particle.mother-color", color);
        plugin.saveConfig();
    }
    
    public void setGhostParticleNormalColor(String color) {
        config.set("ghost-particle.normal-color", color);
        plugin.saveConfig();
    }
    
    public void setGhostParticleSize(double size) {
        config.set("ghost-particle.size", size);
        plugin.saveConfig();
    }
    
    public void setGhostParticleShowInPreparation(boolean show) {
        config.set("ghost-particle.show-in-preparation", show);
        plugin.saveConfig();
    }
    
    // 生物清理设置
    public boolean isClearEntitiesEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("entity-clear.enabled", true);
    }
    
    public boolean isPreventEntitySpawnEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("entity-clear.prevent-spawn", true);
    }
    
    // 救赎者常驻设置
    public boolean isRedeemerEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("redeemer.enabled", true);
    }
    
    public int getRedeemerMaxCount() {
        ensureConfigLoaded();
        return config.getInt("redeemer.max-count", 2);
    }
    
    // 收割者技能设置
    public int getReaperHarvestCooldown() {
        ensureConfigLoaded();
        return config.getInt("reaper.harvest-cooldown", 10);
    }
    
    public double getReaperHarvestRange() {
        ensureConfigLoaded();
        return config.getDouble("reaper.harvest-range", 4.0);
    }
    
    // 额外奖励设置
    public int getRewardRedeemGhost() {
        ensureConfigLoaded();
        return config.getInt("reward.redeem-ghost", 100);
    }
    
    public int getRewardKillNormal() {
        ensureConfigLoaded();
        return config.getInt("reward.kill-normal", 50);
    }
    
    public int getRewardKillMother() {
        ensureConfigLoaded();
        return config.getInt("reward.kill-mother", 100);
    }
    
    // 背包限制设置
    public int getMaxItemTypes() {
        ensureConfigLoaded();
        return config.getInt("inventory.max-item-types", 9);
    }
    
    // 游戏血量配置
    public double getMaxHealth() {
        ensureConfigLoaded();
        return config.getDouble("health.max-health", 10.0);
    }
    
    public void setMaxHealth(double maxHealth) {
        config.set("health.max-health", maxHealth);
        plugin.saveConfig();
    }
    
    // ==============================================
    // 神圣守护系统 v2.2.2 新配置方法
    // ==============================================
    
    // 基础设置
    public boolean isDivineGuardianSystemEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.enabled", true);
    }
    
    public int getDivineGuardianTriggerHumanCount() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.trigger-human-count", 2);
    }
    
    public boolean isDivineGuardianBroadcastEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.broadcast", true);
    }
    
    // 神圣守护效果设置
    public boolean isHolyGuardianTeleportAttackerEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.holy-guardian.teleport-attacker", true);
    }
    
    public double getHolyGuardianTeleportRadius() {
        ensureConfigLoaded();
        return config.getDouble("divine-guardian.holy-guardian.teleport-radius", 10.0);
    }
    
    public int getHolyGuardianEffectDuration() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.holy-guardian.effect-duration", 30);
    }
    
    public int getHolyGuardianDefenseCharges() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.holy-guardian.defense-charges", 3);
    }
    
    // 猎魔人阶段设置
    public int getDemonHunterPhaseStartTime() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.demon-hunter.phase-start-time", 90);
    }
    
    public int getDemonHunterMaxUses() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.demon-hunter.max-uses", 2);
    }
    
    // 猎魔人阶段：猎魔人获得/刷新神圣守护效果开关
    public boolean isDemonHunterHolyGuardianEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.demon-hunter.holy-guardian-on-phase", true);
    }
    
    public int getDemonHunterHolyRedemptionCooldown() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.demon-hunter.holy-redemption-cooldown", 10);
    }
    
    // 收割者道具设置
    public double getReaperWeaponDamagePerHit() {
        ensureConfigLoaded();
        return config.getDouble("divine-guardian.demon-hunter.reaper-weapon.damage-per-hit", 1.0);
    }
    
    public int getReaperWeaponHitsToKill() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.demon-hunter.reaper-weapon.hits-to-kill", 2);
    }
    
    public double getReaperWeaponAttackCooldown() {
        ensureConfigLoaded();
        return config.getDouble("divine-guardian.demon-hunter.reaper-weapon.attack-cooldown", 2.0);
    }
    
    public boolean isReaperWeaponEnchantGlowEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.demon-hunter.reaper-weapon.enchant-glow", true);
    }
    
    // 击杀奖励设置
    public double getDemonHunterKillRewardRatio() {
        ensureConfigLoaded();
        return config.getDouble("divine-guardian.demon-hunter.kill-rewards.demon-hunter-kill-reward", 0.3);
    }
    
    public double getMotherKillDemonHunterRewardRatio() {
        ensureConfigLoaded();
        return config.getDouble("divine-guardian.demon-hunter.kill-rewards.mother-kill-demon-hunter-reward", 0.5);
    }
    
    // 母体新增设置
    public boolean isAdditionalMotherEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.demon-hunter.additional-mother.enabled", true);
    }
    
    public int getAdditionalMotherPlayerThreshold() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.demon-hunter.additional-mother.player-threshold", 8);
    }
    
    public boolean isAdditionalMotherOnlyInDemonHunterPhase() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.demon-hunter.additional-mother.only-in-demon-hunter-phase", true);
    }
    
    // 设置方法
    public void setDivineGuardianSystemEnabled(boolean enabled) {
        ensureConfigLoaded();
        config.set("divine-guardian.enabled", enabled);
        plugin.saveConfig();
    }
    
    public void setDivineGuardianTriggerHumanCount(int count) {
        ensureConfigLoaded();
        config.set("divine-guardian.trigger-human-count", count);
        plugin.saveConfig();
    }
    
    public void setDemonHunterPhaseStartTime(int seconds) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.phase-start-time", seconds);
        plugin.saveConfig();
    }
    
    public void setDemonHunterMaxUses(int uses) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.max-uses", uses);
        plugin.saveConfig();
    }
    
    public void setDemonHunterHolyRedemptionCooldown(int cooldown) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.holy-redemption-cooldown", cooldown);
        plugin.saveConfig();
    }
    
    public void setReaperWeaponHitsToKill(int hits) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.reaper-weapon.hits-to-kill", hits);
        plugin.saveConfig();
    }
    
    public void setReaperWeaponAttackCooldown(double cooldown) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.reaper-weapon.attack-cooldown", cooldown);
        plugin.saveConfig();
    }
    
    public void setDemonHunterKillRewardRatio(double ratio) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.kill-rewards.demon-hunter-kill-reward", ratio);
        plugin.saveConfig();
    }
    
    public void setMotherKillDemonHunterRewardRatio(double ratio) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.kill-rewards.mother-kill-demon-hunter-reward", ratio);
        plugin.saveConfig();
    }
    
    public void setAdditionalMotherPlayerThreshold(int threshold) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.additional-mother.player-threshold", threshold);
        plugin.saveConfig();
    }
    
    // 复活机制相关方法
    public boolean isRespawnEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.demon-hunter.respawn.enabled", true);
    }
    
    public int getRespawnTime() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.demon-hunter.respawn.respawn-time", 15);
    }
    
    // 血量设置相关方法
    public double getGhostNormalHealth() {
        ensureConfigLoaded();
        return config.getDouble("divine-guardian.demon-hunter.health.ghost-normal", 2.0);
    }
    
    public double getGhostMotherHealth() {
        ensureConfigLoaded();
        return config.getDouble("divine-guardian.demon-hunter.health.ghost-mother", 4.0);
    }
    
    public double getDemonHunterHealth() {
        ensureConfigLoaded();
        return config.getDouble("divine-guardian.demon-hunter.health.demon-hunter", 2.0);
    }
    
    public double getMotherAttackDamage() {
        ensureConfigLoaded();
        return config.getDouble("divine-guardian.demon-hunter.health.mother-attack-damage", 1.0);
    }
    
    public boolean isNoHealingInDemonHunterPhase() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.demon-hunter.health.no-healing", true);
    }
    
    public void setRespawnEnabled(boolean enabled) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.respawn.enabled", enabled);
        plugin.saveConfig();
    }
    
    public void setRespawnTime(int seconds) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.respawn.respawn-time", seconds);
        plugin.saveConfig();
    }
    
    public void setGhostNormalHealth(double health) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.health.ghost-normal", health);
        plugin.saveConfig();
    }
    
    public void setGhostMotherHealth(double health) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.health.ghost-mother", health);
        plugin.saveConfig();
    }
    
    public void setDemonHunterHealth(double health) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.health.demon-hunter", health);
        plugin.saveConfig();
    }
    
    public void setNoHealingInDemonHunterPhase(boolean noHealing) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.health.no-healing", noHealing);
        plugin.saveConfig();
    }
    
    public void setDemonHunterPhaseActivateTime(int seconds) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.phase-start-time", seconds);
        plugin.saveConfig();
    }
    
    public void setDemonHunterKillReward(double ratio) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.kill-rewards.demon-hunter-kill-reward", ratio);
        plugin.saveConfig();
    }
    
    public void setAdditionalMotherThreshold(int threshold) {
        ensureConfigLoaded();
        config.set("divine-guardian.demon-hunter.additional-mother.player-threshold", threshold);
        plugin.saveConfig();
    }
    
    /**
     * 检查Vault经济系统是否启用
     */
    public boolean isVaultEnabled() {
        ensureConfigLoaded();
        // 默认返回true，假设经济系统可用
        return true;
    }

    // ===== v2.3.3 母体进化水晶 =====
    public boolean isMotherEmeraldEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("mother-emerald.enabled", true);
    }
    public String getMotherEmeraldSpawnPhase() {
        ensureConfigLoaded();
        return config.getString("mother-emerald.spawn-phase", "holy-guardian");
    }
    public int getMotherEmeraldPlayerThreshold() {
        ensureConfigLoaded();
        return config.getInt("mother-emerald.player-threshold", 5);
    }
    public String getMotherEmeraldLocationMode() {
        ensureConfigLoaded();
        return config.getString("mother-emerald.location-mode", "center");
    }
    public boolean hasMotherEmeraldFixedLocation() {
        ensureConfigLoaded();
        return config.contains("mother-emerald.fixed.x") && config.contains("mother-emerald.fixed.z");
    }
    public org.bukkit.Location getMotherEmeraldFixedLocation(org.bukkit.World fallbackWorld) {
        ensureConfigLoaded();
        String worldName = config.getString("mother-emerald.fixed.world", "");
        org.bukkit.World world = fallbackWorld;
        if (worldName != null && !worldName.isEmpty()) {
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(worldName);
            if (w != null) world = w;
        }
        double x = config.getDouble("mother-emerald.fixed.x", 0);
        double y = config.getDouble("mother-emerald.fixed.y", 64);
        double z = config.getDouble("mother-emerald.fixed.z", 0);
        return new org.bukkit.Location(world, x, y, z);
    }
    public int getMotherEmeraldPickupDelayTicks() {
        ensureConfigLoaded();
        return config.getInt("mother-emerald.pickup-delay-ticks", 40);
    }
    public int getMotherEmeraldGlowIntervalTicks() {
        ensureConfigLoaded();
        return config.getInt("mother-emerald.glow-interval-ticks", 20);
    }
    
    // ===== v2.3.3 猎魔人死亡 =====
    public boolean isDemonHunterDeathConvertToNormalGhost() {
        ensureConfigLoaded();
        return config.getBoolean("demon-hunter.death.convert-to-normal-ghost", true);
    }
    public boolean isDemonHunterRespawnAsGhost() {
        ensureConfigLoaded();
        return config.getBoolean("demon-hunter.death.respawn-as-ghost", true);
    }
    
    // ===== 道具补全 =====
    /** 漂浮药水的漂浮等级（0 = 漂浮Ⅰ） */
    public int getLevitationAmplifier() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("items.levitation.amplifier", 1));
    }

    /** 漂浮药水冷却（秒）；0 = 无冷却 */
    public int getLevitationCooldown() {
        ensureConfigLoaded();
        return Math.max(0, config.getInt("items.levitation.cooldown", 0));
    }

    public int getLevitationDurationTicks() {
        ensureConfigLoaded();
        return Math.max(20, config.getInt("items.levitation.duration-ticks", 120));
    }
    public double getSpearRushDistance() {
        ensureConfigLoaded();
        return config.getDouble("items.spear-rush.distance", 5.5);
    }

    /**
     * 冲刺矛的**初速度**（格/刻）。
     *
     * <p>坑：老键 {@code distance} 是直接当速度用的——填 5.5 就是每秒 110 格，
     * 玩家会直接飞出去几十格。真实可用的区间是 0.8~1.6，默认 1.1（约 4~5 格）。</p>
     */
    public double getSpearRushVelocity() {
        ensureConfigLoaded();
        return Math.max(0.4, Math.min(2.0, config.getDouble("items.spear-rush.velocity", 1.1)));
    }
    public boolean isSpearRushConsumeOnUse() {
        ensureConfigLoaded();
        return config.getBoolean("items.spear-rush.consume-on-use", true);
    }
    public int getHolyRedemptionUses() {
        ensureConfigLoaded();
        return config.getInt("items.holy-redemption.uses", 1);
    }
    public boolean isHolyRedemptionTeleportOnUse() {
        ensureConfigLoaded();
        return config.getBoolean("items.holy-redemption.teleport-on-use", true);
    }
    public double getReaperDamagePerHit() {
        ensureConfigLoaded();
        return config.getDouble("items.reaper.damage-per-hit",
            config.getDouble("divine-guardian.demon-hunter.reaper-weapon.damage-per-hit", 1.0));
    }
    public int getReaperAttackCooldownSeconds() {
        ensureConfigLoaded();
        double v = config.getDouble("items.reaper.attack-cooldown",
            config.getDouble("divine-guardian.demon-hunter.reaper-weapon.attack-cooldown", 2.0));
        return (int) Math.ceil(v);
    }
    public int getStinkySteakEatTimeTicks() {
        ensureConfigLoaded();
        return config.getInt("items.stinky-steak.eat-time-ticks", 20);
    }
    public boolean isSecondChanceConsumeOnTrigger() {
        ensureConfigLoaded();
        return config.getBoolean("items.second-chance.consume-on-trigger", false);
    }
    public boolean isSecondChanceTeleportOnTrigger() {
        ensureConfigLoaded();
        return config.getBoolean("items.second-chance.teleport-on-trigger", true);
    }
    public int getMotherHitsToInfectHunter() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.demon-hunter.mother.hits-to-infect-hunter", 4);
    }
    public int getMotherHitsToBreakGuardian() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.demon-hunter.mother.hits-to-break-guardian", 3);
    }
    public boolean isKillFireworkEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("effects.kill-firework.enabled", true);
    }
    public boolean isSoundsEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("sounds.enabled", true);
    }
    public boolean isTitlesEnabled() {
        ensureConfigLoaded();
        return config.getBoolean("titles.enabled", true);
    }
    public boolean isRespawnRandomLocation() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.demon-hunter.respawn.random-location", true);
    }
    public boolean isRespawnBoundaryCheck() {
        ensureConfigLoaded();
        return config.getBoolean("divine-guardian.demon-hunter.respawn.boundary-check", true);
    }
    public int getRespawnBoundaryRadius() {
        ensureConfigLoaded();
        return config.getInt("divine-guardian.demon-hunter.respawn.boundary-radius", 50);
    }
}
