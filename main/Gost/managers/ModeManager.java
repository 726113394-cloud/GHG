package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;

/**
 * 对局模式上下文。
 *
 * <p>同一时刻只会跑一种玩法：CLASSIC 与 HUNTER 不能并存，否则背包规则、
 * 方块破坏、感染判定会互相踩。所以进入模式是「抢占式」的，退出一律走
 * {@link #leave(MatchMode)}，只有当前模式确实是它才回到 {@link MatchMode#IDLE}。</p>
 */
public class ModeManager {

    private final GHG plugin;
    private volatile MatchMode mode = MatchMode.IDLE;

    public ModeManager(GHG plugin) {
        this.plugin = plugin;
    }

    public MatchMode getMode() {
        return mode;
    }

    public boolean is(MatchMode expected) {
        return mode == expected;
    }

    public boolean isIdle() {
        return mode == MatchMode.IDLE;
    }

    public boolean isClassic() {
        return mode == MatchMode.CLASSIC;
    }

    public boolean isHunter() {
        return mode == MatchMode.HUNTER;
    }

    /** 是否有对局在跑（任一模式） */
    public boolean isMatchRunning() {
        return mode != MatchMode.IDLE;
    }

    /**
     * 进入某个模式。
     *
     * @return false 表示已经有另一种玩法在跑，本次不许进入
     */
    public synchronized boolean enter(MatchMode target) {
        if (target == null || target == MatchMode.IDLE) return false;
        if (mode != MatchMode.IDLE && mode != target) {
            plugin.getLogger().warning("拒绝进入 " + target + " 模式：当前 " + mode + " 仍在进行");
            return false;
        }
        if (mode == target) return true;
        mode = target;
        plugin.getLogger().info("对局模式切换: IDLE → " + target);
        return true;
    }

    /**
     * 退出模式。只有当当前模式确实是 {@code expected} 时才回到 IDLE，
     * 避免旧玩法的收尾流程把新玩法的状态清掉。
     */
    public synchronized void leave(MatchMode expected) {
        if (expected == null || mode != expected) return;
        mode = MatchMode.IDLE;
        plugin.getLogger().info("对局模式切换: " + expected + " → IDLE");
    }

    /** 强制回到空闲（插件禁用时用） */
    public synchronized void reset() {
        mode = MatchMode.IDLE;
    }

    /** 自检用的可读描述 */
    public String describe() {
        return mode.name();
    }
}
