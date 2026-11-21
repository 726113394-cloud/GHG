package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 全部道具效果的实现，集中在 {@link #registerAll(ItemEffectRegistry)} 里注册。
 *
 * <p>效果逻辑从原来的 {@code ItemListener} 分支里原样搬过来，只是入口从
 * 「中文名 contains」换成了「PDC 稳定 ID → 注册表」。</p>
 */
public final class ItemEffects {

    private ItemEffects() {}

    /** 把本插件的全部道具效果注册进注册表 */
    public static void registerAll(ItemEffectRegistry registry) {
        registry.register(new Adrenaline());
        registry.register(new FrenzyPotion());
        registry.register(new IceBall());
        registry.register(new IceBallCommon());
        registry.register(new SoulControl());
        registry.register(new TeleportPearl());
        registry.register(new SoulDetector());
        registry.register(new SpearRush());
        registry.register(new LevitationPotion());
        registry.register(new Medkit());
        registry.register(new RitualKnife());
        registry.register(new RedemptionCharm());
        registry.register(new FenceKit());
        registry.register(new StunPotion());
        registry.register(new ShadowCloak());
        registry.register(new MenuClock());
    }

    // ================================================================ 肾上腺素

    private static final class Adrenaline implements ItemEffect {
        @Override public String itemId() { return ItemIds.ADRENALINE; }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            if (!plugin.getPlayerManager().isHuman(player.getUniqueId())) {
                plugin.getLanguageManager().sendMessage(player, "item.adrenaline-human-only");
                return false;
            }
            plugin.getActionBarManager().sendAdrenalineHint(player);
            plugin.getItemManager().applyAdrenalineEffect(player);
            player.sendTitle(
                plugin.getLanguageManager().getMessage(player, "item.adrenaline-title"),
                plugin.getLanguageManager().getMessage(player, "item.adrenaline-subtitle"), 10, 30, 10);
            player.playSound(player.getLocation(), SoundCompat.playerSplash(), 1.0f, 1.5f);
            ctx.consume();
            return false;
        }
    }

    // ================================================================ 狂暴药水

    private static final class FrenzyPotion implements ItemEffect {
        @Override public String itemId() { return ItemIds.FRENZY; }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            if (!plugin.getPlayerManager().isGhost(player.getUniqueId())) {
                plugin.getLanguageManager().sendMessage(player, "item.frenzy-ghost-only");
                return false;
            }
            plugin.getActionBarManager().sendFrenzyPotionHint(player);
            plugin.getItemManager().applyFrenzyEffect(player);
            player.sendTitle(
                plugin.getLanguageManager().getMessage(player, "item.frenzy-title"),
                plugin.getLanguageManager().getMessage(player, "item.frenzy-subtitle"), 10, 30, 10);
            player.playSound(player.getLocation(), SoundCompat.witherShoot(), 1.0f, 1.2f);
            ctx.consume();
            return false;
        }
    }

    // ================================================================ 凝冰球

    private static final class IceBall implements ItemEffect {
        @Override public String itemId() { return ItemIds.ICE_BALL; }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            plugin.getActionBarManager().sendIceBallHint(player);
            player.playSound(player.getLocation(), SoundCompat.snowballThrow(), 1.0f, 1.0f);
            // 投掷交给原版，命中在 ItemListener 的 ProjectileHitEvent 里处理
            ctx.allowVanillaAction();
            return false;
        }
    }

    // ============================================================ 通用凝冰球

    /**
     * 通用凝冰球：从道具箱出的那一颗，和冰球手的职业凝冰球**互相独立**（ID / 冷却 / 持有判定分开）。
     *
     * <p>无冷却，靠雪球数量限速；投掷放行给原版，命中减速率和「砸母体眩晕」都在
     * {@code ItemListener.onProjectileHit} 里按雪球统一结算。</p>
     */
    private static final class IceBallCommon implements ItemEffect {
        @Override public String itemId() { return ItemIds.ICE_BALL_COMMON; }

        @Override public boolean accepts(boolean rightClick, boolean leftClick) {
            return rightClick;
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            // 倒地的人进不到这里（ItemListener 先拦了），这里只做提示与放行
            plugin.getActionBarManager().sendIceBallHint(player);
            player.playSound(player.getLocation(), SoundCompat.snowballThrow(), 1.0f, 1.15f);
            ctx.allowVanillaAction();
            return false;
        }
    }

    // ================================================================ 控魂术

    private static final class SoulControl implements ItemEffect {
        @Override public String itemId() { return ItemIds.SOUL_CONTROL; }

        @Override
        public int cooldownSeconds() {
            GHG plugin = GHG.getInstance();
            return plugin == null ? 0 : plugin.getConfigManager().getSoulControlCooldown();
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();

            // GHG：母体是 Mob，没有「鬼玩家」可找 —— 这道具改成定身母体
            if (plugin.getModeManager().isHunter()) {
                HunterGameManager hunter = plugin.getHunterGameManager();
                int seconds = plugin.getConfigManager().getSoulControlFreezeDuration();
                if (!hunter.freezeEnemyMother(seconds)) {
                    plugin.getLanguageManager().sendMessage(player, "hunter.item.no-mother");
                    return false;
                }
                plugin.getActionBarManager().sendSoulControlHint(player);
                player.playSound(player.getLocation(), SoundCompat.illusionerCast(), 1.0f, 1.0f);
                plugin.getLanguageManager().sendMessage(player, "hunter.item.soul-control-used", seconds);
                ctx.consume();
                return true;
            }

            if (!plugin.getPlayerManager().isHuman(player.getUniqueId())) {
                plugin.getLanguageManager().sendMessage(player, "item.soul-control-human-only");
                return false;
            }

            List<Player> ghostPlayers = new ArrayList<>();
            AreaManager.GameArea selectedArea = plugin.getAreaManager().getSelectedArea();
            if (selectedArea != null) {
                ghostPlayers = plugin.getAreaManager().getPlayersInArea(selectedArea);
                ghostPlayers.removeIf(p -> !plugin.getPlayerManager().isGhost(p.getUniqueId()));
            }
            if (ghostPlayers.isEmpty()) {
                plugin.getLanguageManager().sendMessage(player, "item.no-ghosts-nearby");
                return false;
            }

            plugin.getActionBarManager().sendSoulControlHint(player);
            plugin.getItemManager().applySoulControlEffect(ghostPlayers);

            player.playSound(player.getLocation(), SoundCompat.illusionerCast(), 1.0f, 1.0f);
            for (Player ghost : ghostPlayers) {
                ghost.playSound(ghost.getLocation(), SoundCompat.elderCurse(), 1.0f, 0.8f);
                ghost.sendTitle(
                    plugin.getLanguageManager().getMessage(ghost, "item.soul-controlled-title"),
                    plugin.getLanguageManager().getMessage(ghost, "item.soul-controlled-subtitle"), 10, 30, 10);
            }
            ctx.consume();
            return true;
        }
    }

    // ================================================================ 臭牛排

    /* ⚠️ 这里**故意没有**注册右键效果。
       臭牛排是「真食物」：按住右键由原版走进食（有动作/进度条），吃完那一下触发
       PlayerItemConsumeEvent，由 ItemListener 结算效果。
       以前它有自己的 ItemEffect，会拦掉原版进食、还带 45 秒冷却 —— 于是右键一下就进冷却、
       冷却期间连吃都吃不了（饱食度满时更明显），等于这块肉没法用。 */

    /**
     * 臭牛排吃下去的那一刻：速度 + 发光 + 吐气。
     *
     * <p>唯一的调用方是 {@code ItemListener.onConsume}（原版进食结束那一下）。</p>
     */
    public static void giveStinkySteakBuff(GHG plugin, Player player) {
        if (plugin == null || player == null) return;
        int speedDuration = plugin.getConfigManager().getStinkySteakSpeedDuration();
        int speedLevel = plugin.getConfigManager().getStinkySteakSpeedLevel();
        int glowingDuration = plugin.getConfigManager().getStinkySteakGlowingDuration();

        // level 是「等级」：1 = 速度Ⅰ → 放大器 0（原来是直接当放大器，1 就变成速度Ⅱ）
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
            speedDuration * 20, Math.max(0, speedLevel - 1)));
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowingDuration * 20, 0));

        player.sendTitle(
            plugin.getLanguageManager().getMessage(player, "item.stinky-steak-title"),
            plugin.getLanguageManager().getMessage(player, "item.stinky-steak-subtitle"), 10, 30, 10);
        player.playSound(player.getLocation(), SoundCompat.playerBurp(), 1.0f, 1.0f);
        /* 文案里的 {0} 是「速度几」：1 → Ⅰ、2 → Ⅱ、3 → Ⅲ（和 README / 其它道具的写法一致） */
        String levelLabel = speedLevel <= 1 ? "Ⅰ" : (speedLevel == 2 ? "Ⅱ" : "Ⅲ");
        plugin.getLanguageManager().sendMessage(player, "item.stinky-steak-eaten", levelLabel);
    }

    // ================================================================ 传送珍珠

    private static final class TeleportPearl implements ItemEffect {
        @Override public String itemId() { return ItemIds.TELEPORT_PEARL; }

        @Override
        public int cooldownSeconds() {
            GHG plugin = GHG.getInstance();
            return plugin == null ? 0 : plugin.getConfigManager().getTeleportPearlCooldown();
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            plugin.getActionBarManager().sendTeleportPearlHint(player);

            // 自己发射一颗末影珍珠，而不是靠「放行原版行为」——
            // 原版那条路依赖交互事件没被别人取消、也依赖各版本对物品使用的处理，
            // 实测出现过右键完全没反应。launchProjectile 出来的就是原版 EnderPearl，
            // 落地传送的逻辑仍然由原版负责，只是发射这一下掌握在自己手里。
            try {
                player.launchProjectile(org.bukkit.entity.EnderPearl.class);
            } catch (Throwable t) {
                plugin.getLogger().warning("传送珍珠发射失败: " + t.getMessage());
                // 退回原版投掷
                ctx.allowVanillaAction();
                return true;
            }

            player.playSound(player.getLocation(), SoundCompat.enderPearlThrow(), 1.0f, 1.0f);
            ctx.consume();
            plugin.getLanguageManager().sendMessage(player, "item.teleport-pearl-used", cooldownSeconds());
            return true;
        }
    }

    // ================================================================ 灵魂探测器

    private static final class SoulDetector implements ItemEffect {
        @Override public String itemId() { return ItemIds.SOUL_DETECTOR; }

        @Override
        public int cooldownSeconds() {
            GHG plugin = GHG.getInstance();
            return plugin == null ? 0 : plugin.getConfigManager().getSoulDetectorCooldown();
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            if (!plugin.getPlayerManager().isMotherGhost(player.getUniqueId())) {
                plugin.getLanguageManager().sendMessage(player, "item.soul-detector-mother-only");
                return false;
            }

            plugin.getActionBarManager().sendSoulDetectorHint(player);

            List<Player> allPlayers = new ArrayList<>();
            for (UUID uuid : plugin.getPlayerManager().getAllPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) allPlayers.add(p);
            }
            if (allPlayers.isEmpty()) {
                plugin.getLanguageManager().sendMessage(player, "item.no-other-players");
                return false;
            }

            for (Player target : allPlayers) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 500, 0, true, true));
                if (!target.getUniqueId().equals(player.getUniqueId())) {
                    target.sendTitle(
                        plugin.getLanguageManager().getMessage(target, "item.revealed-title"),
                        plugin.getLanguageManager().getMessage(target, "item.revealed-subtitle"), 10, 30, 10);
                }
            }

            ctx.consume();
            player.playSound(player.getLocation(), SoundCompat.illusionerMirror(), 1.0f, 1.0f);
            Bukkit.getOnlinePlayers().forEach(p ->
                p.playSound(p.getLocation(), SoundCompat.enderDragonFlap(), 0.3f, 1.5f));

            plugin.getLanguageManager().sendMessage(player, "item.soul-detector-used");
            Bukkit.broadcastMessage(plugin.getLanguageManager().getMessage("item.soul-detector-broadcast", player.getName()));

            /* ⚠️ 这里原来还有一段「1 tick 后纠偏」的代码：只要母体在这 1 tick 里移动超过 1 格
               （冲刺、被击退、下落、骑乘坐骑都会），就把她**拽回使用前的位置**并提示。
               本意是「有些服务端把指南针使用当成传送触发点」，但实际效果就是母体莫名其妙被传一下 ——
               按批注整段删掉：灵魂探测器只负责揭位置，不碰位置。 */
            return true;
        }
    }

    // ================================================================ 冲刺矛

    private static final class SpearRush implements ItemEffect {
        @Override public String itemId() { return ItemIds.SPEAR_RUSH; }

        /** 冲刺矛只认右键 */
        @Override
        public boolean accepts(boolean rightClick, boolean leftClick) {
            return rightClick;
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            if (!plugin.isSpearRushEnabled()) {
                plugin.getLanguageManager().sendMessage(player, "item.spear-unavailable");
                return false;
            }
            plugin.getItemManager().applySpearRushEffect(player);
            ctx.consume();
            return true;
        }
    }

    // ================================================================ 医疗包（GHG）

    private static final class Medkit implements ItemEffect {
        @Override public String itemId() { return ItemIds.MEDKIT; }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            if (!plugin.getHunterGameManager().instantHeal(player)) {
                plugin.getLanguageManager().sendMessage(player, "hunter.item.not-injured");
                return false;
            }
            plugin.getLanguageManager().sendMessage(player, "hunter.item.medkit-used");
            ctx.consume();
            return false;
        }
    }

    // ================================================================ 救赎护符（GHG）

    /**
     * 救赎护符：不是远程救人，只是把「走到队友身边按住 Shift」的读条时间缩短。
     */
    private static final class RedemptionCharm implements ItemEffect {
        @Override public String itemId() { return ItemIds.REDEMPTION_CHARM; }

        @Override
        public int cooldownSeconds() {
            GHG plugin = GHG.getInstance();
            return plugin == null ? 0 : plugin.getConfigManager().getCharmCooldownSeconds();
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            if (!plugin.getModeManager().isHunter()
                || !plugin.getHunterGameManager().applyRescueBoost(player)) {
                plugin.getLanguageManager().sendMessage(player, "item.charm-unavailable");
                return false;
            }
            ctx.consume();
            return true;
        }
    }

    // ================================================================ 工程师栅栏（GHG）

    /** 栅栏工具：右键在准星处架一段木栅栏；母体与工程师都能拆 */
    private static final class FenceKit implements ItemEffect {
        @Override public String itemId() { return ItemIds.FENCE_KIT; }

        @Override
        public boolean accepts(boolean rightClick, boolean leftClick) {
            return rightClick;
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            if (!plugin.getHunterGameManager().placeFence(player)) {
                plugin.getLanguageManager().sendMessage(player, "hunter.role.fence-failed");
                return false;
            }
            ctx.consume();
            return true;
        }
    }

    // ============================================================ 选择面板（GHG）

    /**
     * 准备阶段的选择面板：**左右键都行**，逃生者开职业菜单、玩家母体开母体种类菜单。
     *
     * <p>它不消耗、不冷却；开打之后这块钟表会被收走，所以只有「准备阶段（或测试局）」能用。
     * 注意材质是时钟，和「控魂术」撞外观 —— 两者靠 PDC 的稳定 ID 区分，功能互不影响。</p>
     */
    private static final class MenuClock implements ItemEffect {
        @Override public String itemId() { return ItemIds.MENU_CLOCK; }

        @Override public boolean accepts(boolean rightClick, boolean leftClick) {
            return true;
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            HunterGameManager hunter = plugin.getHunterGameManager();
            if (!hunter.isPreparing() && !hunter.isTestMode()) {
                plugin.getLanguageManager().sendMessage(player, "hunter.prep.menu-clock-too-late");
                return false;
            }
            boolean opened = hunter.isPlayerMother(player.getUniqueId())
                ? hunter.openKindMenu(player)
                : hunter.openRoleMenu(player);
            if (!opened) {
                plugin.getLanguageManager().sendMessage(player, "hunter.prep.menu-clock-too-late");
            }
            // 不拦原版交互：拿着钟表左键也应该能正常敲箱子（取消事件会让「敲不动」）
            ctx.allowVanillaAction();
            return false;
        }
    }

    // ============================================================ 眩晕药水（GHG）

    /**
     * 眩晕药水：喷溅药水，**投掷交给原版**（命中判定在 {@code ItemListener.onPotionSplash}），
     * 砸中母体就让她立刻定身 1.5 秒 —— 期间不能移动、不能放技能。
     */
    private static final class StunPotion implements ItemEffect {
        @Override public String itemId() { return ItemIds.STUN_POTION; }

        @Override public boolean accepts(boolean rightClick, boolean leftClick) {
            return rightClick;
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            if (!plugin.getModeManager().isHunter()) {
                plugin.getLanguageManager().sendMessage(player, "hunter.item.stun-no-mother");
                return false;
            }
            player.playSound(player.getLocation(), SoundCompat.enderPearlThrow(), 1.0f, 1.4f);
            ctx.allowVanillaAction();
            return false;
        }
    }

    // ============================================================ 隐身斗篷（GHG）

    /**
     * 黑衣人：隐身一段时间。
     *
     * <p>⚠️ 隐身**不挡**母体「静止时才生效的被动透视」——那套被动给的是发光效果，
     * 而 MC 里发光会让隐身的人露出轮廓。这就是这个职业的克制点，别顺手「修」掉。</p>
     */
    private static final class ShadowCloak implements ItemEffect {
        @Override public String itemId() { return ItemIds.SHADOW_CLOAK; }

        @Override public boolean accepts(boolean rightClick, boolean leftClick) {
            return rightClick;
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            int seconds = plugin.getConfigManager().getShadowCloakSeconds();
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                seconds * 20, 0, true, true));
            player.sendTitle(
                plugin.getLanguageManager().getMessage(player, "item.shadow-cloak-title"),
                plugin.getLanguageManager().getMessage(player, "item.shadow-cloak-subtitle", seconds),
                10, 30, 10);
            player.playSound(player.getLocation(), SoundCompat.illusionerMirror(), 0.9f, 1.4f);
            ctx.consume();
            return false;
        }
    }

    // ================================================================ 破阵石（GHG）

    /**
     * 破阵石：不再直接推进度，而是让**脚下这一座**法阵在一段时间内破解速度 ×倍率。
     * 用掉即消耗，另有一段使用冷却（防止攒两三块连着叠）。
     */
    private static final class RitualKnife implements ItemEffect {
        @Override public String itemId() { return ItemIds.RITUAL_KNIFE; }

        @Override public boolean accepts(boolean rightClick, boolean leftClick) {
            return rightClick;
        }

        @Override
        public int cooldownSeconds() {
            GHG plugin = GHG.getInstance();
            return plugin == null ? 0 : plugin.getConfigManager().getRitualKnifeCooldown();
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            int altar = plugin.getHunterGameManager().boostAltar(player);
            if (altar == 0) {
                plugin.getLanguageManager().sendMessage(player, "hunter.item.not-on-altar");
                return false;
            }
            // -1 = 这座阵正在加固 / 瘫痪，boostAltar 里已经提示过
            if (altar < 0) return false;
            ctx.consume();
            return true;
        }
    }

    // ================================================================ 应急口粮（GHG）

    // 应急口粮改走「真进食」：PlayerItemConsumeEvent 在 ItemListener 里处理，
    // 这里不再注册 right-click 效果（否则会出现点一下就吃、没有进食动作）。


    // ================================================================ 漂浮药水

    private static final class LevitationPotion implements ItemEffect {
        @Override public String itemId() { return ItemIds.LEVITATION_POTION; }

        /** items.levitation.cooldown 默认 0 = 无冷却（靠瓶数限速） */
        @Override
        public int cooldownSeconds() {
            GHG plugin = GHG.getInstance();
            return plugin == null ? 0 : plugin.getConfigManager().getLevitationCooldown();
        }

        @Override
        public boolean apply(Player player, ItemUseContext ctx) {
            GHG plugin = ctx.plugin();
            plugin.getItemManager().applyLevitationEffect(player);
            int seconds = plugin.getConfigManager().getLevitationDurationTicks() / 20;
            int level = plugin.getConfigManager().getLevitationAmplifier() + 1;
            player.sendTitle(
                plugin.getLanguageManager().getMessage(player, "item.levitation-title"),
                plugin.getLanguageManager().getMessage(player, "item.levitation-subtitle",
                    seconds, level), 10, 30, 10);
            player.playSound(player.getLocation(), SoundCompat.illusionerCast(), 1.0f, 1.2f);
            ctx.consume();
            return true;
        }
    }
}
