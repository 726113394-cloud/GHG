package io.Sriptirc_wp_1258.gost.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 逃生者职业。
 *
 * <p>每个职业带一件**技能道具**（直接引用常驻道具，不重复造物品）：冰球手给凝冰球、
 * 守护者给救赎护符、突击手给冲刺矛；工程师不给道具，而是能在猎场里放**木栅栏**。
 * 技能道具不可叠加（包里只留一件），而且**只有在用掉之后才开始计时刷新**。</p>
 *
 * <p>原来的三个被动（破阵快 / 救人快 / 心跳远）没有丢，分别并入工程师 / 守护者 / 突击手，
 * 这样职业选择同时决定「主动技能」和「被动倾向」。</p>
 */
public enum HunterRole {

    /** 冰球手：凝冰球，砸中母体让她眩晕并松手 */
    ICER("icer", ItemIds.ICE_BALL, 1.0, 1.0, 1.0),

    /** 守护者：救赎护符，救人读条加速（被动：救人治疗更快） */
    GUARDIAN("guardian", ItemIds.REDEMPTION_CHARM, 1.0, 1.3, 1.0),

    /** 突击手：冲刺矛，短距突进（被动：心跳听得更远） */
    LANCER("lancer", ItemIds.SPEAR_RUSH, 1.0, 1.0, 1.5),

    /** 工程师：不放道具，改为放置木栅栏（被动：破阵更快） */
    ENGINEER("engineer", ItemIds.FENCE_KIT, 1.25, 1.0, 1.0),

    /**
     * 跑者：肾上腺素。**GHG 版的肾上腺素是弱化过的**——GHG 里玩家不能疾跑、母体只有 0.21，
     * 原版那种「速度Ⅱ 10 秒」等于直接甩开母体，所以改成短时速度Ⅰ + 结束后一段后遗症。
     */
    RUNNER("runner", ItemIds.ADRENALINE, 1.0, 1.0, 1.0),

    /**
     * 黑衣人：隐身。**母体的被动透视照样能看到轮廓**（MC 里发光会让隐身的人显形），
     * 所以这是「避开视线」而不是「无解消失」——只要不让她站定 3 秒就行。
     */
    WRAITH("wraith", ItemIds.SHADOW_CLOAK, 1.0, 1.0, 1.0);

    private final String id;
    private final String skillItemId;
    private final double chargeWeight;
    private final double actionSpeed;
    private final double heartbeatMultiplier;

    HunterRole(String id, String skillItemId,
               double chargeWeight, double actionSpeed, double heartbeatMultiplier) {
        this.id = id;
        this.skillItemId = skillItemId;
        this.chargeWeight = chargeWeight;
        this.actionSpeed = actionSpeed;
        this.heartbeatMultiplier = heartbeatMultiplier;
    }

    public String id() { return id; }

    /** 职业自带的技能道具（工程师是栅栏工具） */
    public String skillItemId() { return skillItemId; }

    public double chargeWeight() { return chargeWeight; }
    public double actionSpeed() { return actionSpeed; }
    public double heartbeatMultiplier() { return heartbeatMultiplier; }

    public String nameKey() { return "hunter.role." + id; }

    /* ⚠️ 下面三个子键刻意放在**兄弟命名空间**（hunter.role.desc.<id> 而不是 hunter.role.<id>.desc）：
       Bukkit 的 YamlConfiguration 把 `.` 当路径分隔符，同一个 path 既当叶子又当父级时，
       后写入的会把前一个覆盖 —— 写成 hunter.role.icer.desc 会让 hunter.role.icer（名字）直接消失。 */
    public String descKey() { return "hunter.role.desc." + id; }

    /** 技能效果的详细说明（多行，菜单里逐行铺开） */
    public String effectKey() { return "hunter.role.effect." + id; }

    /** 被动说明 */
    public String passiveKey() { return "hunter.role.passive." + id; }

    /** 宽恕解析：认不出来就抽第一个（冰球手） */
    public static HunterRole resolve(String name) {
        HunterRole role = resolveOrNull(name);
        return role != null ? role : values()[0];
    }

    public static HunterRole resolveOrNull(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 配置里启用的职业列表；全都不合法时返回全部 */
    public static List<HunterRole> resolveEnabled(List<String> names) {
        List<HunterRole> result = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                HunterRole role = resolveOrNull(name);
                if (role != null && !result.contains(role)) result.add(role);
            }
        }
        if (result.isEmpty()) {
            for (HunterRole role : values()) result.add(role);
        }
        return result;
    }
}
