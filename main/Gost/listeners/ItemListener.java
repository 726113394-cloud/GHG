package io.Sriptirc_wp_1258.gost.listeners;

import io.Sriptirc_wp_1258.gost.GHG;
import io.Sriptirc_wp_1258.gost.managers.ItemEffects;
import io.Sriptirc_wp_1258.gost.managers.ItemEffectRegistry;
import io.Sriptirc_wp_1258.gost.managers.ItemIds;
import io.Sriptirc_wp_1258.gost.managers.SoundCompat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 道具使用的事件入口。
 *
 * <p>这里只做事件分发：认出是本插件的道具、判左右键，然后全部交给
 * {@link ItemEffectRegistry}。道具识别走 PDC 稳定 ID（{@link ItemIds}），
 * 不再比对中文显示名。</p>
 */
public class ItemListener implements Listener {

    private final GHG plugin;

    public ItemListener(GHG plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getPlayerManager().getAllPlayers().contains(player.getUniqueId())) {
            return;
        }

        // 击倒期间动弹不得，也不能用道具
        if (plugin.getHunterGameManager() != null
            && plugin.getHunterGameManager().isDowned(player.getUniqueId())) {
            return;
        }

        // 被定身的母体（眩晕药水 / 传送硬直）：任何道具都用不了
        if (plugin.getModeManager().isHunter()
            && plugin.getHunterGameManager().isPlayerMother(player.getUniqueId())
            && plugin.getHunterGameManager().isMotherFrozen()) {
            return;
        }

        Action action = event.getAction();
        boolean isRightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean isLeftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        if (!isRightClick && !isLeftClick) {
            return;
        }

        ItemStack item = event.getItem();
        if (!ItemIds.isCustom(item)) {
            return;
        }

        /* 准备阶段只能用「选择面板」：技能道具、消耗品一律锁上。
           以前这段时间能直接喝药水、扔凝冰球、放技能，等于开打前就能消耗掉对局资源。 */
        if (plugin.getModeManager().isHunter()
            && plugin.getHunterGameManager().isPreparing()
            && !ItemIds.is(item, ItemIds.MENU_CLOCK)) {
            plugin.getLanguageManager().sendMessage(player, "hunter.prep.item-blocked");
            event.setCancelled(true);
            return;
        }

        // 面包 / 臭牛排：饱食度满时原版吃不动、也不会触发 consume 事件，这里补一句提示
        boolean fullFood = isRightClick && player.getFoodLevel() >= 20;
        if (fullFood && ItemIds.is(item, ItemIds.RATION)) {
            plugin.getLanguageManager().sendMessage(player, "hunter.item.ration-full");
            event.setCancelled(true);
            return;
        }
        if (fullFood && ItemIds.is(item, ItemIds.STINKY_STEAK)) {
            plugin.getLanguageManager().sendMessage(player, "hunter.item.steak-full");
            event.setCancelled(true);
            return;
        }

        ItemEffectRegistry.Outcome outcome =
            plugin.getItemEffectRegistry().use(player, item, isRightClick, isLeftClick);
        if (outcome.result() == ItemEffectRegistry.Result.APPLIED) {
            // 职业技能道具被用掉了：从现在开始计时补发（没这件就什么都不做）
            plugin.getHunterGameManager().onRoleItemUsed(player, ItemIds.idOf(item));
        }
        if (outcome.cancelVanilla()) {
            event.setCancelled(true);
        }
    }

    /**
     * 应急口粮的「真进食」：等原版把面包嚼完（PlayerItemConsumeEvent 才触发），
     * 再按配置结算饱食度与剩余口数。
     *
     * <p>原来是在右键那一刻直接用效果补饱食度，没有进食动作；现在取消原版消耗，
     * 药水值由我们自己给，口数记在 PDC 上。</p>
     */
    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        boolean isRation = ItemIds.is(item, ItemIds.RATION);
        boolean isSteak = ItemIds.is(item, ItemIds.STINKY_STEAK);
        if (!isRation && !isSteak) return;
        if (!plugin.getPlayerManager().getAllPlayers().contains(player.getUniqueId())) return;

        // 准备阶段不让吃东西（食物也属于「道具」，而且这段时间饱食度本来就被锁满）
        if (plugin.getModeManager().isHunter() && plugin.getHunterGameManager().isPreparing()) {
            event.setCancelled(true);
            plugin.getLanguageManager().sendMessage(player, "hunter.prep.item-blocked");
            return;
        }

        /* 臭牛排：原版熟牛肉会回 8 点饱食度，这块肉的定位是「拿发光换速度」，
           所以拦掉原版那一口，效果自己结算（不补饱食度）。 */
        if (isSteak) {
            event.setCancelled(true);
            /* 「不多但有」的饱食度：这块肉的定位是拿发光换速度，
               完全不补吃的会让它连「垫一口」都做不到。 */
            int steakFood = plugin.getConfigManager().getStinkySteakRestoreFood();
            if (steakFood > 0) {
                player.setFoodLevel(Math.min(20, player.getFoodLevel() + steakFood));
                player.setSaturation((float) Math.min(20.0,
                    player.getSaturation() + plugin.getConfigManager().getStinkySteakRestoreSaturation()));
            }
            ItemEffects.giveStinkySteakBuff(plugin, player);
            item.setAmount(item.getAmount() - 1);
            player.getInventory().setItemInMainHand(
                item.getAmount() <= 0 ? new ItemStack(Material.AIR) : item);
            player.updateInventory();
            return;
        }

        // 取消原版消耗：不然面包自带的 5/6 会和我们的配置叠成两份
        event.setCancelled(true);

        int bites = ItemIds.getBites(item);
        int fullBites = Math.max(1, plugin.getConfigManager().getHunterRationBites());
        if (bites <= 0) bites = fullBites;
        int food = plugin.getConfigManager().getHunterRationRestoreFood();
        double saturation = plugin.getConfigManager().getHunterRationRestoreSaturation();

        player.setFoodLevel(Math.min(20, player.getFoodLevel() + food));
        player.setSaturation((float) Math.min(20.0, player.getSaturation() + saturation));
        player.playSound(player.getLocation(), SoundCompat.genericEat(), 1.0f, 1.1f);

        /* 口粮可以叠成一摞：口数记在整摞的 PDC 上，一份吃完就把口数重置回满，
           所以「一摞 3 份 × 每份 3 口」是 9 口，而不是共用 3 口。 */
        if (bites > 1) {
            plugin.getItemManager().consumeRationBite(item, bites - 1);
            player.getInventory().setItemInMainHand(item);
            plugin.getLanguageManager().sendMessage(player, "hunter.item.ration-left", food, bites - 1);
        } else if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            plugin.getItemManager().consumeRationBite(item, fullBites);
            player.getInventory().setItemInMainHand(item);
            plugin.getLanguageManager().sendMessage(player, "hunter.item.ration-left", food, fullBites);
        } else {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            plugin.getLanguageManager().sendMessage(player, "hunter.item.ration-used", food);
        }
        player.updateInventory();
    }

    /**
     * 眩晕药水命中：喷溅药水由原版投出，这里接管命中结算。
     *
     * <p>判定直接读药水物品的 PDC ID（{@link ItemIds#idOf}），不需要给实体打标记，
     * 也不会和凝冰球（雪球）那条路撞车。命中母体就定身，没砸到就只算浪费一瓶。</p>
     */
    @EventHandler
    public void onPotionSplash(org.bukkit.event.entity.PotionSplashEvent event) {
        org.bukkit.entity.ThrownPotion potion = event.getPotion();
        if (!ItemIds.is(potion.getItem(), ItemIds.STUN_POTION)) return;

        // 这件道具不吃原版药水效果：全部由我们自己结算
        event.setCancelled(true);
        if (!(potion.getShooter() instanceof Player)) return;
        Player shooter = (Player) potion.getShooter();
        if (!plugin.getPlayerManager().getAllPlayers().contains(shooter.getUniqueId())) return;

        Location hit = potion.getLocation();
        int ticks = plugin.getConfigManager().getHunterStunPotionTicks();
        double radius = plugin.getConfigManager().getHunterStunPotionRadius();

        try {
            hit.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, hit.clone().add(0, 0.4, 0),
                26, radius * 0.5, 0.25, radius * 0.5);
            hit.getWorld().playSound(hit, SoundCompat.playerSplash(), 1.0f, 0.8f);
        } catch (Throwable ignored) {
        }

        if (plugin.getHunterGameManager().stunMotherEntity(hit, radius, ticks)) {
            plugin.getLanguageManager().sendMessage(shooter, "hunter.item.stun-hit");
        } else {
            plugin.getLanguageManager().sendMessage(shooter, "hunter.item.stun-miss");
        }
    }

    /** 凝冰球命中：雪球飞行由原版处理，命中减速在这里结算 */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)) {
            return;
        }
        Snowball snowball = (Snowball) event.getEntity();
        if (!(snowball.getShooter() instanceof Player)) {
            return;
        }
        Player shooter = (Player) snowball.getShooter();
        if (!plugin.getPlayerManager().getAllPlayers().contains(shooter.getUniqueId())) {
            return;
        }
        // GHG：命中 AI 母体也要生效，否则这道具在 PVE 里完全没用
        if (plugin.getModeManager().isHunter()
            && plugin.getHunterGameManager().isMotherEntity(event.getHitEntity())) {
            plugin.getLanguageManager().sendMessage(shooter, "hunter.item.freeze-mother");
            plugin.getLanguageManager().sendMessage(shooter, "hunter.item.hit-mother");
            plugin.getHunterGameManager().freezeEnemyMother(
                plugin.getConfigManager().getHunterIceBallMotherStunSeconds());
            return;
        }

        if (!(event.getHitEntity() instanceof Player)) {
            return;
        }

        Player target = (Player) event.getHitEntity();
        plugin.getItemManager().applyIceBallEffect(target);
        plugin.getLanguageManager().sendMessage(shooter, "item.freeze-ball-hit", target.getName());
        target.sendTitle(
            plugin.getLanguageManager().getMessage(target, "item.frozen-title"),
            plugin.getLanguageManager().getMessage(target, "item.frozen-subtitle"), 10, 30, 10);
    }

    /** 玩家离场时清掉他的道具冷却 */
    public void clearPlayerCooldowns(UUID playerId) {
        plugin.getItemEffectRegistry().clearPlayerCooldowns(playerId);
    }

    public void clearAllCooldowns() {
        plugin.getItemEffectRegistry().clearAllCooldowns();
    }
}
