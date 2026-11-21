package io.Sriptirc_wp_1258.gost.listeners;

import io.Sriptirc_wp_1258.gost.GHG;
import io.Sriptirc_wp_1258.gost.managers.HunterGameManager;
import io.Sriptirc_wp_1258.gost.managers.MotherSkills;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

/**
 * GHG 对局的事件入口。
 *
 * <p>只在 {@link io.Sriptirc_wp_1258.gost.managers.MatchMode#HUNTER} 下生效，
 * 经典局的同类事件由各自监听器负责，两边互不干扰。</p>
 */
public class HunterListener implements Listener {

    private final GHG plugin;

    public HunterListener(GHG plugin) {
        this.plugin = plugin;
    }

    /** 击倒后坐标锁定：位置被挪动就拉回去 */
    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.getModeManager().isHunter()) return;

        Player player = event.getPlayer();
        // 被冲撞撞晕：动不了
        if (plugin.getHunterGameManager().isStunned(player.getUniqueId())) {
            event.setTo(event.getFrom());
            return;
        }
        // 跳跃疲劳（腿软）：这段时间内不许起跳，人人都一样，不会因为身上带加速就多跳
        if (plugin.getHunterGameManager().isJumpFatigued(player.getUniqueId())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to != null && to.getY() - from.getY() > 0.02) {
                event.setTo(new Location(from.getWorld(), to.getX(), from.getY(), to.getZ(),
                    to.getYaw(), to.getPitch()));
                return;
            }
        }
        // 母体被定住（准备阶段 / 传送硬直）：一步都不许走
        if (plugin.getHunterGameManager().isPlayerMother(player.getUniqueId())
            && plugin.getHunterGameManager().isMotherFrozen()) {
            event.setTo(event.getFrom());
            return;
        }

        // 观战席 / 已被淘汰的人：只能在猎场 X/Z 范围（外扩 margin 格）内活动
        Location clamped = plugin.getHunterGameManager()
            .clampSpectator(player, event.getFrom(), event.getTo());
        if (clamped != null) {
            event.setTo(clamped);
            return;
        }

        Location locked = plugin.getHunterGameManager().getDownedLocation(player.getUniqueId());
        if (locked == null) return;

        Location to = event.getTo();
        if (to == null) return;
        if (to.getWorld() != locked.getWorld() || to.distanceSquared(locked) > 0.04) {
            event.setTo(locked);
        }
    }

    /** 敲道具箱：GHG 里唯一允许破坏的方块 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getModeManager().isHunter()) return;

        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();

        // 被定身（眩晕药水 / 传送硬直）的母体：连箱子都不能敲 —— 这才叫「无法互动」
        if (plugin.getHunterGameManager().isPlayerMother(player.getUniqueId())
            && plugin.getHunterGameManager().isMotherFrozen()) {
            event.setCancelled(true);
            plugin.getLanguageManager().sendMessage(player, "hunter.mother.frozen");
            return;
        }

        // 母体砸法阵的红石块 → 该法阵加固（人类在加固期内破不了）
        if (plugin.getHunterGameManager().tryHardenAltar(player, location)) {
            event.setCancelled(true);
            return;
        }

        if (plugin.getHunterGameManager().handleChestHit(player, location)) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        player.sendMessage(plugin.getLanguageManager().getMessage(player, "hunter.chest.not-chest"));
    }

    /**
     * 跳跃检测 → 交给跳跃疲劳。
     *
     * <p>不用 PlayerJumpEvent：那个在部分服务端没有。这里认「脚下一格是实心、脚下这格是空的、
     * 且 Y 在上升」这一帧，正好是离地的那一下。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveDetectJump(PlayerMoveEvent event) {
        if (!plugin.getModeManager().isHunter()) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        if (to.getY() - from.getY() < 0.05) return;

        Player player = event.getPlayer();
        if (player.isFlying() || player.isGliding()) return;

        if (!onGround(from) || onGround(to)) return;
        plugin.getHunterGameManager().registerJump(player);
    }

    /** 脚下是实心方块、自己这一格站得下 */
    private boolean onGround(Location location) {
        if (location == null || location.getWorld() == null) return false;
        Block feet = location.getBlock();
        Block below = feet.getRelative(BlockFace.DOWN);
        return below.getType().isSolid() && !feet.getType().isSolid();
    }

    /** 对局中所有玩家禁止疾跑（含玩家母体） */
    @EventHandler(priority = EventPriority.HIGH)
    public void onToggleSprint(PlayerToggleSprintEvent event) {
        if (!plugin.getModeManager().isHunter()) return;
        if (!plugin.getConfigManager().isHunterNoSprint()) return;
        if (!event.isSprinting()) return;

        Player player = event.getPlayer();
        if (!plugin.getHunterGameManager().getAllParticipants().contains(player)) return;

        event.setCancelled(true);
        try {
            player.setSprinting(false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 母体伤害一律拦掉，改由 HunterGameManager 的「击倒计数」结算；
     * GHG 里逃生者之间也不打伤害。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!plugin.getModeManager().isHunter()) return;
        if (!(event.getEntity() instanceof Player)) return;

        Player victim = (Player) event.getEntity();
        Entity damager = event.getDamager();
        HunterGameManager game = plugin.getHunterGameManager();

        // 玩家母体不吃伤害
        if (game.isPlayerMother(victim.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        // AI 母体的伤害一律拦掉，改由击倒计数结算
        Entity mother = game.getMother();
        if (mother != null && damager.getUniqueId().equals(mother.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (damager instanceof Player) {
            // 玩家母体打逃生者 → 走同一套击倒计数与冷却
            if (game.isPlayerMother(damager.getUniqueId())) {
                event.setCancelled(true);
                Player motherPlayer = (Player) damager;
                // 必须是「拿着战斧 + 原版攻击条走完」才算一次砍击，
                // 不然左键连点就等于无限攻速
                if (!game.canMotherMeleeNow(motherPlayer)) return;
                game.registerPlayerMotherHit(motherPlayer, victim);
                return;
            }
            // 逃生者互殴一律拦掉
            event.setCancelled(true);
        }
    }

    /** 母体打测试假人：只计次数 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDummyDamaged(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!plugin.getModeManager().isHunter()) return;
        HunterGameManager game = plugin.getHunterGameManager();
        if (!game.isTestDummy(event.getEntity())) return;

        event.setCancelled(true);
        if (!(event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent)) return;
        Entity damager = ((org.bukkit.event.entity.EntityDamageByEntityEvent) event).getDamager();
        if (damager instanceof Player && game.isPlayerMother(damager.getUniqueId())) {
            Player mother = (Player) damager;
            if (!game.canMotherMeleeNow(mother)) return;
            game.hitTestDummy(event.getEntity(), mother);
        }
    }

    /** 玩家母体：**拿着技能道具**右键释放 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMotherInteract(PlayerInteractEvent event) {
        if (!plugin.getModeManager().isHunter()) return;

        Player player = event.getPlayer();
        if (!plugin.getHunterGameManager().isPlayerMother(player.getUniqueId())) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        // 技能绑定在专门的道具上：空手或拿着别的东西右键不该放技能
        if (!plugin.getHunterGameManager().isMotherSkillItem(event.getItem())) {
            plugin.getLanguageManager().sendMessage(player, "ghg.mother.need-skill-item");
            return;
        }

        event.setCancelled(true);
        if (plugin.getHunterGameManager().isMotherFrozen()) {
            plugin.getLanguageManager().sendMessage(player, "hunter.mother.frozen");
            return;
        }
        plugin.getHunterGameManager().castMotherSkill(player, null);
    }

    /** 炼金者弩箭命中：在原地炸出一瓶喷溅减速药水（比射手弱一档） */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMotherBoltHit(ProjectileHitEvent event) {
        if (!plugin.getModeManager().isHunter()) return;
        if (!event.getEntity().hasMetadata(MotherSkills.BOLT_META)) return;

        Location hit = event.getEntity().getLocation();
        event.getEntity().remove();

        double radius = plugin.getConfigManager().getHunterSkillPotionRadius();
        int seconds = plugin.getConfigManager().getHunterSkillPotionSlowSeconds();
        int level = plugin.getConfigManager().getHunterSkillPotionSlowLevel();
        HunterGameManager game = plugin.getHunterGameManager();

        try {
            hit.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, hit.clone().add(0, 0.4, 0),
                30, radius * 0.6, 0.25, radius * 0.6);
        } catch (Throwable ignored) {
        }

        for (Player player : game.getSurvivors()) {
            if (game.isDowned(player.getUniqueId())) continue;
            if (player.getWorld() != hit.getWorld()) continue;
            if (player.getLocation().distance(hit) > radius) continue;
            game.applySlow(player, seconds, level);
            plugin.getLanguageManager().sendMessage(player, "hunter.skill.bolt-hit", seconds);
        }

        // 母体测试局：溅到假人也算命中
        for (Entity entity : game.skillTargets(hit, radius)) {
            if (!(entity instanceof Player)) game.hitTestDummy(entity, null, false);
        }
    }

    /** 骨矢命中：减速，箭本身销毁（不靠原版伤害） */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMotherArrowHit(ProjectileHitEvent event) {
        if (!plugin.getModeManager().isHunter()) return;
        if (!event.getEntity().hasMetadata(MotherSkills.ARROW_META)) return;

        HunterGameManager game = plugin.getHunterGameManager();
        if (event.getHitEntity() instanceof Player) {
            Player target = (Player) event.getHitEntity();
            if (game.getSurvivors().contains(target)) {
                // 射手是「攻击型」：命中就是正常受击一次（计入击倒次数），**不给任何减速**
                game.registerMotherHit(target);
                plugin.getLanguageManager().sendMessage(target, "hunter.skill.arrow-hit");
            }
        } else if (game.isTestDummy(event.getHitEntity())) {
            // 母体测试局：射中假人也算一次命中，不然射手技能看起来像没生效
            game.hitTestDummy(event.getHitEntity(), null);
        }
        event.getEntity().remove();
    }

    /**
     * 血量只是**状态指示器**（健康 10 心 / 受伤 3 心 / 倒地 1 心），任何来源的伤害都拦掉。
     *
     * <p>受伤时血量被钳到 3 颗心，如果此时还让环境/野怪真打进来，玩家会直接被打死 ——
     * 而 GHG 的淘汰只有「倒地放血 / 狂欢之椅」两条路。母体的攻击本来也在
     * {@link #onEntityDamageByEntity} 里换成击倒计数了，所以这里一律取消是安全的。</p>
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!plugin.getModeManager().isHunter()) return;
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();
        if (plugin.getPlayerManager().getAllPlayers().contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** 掉线：清掉击倒记录（背包由 PlayerListener 统一还原） */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getModeManager().isHunter()) return;
        boolean wasMother = plugin.getHunterGameManager().isPlayerMother(event.getPlayer().getUniqueId());
        plugin.getHunterGameManager().handleQuit(event.getPlayer());
        // 玩家母体掉线，这局就没意义了，直接收尾
        if (wasMother) {
            plugin.getHunterGameManager().forceStop();
        }
    }
}
