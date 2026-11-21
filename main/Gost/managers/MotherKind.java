package io.Sriptirc_wp_1258.gost.managers;

import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 母体种类。**技能因种类而异**，一个种类带一个技能，外观也随种类走。
 *
 * <p>{@code hunter.mother.kind} 填 {@code RANDOM} 时，AI 母体每局从
 * {@code hunter.mother.enabled-kinds} 里随机抽一个；玩家母体在准备阶段自己选。</p>
 */
public enum MotherKind {

    /** 疾行者：短时间加速 */
    SPRINTER("sprint", EntityType.ZOMBIE),

    /** 冲撞者：朝面向方向瞬时冲刺 */
    CHARGER("dash", EntityType.HUSK),

    /** 射手：发射骨矢，命中减速 */
    ARCHER("arrow", EntityType.SKELETON),

    /** 炼金者：弩箭射出，命中位置炸出喷溅减速药水 */
    ALCHEMIST("bolt", EntityType.WITCH),

    /** 穿隙者：传送到目标身边，但自己会硬直 */
    WALKER("teleport", EntityType.ENDERMAN),

    /** 窥视者：隐身 + 加速，打中人后加速立刻消失 */
    WATCHER("vanish", EntityType.STRAY),

    /** 缚网者：以自身为中心向四周展开减速网 */
    WEAVER("snare", EntityType.SPIDER),

    /** 珍珠女士：母体版传送珍珠，闪过去之后自己会慢一会儿，打中人立刻解除 */
    PEARL_LADY("pearl", EntityType.DROWNED);

    private final String skillId;
    private final EntityType defaultEntityType;

    MotherKind(String skillId, EntityType defaultEntityType) {
        this.skillId = skillId;
        this.defaultEntityType = defaultEntityType;
    }

    public String getSkillId() {
        return skillId;
    }

    public EntityType getDefaultEntityType() {
        return defaultEntityType;
    }

    /** 种类显示名 key */
    public String nameKey() {
        return "hunter.mother.kind." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * 种类定位（一句话说明这个种类是干什么的），菜单里当副标题用。
     *
     * <p>⚠️ 不能写成 {@code nameKey() + ".desc"}：Bukkit 把 `.` 当路径分隔符，
     * 同一个键既当叶子又当父级时后者会把前者覆盖掉（`hunter.mother.kind.sprinter`
     * 会因为它下面的 `.desc` 而消失，日志刷「找不到语言消息」）。所以子键放在兄弟命名空间。</p>
     */
    public String descKey() {
        return "hunter.mother.kind.desc." + name().toLowerCase(Locale.ROOT);
    }

    /** 种类对应技能的显示名 key */
    public String skillNameKey() {
        return "hunter.skill.name." + skillId;
    }

    /** 技能详细说明 key（多行，菜单里逐行铺开） */
    public String skillDescKey() {
        return "hunter.skill." + skillId + ".desc";
    }

    /** 宽容解析：认不出来返回 null（由调用方决定回退） */
    public static MotherKind resolveOrNull(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 宽容解析，认不出来回退 BRUTE 位（噬体已移除时回退第一个） */
    public static MotherKind resolve(String name) {
        MotherKind kind = resolveOrNull(name);
        return kind != null ? kind : values()[0];
    }

    /** 从配置的启用列表里解析，全都不合法时返回全部种类 */
    public static List<MotherKind> resolveEnabled(List<String> names) {
        List<MotherKind> result = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                MotherKind kind = resolveOrNull(name);
                if (kind != null && !result.contains(kind)) result.add(kind);
            }
        }
        if (result.isEmpty()) {
            for (MotherKind kind : values()) result.add(kind);
        }
        return result;
    }
}
