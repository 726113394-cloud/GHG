package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class ItemManager {
    
    private final GHG plugin;
    private final Map<String, ItemStack> customItems = new HashMap<>();
    
    public ItemManager(GHG plugin) {
        this.plugin = plugin;
        initializeCustomItems();
    }
    
    private void initializeCustomItems() {
        createAdrenaline();
        createFrenzyPotion();
        createIceBall();
        createIceBallCommon();
        createSoulControl();
        createSecondChance();
        createLevitationPotion();
        createMedkit();
        createRation();
        createRitualKnife();
        createRedemptionCharm();
        createFenceKit();
        createStunPotion();
        createShadowCloak();
        createMenuClock();
        // createBlinkPearl(); // 已废弃，使用传送珍珠
        
        // 根据服务器版本决定是否创建冲刺矛
        if (plugin.isSpearRushEnabled()) {
            createSpearRush();
        }
        
    }
    
    /** 已注册的自定义道具数量（启动报告用） */
    public int getCustomItemCount() {
        return customItems.size();
    }
    
    // 创建肾上腺素
    private void createAdrenaline() {
        ItemStack adrenaline = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) adrenaline.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + name(ItemIds.ADRENALINE));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键使用：短时间速度提升（GHG 弱化版）",
                ChatColor.GRAY + "加速时长: " + plugin.getConfigManager().getHunterAdrenalineSeconds() + "秒",
                ChatColor.GRAY + "速度等级: " + plugin.getConfigManager().getHunterAdrenalineSpeedLevel(),
                ChatColor.GRAY + "之后 " + plugin.getConfigManager().getHunterAdrenalineAfterSlowSeconds()
                    + " 秒缓慢（后遗症）",
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));
            
            // 设置药水颜色为绿色
            meta.setColor(Color.fromRGB(0, 255, 0));
            
            adrenaline.setItemMeta(meta);
        }
        
        customItems.put(ItemIds.ADRENALINE, ItemIds.tag(adrenaline, ItemIds.ADRENALINE));
    }
    
    // 创建狂暴药水
    private void createFrenzyPotion() {
        ItemStack frenzy = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) frenzy.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + name(ItemIds.FRENZY));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键使用获得速度效果",
                ChatColor.GRAY + "持续时间: " + plugin.getConfigManager().getFrenzyDuration() + "秒",
                ChatColor.GRAY + "速度等级: " + plugin.getConfigManager().getFrenzySpeedLevel(),
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));
            
            // 设置药水颜色为红色
            meta.setColor(Color.fromRGB(255, 0, 0));
            
            frenzy.setItemMeta(meta);
        }
        
        customItems.put(ItemIds.FRENZY, ItemIds.tag(frenzy, ItemIds.FRENZY));
    }
    
    // 创建凝冰球
    private void createIceBall() {
        ItemStack iceBall = new ItemStack(Material.SNOWBALL);
        ItemMeta meta = iceBall.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + name(ItemIds.ICE_BALL));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键投掷对鬼造成减速效果",
                ChatColor.GRAY + "减速时间: " + plugin.getConfigManager().getIceBallSlowDuration() + "秒",
                ChatColor.GRAY + "减速等级: " + plugin.getConfigManager().getIceBallSlowLevel(),
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));
            
            // 添加发光效果
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            
            iceBall.setItemMeta(meta);
        }
        
        customItems.put(ItemIds.ICE_BALL, ItemIds.tag(iceBall, ItemIds.ICE_BALL));
    }
    
    /**
     * 通用凝冰球：和冰球手的职业凝冰球是两件**独立**的东西。
     *
     * <p>这件从道具箱出、没有冷却，靠雪球数量限速；职业那件由职业补发，两边互不影响
     * （ID 不同，冷却/持有判定都分开）。</p>
     */
    private void createIceBallCommon() {
        ItemStack iceBall = new ItemStack(Material.SNOWBALL);
        ItemMeta meta = iceBall.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + name(ItemIds.ICE_BALL_COMMON));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键投掷：命中母体让她眩晕并松手",
                ChatColor.GRAY + "命中逃生者则减速",
                ChatColor.GRAY + "无冷却（靠数量限速）",
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));

            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            iceBall.setItemMeta(meta);
        }

        customItems.put(ItemIds.ICE_BALL_COMMON,
            ItemIds.tag(iceBall, ItemIds.ICE_BALL_COMMON));
    }

    // 创建控魂术
    private void createSoulControl() {
        ItemStack soulControl = new ItemStack(Material.CLOCK);
        ItemMeta meta = soulControl.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_PURPLE + name(ItemIds.SOUL_CONTROL));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键使用让母体短暂无法移动",
                ChatColor.GRAY + "定身时间: " + plugin.getConfigManager().getSoulControlFreezeDuration() + "秒",
                ChatColor.GRAY + "冷却时间: " + plugin.getConfigManager().getSoulControlCooldown() + "秒",
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));
            
            // 添加发光效果
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            
            soulControl.setItemMeta(meta);
        }
        
        customItems.put(ItemIds.SOUL_CONTROL, ItemIds.tag(soulControl, ItemIds.SOUL_CONTROL));
    }
    
    // 创建闪现珍珠（已废弃，保留但不使用）
    private void createBlinkPearl() {
        // 这个方法已废弃，保留但不使用
        // 所有传送功能已合并到传送珍珠中
        plugin.getLogger().info("闪现珍珠创建方法已废弃，使用传送珍珠代替");
    }
    
    // 获取物品的方法
    public ItemStack getAdrenaline() {
        return customItems.get(ItemIds.ADRENALINE).clone();
    }
    
    public ItemStack getFrenzyPotion() {
        return customItems.get(ItemIds.FRENZY).clone();
    }
    
    public ItemStack getIceBall() {
        return customItems.get(ItemIds.ICE_BALL).clone();
    }
    
    public ItemStack getIceBallCommon() {
        ItemStack item = customItems.get(ItemIds.ICE_BALL_COMMON);
        return item != null ? item.clone() : null;
    }

    public ItemStack getSoulControl() {
        return customItems.get(ItemIds.SOUL_CONTROL).clone();
    }
    
    public ItemStack getBlinkPearl() {
        // 这个方法已废弃，返回空物品
        plugin.getLogger().warning("getBlinkPearl() 方法已废弃，请使用传送珍珠");
        return new ItemStack(Material.AIR);
    }
    
    public ItemStack getSpearRush() {
        ItemStack item = customItems.get(ItemIds.SPEAR_RUSH);
        return item != null ? item.clone() : null;
    }
    
    public ItemStack getLevitationPotion() {
        return customItems.get(ItemIds.LEVITATION_POTION).clone();
    }
    
    // 创建冲刺矛
    /**
     * 肾上腺素（GHG 弱化版）。
     *
     * <p>GHG 里所有人禁疾跑、母体速度只有 0.21，照搬 Gost 的「速度Ⅱ 10 秒」等于直接甩开母体，
     * 所以这里改成**短时速度Ⅰ + 结束后一段缓慢后遗症**：能拉开一次距离，但要还回去一点。</p>
     */
    public void applyAdrenalineEffect(Player player) {
        int seconds = plugin.getConfigManager().getHunterAdrenalineSeconds();
        int level = plugin.getConfigManager().getHunterAdrenalineSpeedLevel() - 1;
        int afterSlow = plugin.getConfigManager().getHunterAdrenalineAfterSlowSeconds();

        player.addPotionEffect(new PotionEffect(
            PotionEffectType.SPEED,
            seconds * 20,
            level,
            true,
            true
        ));

        if (afterSlow > 0) {
            ServerCompat.runLater(plugin, () -> {
                if (player.isOnline()) {
                    player.addPotionEffect(new PotionEffect(
                        ApiCompat.slowness(), afterSlow * 20, 0, true, true));
                    plugin.getLanguageManager().sendMessage(player, "item.adrenaline-after-slow", afterSlow);
                }
            }, seconds * 20L);
        }

        plugin.getLanguageManager().sendMessage(player, "item.adrenaline-used");
    }
    
    public void applyFrenzyEffect(Player player) {
        int duration = plugin.getConfigManager().getFrenzyDuration() * 20;
        int level = plugin.getConfigManager().getFrenzySpeedLevel() - 1;
        
        player.addPotionEffect(new PotionEffect(
            PotionEffectType.SPEED,
            duration,
            level,
            true,
            true
        ));
        
        plugin.getLanguageManager().sendMessage(player, "item.berserk-potion-used");
    }
    
    public void applyIceBallEffect(Player target) {
        int duration = plugin.getConfigManager().getIceBallSlowDuration() * 20;
        int level = plugin.getConfigManager().getIceBallSlowLevel() - 1;
        
        plugin.getLogger().info("开始应用凝冰球效果给玩家: " + target.getName() + " (减速等级: " + level + ", 持续时间: " + duration + " ticks)");
        
        boolean success = target.addPotionEffect(new PotionEffect(
            ApiCompat.slowness(),
            duration,
            level,
            true,
            true
        ));
        
        plugin.getLanguageManager().sendMessage(target, "item.freeze-ball-used");
        plugin.getLogger().info("凝冰球效果应用给玩家: " + target.getName() + " (减速等级: " + level + ", 持续时间: " + duration + " ticks, 成功: " + success + ")");
    }
    
    public void applySoulControlEffect(List<Player> ghostPlayers) {
        int duration = plugin.getConfigManager().getSoulControlFreezeDuration() * 20;
        
        for (Player ghost : ghostPlayers) {
            ghost.addPotionEffect(new PotionEffect(
                ApiCompat.slowness(),
                duration,
                255, // 最大减速效果
                true,
                true
            ));
            
            plugin.getLanguageManager().sendMessage(ghost, "item.soul-control-used");
        }
    }
    
    // 应用幽灵感知效果（所有玩家高亮）
    public void applyGhostSenseEffect(List<Player> allPlayers) {
        int duration = plugin.getConfigManager().getGhostSenseDuration() * 20;
        
        // 直接使用GLOWING效果（1.20.x支持）
        for (Player player : allPlayers) {
            player.addPotionEffect(new PotionEffect(
                PotionEffectType.GLOWING,
                duration,
                0,
                true,
                true
            ));
        }
        
        Bukkit.broadcastMessage(plugin.getLanguageManager().getMessage("item.ghost-sense-activated"));
    }
    
    // 随机分配道具
    
    /** 道具显示名（走语言文件 item-name.<id>） */
    private String name(String itemId) {
        return plugin.getLanguageManager().getMessage(ItemIds.nameKey(itemId));
    }

    /**
     * 安全发放道具：物品栏满不发放并提示；专属道具已有同款不发放；只放入物品栏（0-8格）
     */
    private void tryGiveItem(Player player, ItemStack item, String itemId) {
        if (player == null || !player.isOnline()) return;
        // 专属道具不允许重复：已有同款不发放（人鬼通用道具不受限）
        if (!ItemIds.isUniversal(itemId)) {
            for (ItemStack inv : player.getInventory().getContents()) {
                if (ItemIds.is(inv, itemId)) {
                    return; // 已有同款，不发放
                }
            }
        }
        String itemName = name(itemId);
        // 物品栏（前9格）已满 → 不发放（道具只放入物品栏，不放背包）
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s == null || s.getType() == org.bukkit.Material.AIR) {
                player.getInventory().setItem(i, item);
                plugin.getLanguageManager().sendMessage(player, "item.received", itemName);
                return;
            }
        }
        plugin.getLanguageManager().sendMessage(player, "item.inventory-full", itemName);
    }

    
    // 创建第二次机会道具
    private void createSecondChance() {
        ItemStack secondChance = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = secondChance.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name(ItemIds.SECOND_CHANCE));
            meta.setLore(Arrays.asList(
                ChatColor.translateAlternateColorCodes('&', "&7可抵挡一次鬼的感染"),
                ChatColor.translateAlternateColorCodes('&', "&6被动触发道具"),
                ChatColor.translateAlternateColorCodes('&', "&a仅限人类使用"),
                ChatColor.translateAlternateColorCodes('&', "&c[唯一道具]"),
                "",
                ChatColor.GRAY + "效果:",
                ChatColor.GRAY + "• 人类: 速度II " + plugin.getConfigManager().getSecondChanceHumanSpeedDuration() + "秒",
                ChatColor.GRAY + "• 人类: 高亮 " + plugin.getConfigManager().getSecondChanceHumanGlowingDuration() + "秒",
                ChatColor.GRAY + "• 鬼: 缓慢I " + plugin.getConfigManager().getSecondChanceGhostSlowDuration() + "秒",
                "",
                ChatColor.GRAY + "冷却时间: " + plugin.getConfigManager().getSecondChanceCooldown() + "秒",
                "",
                ChatColor.DARK_GRAY + "[被动触发]"
            ));
            
            // 添加发光效果
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            
            secondChance.setItemMeta(meta);
        }
        
        customItems.put(ItemIds.SECOND_CHANCE, ItemIds.tag(secondChance, ItemIds.SECOND_CHANCE));
    }
    
    // 创建漂浮药水
    private void createLevitationPotion() {
        ItemStack potion = new ItemStack(Material.LINGERING_POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + name(ItemIds.LEVITATION_POTION));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键使用获得漂浮效果",
                ChatColor.GRAY + "持续时间: " + (plugin.getConfigManager().getLevitationDurationTicks() / 20.0) + " 秒",
                ChatColor.GRAY + "漂浮等级: " + (plugin.getConfigManager().getLevitationAmplifier() + 1),
                ChatColor.GRAY + "冷却时间: " + plugin.getConfigManager().getLevitationCooldown() + " 秒",
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));
            
            // 设置药水颜色为紫色
            meta.setColor(Color.fromRGB(180, 0, 255));
            
            // 添加发光效果
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            
            potion.setItemMeta(meta);
        }
        
        customItems.put(ItemIds.LEVITATION_POTION, ItemIds.tag(potion, ItemIds.LEVITATION_POTION));
    }
    
    // 创建冲刺矛（v2.3.2：低版本矛不存在时使用三叉戟外观）
    private void createSpearRush() {
        ItemStack spear = new ItemStack(resolveSpearMaterial());
        ItemMeta meta = spear.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name(ItemIds.SPEAR_RUSH));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键使用向前冲刺5.5米",
                ChatColor.GRAY + "使用后即刻消失",
                "",
                ChatColor.DARK_GRAY + "[一次性道具]"
            ));
            
            // 添加附魔光效
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            
            spear.setItemMeta(meta);
        }
        
        customItems.put(ItemIds.SPEAR_RUSH, ItemIds.tag(spear, ItemIds.SPEAR_RUSH));
    }
    
    /**
     * 解析冲刺矛使用的物品材质（v2.3.2）
     * ≥1.21.4 使用 GOLDEN_SPEAR（矛），低版本自动回退为 TRIDENT（三叉戟）
     * 使用字符串匹配避免低版本直接引用枚举常量导致 NoSuchFieldError
     */
    private Material resolveSpearMaterial() {
        try {
            Material spear = Material.matchMaterial("GOLDEN_SPEAR");
            if (spear != null) {
                return spear;
            }
        } catch (Exception ignored) {
            // 低版本枚举不存在，忽略
        }
        return Material.TRIDENT;
    }
    
    // 获取第二次机会道具
    public ItemStack getSecondChance() {
        return customItems.get(ItemIds.SECOND_CHANCE).clone();
    }

    /**
     * 把道具放进**空格**，绝不和已有同类合并。
     *
     * <p>GHG 的道具一律单件——用 {@code inventory.addItem} 会让两个凝冰球直接叠成一组，
     * 所以发放一律走这里：先找物品栏（0-8），再找背包其余格子。</p>
     *
     * @return false 表示没有空格
     */
    public boolean addUnstacked(Player player, ItemStack item) {
        if (player == null || item == null) return false;
        ItemStack one = item.clone();
        one.setAmount(1);

        for (int i = 0; i < 9; i++) {
            if (isEmpty(player.getInventory().getItem(i))) {
                player.getInventory().setItem(i, one);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (isEmpty(player.getInventory().getItem(i))) {
                player.getInventory().setItem(i, one);
                return true;
            }
        }
        return false;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    /**
     * 发放**可叠加**的道具（应急口粮）：先并进已有的同 ID 堆，装不下再找空格。
     *
     * <p>口粮的「剩余口数」记在整摞的 PDC 上，吃掉一份时会把口数重置回满，
     * 所以并进半摞也不会吃掉已有的口数。</p>
     */
    public boolean addStackable(Player player, ItemStack item) {
        if (player == null || item == null) return false;
        String id = ItemIds.idOf(item);
        if (id == null) return addUnstacked(player, item);
        int max = Math.max(1, item.getMaxStackSize());

        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot == null || slot.getType().isAir()) continue;
            if (!id.equals(ItemIds.idOf(slot))) continue;
            if (slot.getAmount() >= max) continue;
            slot.setAmount(slot.getAmount() + 1);
            player.getInventory().setItem(i, slot);
            return true;
        }
        return addUnstacked(player, item);
    }

    /** 医疗包：立刻治好受伤（GHG 专属） */
    private void createMedkit() {
        ItemStack item = new ItemStack(Material.HONEY_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + name(ItemIds.MEDKIT));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键使用，立刻治好身上的伤",
                ChatColor.GRAY + "只能在受伤状态使用",
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        customItems.put(ItemIds.MEDKIT, ItemIds.tag(item, ItemIds.MEDKIT));
    }

    /** 应急口粮：分几口吃，每口恢复饱食度（GHG 专属） */
    private void createRation() {
        ItemStack item = new ItemStack(Material.BREAD);
        int bites = plugin.getConfigManager().getHunterRationBites();
        ItemIds.tagBites(item, bites);
        applyRationMeta(item, bites);
        customItems.put(ItemIds.RATION, ItemIds.tag(item, ItemIds.RATION));
    }

    /** 重建口粮的名字与 lore（吃掉一口后要刷新剩余口数） */
    public void applyRationMeta(ItemStack item, int bites) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setDisplayName(ChatColor.GOLD + name(ItemIds.RATION));
        meta.setLore(Arrays.asList(
            ChatColor.GRAY + "按住右键吃一口（真实进食动作）",
            ChatColor.GRAY + "每口恢复: " + plugin.getConfigManager().getHunterRationRestoreFood() + " 点",
            ChatColor.GRAY + "剩余: " + bites + " 口",
            "",
            ChatColor.DARK_GRAY + "[消耗品]"
        ));
        meta.addEnchant(ApiCompat.lure(), 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
    }

    /** 吃掉一口：扣口数并刷新显示 */
    public void consumeRationBite(ItemStack item, int left) {
        if (item == null) return;
        ItemIds.tagBites(item, left);
        applyRationMeta(item, left);
    }

    public ItemStack getRation() {
        return customItems.get(ItemIds.RATION).clone();
    }

    /** 破阵石：给脚下这座法阵挂「破解速度 ×倍率」buff（GHG 专属） */
    private void createRitualKnife() {
        ItemStack item = new ItemStack(Material.FLINT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + name(ItemIds.RITUAL_KNIFE));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键使用：脚下的法阵破解速度",
                ChatColor.GRAY + "×" + plugin.getConfigManager().getRitualKnifeBoostMultiplier()
                    + "，持续 " + plugin.getConfigManager().getRitualKnifeBoostSeconds() + " 秒",
                ChatColor.GRAY + "必须站在法阵格里使用",
                ChatColor.GRAY + "冷却时间: " + plugin.getConfigManager().getRitualKnifeCooldown() + " 秒",
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        customItems.put(ItemIds.RITUAL_KNIFE, ItemIds.tag(item, ItemIds.RITUAL_KNIFE));
    }

    public ItemStack getMedkit() {
        return customItems.get(ItemIds.MEDKIT).clone();
    }

    public ItemStack getRitualKnife() {
        return customItems.get(ItemIds.RITUAL_KNIFE).clone();
    }

    /** 救赎护符：右键后一段时间内救人读条加速 */
    private void createRedemptionCharm() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + name(ItemIds.REDEMPTION_CHARM));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键使用：接下来 " + plugin.getConfigManager().getCharmDurationSeconds() + " 秒内",
                ChatColor.GRAY + "救人读条速度 ×" + plugin.getConfigManager().getCharmSpeedMultiplier(),
                ChatColor.GRAY + "仍然要走到队友身边按住 Shift",
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        customItems.put(ItemIds.REDEMPTION_CHARM,
            ItemIds.tag(item, ItemIds.REDEMPTION_CHARM));
    }

    public ItemStack getRedemptionCharm() {
        return customItems.get(ItemIds.REDEMPTION_CHARM).clone();
    }

    /** 工程师的栅栏工具：右键在准星处架一段木栅栏 */
    private void createFenceKit() {
        ItemStack item = new ItemStack(Material.OAK_FENCE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name(ItemIds.FENCE_KIT));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键：在准星处架起一段木栅栏",
                ChatColor.GRAY + "栅栏可以挡住母体的路",
                ChatColor.GRAY + "母体与其他工程师都能拆掉它",
                "",
                ChatColor.DARK_GRAY + "[职业技能]"
            ));
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        customItems.put(ItemIds.FENCE_KIT, ItemIds.tag(item, ItemIds.FENCE_KIT));
    }

    public ItemStack getFenceKit() {
        return customItems.get(ItemIds.FENCE_KIT).clone();
    }

    /** 眩晕药水：喷溅药水，砸中母体立刻定身（不能移动、不能互动） */
    private void createStunPotion() {
        ItemStack item = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_AQUA + name(ItemIds.STUN_POTION));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键投掷：砸中母体让她立刻定身",
                ChatColor.GRAY + "定身时长: " + (plugin.getConfigManager().getHunterStunPotionTicks() / 20.0) + " 秒",
                ChatColor.GRAY + "定身期间她不能移动、也不能放技能",
                "",
                ChatColor.DARK_GRAY + "[消耗品]"
            ));
            meta.setColor(Color.fromRGB(0, 60, 120));
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        customItems.put(ItemIds.STUN_POTION, ItemIds.tag(item, ItemIds.STUN_POTION));
    }

    public ItemStack getStunPotion() {
        ItemStack item = customItems.get(ItemIds.STUN_POTION);
        return item != null ? item.clone() : null;
    }

    /** 隐身斗篷：黑衣人的职业技能道具 */
    private void createShadowCloak() {
        ItemStack item = new ItemStack(Material.PHANTOM_MEMBRANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_GRAY + name(ItemIds.SHADOW_CLOAK));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键使用：隐身 " + plugin.getConfigManager().getShadowCloakSeconds() + " 秒",
                ChatColor.GRAY + "隐身期间看不出来，但母体静止时的被动透视",
                ChatColor.GRAY + "仍然能看到你的轮廓",
                "",
                ChatColor.DARK_GRAY + "[职业技能]"
            ));
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        customItems.put(ItemIds.SHADOW_CLOAK, ItemIds.tag(item, ItemIds.SHADOW_CLOAK));
    }

    public ItemStack getShadowCloak() {
        ItemStack item = customItems.get(ItemIds.SHADOW_CLOAK);
        return item != null ? item.clone() : null;
    }

    /**
     * 准备阶段的选择面板（钟表）。
     *
     * <p>玩家选完职业 / 母体种类、把 UI 关掉之后，原来只能再敲 `/ghg role` 才能重选。
     * 现在准备阶段每人手里有一块钟表，**左键右键都能打开**对应菜单；开打时统一收走。</p>
     */
    private void createMenuClock() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + name(ItemIds.MENU_CLOCK));
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "左键或右键：打开选择面板",
                ChatColor.GRAY + "逃生者 = 职业菜单",
                ChatColor.GRAY + "母体 = 母体种类菜单",
                ChatColor.GRAY + "只在准备阶段有用，开打时自动收走",
                "",
                ChatColor.DARK_GRAY + "[准备阶段专属]"
            ));
            meta.addEnchant(ApiCompat.lure(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        customItems.put(ItemIds.MENU_CLOCK, ItemIds.tag(item, ItemIds.MENU_CLOCK));
    }

    public ItemStack getMenuClock() {
        ItemStack item = customItems.get(ItemIds.MENU_CLOCK);
        return item != null ? item.clone() : null;
    }

    /**
     * 按 ID 创建一件道具。
     *
     * <p>命令发放、道具箱掉落、GHG 的「拾取即可用」都走这里，
     * 不要再各自 if-else 一套中文名。</p>
     *
     * @return 不认识这个 ID 或当前服务端不支持时返回 null
     */
    public ItemStack createById(String itemId) {
        if (itemId == null) return null;
        switch (itemId) {
            case ItemIds.ADRENALINE:
                return getAdrenaline();
            case ItemIds.FRENZY:
                return getFrenzyPotion();
            case ItemIds.ICE_BALL:
                return getIceBall();
            case ItemIds.SOUL_CONTROL:
                return getSoulControl();
            case ItemIds.STINKY_STEAK:
                return plugin.getItemSpawnManager().createStinkySteak();
            case ItemIds.TELEPORT_PEARL:
                return plugin.getItemSpawnManager().createTeleportPearl();
            case ItemIds.SOUL_DETECTOR:
                return plugin.getItemSpawnManager().createSoulDetector();
            case ItemIds.SECOND_CHANCE:
                return getSecondChance();
            case ItemIds.LEVITATION_POTION:
                return getLevitationPotion();
            case ItemIds.SPEAR_RUSH:
                return plugin.isSpearRushEnabled() ? getSpearRush() : null;
            case ItemIds.ICE_BALL_COMMON:
                return getIceBallCommon();
            case ItemIds.MEDKIT:
                return getMedkit();
            case ItemIds.RATION:
                return getRation();
            case ItemIds.RITUAL_KNIFE:
                return getRitualKnife();
            case ItemIds.REDEMPTION_CHARM:
                return getRedemptionCharm();
            case ItemIds.FENCE_KIT:
                return getFenceKit();
            case ItemIds.STUN_POTION:
                return getStunPotion();
            case ItemIds.SHADOW_CLOAK:
                return getShadowCloak();
            case ItemIds.MENU_CLOCK:
                return getMenuClock();
            default:
                return null;
        }
    }
    
    // 应用第二次机会效果
    public void applySecondChanceEffect(Player humanPlayer, Player ghostPlayer) {
        plugin.getLogger().info("第二次机会效果触发: 人类=" + humanPlayer.getName() + ", 鬼=" + ghostPlayer.getName());
        
        // 给人类玩家效果
        int humanSpeedDuration = plugin.getConfigManager().getSecondChanceHumanSpeedDuration() * 20;
        int humanSpeedLevel = plugin.getConfigManager().getSecondChanceHumanSpeedLevel() - 1;
        int humanGlowingDuration = plugin.getConfigManager().getSecondChanceHumanGlowingDuration() * 20;
        
        humanPlayer.addPotionEffect(new PotionEffect(
            PotionEffectType.SPEED,
            humanSpeedDuration,
            humanSpeedLevel,
            true,
            true
        ));
        
        humanPlayer.addPotionEffect(new PotionEffect(
            PotionEffectType.GLOWING,
            humanGlowingDuration,
            0,
            true,
            true
        ));
        
        // 给鬼玩家效果
        int ghostSlowDuration = plugin.getConfigManager().getSecondChanceGhostSlowDuration() * 20;
        int ghostSlowLevel = plugin.getConfigManager().getSecondChanceGhostSlowLevel() - 1;
        
        ghostPlayer.addPotionEffect(new PotionEffect(
            ApiCompat.slowness(),
            ghostSlowDuration,
            ghostSlowLevel,
            true,
            true
        ));
        
        // 发送消息
        humanPlayer.sendMessage(plugin.getLanguageManager().getMessage("item.one_chance_triggered"));
        humanPlayer.sendMessage(plugin.getLanguageManager().getMessage("item.random_teleport"));
        plugin.getLanguageManager().sendMessage(ghostPlayer, "item.second-chance-slowed");
        
        // 广播消息
        Bukkit.broadcastMessage(plugin.getLanguageManager().getMessage("item.second-chance-blocked", humanPlayer.getName(), ghostPlayer.getName()));
        
        plugin.getLogger().info("第二次机会效果应用完成");
    }
    
    // 应用冲刺矛效果
    public void applySpearRushEffect(Player player) {
        // 获取玩家面向方向
        org.bukkit.util.Vector direction = player.getLocation().getDirection();
        
        // 冲刺初速度：老实现把「距离」直接当速度用（5.5 格/刻），玩家会飞出去几十格
        double power = plugin.getConfigManager().getSpearRushVelocity();
        direction.setY(0); // 取消上升位移
        direction.normalize().multiply(power);
        
        // 应用冲刺速度
        player.setVelocity(direction);
        
        // 音效
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_DEATH, 1.0f, 0.8f);
        
        // 居中字幕提示
        player.sendTitle(plugin.getLanguageManager().getMessage(player, "item.spear-rush-title"), plugin.getLanguageManager().getMessage(player, "item.spear-rush-subtitle"), 5, 20, 5);
        
        plugin.getLogger().info("玩家 " + player.getName() + " 使用了冲刺矛");
    }
    
    /**
     * 漂浮药水：时长与漂浮等级都走配置。
     *
     * <p>⚠️ 原来这里写死 4.5 秒 / 放大器 0（漂浮Ⅰ），配置里的
     * {@code items.levitation.duration-ticks / amplifier} 根本没被读过 ——
     * 改了配置没反应，所以「提高倍率」一直是空话。</p>
     */
    public void applyLevitationEffect(Player player) {
        int duration = plugin.getConfigManager().getLevitationDurationTicks();
        int amplifier = plugin.getConfigManager().getLevitationAmplifier();
        player.addPotionEffect(new PotionEffect(
            ApiCompat.levitation(),
            duration,
            amplifier,
            true,
            true
        ));
        plugin.getLanguageManager().sendMessage(player, "item.levitation-used");
    }
}