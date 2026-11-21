package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 道具身份层。
 *
 * <p>道具身份一律以 {@link PersistentDataContainer} 里的稳定 ID 为准，
 * <b>不再用中文显示名判断道具是什么</b>。中文名只是显示层，显示名本身走
 * 语言文件（{@code item-name.<id>}），因此改语言不会让监听器失配。</p>
 *
 * <p>旧存档里的道具没有 PDC，{@link #idOf(ItemStack)} 会退回按中文显示名识别，
 * 识别一次后由调用方决定是否回写。</p>
 */
public final class ItemIds {

    // ---- 稳定 ID：只增不改。改名会让旧存档道具失去身份 ----
    public static final String ADRENALINE = "adrenaline";
    public static final String FRENZY = "frenzy";
    public static final String ICE_BALL = "ice-ball";
    public static final String SOUL_CONTROL = "soul-control";
    public static final String STINKY_STEAK = "stinky-steak";
    public static final String TELEPORT_PEARL = "teleport-pearl";
    public static final String SOUL_DETECTOR = "soul-detector";
    public static final String SPEAR_RUSH = "spear-rush";
    public static final String LEVITATION_POTION = "levitation-potion";
    public static final String SECOND_CHANCE = "second-chance";
    public static final String HARVESTER = "harvester";
    public static final String DIVINE_REDEMPTION = "divine-redemption";
    public static final String SELECTION_TOOL = "selection-tool";
    /** 通用凝冰球（和冰球手的职业凝冰球是两件东西：这件从箱子出、无冷却） */
    public static final String ICE_BALL_COMMON = "ice-ball-common";
    /** GHG 专属：医疗包（立刻治好受伤） */
    public static final String MEDKIT = "medkit";
    /** GHG 专属：破阵石（给脚下这座法阵挂「破解速度 ×倍率」buff） */
    public static final String RITUAL_KNIFE = "ritual-knife";
    /** GHG 专属：应急口粮（恢复饱食度，可吃多口） */
    public static final String RATION = "ration";
    /** GHG 专属：救赎护符（一段时间内救人读条加速；替代原来的「第二次机会」自救） */
    public static final String REDEMPTION_CHARM = "redemption-charm";
    /** GHG 专属：工程师的栅栏工具（右键在猎场里架一段木栅栏） */
    public static final String FENCE_KIT = "fence-kit";
    /** GHG 专属：母体技能道具（材质与名字随母体种类变，右键放技能） */
    public static final String MOTHER_SKILL = "mother-skill";
    /** GHG 专属：准备阶段的选择面板（钟表），左右键都能打开职业 / 母体种类菜单 */
    public static final String MENU_CLOCK = "menu-clock";
    /** GHG 专属：眩晕药水（喷溅药水）：砸中母体立刻定身 1.5 秒 */
    public static final String STUN_POTION = "stun-potion";
    /** GHG 专属：黑衣人的隐身道具 */
    public static final String SHADOW_CLOAK = "shadow-cloak";
    /** GHG 专属：母体的战斧（左键砍击，节奏由原版战斗冷却控制） */
    public static final String MOTHER_AXE = "mother-axe";

    /** 人鬼通用道具：允许重复持有 */
    private static final Set<String> UNIVERSAL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        ICE_BALL, ICE_BALL_COMMON, TELEPORT_PEARL, LEVITATION_POTION, STINKY_STEAK,
        SPEAR_RUSH, RATION, STUN_POTION
    )));

    /**
     * GHG 逃生者可用的道具池。
     *
     * <p>Gost 里「鬼专属」（狂暴药水）与「母体专属」（灵魂探测器）的道具在 PVE 里没有使用场景，
     * 逃生者拿到也放不出效果，所以不能整套照搬。</p>
     */
    private static final Set<String> SURVIVOR_POOL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        // 职业技能道具（护符 / 肾上腺素）**不进箱子**：靠职业补发，进箱子会和补发叠成两件
        SOUL_CONTROL, STINKY_STEAK,
        TELEPORT_PEARL, LEVITATION_POTION,
        MEDKIT, RITUAL_KNIFE, RATION, STUN_POTION,
        // 「通用」版凝冰球 / 冲刺矛：独立 ID，可以从箱子抽到，和职业那件互不影响
        ICE_BALL_COMMON, SPEAR_RUSH
    )));

    /** 全部合法 ID，自检与命令补全用 */
    private static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        ADRENALINE, FRENZY, ICE_BALL, SOUL_CONTROL, STINKY_STEAK, TELEPORT_PEARL,
        SOUL_DETECTOR, SPEAR_RUSH, LEVITATION_POTION, SECOND_CHANCE,
        HARVESTER, DIVINE_REDEMPTION, SELECTION_TOOL, MEDKIT, RITUAL_KNIFE, RATION,
        ICE_BALL_COMMON, STUN_POTION, SHADOW_CLOAK, MENU_CLOCK,
        MOTHER_SKILL, MOTHER_AXE, REDEMPTION_CHARM, FENCE_KIT
    )));

    /** 母体专属道具：这些不允许从背包丢出来 */
    private static final Set<String> MOTHER_ITEMS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        FRENZY, SOUL_DETECTOR, MOTHER_SKILL, MOTHER_AXE
    )));

    /** 旧存档兜底：中文显示名 → ID。仅用于没有 PDC 的老道具 */
    private static final String[][] LEGACY_NAMES = {
        {"肾上腺素", ADRENALINE},
        {"狂暴药水", FRENZY},
        {"凝冰球（通用）", ICE_BALL_COMMON},
        {"凝冰球", ICE_BALL},
        {"控魂术", SOUL_CONTROL},
        {"臭牛排", STINKY_STEAK},
        {"传送珍珠", TELEPORT_PEARL},
        {"灵魂探测器", SOUL_DETECTOR},
        {"冲刺矛", SPEAR_RUSH},
        {"漂浮药水", LEVITATION_POTION},
        {"第二次机会", SECOND_CHANCE},
        {"收割者", HARVESTER},
        {"神之救赎", DIVINE_REDEMPTION},
        {"区域选择工具", SELECTION_TOOL},
        {"选择面板", MENU_CLOCK},
        {"眩晕药水", STUN_POTION},
        {"隐身斗篷", SHADOW_CLOAK},
        {"破阵石", RITUAL_KNIFE},
        {"破阵刀", RITUAL_KNIFE}
    };

    private static NamespacedKey key;
    private static NamespacedKey bitesKey;

    private ItemIds() {}

    private static NamespacedKey key() {
        if (key == null) {
            GHG plugin = GHG.getInstance();
            if (plugin == null) return null;
            key = new NamespacedKey(plugin, "item_id");
        }
        return key;
    }

    private static NamespacedKey bitesKey() {
        if (bitesKey == null) {
            GHG plugin = GHG.getInstance();
            if (plugin == null) return null;
            bitesKey = new NamespacedKey(plugin, "item_bites");
        }
        return bitesKey;
    }

    /** 写入剩余口数（应急口粮用）；bites &lt;= 0 时清掉 */
    public static void tagBites(ItemStack item, int bites) {
        if (item == null) return;
        NamespacedKey k = bitesKey();
        if (k == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (bites <= 0) {
            meta.getPersistentDataContainer().remove(k);
        } else {
            meta.getPersistentDataContainer().set(k, PersistentDataType.INTEGER, bites);
        }
        item.setItemMeta(meta);
    }

    /** 读剩余口数；没写过返回 -1 */
    public static int getBites(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return -1;
        NamespacedKey k = bitesKey();
        if (k == null) return -1;
        Integer value = meta.getPersistentDataContainer().get(k, PersistentDataType.INTEGER);
        return value == null ? -1 : value;
    }

    /**
     * 给道具写入稳定 ID。所有自定义道具在创建时必须调它。
     * @return 同一个 ItemStack，方便链式调用
     */
    public static ItemStack tag(ItemStack item, String id) {
        if (item == null || id == null) return item;
        NamespacedKey k = key();
        if (k == null) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.getPersistentDataContainer().set(k, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 读道具 ID。先读 PDC；没有 PDC 时按中文显示名兜底（旧存档）。
     * @return 认不出来返回 null
     */
    public static String idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        NamespacedKey k = key();
        if (k != null) {
            String stored = meta.getPersistentDataContainer().get(k, PersistentDataType.STRING);
            if (stored != null && !stored.isEmpty()) return stored;
        }
        return legacyIdOf(meta.hasDisplayName() ? meta.getDisplayName() : null);
    }

    /** 只按中文显示名识别（兜底路径，独立出来便于自检） */
    public static String legacyIdOf(String displayName) {
        if (displayName == null || displayName.isEmpty()) return null;
        for (String[] pair : LEGACY_NAMES) {
            if (displayName.contains(pair[0])) return pair[1];
        }
        return null;
    }

    /** 这件道具是不是指定的那种 */
    public static boolean is(ItemStack item, String id) {
        return id != null && id.equals(idOf(item));
    }

    /** 是否本插件的自定义道具（认得出 ID 就算） */
    public static boolean isCustom(ItemStack item) {
        return idOf(item) != null;
    }

    /**
     * 允许**叠加成一摞**的道具（默认所有自定义道具都是单件制）。
     *
     * <p>应急口粮现在是消耗品里唯一会攒很多的，叠起来才不占格子。</p>
     */
    private static final Set<String> STACKABLE = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        RATION
    )));

    /** 这件道具允不允许叠加 */
    public static boolean isStackable(String id) {
        return id != null && STACKABLE.contains(id);
    }

    /** 人鬼通用道具允许重复持有 */
    public static boolean isUniversal(String id) {
        return id != null && UNIVERSAL.contains(id);
    }

    public static boolean isValid(String id) {
        return id != null && ALL.contains(id);
    }

    /** GHG 逃生者可用的道具池 */
    public static Set<String> survivorPool() {
        return SURVIVOR_POOL;
    }

    public static boolean isMotherItem(String id) {
        return id != null && MOTHER_ITEMS.contains(id);
    }

    public static Set<String> allIds() {
        return ALL;
    }

    /** 道具显示名在语言文件里的 key */
    public static String nameKey(String id) {
        return "item-name." + id;
    }
}
