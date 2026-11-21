package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 道具效果注册表并把冷却收在一处。
 *
 * <p>这是 GHG 复用 Gost 道具的唯一入口：一切「用一件道具」的行为都从这里走，
 * 不再散在监听器的 if-else 里。识别道具靠 {@link ItemIds} 的 PDC 稳定 ID。</p>
 */
public class ItemEffectRegistry {

    /** 一次使用的结果 */
    public enum Result {
        /** 不是本插件的自定义道具 */
        NOT_CUSTOM,
        /** 没有为这个 ID 注册效果 */
        NO_EFFECT,
        /** 当前点击方式不触发本效果 */
        IGNORED_CLICK,
        /** 冷却中，已提示玩家 */
        ON_COOLDOWN,
        /** 已执行 */
        APPLIED
    }

    /**
     * 使用结果 + 事件该怎么处理。
     *
     * <p>只要认出了是本插件的道具，默认就该拦截原版行为（否则药水会被真喝掉、
     * 钟会被真摇），只有投掷类效果会显式放行。</p>
     */
    public record Outcome(Result result, boolean cancelVanilla) {
        static Outcome of(Result result) {
            return new Outcome(result, result != Result.NOT_CUSTOM);
        }
    }

    private final GHG plugin;
    private final Map<String, ItemEffect> effects = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public ItemEffectRegistry(GHG plugin) {
        this.plugin = plugin;
    }

    public void register(ItemEffect effect) {
        if (effect == null || effect.itemId() == null) return;
        effects.put(effect.itemId(), effect);
    }

    public ItemEffect get(String itemId) {
        return itemId == null ? null : effects.get(itemId);
    }

    public boolean hasEffect(String itemId) {
        return itemId != null && effects.containsKey(itemId);
    }

    public Map<String, ItemEffect> registered() {
        return java.util.Collections.unmodifiableMap(effects);
    }

    /**
     * 使用一件道具（右键/左键的统一点）。
     *
     * <p>调用方负责：已经是自定义道具才调这里；根据返回结果决定是否取消原版事件。</p>
     */
    public Outcome use(Player player, ItemStack item, boolean rightClick, boolean leftClick) {
        String id = ItemIds.idOf(item);
        if (id == null) return Outcome.of(Result.NOT_CUSTOM);

        ItemEffect effect = effects.get(id);
        if (effect == null) {
            // 没注册效果的道具（比如应急口粮走的是原版进食）不要拦原版行为，
            // 否则面包右键会被吞掉、PlayerItemConsumeEvent 永远不触发 —— 就是「吃不了」
            return new Outcome(Result.NO_EFFECT, false);
        }

        if (!effect.accepts(rightClick, leftClick)) return Outcome.of(Result.IGNORED_CLICK);

        if (isOnCooldown(player, id)) {
            int remaining = getRemainingCooldown(player, id);
            plugin.getLanguageManager().sendMessage(player, "item." + id + "-cooldown", remaining);
            return Outcome.of(Result.ON_COOLDOWN);
        }

        ItemUseContext ctx = new ItemUseContext(plugin, player, item, rightClick, leftClick);
        boolean startCooldown = effect.apply(player, ctx);

        if (plugin.getHunterStatsManager() != null) {
            plugin.getHunterStatsManager().countItemUsed(id);
        }

        int seconds = effect.cooldownSeconds();
        if (startCooldown && seconds > 0) {
            setCooldown(player, id, seconds);
        }
        return new Outcome(Result.APPLIED, ctx.shouldCancelVanilla());
    }

    // ------------------------------------------------------------ 冷却

    public boolean isOnCooldown(Player player, String itemId) {
        Map<String, Long> perPlayer = cooldowns.get(player.getUniqueId());
        if (perPlayer == null) return false;
        Long end = perPlayer.get(itemId);
        return end != null && System.currentTimeMillis() < end;
    }

    /** 剩余冷却秒数（向上取整） */
    public int getRemainingCooldown(Player player, String itemId) {
        Map<String, Long> perPlayer = cooldowns.get(player.getUniqueId());
        if (perPlayer == null) return 0;
        Long end = perPlayer.get(itemId);
        if (end == null) return 0;
        long remaining = end - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (int) Math.ceil(remaining / 1000.0);
    }

    public void setCooldown(Player player, String itemId, int seconds) {
        if (seconds <= 0) return;
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
            .put(itemId, System.currentTimeMillis() + seconds * 1000L);
    }

    public void clearPlayerCooldowns(UUID playerId) {
        cooldowns.remove(playerId);
    }

    public void clearAllCooldowns() {
        cooldowns.clear();
    }
}
