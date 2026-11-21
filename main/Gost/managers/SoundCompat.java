package io.Sriptirc_wp_1258.gost.managers;

import org.bukkit.Sound;

/**
 * 音效兼容工具类
 * 通过 Sound.valueOf() 运行时解析，避免 Java 枚举编译期内联导致的低版本 NoSuchFieldError
 * 兼容 1.20.x 与 1.21.x 全版本音效名称差异
 */
public final class SoundCompat {

    private SoundCompat() {}

    private static Sound resolve(String primary, String... fallbacks) {
        try {
            return Sound.valueOf(primary);
        } catch (IllegalArgumentException e) {
            for (String fb : fallbacks) {
                try {
                    return Sound.valueOf(fb);
                } catch (IllegalArgumentException ignore) {}
            }
        }
        return null;
    }

    /** 传送音效（ENDERMAN/ENDERMEN 版本差异） */
    public static Sound endermanTeleport() {
        Sound s = resolve("ENTITY_ENDERMAN_TELEPORT", "ENTITY_ENDERMEN_TELEPORT");
        return s != null ? s : Sound.ENTITY_ENDERMAN_TELEPORT;
    }

    /** 凋零骷髅死亡（1.21.x 新枚举，1.20.x 无，用凋零死亡替代） */
    public static Sound witherSkeletonDeath() {
        Sound s = resolve("ENTITY_WITHER_SKELETON_DEATH");
        return s != null ? s : Sound.ENTITY_WITHER_DEATH;
    }

    /** 凋零射击 */
    public static Sound witherShoot() {
        Sound s = resolve("ENTITY_WITHER_SHOOT");
        return s != null ? s : Sound.ENTITY_WITHER_DEATH;
    }

    /** 玩家受伤 */
    public static Sound playerHurt() {
        return resolve("ENTITY_PLAYER_HURT", "ENTITY_PLAYER_HURT");
    }

    /** 铁傀儡受伤 */
    public static Sound ironGolemDamage() {
        Sound s = resolve("ENTITY_IRON_GOLEM_DAMAGE");
        return s != null ? s : Sound.ENTITY_IRON_GOLEM_HURT;
    }

    /** 末影龙死亡 */
    public static Sound enderDragonDeath() {
        return resolve("ENTITY_ENDER_DRAGON_DEATH");
    }

    /** 末影龙低吼 */
    public static Sound enderDragonGrowl() {
        return resolve("ENTITY_ENDER_DRAGON_GROWL");
    }

    /** 末影龙振翅 */
    public static Sound enderDragonFlap() {
        return resolve("ENTITY_ENDER_DRAGON_FLAP");
    }

    /** 玩家升级 */
    public static Sound playerLevelup() {
        return resolve("ENTITY_PLAYER_LEVELUP");
    }

    /** 玩家攻击横扫 */
    public static Sound playerAttackSweep() {
        return resolve("ENTITY_PLAYER_ATTACK_SWEEP");
    }

    /** 玩家暴击 */
    public static Sound playerAttackCrit() {
        return resolve("ENTITY_PLAYER_ATTACK_CRIT");
    }

    /** 不死图腾使用 */
    public static Sound totemUse() {
        return resolve("ITEM_TOTEM_USE");
    }

    /** 挑战完成（胜利提示） */
    public static Sound toastChallengeComplete() {
        return resolve("UI_TOAST_CHALLENGE_COMPLETE");
    }

    /** 凋零死亡 */
    public static Sound witherDeath() {
        return resolve("ENTITY_WITHER_DEATH");
    }

    /**
     * 音符块音效（BELL/PLING/HAT）。
     *
     * <p>每个都带一串别名：1.13 之后是 {@code BLOCK_NOTE_BLOCK_*}，更早是 {@code BLOCK_NOTE_*} /
     * {@code NOTE_*}，某些分支版本还会换名字。原来只试一个名字，解析不到就静默返回 null，
     * 表现出来就是「一堆音效没有声音」。</p>
     */
    public static Sound noteBell() {
        return resolve("BLOCK_NOTE_BLOCK_BELL", "BLOCK_NOTE_BELL", "NOTE_BELL",
            "BLOCK_NOTE_BLOCK_CHIME", "ENTITY_EXPERIENCE_ORB_PICKUP");
    }

    public static Sound notePling() {
        return resolve("BLOCK_NOTE_BLOCK_PLING", "BLOCK_NOTE_PLING", "NOTE_PLING",
            "BLOCK_NOTE_BLOCK_BIT", "UI_BUTTON_CLICK", "CLICK");
    }

    public static Sound noteHat() {
        return resolve("BLOCK_NOTE_BLOCK_HAT", "BLOCK_NOTE_HAT", "NOTE_HAT",
            "BLOCK_NOTE_BLOCK_SNARE", "UI_BUTTON_CLICK", "CLICK");
    }

    /** 物品拾取 */
    public static Sound itemPickup() {
        return resolve("ENTITY_ITEM_PICKUP", "ITEM_PICKUP", "ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
    }

    /** 闪电 */
    public static Sound lightningThunder() { return resolve("ENTITY_LIGHTNING_BOLT_THUNDER"); }

    /** 幻术师施法 */
    public static Sound illusionerCast() { return resolve("ENTITY_ILLUSIONER_CAST_SPELL"); }

    /** 幻术师准备镜像 */
    public static Sound illusionerMirror() { return resolve("ENTITY_ILLUSIONER_PREPARE_MIRROR"); }

    /** 远古守卫者诅咒 */
    public static Sound elderCurse() { return resolve("ENTITY_ELDER_GUARDIAN_CURSE"); }

    /** 玩家泼水 */
    public static Sound playerSplash() { return resolve("ENTITY_PLAYER_SPLASH"); }

    /** 雪球投掷 */
    public static Sound snowballThrow() { return resolve("ENTITY_SNOWBALL_THROW"); }

    /** 吃食物 */
    public static Sound genericEat() { return resolve("ENTITY_GENERIC_EAT"); }

    /** 打嗝 */
    public static Sound playerBurp() { return resolve("ENTITY_PLAYER_BURP"); }

    /** 心跳声：1.19+ 有监守者心跳，低版本回退底鼓 */
    public static Sound heartbeat() {
        return resolve("ENTITY_WARDEN_HEARTBEAT", "BLOCK_NOTE_BLOCK_BASS", "BLOCK_NOTE_BASS", "NOTE_BASS");
    }

    /**
     * 自检用：把**所有**音效在这台服务端上解析一遍，返回「缺哪些」。
     *
     * <p>以前只抽查 8 个，漏掉的（比如整套绘制音、铁砧、潮涌）解析不到也没人发现 ——
     * 而那些音效在部分服务端分支上会被改名，解析失败的默认表现是**静默**。</p>
     *
     * @return 空列表 = 全部可用；否则每项形如 {@code 心跳(ENTITY_WARDEN_HEARTBEAT) }
     */
    public static java.util.List<String> describe() {
        java.util.List<String> broken = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Sound> e : catalog().entrySet()) {
            if (e.getValue() == null) broken.add(e.getKey());
        }
        return broken;
    }

    /** 音效总数（自检里报「N/M 可用」用） */
    public static int total() {
        return catalog().size();
    }

    /** 全部音效的「名字 → 解析结果」，顺序固定方便对照 */
    private static java.util.Map<String, Sound> catalog() {
        java.util.Map<String, Sound> map = new java.util.LinkedHashMap<>();
        map.put("心跳", heartbeat());
        map.put("钟声", noteBell());
        map.put("Pling", notePling());
        map.put("滴答", noteHat());
        map.put("拾取", itemPickup());
        map.put("升级", playerLevelup());
        map.put("龙吼", enderDragonGrowl());
        map.put("龙翼", enderDragonFlap());
        map.put("龙死", enderDragonDeath());
        map.put("爆炸", genericExplode());
        map.put("受击", playerHurt());
        map.put("横扫", playerAttackSweep());
        map.put("暴击", playerAttackCrit());
        map.put("图腾", totemUse());
        map.put("胜利提示", toastChallengeComplete());
        map.put("凋零死", witherDeath());
        map.put("凋零骷髅死", witherSkeletonDeath());
        map.put("凋零射击", witherShoot());
        map.put("铁傀儡", ironGolemDamage());
        map.put("末影人传送", endermanTeleport());
        map.put("珍珠投掷", enderPearlThrow());
        map.put("雪球投掷", snowballThrow());
        map.put("进食", genericEat());
        map.put("打嗝", playerBurp());
        map.put("落水", playerSplash());
        map.put("雷声", lightningThunder());
        map.put("幻术师施法", illusionerCast());
        map.put("幻术师镜像", illusionerMirror());
        map.put("远古诅咒", elderCurse());
        map.put("信标激活", blockBeaconActivate());
        map.put("潮涌激活", blockConduitActivate());
        map.put("潮涌失活", blockConduitDeactivate());
        map.put("铁砧落地", blockAnvilLand());
        map.put("UI 点击", uiButtonClick());
        return map;
    }

    private static String name(Sound sound) {
        return sound == null ? "缺失" : sound.name();
    }
    /** 末影珍珠投掷 */
    public static Sound enderPearlThrow() { return resolve("ENTITY_ENDER_PEARL_THROW"); }

    // ---- 事件音效（这批原来都没有声音，打架/破阵全凭眼睛看） ----

    /** 信标激活：法阵破除时用 */
    public static Sound blockBeaconActivate() { return resolve("BLOCK_BEACON_ACTIVATE"); }

    /** 潮涌核心激活：撤离点解锁用 */
    public static Sound blockConduitActivate() { return resolve("BLOCK_CONDUIT_ACTIVATE"); }

    /** 铁砧落地：道具箱被敲一下 */
    public static Sound blockAnvilLand() { return resolve("BLOCK_ANVIL_LAND"); }

    /** 爆炸：第三次上椅放飞用 */
    public static Sound genericExplode() { return resolve("ENTITY_GENERIC_EXPLODE"); }

    /** UI 点击：救援/自救开始读条 */
    public static Sound uiButtonClick() { return resolve("UI_BUTTON_CLICK"); }

    /** 潮涌核心失活（低沉收尾）：逃生者全灭 / 对局中断 */
    public static Sound blockConduitDeactivate() { return resolve("BLOCK_CONDUIT_DEACTIVATE"); }
}
