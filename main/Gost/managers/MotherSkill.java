package io.Sriptirc_wp_1258.gost.managers;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 母体技能。
 *
 * <p>施法载体刻意用 {@link Entity} 而不是 {@code Mob} 或 {@code Player}：
 * AI 母体和玩家母体共用同一套实现，只换「谁来放」，调试时能真人验证手感。</p>
 */
public interface MotherSkill {

    /** 技能 ID，与 {@link MotherKind#getSkillId()} 对上 */
    String id();

    /** 冷却秒数（从配置读；0 表示不主动释放） */
    int cooldownSeconds(HunterGameManager game);

    /** 作用距离；0 表示不限 */
    double range(HunterGameManager game);

    /** 是否必须有目标才能放 */
    default boolean requiresTarget() {
        return true;
    }

    /**
     * 被动技能：不靠释放，而是由 {@link #tick} 持续生效。
     * 视野（站着不动时透视）就是这种。
     */
    default boolean passive() {
        return false;
    }

    /** 被动技能每秒调用一次（主动技能不会走到这里） */
    default void tick(HunterGameManager game, Entity caster, Location motherLocation) {
    }

    /**
     * 释放技能。
     *
     * <p>目标刻意是 {@link Entity} 而不是 {@link Player}：母体测试局的「猎物」是
     * 测试假人（villager），如果只认玩家，那些技能在测试局里全是「没目标」——右键等于没反应。</p>
     *
     * @return true 表示真的放出来了（进入冷却）
     */
    boolean cast(HunterGameManager game, Entity caster, Entity target);
}
