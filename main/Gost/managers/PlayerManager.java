package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class PlayerManager {
    
    public enum PlayerRole {
        HUMAN,          // 人类
        GHOST_MOTHER,   // 母体鬼
        GHOST_NORMAL    // 普通鬼
    }
    
    private final GHG plugin;
    
    // 玩家数据存储
    private final Map<UUID, PlayerRole> playerRoles = new HashMap<>();
    private final Map<UUID, PlayerState> savedStates = new HashMap<>();
    private final Map<UUID, Integer> infectionCounts = new HashMap<>();
    private final Map<UUID, Long> survivalTimes = new HashMap<>();
    private final Map<UUID, Long> roleStartTimes = new HashMap<>();
    private final Map<UUID, Long> ghostAccumulatedTimes = new HashMap<>(); // 累计鬼时间（毫秒）
    private final Set<UUID> convertedByRedemption = new HashSet<>(); // 被神之救赎转化的玩家

    /** 快照落盘文件名：服务器崩了/被强杀时，靠它把背包和坐标还给玩家 */
    private static final String STATE_FILE_NAME = "player-states.yml";
    /** 超过这个时间的残留快照不再自动套用（避免拿一周前的旧背包盖掉玩家现在的东西） */
    private static final long STATE_TTL_MILLIS = 7L * 24 * 3600 * 1000;

    public PlayerManager(GHG plugin) {
        this.plugin = plugin;
        loadPersistedStates();
    }
    
    // 玩家加入游戏
    // v2.3.2_B ④：顺序固化——必须「先保存原始背包、再清空/初始化」，防止后续改动误调次序
    public boolean joinGame(Player player) {
        UUID playerId = player.getUniqueId();

        if (playerRoles.containsKey(playerId)) {
            return false; // 已经在游戏中
        }

        // 【第一步】保存玩家原始状态（深拷贝快照，必须在任何清空/发放道具之前）
        savePlayerState(player);

        // 【第二步】初始化为人类（内部会清空背包并发放初始道具）
        setPlayerRole(playerId, PlayerRole.HUMAN);

        // 重置游戏内统计
        infectionCounts.put(playerId, 0);
        survivalTimes.put(playerId, 0L);
        roleStartTimes.put(playerId, System.currentTimeMillis());

        // 应用游戏状态
        applyGameState(player);

        plugin.getLogger().info("玩家 " + player.getName() + " 加入游戏");
        return true;
    }

    /**
     * GHG 玩家母体加入对局：与逃生者共用背包快照，但阵营登记为母体、不发道具。
     */
    public boolean joinHunterGameAsMother(Player player) {
        UUID playerId = player.getUniqueId();
        if (playerRoles.containsKey(playerId)) {
            return false;
        }

        savePlayerState(player);

        playerRoles.put(playerId, PlayerRole.GHOST_MOTHER);
        infectionCounts.put(playerId, 0);
        survivalTimes.put(playerId, 0L);
        roleStartTimes.put(playerId, System.currentTimeMillis());

        plugin.getTeamManager().setPlayerTeam(player, PlayerRole.GHOST_MOTHER);
        applyGameState(player);

        player.getInventory().clear();
        player.updateInventory();

        plugin.getLogger().info("玩家 " + player.getName() + " 加入 GHG 对局（玩家母体）");
        return true;
    }

    /**
     * GHG 加入对局：与经典局共用背包快照与阵营登记，但**不发放初始道具**。
     *
     * <p>GHG 的道具只能从地图刷新和道具箱里拿，所以这里只登记角色与状态、
     * 清空背包，不走 {@link #setPlayerRole}（那条路径会发肾上腺素）。</p>
     */
    public boolean joinHunterGame(Player player) {
        UUID playerId = player.getUniqueId();
        if (playerRoles.containsKey(playerId)) {
            return false;
        }

        savePlayerState(player);

        playerRoles.put(playerId, PlayerRole.HUMAN);
        infectionCounts.put(playerId, 0);
        survivalTimes.put(playerId, 0L);
        roleStartTimes.put(playerId, System.currentTimeMillis());

        plugin.getTeamManager().setPlayerTeam(player, PlayerRole.HUMAN);
        applyGameState(player);

        // 清空前兜底：万一快照因为别的原因没存下来，这里补一次，
        // 否则玩家离场时会拿到一副空背包（Gost 2.3.2_B 踩过这个坑）
        if (!savedStates.containsKey(playerId)) {
            savePlayerState(player);
        }
        player.getInventory().clear();
        player.updateInventory();

        plugin.getLogger().info("玩家 " + player.getName() + " 加入 GHG 对局（逃生者）");
        return true;
    }
    
    // 玩家离开游戏
    public boolean leaveGame(Player player) {
        return leaveGame(player, false);
    }
    /**
     * 玩家离开游戏（v2.3.2_B：silent=true 时不向玩家发消息，用于掉线场景）
     */
    public boolean leaveGame(Player player, boolean silent) {
        UUID playerId = player.getUniqueId();

        if (!playerRoles.containsKey(playerId)) {
            // 不在游戏中，但可能有遗留快照（v2.3.2_B 兜底）
            if (savedStates.containsKey(playerId)) {
                plugin.getLogger().warning("玩家 " + player.getName() + " 不在对局中但存在遗留背包快照，执行兜底还原");
                restorePlayerState(player, silent);
                return true;
            }
            return false;
        }

        // 恢复玩家原始状态
        restorePlayerState(player, silent);

        // 清理数据
        playerRoles.remove(playerId);
        savedStates.remove(playerId);
        infectionCounts.remove(playerId);
        survivalTimes.remove(playerId);
        roleStartTimes.remove(playerId);

        // 从队伍中移除
        plugin.getTeamManager().removeFromAllTeams(player);

        if (!silent) {
            plugin.getLanguageManager().sendMessage(player, "game.left-game");
        }
        plugin.getLogger().info("玩家 " + player.getName() + " 离开游戏" + (silent ? "（掉线静默）" : ""));
        return true;
    }
    
    /** 角色 → 语言 key */
    private String roleLangKey(PlayerRole role) {
        if (role == PlayerRole.GHOST_MOTHER) return "role.mother-ghost";
        if (role == PlayerRole.GHOST_NORMAL) return "role.ghost";
        return "role.human";
    }
    
    // 设置玩家角色
    public void setPlayerRole(UUID playerId, PlayerRole role) {
        Player player = Bukkit.getPlayer(playerId);
        PlayerRole oldRole = playerRoles.get(playerId);
        
        // 更新角色开始时间
        if (oldRole != null) {
            updateSurvivalTime(playerId);
            
            // 如果从鬼转换为人类，记录累计鬼时间
            if ((oldRole == PlayerRole.GHOST_MOTHER || oldRole == PlayerRole.GHOST_NORMAL) 
                && role == PlayerRole.HUMAN) {
                recordGhostAccumulatedTime(playerId);
                plugin.getLogger().info("玩家 " + playerId + " 从鬼转换为人类，记录累计鬼时间");
            }
        }
        roleStartTimes.put(playerId, System.currentTimeMillis());
        
        // 更新角色
        playerRoles.put(playerId, role);
        
        if (player != null && player.isOnline()) {
            // 更新队伍
            plugin.getTeamManager().setPlayerTeam(player, role);
            
            // 更新背包
            updatePlayerInventory(player, role);
            
            // 应用角色特定效果
            applyRoleEffects(player, role);
            
            // 发送角色变更消息
            ChatColor color = plugin.getTeamManager().getTeamColor(role);
            player.sendMessage(color + plugin.getLanguageManager().getMessage(player, "role.changed",
                plugin.getLanguageManager().getMessage(player, roleLangKey(role))));
            
            // 阵容切换音效
            if (oldRole != null && oldRole != role) {
                // v2.3.2：阵容切换时饱食度回满
                player.setFoodLevel(20);
                player.setSaturation(20);
                
                if (role == PlayerRole.GHOST_MOTHER || role == PlayerRole.GHOST_NORMAL) {
                    // 变为鬼 - 阴森音效
                    player.playSound(player.getLocation(), Sound.ENTITY_VEX_AMBIENT, 0.8f, 0.7f);
                } else if (role == PlayerRole.HUMAN) {
                    // 变回人类 - 正面音效
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
                }
            }
            
        }
    }
    
    // 获取玩家角色
    public PlayerRole getPlayerRole(UUID playerId) {
        PlayerRole role = playerRoles.get(playerId);
        if (role == null) {
            plugin.getLogger().warning("玩家 " + playerId + " 的角色为null，可能未加入游戏");
        }
        return role;
    }
    
    // 检查是否是鬼
    public boolean isGhost(UUID playerId) {
        PlayerRole role = getPlayerRole(playerId);
        return role != null && (role == PlayerRole.GHOST_MOTHER || role == PlayerRole.GHOST_NORMAL);
    }
    
    // 将鬼玩家转换为人类（用于救赎者系统）
    public boolean convertGhostToHuman(UUID playerId) {
        if (!isGhost(playerId)) {
            return false; // 不是鬼玩家，无法转换
        }
        
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return false; // 玩家不在线
        }
        
        // 设置玩家角色为人类
        setPlayerRole(playerId, PlayerRole.HUMAN);
        
        // 标记为被神之救赎转化
        convertedByRedemption.add(playerId);
        
        // 应用人类效果
        applyRoleEffects(player, PlayerRole.HUMAN);
        
        // 更新存活时间
        updateSurvivalTime(playerId);
        
        plugin.getLogger().info("鬼玩家 " + player.getName() + " 被转换为人类");
        return true;
    }
    
    // 检查是否是母体鬼
    public boolean isGhostMother(UUID playerId) {
        PlayerRole role = getPlayerRole(playerId);
        return role != null && role == PlayerRole.GHOST_MOTHER;
    }
    
    // 检查是否是人类
    public boolean isHuman(UUID playerId) {
        PlayerRole role = getPlayerRole(playerId);
        return role != null && role == PlayerRole.HUMAN;
    }
    
    // 检查是否是母体鬼
    public boolean isMotherGhost(UUID playerId) {
        PlayerRole role = getPlayerRole(playerId);
        return role != null && role == PlayerRole.GHOST_MOTHER;
    }
    
    // 感染玩家
    
    // 检查神圣守护状态
    
    // 显示感染效果
    
    // 更新游戏统计
    
    // 更新存活时间
    public void updateSurvivalTime(UUID playerId) {
        Long startTime = roleStartTimes.get(playerId);
        if (startTime != null) {
            long currentTime = System.currentTimeMillis();
            long elapsed = currentTime - startTime;
            
            Long currentSurvival = survivalTimes.getOrDefault(playerId, 0L);
            survivalTimes.put(playerId, currentSurvival + elapsed);
            
            PlayerRole role = getPlayerRole(playerId);
            plugin.getLogger().info("更新玩家 " + playerId + " 存活时间，角色: " + role + 
                ", 本次存活: " + (elapsed / 1000) + "秒, 累计: " + ((currentSurvival + elapsed) / 1000) + "秒");
        } else {
            plugin.getLogger().warning("更新存活时间失败：玩家 " + playerId + " 没有角色开始时间");
        }
    }
    
    // 获取存活时间（秒）
    public long getSurvivalTime(UUID playerId) {
        updateSurvivalTime(playerId);
        long survivalTime = survivalTimes.getOrDefault(playerId, 0L) / 1000;
        PlayerRole role = getPlayerRole(playerId);
        plugin.getLogger().info("获取玩家 " + playerId + " 存活时间，角色: " + role + ", 存活: " + survivalTime + "秒");
        return survivalTime;
    }
    
    // 获取鬼的存活时间（秒）- 从成为鬼开始计算
    public long getGhostTime(UUID playerId) {
        PlayerRole role = getPlayerRole(playerId);
        
        // 如果不是鬼，返回0
        if (role != PlayerRole.GHOST_MOTHER && role != PlayerRole.GHOST_NORMAL) {
            return 0;
        }
        
        // 获取角色开始时间
        Long startTime = roleStartTimes.get(playerId);
        if (startTime == null) {
            return 0;
        }
        
        // 计算从成为鬼开始到现在的存活时间
        long currentTime = System.currentTimeMillis();
        long ghostTime = currentTime - startTime;
        
        plugin.getLogger().info("获取玩家 " + playerId + " 鬼存活时间，角色: " + role + ", 鬼存活: " + (ghostTime / 1000) + "秒");
        return ghostTime / 1000; // 转换为秒
    }
    
    // 记录累计鬼时间（当鬼转换为人类时调用）
    private void recordGhostAccumulatedTime(UUID playerId) {
        Long startTime = roleStartTimes.get(playerId);
        if (startTime == null) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long ghostTime = currentTime - startTime;
        
        // 累加到累计鬼时间
        long accumulatedTime = ghostAccumulatedTimes.getOrDefault(playerId, 0L);
        accumulatedTime += ghostTime;
        ghostAccumulatedTimes.put(playerId, accumulatedTime);
        
        plugin.getLogger().info("记录玩家 " + playerId + " 累计鬼时间: " + (accumulatedTime / 1000) + "秒");
    }
    
    // 获取累计鬼时间（秒）- 用于奖金计算
    public long getGhostAccumulatedTime(UUID playerId) {
        return ghostAccumulatedTimes.getOrDefault(playerId, 0L) / 1000; // 转换为秒
    }
    
    // 获取感染次数
    public int getInfectionCount(UUID playerId) {
        return infectionCounts.getOrDefault(playerId, 0);
    }
    
    // 应用游戏状态
    public void applyGameState(Player player) {
        plugin.getLogger().info("应用游戏状态给玩家: " + player.getName());
        
        // 检查玩家是否处于创造模式，如果是则强制切换并提示
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            GameMode originalMode = player.getGameMode();
            player.setGameMode(GameMode.SURVIVAL);
            
            // 发送提示消息
            plugin.getLanguageManager().sendMessage(player, "game.creative-switched");
            plugin.getLanguageManager().sendMessage(player, "game.creative-required");
            
            plugin.getLogger().info("强制切换玩家 " + player.getName() + " 从 " + originalMode + " 到 SURVIVAL 模式");
        } else {
            // 设置游戏模式为生存模式
            player.setGameMode(GameMode.SURVIVAL);
        }
        
        // 设置生命值（使用配置文件中的值）
        double maxHealth = plugin.getConfigManager().getMaxHealth();
        player.getAttribute(ApiCompat.maxHealth()).setBaseValue(maxHealth);
        player.setHealth(maxHealth);
        plugin.getLogger().info("设置玩家 " + player.getName() + " 生命值为" + maxHealth + "点");
        
        // 清除所有药水效果
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        
        // 设置饱食度：饱食度满、但**饱和度只给一点**
        // ——饱和度会先被消耗，给满等于送 400 秒缓冲，饥饿效果看起来就「完全不掉」
        player.setFoodLevel(20);
        player.setSaturation((float) plugin.getConfigManager().getHunterHungerStartSaturation());
        
        // 传送到游戏区域
        if (plugin.getConfigManager().isAutoTeleportEnabled()) {
            plugin.getAreaManager().teleportPlayerToArea(player, plugin.getAreaManager().getSelectedArea());
        }
    }
    
    // 应用角色特定效果
    public void applyRoleEffects(Player player, PlayerRole role) {
        plugin.getLogger().info("应用角色效果给玩家: " + player.getName() + ", 角色: " + role);
        
        // 无论玩家是否是管理员或创造模式，都强制应用游戏效果
        // 这是为了确保游戏平衡性，所有玩家参与游戏时应该受到相同的影响
        
        boolean isAdmin = player.hasPermission("ghg.admin");
        boolean wasCreative = player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
        
        switch (role) {
            case GHOST_MOTHER:
                String playerType = "";
                if (isAdmin) playerType = "管理员";
                if (wasCreative) playerType += (playerType.isEmpty() ? "" : "且") + "创造模式玩家";
                if (playerType.isEmpty()) playerType = "玩家";
                
                plugin.getLogger().info("应用母体鬼效果给" + playerType + ": " + player.getName() + " (强制应用效果)");
                
                // 首先清除可能存在的免疫效果
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.removePotionEffect(ApiCompat.slowness());
                player.removePotionEffect(ApiCompat.miningFatigue());
                
                // 母体鬼失明效果 - 强制应用
                int blindnessDuration = plugin.getConfigManager().getMotherGhostBlindnessDuration() * 20;
                PotionEffect blindnessEffect = new PotionEffect(
                    PotionEffectType.BLINDNESS,
                    blindnessDuration,
                    0,
                    true,
                    true
                );
                // 使用 forceAddPotionEffect 确保效果被应用
                player.addPotionEffect(blindnessEffect, true); // true 表示强制覆盖现有效果
                plugin.getLogger().info("强制应用失明效果给" + playerType + ": " + player.getName() + ", 持续时间: " + blindnessDuration + " ticks");
                
                // 母体鬼20秒无法移动效果（神鬼药水效果） - 强制应用
                int immobilizeDuration = plugin.getConfigManager().getGhostImmobilizeDuration() * 20;
                PotionEffect slowEffect = new PotionEffect(
                    ApiCompat.slowness(),
                    immobilizeDuration,
                    255, // 最大等级，几乎无法移动
                    true,
                    true
                );
                player.addPotionEffect(slowEffect, true); // 强制覆盖
                plugin.getLogger().info("强制应用减速效果给" + playerType + ": " + player.getName() + ", 持续时间: " + immobilizeDuration + " ticks");
                
                // 同时添加挖掘疲劳效果，确保完全无法移动 - 强制应用
                PotionEffect miningEffect = new PotionEffect(
                    ApiCompat.miningFatigue(),
                    immobilizeDuration,
                    255,
                    true,
                    true
                );
                player.addPotionEffect(miningEffect, true); // 强制覆盖
                plugin.getLogger().info("强制应用挖掘疲劳效果给" + playerType + ": " + player.getName() + ", 持续时间: " + immobilizeDuration + " ticks");
                
                // 发送提示消息
                if (isAdmin || wasCreative) {
                    String adminRole = isAdmin && wasCreative
                        ? plugin.getLanguageManager().getMessage(player, "game.fairness.role-both")
                        : isAdmin
                        ? plugin.getLanguageManager().getMessage(player, "game.fairness.role-admin")
                        : plugin.getLanguageManager().getMessage(player, "game.fairness.role-creative");
                    plugin.getLanguageManager().sendMessage(player, "game.mother-immobilize-notice", adminRole);
                    plugin.getLanguageManager().sendMessage(player, "game.mother-immobilize-duration",
                        plugin.getConfigManager().getGhostImmobilizeDuration());
                }
                
                plugin.getLanguageManager().sendMessage(player, "role.ghost-disabled-prep");
                break;
        }
    }
    
    // 更新玩家背包
    public void updatePlayerInventory(Player player, PlayerRole role) {
        plugin.getLogger().info("更新玩家背包: " + player.getName() + ", 角色: " + role);
        PlayerInventory inventory = player.getInventory();

        // v2.3.2_B ③：清空前兜底——若该玩家在游戏中却没有原始快照，立即补保存一次
        UUID playerId = player.getUniqueId();
        if (playerRoles.containsKey(playerId) && !savedStates.containsKey(playerId)) {
            plugin.getLogger().warning("玩家 " + player.getName() + " 在游戏中但无原始背包快照，清空前立即补保存");
            savePlayerState(player);
        }

        // 清空背包
        inventory.clear();
        plugin.getLogger().info("清空玩家 " + player.getName() + " 的背包");
        
        // 根据角色给予初始物品
        switch (role) {
            case HUMAN:
                plugin.getLogger().info("给予人类 " + player.getName() + " 肾上腺素");
                // 人类获得肾上腺素
                inventory.addItem(plugin.getItemManager().getAdrenaline());
                break;
            case GHOST_MOTHER:
                plugin.getLogger().info("给予母体鬼 " + player.getName() + " 狂暴药水");
                // 母体鬼获得狂暴药水
                inventory.addItem(plugin.getItemManager().getFrenzyPotion());
                break;
            case GHOST_NORMAL:
                plugin.getLogger().info("给予普通鬼 " + player.getName() + " 狂暴药水");
                // 普通鬼获得狂暴药水
                inventory.addItem(plugin.getItemManager().getFrenzyPotion());
                break;
        }
        
        player.updateInventory();
        plugin.getLogger().info("玩家 " + player.getName() + " 背包更新完成");
    }
    
    // 保存玩家原始状态
    public void savePlayerState(Player player) {
        UUID playerId = player.getUniqueId();
        // v2.3.2_B ②：防重复覆盖——已有原始快照则跳过，杜绝用游戏背包覆盖原始背包
        if (savedStates.containsKey(playerId)) {
            plugin.getLogger().warning("玩家 " + player.getName() + " 已存在原始背包快照，跳过重复保存（防止污染）");
            return;
        }
        PlayerState state = new PlayerState(player);
        savedStates.put(playerId, state);
        // 落盘：服务器崩溃时内存快照会一起没掉，玩家再进来就是「旁观 + 空背包」
        persistState(playerId, state);
        // v2.3.2_B ⑤：诊断日志
        int itemCount = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() != org.bukkit.Material.AIR) itemCount++;
        }
        plugin.getLogger().info("已保存玩家 " + player.getName() + " 的原始背包（" + itemCount + " 个物品）");
    }

    // 恢复玩家原始状态
    public void restorePlayerState(Player player) {
        restorePlayerState(player, false);
    }

    /**
     * 恢复玩家原始状态（v2.3.2_B：silent=true 时不向玩家发消息）
     */
    public void restorePlayerState(Player player, boolean silent) {
        PlayerState state = savedStates.get(player.getUniqueId());
        if (state != null) {
            int itemCount = state.getInventoryItemCount();
            state.restore(player, plugin);
            savedStates.remove(player.getUniqueId());
            forgetPersistedState(player.getUniqueId());
            // v2.3.2_B ⑤：诊断日志
            plugin.getLogger().info("已返还玩家 " + player.getName() + " 的原始背包（" + itemCount + " 个物品）" + (silent ? " [silent]" : ""));
        } else {
            plugin.getLogger().warning("玩家 " + player.getName() + " 没有原始背包快照可返还");
        }
        
        // 清除所有药水效果
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        
        // 移除黑暗效果和疾跑修复
        
        // 恢复最大血量
        player.getAttribute(ApiCompat.maxHealth()).setBaseValue(20.0);
        player.setHealth(20.0);
        
        // 从队伍中移除
        plugin.getTeamManager().removeFromAllTeams(player);
    }
    
    // ================================================================ 快照持久化

    private java.io.File stateFile() {
        return new java.io.File(plugin.getDataFolder(), STATE_FILE_NAME);
    }

    private org.bukkit.configuration.file.YamlConfiguration stateConfig() {
        java.io.File file = stateFile();
        if (!file.exists()) return new org.bukkit.configuration.file.YamlConfiguration();
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
    }

    /** 启动时把盘上的残留快照读回内存；过期的直接丢掉 */
    private void loadPersistedStates() {
        try {
            java.io.File file = stateFile();
            if (!file.exists()) return;
            org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            long now = System.currentTimeMillis();
            int loaded = 0;
            int expired = 0;
            for (String key : new ArrayList<>(cfg.getKeys(false))) {
                PlayerState state = PlayerState.read(cfg, key);
                if (state == null) {
                    cfg.set(key, null);
                    continue;
                }
                if (now - state.getSavedAt() > STATE_TTL_MILLIS) {
                    cfg.set(key, null);
                    expired++;
                    continue;
                }
                try {
                    savedStates.put(UUID.fromString(key), state);
                    loaded++;
                } catch (IllegalArgumentException ignored) {
                }
            }
            cfg.save(file);
            if (loaded > 0 || expired > 0) {
                plugin.getLogger().warning("发现 " + loaded + " 份上次未归还的背包快照"
                    + (expired > 0 ? "（另有 " + expired + " 份过期已丢弃）" : "")
                    + "，玩家上线时会自动恢复");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("读取 " + STATE_FILE_NAME + " 失败: " + t.getMessage());
        }
    }

    private void persistState(UUID playerId, PlayerState state) {
        try {
            org.bukkit.configuration.file.YamlConfiguration cfg = stateConfig();
            state.write(cfg, playerId.toString());
            cfg.save(stateFile());
        } catch (Throwable t) {
            plugin.getLogger().warning("保存 " + STATE_FILE_NAME + " 失败: " + t.getMessage());
        }
    }

    private void forgetPersistedState(UUID playerId) {
        try {
            java.io.File file = stateFile();
            if (!file.exists()) return;
            org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            if (!cfg.contains(playerId.toString())) return;
            cfg.set(playerId.toString(), null);
            cfg.save(file);
        } catch (Throwable t) {
            plugin.getLogger().warning("清理 " + STATE_FILE_NAME + " 失败: " + t.getMessage());
        }
    }

    /** 还没归还的快照数量（自检用：不为 0 说明上次没正常收局） */
    public int countPendingStates() {
        return savedStates.size();
    }

    /** 内存里有没有这个玩家的快照（进服兜底用） */
    public boolean hasSavedState(UUID playerId) {
        return playerId != null && savedStates.containsKey(playerId);
    }

    /**
     * 旁观兜底：不是对局参与者却还挂着旁观模式的人，切回生存；人在猎场里就送出猎场。
     *
     * <p>正常流程里 {@code eliminate()} 把人变成旁观，对局结束或退服时会还原。
     * 但只要有一次没还原成（服务器崩了、快照丢了），玩家就会永远卡在旁观 + 卡在猎场里。</p>
     */
    public void escapeSpectator(Player player) {
        if (player == null || !player.isOnline()) return;
        if (player.getGameMode() != GameMode.SPECTATOR) return;

        Location location = player.getLocation();
        boolean insideArea = location != null && plugin.getAreaManager().isInsideAnyArea(location);
        // 猎场外的旁观很可能是管理员自己在看局，别把人的模式抢掉；
        // 猎场里的旁观一定是上次没还原干净，必须处理
        if (!insideArea && player.hasPermission("ghg.admin")) return;

        try {
            player.setGameMode(GameMode.SURVIVAL);
            plugin.getLanguageManager().sendMessage(player, "player.spectator-restored");
        } catch (Throwable ignored) {
        }
        if (!insideArea) return;
        Location outside = spotOutsideArea(location);
        if (outside == null) return;
        try {
            player.teleport(outside);
            plugin.getLanguageManager().sendMessage(player, "player.ejected-from-arena");
        } catch (Throwable ignored) {
        }
    }

    /** 把猎场内的坐标往最近的一条边界外推 4 格，落到那一列的地表上 */
    private Location spotOutsideArea(Location inside) {
        for (AreaManager.GameArea area : plugin.getAreaManager().getAllAreas()) {
            if (area == null || !area.contains(inside)) continue;
            Location min = area.getMinPoint();
            Location max = area.getMaxPoint();
            if (min == null || max == null) continue;

            double toMinX = inside.getX() - min.getX();
            double toMaxX = max.getX() - inside.getX();
            double toMinZ = inside.getZ() - min.getZ();
            double toMaxZ = max.getZ() - inside.getZ();
            double best = Math.min(Math.min(toMinX, toMaxX), Math.min(toMinZ, toMaxZ));

            double x = inside.getX();
            double z = inside.getZ();
            if (best == toMinX) x = min.getX() - 4.5;
            else if (best == toMaxX) x = max.getX() + 4.5;
            else if (best == toMinZ) z = min.getZ() - 4.5;
            else z = max.getZ() + 4.5;

            org.bukkit.World world = inside.getWorld();
            if (world == null) return null;
            int y = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            return new Location(world, x, y, z, inside.getYaw(), inside.getPitch());
        }
        return null;
    }

    // 获取所有游戏中的玩家
    public List<UUID> getAllPlayers() {
        return new ArrayList<>(playerRoles.keySet());
    }
    
    // 获取所有人类玩家
    public List<UUID> getHumanPlayers() {
        List<UUID> humans = new ArrayList<>();
        for (Map.Entry<UUID, PlayerRole> entry : playerRoles.entrySet()) {
            if (entry.getValue() == PlayerRole.HUMAN) {
                humans.add(entry.getKey());
            }
        }
        return humans;
    }
    
    // 获取所有鬼玩家
    public List<UUID> getGhostPlayers() {
        List<UUID> ghosts = new ArrayList<>();
        for (Map.Entry<UUID, PlayerRole> entry : playerRoles.entrySet()) {
            if (entry.getValue() == PlayerRole.GHOST_MOTHER || entry.getValue() == PlayerRole.GHOST_NORMAL) {
                ghosts.add(entry.getKey());
            }
        }
        return ghosts;
    }
    
    // 检查玩家是否被神之救赎转化
    public boolean isConvertedByRedemption(UUID playerId) {
        return convertedByRedemption.contains(playerId);
    }
    
    // 清理所有玩家数据
    public void cleanup() {
        // 首先恢复所有在线玩家的状态
        for (UUID playerId : new ArrayList<>(playerRoles.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                restorePlayerState(player);
                plugin.getLanguageManager().sendMessage(player, "game.state-restored");
            }
        }
        
        // 清理所有数据
        playerRoles.clear();
        savedStates.clear();
        infectionCounts.clear();
        survivalTimes.clear();
        roleStartTimes.clear();
        ghostAccumulatedTimes.clear();
        convertedByRedemption.clear();
        
        // 清理玩家数据完成
        plugin.getLogger().info("清理玩家数据完成");
    }
    
    // 玩家状态存储类（v2.3.2_B：快照深拷贝，切断与游戏内物品的引用共享）
    private static class PlayerState {
        private final GameMode gameMode;
        private final Location location;
        private final ItemStack[] inventory;
        private final ItemStack[] armor;
        private final double health;
        private final int foodLevel;
        private final float saturation;
        private final int level;
        private final float exp;
        private final boolean allowFlight;
        private final boolean flying;
        /** 快照落盘时间：用来判断残留快照是否过期 */
        private final long savedAt;

        public PlayerState(Player player) {
            this.savedAt = System.currentTimeMillis();
            this.gameMode = player.getGameMode();
            // v2.3.2_B ①：Location 也做克隆，防止后续传送污染快照
            this.location = player.getLocation().clone();
            // v2.3.2_B ①：快照深拷贝——逐个 ItemStack.clone()，切断引用共享
            this.inventory = cloneItems(player.getInventory().getContents());
            this.armor = cloneItems(player.getInventory().getArmorContents());
            // 安全地保存生命值，考虑当前服务器的限制
            double currentHealth = player.getHealth();
            double maxHealth = player.getMaxHealth();
            this.health = Math.min(currentHealth, maxHealth);

            this.foodLevel = player.getFoodLevel();
            this.saturation = player.getSaturation();
            this.level = player.getLevel();
            this.exp = player.getExp();
            this.allowFlight = player.getAllowFlight();
            this.flying = player.isFlying();
        }

        /** 从磁盘读回时用：字段直接给定 */
        private PlayerState(GameMode gameMode, Location location, ItemStack[] inventory, ItemStack[] armor,
                            double health, int foodLevel, float saturation, int level, float exp,
                            boolean allowFlight, boolean flying, long savedAt) {
            this.gameMode = gameMode == null ? GameMode.SURVIVAL : gameMode;
            this.location = location;
            this.inventory = inventory;
            this.armor = armor;
            this.health = health;
            this.foodLevel = foodLevel;
            this.saturation = saturation;
            this.level = level;
            this.exp = exp;
            this.allowFlight = allowFlight;
            this.flying = flying;
            this.savedAt = savedAt;
        }

        public long getSavedAt() {
            return savedAt;
        }

        /** 写进 player-states.yml（背包用 BukkitObjectOutputStream + Base64，NBT 不丢） */
        public void write(org.bukkit.configuration.file.YamlConfiguration cfg, String path) {
            cfg.set(path + ".saved-at", savedAt);
            cfg.set(path + ".game-mode", gameMode.name());
            if (location != null && location.getWorld() != null) {
                cfg.set(path + ".world", location.getWorld().getName());
                cfg.set(path + ".x", location.getX());
                cfg.set(path + ".y", location.getY());
                cfg.set(path + ".z", location.getZ());
                cfg.set(path + ".yaw", location.getYaw());
                cfg.set(path + ".pitch", location.getPitch());
            }
            cfg.set(path + ".inventory", encodeItems(inventory));
            cfg.set(path + ".armor", encodeItems(armor));
            cfg.set(path + ".health", health);
            cfg.set(path + ".food", foodLevel);
            cfg.set(path + ".saturation", (double) saturation);
            cfg.set(path + ".level", level);
            cfg.set(path + ".exp", (double) exp);
            cfg.set(path + ".allow-flight", allowFlight);
            cfg.set(path + ".flying", flying);
        }

        /** 从 player-states.yml 读一条；数据不完整返回 null（调用方会删掉这条） */
        public static PlayerState read(org.bukkit.configuration.file.YamlConfiguration cfg, String path) {
            try {
                if (!cfg.isConfigurationSection(path)) return null;
                String worldName = cfg.getString(path + ".world");
                org.bukkit.World world = worldName == null ? null
                    : org.bukkit.Bukkit.getWorld(worldName);
                Location loc = null;
                if (world != null && cfg.contains(path + ".x")) {
                    loc = new Location(world,
                        cfg.getDouble(path + ".x"), cfg.getDouble(path + ".y"), cfg.getDouble(path + ".z"),
                        (float) cfg.getDouble(path + ".yaw"), (float) cfg.getDouble(path + ".pitch"));
                }
                if (loc == null) return null;

                GameMode mode;
                try {
                    mode = GameMode.valueOf(cfg.getString(path + ".game-mode", "SURVIVAL"));
                } catch (IllegalArgumentException e) {
                    mode = GameMode.SURVIVAL;
                }
                return new PlayerState(mode, loc,
                    decodeItems(cfg.getString(path + ".inventory"), 36),
                    decodeItems(cfg.getString(path + ".armor"), 4),
                    cfg.getDouble(path + ".health", 20.0),
                    cfg.getInt(path + ".food", 20),
                    (float) cfg.getDouble(path + ".saturation", 5.0),
                    cfg.getInt(path + ".level", 0),
                    (float) cfg.getDouble(path + ".exp", 0.0),
                    cfg.getBoolean(path + ".allow-flight", false),
                    cfg.getBoolean(path + ".flying", false),
                    cfg.getLong(path + ".saved-at", 0L));
            } catch (Throwable t) {
                return null;
            }
        }

        /** 背包/护甲序列化成 Base64 字符串 */
        private static String encodeItems(ItemStack[] items) {
            if (items == null) return null;
            try (java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
                 org.bukkit.util.io.BukkitObjectOutputStream out =
                     new org.bukkit.util.io.BukkitObjectOutputStream(raw)) {
                out.writeInt(items.length);
                for (ItemStack item : items) out.writeObject(item);
                out.flush();
                return java.util.Base64.getEncoder().encodeToString(raw.toByteArray());
            } catch (Throwable t) {
                return null;
            }
        }

        /** 反序列化；读不出来就返回一个空数组（宁可丢物品也不要报错卡住还原） */
        private static ItemStack[] decodeItems(String data, int fallbackSize) {
            if (data == null || data.isEmpty()) return new ItemStack[fallbackSize];
            try (java.io.ByteArrayInputStream raw =
                     new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(data));
                 org.bukkit.util.io.BukkitObjectInputStream in =
                     new org.bukkit.util.io.BukkitObjectInputStream(raw)) {
                int size = in.readInt();
                ItemStack[] items = new ItemStack[Math.max(0, size)];
                for (int i = 0; i < items.length; i++) {
                    items[i] = (ItemStack) in.readObject();
                }
                return items;
            } catch (Throwable t) {
                return new ItemStack[fallbackSize];
            }
        }

        /** 深拷贝物品数组 */
        private static ItemStack[] cloneItems(ItemStack[] source) {
            if (source == null) return new ItemStack[0];
            ItemStack[] copy = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) {
                copy[i] = (source[i] == null) ? null : source[i].clone();
            }
            return copy;
        }

        /** 统计快照中非空物品数量（用于诊断日志） */
        public int getInventoryItemCount() {
            int count = 0;
            for (ItemStack item : inventory) {
                if (item != null && item.getType() != org.bukkit.Material.AIR) count++;
            }
            return count;
        }

        public void restore(Player player, GHG plugin) {
            player.setGameMode(gameMode);
            // 传送到克隆后的坐标副本，避免坐标对象被后续操作污染
            player.teleport(location.clone());
            // v2.3.2_B ①：返还时再次克隆，确保写入背包的是独立副本
            player.getInventory().setContents(cloneItems(inventory));
            player.getInventory().setArmorContents(cloneItems(armor));

            // 安全地设置生命值
            try {
                double maxHealth = player.getMaxHealth();
                double safeHealth = Math.min(health, maxHealth);
                player.setHealth(safeHealth);
            } catch (IllegalArgumentException e) {
                // 如果设置失败，使用默认值
                plugin.getLogger().warning("无法设置玩家 " + player.getName() + " 的生命值: " + e.getMessage());
                player.setHealth(player.getMaxHealth());
            }

            player.setFoodLevel(foodLevel);
            player.setSaturation(saturation);
            player.setLevel(level);
            player.setExp(exp);
            player.setAllowFlight(allowFlight);
            player.setFlying(flying);
            // 一次性换掉整副背包（而且经常伴随旁观→生存），不刷客户端会看到旧物品
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
            }
        }
    }
    
    // 检查玩家是否已达到最大道具种类数量
    public boolean hasReachedMaxItemTypes(Player player) {
        return getPlayerItemTypesCount(player) >= plugin.getConfigManager().getMaxItemTypesPerPlayer();
    }

    // 获取玩家当前拥有的道具种类数量
    public int getPlayerItemTypesCount(Player player) {
        Set<String> itemTypes = new HashSet<>();
        for (ItemStack item : player.getInventory().getContents()) {
            String id = ItemIds.idOf(item);
            if (ItemIds.STINKY_STEAK.equals(id) || ItemIds.TELEPORT_PEARL.equals(id)
                || ItemIds.SOUL_DETECTOR.equals(id) || ItemIds.SECOND_CHANCE.equals(id)) {
                itemTypes.add(id);
            }
        }
        return itemTypes.size();
    }
}