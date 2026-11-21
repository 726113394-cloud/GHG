package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;

import java.util.ArrayList;
import java.util.List;

/**
 * GHG 自己的启动横幅与状态汇总。
 *
 * <p>GHG 虽然是基于 Gost 的基础设施做的，但启动时该说的是 GHG 自己的东西：
 * 玩法、法阵数量、母体种类、道具与投放池、猎场、经济。所以这里把各管理器里
 * 零散打印的「已加载 N 个…」统一收成一张表，控制台只出现一份，措辞也用
 * GHG 的术语（母体 / 逃生者 / 法阵 / 道具箱），不再沿用鬼与人类那一套。</p>
 */
public final class StartupReport {

    private static final String LINE = "==================================================";

    private StartupReport() {
    }

    public static void print(GHG plugin) {
        StringBuilder sb = new StringBuilder();

        ConfigManager cfg = plugin.getConfigManager();
        LanguageManager lang = plugin.getLanguageManager();

        String language = lang.getCurrentLanguage() + "，" + lang.getLoadedMessageCount()
            + " 条 · " + LanguageManager.SUPPORTED.length + " 种语言";

        String economy = plugin.getEconomyManager().getEconomyName();
        if (economy == null) economy = "未接入（入场费与奖池关闭）";

        List<String> areaNames = new ArrayList<>();
        for (AreaManager.GameArea area : plugin.getAreaManager().getEnabledAreas()) {
            areaNames.add(area.getName());
        }
        String areas = areaNames.isEmpty() ? "未启用任何区域（用 /ghga area 选一个）" : String.join(", ", areaNames);

        String items = plugin.getItemManager().getCustomItemCount() + " 件自定义 · 自动投放池 "
            + plugin.getItemSpawnManager().getRandomItemCount() + " 种";

        String gost;
        org.bukkit.plugin.Plugin other = plugin.getServer().getPluginManager().getPlugin("Gost");
        // 只报事实，不再强调「两套插件如何如何」：GHG 自己就是一套完整插件
        gost = other != null && other.isEnabled()
            ? "已安装 v" + other.getDescription().getVersion()
            : "未安装（不影响本插件）";

        sb.append('\n').append(LINE).append('\n');
        sb.append(" GHG ").append(plugin.getDescription().getVersion()).append(" · Gost's HunterGame\n");
        sb.append(" PvE 逃亡：AI 母体猎杀，逃生者破除全部法阵后撤离\n");
        sb.append(LINE).append('\n');
        row(sb, "命令", "/ghg 玩家 · /ghga 管理");
        row(sb, "服务端", ServerCompat.describe());
        row(sb, "对局", "准备 " + cfg.getHunterPreparationSeconds() + "s · 上限 " + cfg.getHunterDuration()
            + "s · 法阵 " + cfg.getHunterAltarCount() + " 座 · 母体 " + MotherKind.values().length + " 种");
        row(sb, "配置", "v" + cfg.getConfigVersion() + "（ghg-config-version）");
        row(sb, "语言", language);
        row(sb, "经济", economy);
        row(sb, "猎场", areas);
        row(sb, "道具", items);
        row(sb, "冲刺矛", plugin.isSpearRushEnabled()
            ? "已启用（" + plugin.getSpearVisualName() + "外观）" : "已禁用（服务器版本无法解析）");
        row(sb, "Gost", gost);
        sb.append(LINE).append('\n');
        sb.append(" 单人试玩：/ghga hunter test  ·  参数：/ghga selftest\n");
        sb.append(LINE);

        plugin.getLogger().info(sb.toString());
    }

    /** 左列标签补齐到固定显示宽度（中文按两列算，控制台才对得齐） */
    private static void row(StringBuilder sb, String label, String value) {
        sb.append(' ').append(label);
        int width = displayWidth(label);
        for (int i = width; i < 8; i++) {
            sb.append(' ');
        }
        sb.append("  ").append(value).append('\n');
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            width += (c >= 0x2E80) ? 2 : 1;
        }
        return width;
    }
}
