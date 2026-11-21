package io.Sriptirc_wp_1258.gost.commands;

import io.Sriptirc_wp_1258.gost.GHG;
import io.Sriptirc_wp_1258.gost.managers.AreaManager;
import io.Sriptirc_wp_1258.gost.managers.SelectionManager;
import io.Sriptirc_wp_1258.gost.managers.ServerCompat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

public class GhgAdminCommand implements CommandExecutor, TabCompleter {
    
    private final GHG plugin;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    
    public GhgAdminCommand(GHG plugin) {
        this.plugin = plugin;
    }
    
    /** 按玩家自己的语言发消息；控制台用服务器默认语言 */
    private void send(CommandSender sender, String key, Object... args) {
        Player player = sender instanceof Player ? (Player) sender : null;
        sender.sendMessage(plugin.getLanguageManager().getMessage(player, key, args));
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ghg.admin")) {
            send(sender, "error.no-permission");
            return true;
        }
        
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();

        // 简化指令：/ghga start / stop / kind / queue … 与 /ghga hunter <子命令> 完全等价。
        // 两条路都走到同一个 handler，老写法继续可用，新写法省掉 hunter。
        switch (subCommand) {
            case "start":
            case "stop":
            case "mother":
            case "kind":
            case "test":
            case "testmother":
            case "charge":
            case "down":
            case "kit":
            case "stats":
            case "queue":
            case "mode": {
                String[] expanded = new String[args.length + 1];
                expanded[0] = "hunter";
                System.arraycopy(args, 0, expanded, 1, args.length);
                return handleHunterSub(sender, expanded);
            }
            default:
                break;
        }

        switch (subCommand) {
            case "pos1":
                return handlePos1(sender);
            case "pos2":
                return handlePos2(sender);
            case "save":
                return handleSave(sender, args);
            case "list":
                return handleList(sender);
            case "load":
                return handleLoad(sender, args);
            case "delete":
                return handleDelete(sender, args);
            case "info":
                return handleInfo(sender, args);
            case "tool":
                return handleTool(sender);
            case "clear":
                return handleClear(sender);
            case "reload":
                return handleReload(sender);
            case "status":
                return handleStatus(sender);
            case "selftest":
                return handleSelfTest(sender);

            case "giveitem":
                return handleGiveItem(sender, args);
            case "hunter":
                return handleHunterSub(sender, args);
            case "lang":
            case "language":
                return handleLanguage(sender, args);
            case "help":
                sendHelp(sender);
                return true;
            default:
                send(sender, "ghg.help.unknown");
                return true;
        }
    }
    
    
    
    private boolean handlePos1(CommandSender sender) {
        if (!(sender instanceof Player)) {
            send(sender, "error.player-only");
            return true;
        }
        
        Player player = (Player) sender;
        send(player, "admin.pos1.hint");
        return true;
    }
    
    private boolean handlePos2(CommandSender sender) {
        if (!(sender instanceof Player)) {
            send(sender, "error.player-only");
            return true;
        }
        
        Player player = (Player) sender;
        send(player, "admin.pos2.hint");
        return true;
    }
    
    private boolean handleSave(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, "error.player-only");
            return true;
        }
        
        if (args.length < 2) {
            send(sender, "admin.save.usage");
            return true;
        }
        
        Player player = (Player) sender;
        String areaName = args[1];
        
        // 检查选区是否完整
        SelectionManager.PlayerSelection selection = plugin.getSelectionManager().getSelection(player);
        if (selection == null || !selection.isComplete()) {
            send(player, "admin.save.no-selection");
            send(player, "admin.save.howto");
            return true;
        }
        
        // 检查是否在同一世界
        if (!selection.isValid()) {
            send(player, "admin.save.different-world");
            return true;
        }
        
        // 保存区域
        if (plugin.getAreaManager().saveArea(areaName, selection.getPos1(), selection.getPos2())) {
            send(player, "admin.save.success", areaName);
        } else {
            send(player, "admin.save.failed");
        }
        
        return true;
    }
    
    private boolean handleList(CommandSender sender) {
        List<AreaManager.GameArea> areas = plugin.getAreaManager().getAllAreas();
        
        if (areas.isEmpty()) {
            send(sender, "admin.list.empty");
            return true;
        }
        
        send(sender, "admin.list.header");
        for (AreaManager.GameArea area : areas) {
            int[] dims = area.getDimensions();
            String selected = area.getName().equals(plugin.getAreaManager().getSelectedAreaName()) ? "✓" : " ";
            String enabled = plugin.getAreaManager().isAreaEnabled(area.getName()) ? "✔" : "✘";
            send(sender, "admin.list.entry", selected, enabled, area.getName(), dims[0], dims[1], dims[2], area.getUsageCount());
        }
        send(sender, "admin.separator");
        return true;
    }
    
    private boolean handleLoad(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "admin.load.usage");
            send(sender, "admin.load.example-header");
            send(sender, "admin.load.example-select");
            send(sender, "admin.load.example-enable");
            send(sender, "admin.load.example-disable");
            return true;
        }
        
        String areaName = args[1];
        
        // 检查区域是否存在
        if (plugin.getAreaManager().getArea(areaName) == null) {
            send(sender, "admin.area.not-exist", areaName);
            return true;
        }
        
        // 如果有第三个参数，处理启用/禁用
        if (args.length >= 3) {
            String action = args[2].toLowerCase();
            switch (action) {
                case "enable":
                    if (plugin.getAreaManager().enableArea(areaName)) {
                        send(sender, "admin.load.enabled", areaName);
                    } else if (plugin.getAreaManager().isAreaEnabled(areaName)) {
                        // 已启用：幂等提示，不要和「不存在」混成一句
                        send(sender, "admin.load.already-enabled", areaName);
                    } else {
                        send(sender, "admin.area.not-exist", areaName);
                    }
                    break;
                    
                case "disable":
                    if (plugin.getAreaManager().disableArea(areaName)) {
                        send(sender, "admin.load.disabled", areaName);
                    } else if (!plugin.getAreaManager().isAreaEnabled(areaName)) {
                        send(sender, "admin.load.not-enabled", areaName);
                    } else {
                        send(sender, "admin.area.not-exist", areaName);
                    }
                    break;
                    
                default:
                    send(sender, "admin.load.unknown-action");
                    return true;
            }
        } else {
            // 没有第三个参数，选择区域
            if (plugin.getAreaManager().selectArea(areaName)) {
                send(sender, "admin.load.selected", areaName);
            } else {
                send(sender, "admin.load.select-failed");
            }
        }
        
        return true;
    }
    
    private boolean handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "admin.delete.usage");
            return true;
        }
        
        String areaName = args[1];
        
        if (plugin.getAreaManager().deleteArea(areaName)) {
            send(sender, "admin.delete.success", areaName);
        } else {
            send(sender, "admin.area.not-exist", areaName);
        }
        
        return true;
    }
    
    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "admin.info.usage");
            return true;
        }
        
        String areaName = args[1];
        AreaManager.GameArea area = plugin.getAreaManager().getArea(areaName);
        
        if (area == null) {
            send(sender, "admin.area.not-exist", areaName);
            return true;
        }
        
        int[] dims = area.getDimensions();
        int volume = area.getVolume();
        
        send(sender, "admin.info.header");
        send(sender, "admin.info.name", area.getName());
        send(sender, "admin.info.world", area.getPos1().getWorld().getName());
        send(sender, "admin.info.pos1", formatLocation(area.getPos1()));
        send(sender, "admin.info.pos2", formatLocation(area.getPos2()));
        send(sender, "admin.info.size", dims[0], dims[1], dims[2]);
        send(sender, "admin.info.volume", volume);
        send(sender, "admin.info.created", dateFormat.format(area.getCreatedDate()));
        send(sender, "admin.info.last-used", dateFormat.format(area.getLastUsedDate()));
        send(sender, "admin.info.usage-count", area.getUsageCount());
        send(sender, "admin.separator");
        
        return true;
    }
    
    private boolean handleTool(CommandSender sender) {
        if (!(sender instanceof Player)) {
            send(sender, "error.player-only");
            return true;
        }
        
        Player player = (Player) sender;
        plugin.getSelectionManager().giveSelectionTool(player);
        return true;
    }
    
    private boolean handleClear(CommandSender sender) {
        if (!(sender instanceof Player)) {
            send(sender, "error.player-only");
            return true;
        }
        
        Player player = (Player) sender;
        if (plugin.getSelectionManager().clearSelection(player)) {
            send(player, "admin.clear.success");
        } else {
            send(player, "admin.clear.nothing");
        }
        return true;
    }
    

    /** 切换/查看语言：/ghga lang [zh_CN|en_US|ja_JP|es_ES|fr_FR|de_DE] */
    private boolean handleLanguage(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "admin.lang.current", plugin.getLanguageManager().getCurrentLanguage());
            send(sender, "admin.lang.available", plugin.getLanguageManager().listSupported());
            send(sender, "admin.lang.usage");
            send(sender, "admin.lang.file-hint");
            return true;
        }
        String loc = args[1];
        String[] supported = io.Sriptirc_wp_1258.gost.managers.LanguageManager.SUPPORTED;
        String matched = null;
        for (String s : supported) {
            if (s.equalsIgnoreCase(loc) || s.replace("_", "").equalsIgnoreCase(loc.replace("_", ""))) {
                matched = s;
                break;
            }
        }
        if (matched == null) {
            send(sender, "admin.lang.unsupported", loc);
            send(sender, "admin.lang.available-short", plugin.getLanguageManager().listSupported());
            return true;
        }
        plugin.getConfigManager().setDefaultLanguage(matched);
        plugin.getLanguageManager().reload();
        send(sender, "admin.lang.changed", matched);
        send(sender, "admin.lang.edit-hint", matched);
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        plugin.getConfigManager().reloadConfig();
        plugin.getAreaManager().reload();
        send(sender, "admin.reload.success");
        return true;
    }
    
    private boolean handleStatus(CommandSender sender) {
        send(sender, "admin.status.header");

        // GHG 对局状态
        for (String line : plugin.getHunterGameManager().describeStatus()) {
            sender.sendMessage(ChatColor.GRAY + line);
        }

        send(sender, "admin.status.players", plugin.getPlayerManager().getAllPlayers().size());

        AreaManager.GameArea selectedArea = plugin.getAreaManager().getSelectedArea();
        if (selectedArea != null) {
            send(sender, "admin.status.area", selectedArea.getName());
            int[] dims = selectedArea.getDimensions();
            send(sender, "admin.status.area-size", dims[0], dims[1], dims[2]);
        } else {
            send(sender, "admin.status.area-none");
        }
        send(sender, "admin.status.area-count", plugin.getAreaManager().getAreaNames().size());

        if (plugin.getEconomyManager().isEconomyEnabled()) {
            send(sender, "admin.status.prize-pool", plugin.getEconomyManager().getPrizePool());
        }
        return true;
    }
    

    
    /**
     * /ghga selftest —— 运行期自检。
     *
     * <p>目的是让「必须跑起来才知道」的东西可以单人验证：平台探测、语言加载、
     * 道具能否生成、ActionBar 通道、以及全局/实体两种调度能不能真正执行。
     * 不依赖开一局游戏。</p>
     */
    private boolean handleSelfTest(CommandSender sender) {
        final int[] stats = {0, 0, 0}; // 通过 / 失败 / 警告
        final Player player = sender instanceof Player ? (Player) sender : null;
        final String locale = plugin.getLanguageManager().getCurrentLanguage();

        send(sender, "selftest.header");

        // ---- 平台 ----
        stPass(sender, stats, lbl(player, "selftest.platform", ServerCompat.describe()));
        boolean folia = ServerCompat.isFolia();
        stPass(sender, stats, lbl(player, "selftest.folia",
            lbl(player, folia ? "selftest.yes" : "selftest.no")));

        // ---- 音效解析 ----
        // 有些服务端分支会把音效枚举改名，解析不到就**静默**（没有报错、就是没声音）。
        // 现在把整套音效都解析一遍：全通就一行「34/34 可用」，缺哪个就逐个列出来。
        int soundTotal = io.Sriptirc_wp_1258.gost.managers.SoundCompat.total();
        java.util.List<String> broken = io.Sriptirc_wp_1258.gost.managers.SoundCompat.describe();
        String soundHead = lbl(player, "selftest.sounds", (soundTotal - broken.size()) + "/" + soundTotal);
        if (broken.isEmpty()) {
            stPass(sender, stats, soundHead);
        } else {
            stWarn(sender, stats, soundHead, String.join(", ", broken));
        }

        // ---- 语言 ----
        int msgCount = plugin.getLanguageManager().getMessageCount(locale);
        if (msgCount > 0) {
            stPass(sender, stats, lbl(player, "selftest.lang", locale, String.valueOf(msgCount)));
        } else {
            stFail(sender, stats, lbl(player, "selftest.lang", locale, "0"), "no messages loaded");
        }
        int diff = plugin.getLanguageManager().getMaxKeySetDiff("zh_CN");
        String localeDetail = plugin.getLanguageManager().describeLocaleCounts();
        if (diff == 0) {
            stPass(sender, stats, lbl(player, "selftest.lang-missing", "0"));
        } else if (diff < 0) {
            stWarn(sender, stats, lbl(player, "selftest.lang-missing", "?"), "zh_CN not loaded");
        } else {
            stFail(sender, stats, lbl(player, "selftest.lang-missing", String.valueOf(diff)), localeDetail);
        }

        // ---- Vault 经济 ----
        if (plugin.getEconomyManager().isEconomyEnabled()) {
            stPass(sender, stats, lbl(player, "selftest.vault"));
        } else {
            stWarn(sender, stats, lbl(player, "selftest.vault"), lbl(player, "selftest.disabled"));
        }

        // ---- 区域 ----
        java.util.List<String> areaNames = plugin.getAreaManager().getAreaNames();
        int enabledAreas = 0;
        for (String n : areaNames) {
            if (plugin.getAreaManager().isAreaEnabled(n)) enabledAreas++;
        }
        if (areaNames.isEmpty()) {
            stFail(sender, stats, lbl(player, "selftest.areas", "0", "0"), "no saved area");
        } else if (enabledAreas == 0) {
            stFail(sender, stats, lbl(player, "selftest.areas", String.valueOf(areaNames.size()), "0"),
                "no enabled area");
        } else {
            stPass(sender, stats, lbl(player, "selftest.areas",
                String.valueOf(areaNames.size()), String.valueOf(enabledAreas)));
        }

        String selected = plugin.getAreaManager().getSelectedAreaName();
        if (selected != null && !selected.isEmpty()) {
            stPass(sender, stats, lbl(player, "selftest.area", selected));
        } else {
            stWarn(sender, stats, lbl(player, "selftest.area-none"), lbl(player, "selftest.disabled"));
        }

        // ---- 配置版本 ----
        int cfgVersion = plugin.getConfigManager().getConfigVersion();
        int expected = plugin.getConfigManager().getCurrentConfigVersion();
        if (cfgVersion == expected) {
            stPass(sender, stats, lbl(player, "selftest.config-version",
                String.valueOf(cfgVersion), String.valueOf(expected)));
        } else {
            stFail(sender, stats, lbl(player, "selftest.config-version",
                String.valueOf(cfgVersion), String.valueOf(expected)), "run /ghga reload");
        }

        // ---- 道具工厂 ----
        java.util.List<org.bukkit.inventory.ItemStack> made = new java.util.ArrayList<>();
        made.add(plugin.getItemManager().getAdrenaline());
        made.add(plugin.getItemManager().getFrenzyPotion());
        made.add(plugin.getItemManager().getIceBall());
        made.add(plugin.getItemManager().getSoulControl());
        made.add(plugin.getItemManager().getSecondChance());
        made.add(plugin.getItemManager().getLevitationPotion());
        made.add(plugin.getItemManager().getSpearRush());
        made.add(plugin.getItemSpawnManager().createTeleportPearl());
        made.add(plugin.getItemSpawnManager().createStinkySteak());
        made.add(plugin.getItemSpawnManager().createSoulDetector());
        int madeOk = 0;
        for (org.bukkit.inventory.ItemStack it : made) {
            if (it != null && it.getType() != org.bukkit.Material.AIR) madeOk++;
        }
        String itemText = lbl(player, "selftest.items",
            String.valueOf(madeOk), String.valueOf(made.size()));
        if (madeOk == made.size()) {
            stPass(sender, stats, itemText);
        } else {
            stFail(sender, stats, itemText, "item factory returned empty");
        }

        // ---- GHG：道具稳定 ID（必须能靠 PDC 认出来）----
        int taggedOk = 0;
        for (org.bukkit.inventory.ItemStack it : made) {
            if (it != null && io.Sriptirc_wp_1258.gost.managers.ItemIds.idOf(it) != null) taggedOk++;
        }
        String idText = lbl(player, "selftest.items-id",
            String.valueOf(taggedOk), String.valueOf(made.size()));
        if (taggedOk == made.size()) {
            stPass(sender, stats, idText);
        } else {
            stFail(sender, stats, idText, "item missing stable id");
        }

        // ---- GHG：道具效果注册表 ----
        int effectCount = plugin.getItemEffectRegistry().registered().size();
        String effectText = lbl(player, "selftest.item-effects", String.valueOf(effectCount));
        if (effectCount >= 9) {
            stPass(sender, stats, effectText);
        } else {
            stFail(sender, stats, effectText, "registry incomplete");
        }

        // ---- GHG：模式上下文 ----
        // 残留快照：不为 0 说明上次对局/退服没能把背包还回去
        int pendingStates = plugin.getPlayerManager().countPendingStates();
        String pendingText = lbl(player, "selftest.pending-states", String.valueOf(pendingStates));
        if (pendingStates > 0) {
            stWarn(sender, stats, pendingText, lbl(player, "selftest.pending-states-hint"));
        } else {
            stPass(sender, stats, pendingText);
        }

        stPass(sender, stats, lbl(player, "selftest.mode", plugin.getModeManager().describe()));

        // ---- GHG：逃亡模式配置 ----
        String hunterDetail = hunterConfigProblem();
        int altarCount = plugin.getConfigManager().getHunterAltarCount();
        String altarLabel = altarCount > 0
            ? String.valueOf(altarCount)
            : "auto/" + plugin.getConfigManager().getHunterAltarMax();
        String hunterText = lbl(player, "selftest.hunter-config",
            altarLabel,
            String.valueOf(plugin.getConfigManager().getHunterAltarSize()),
            plugin.getConfigManager().getHunterMotherKind() + "/"
                + plugin.getConfigManager().getHunterMotherType());
        if (hunterDetail == null) {
            stPass(sender, stats, hunterText);
        } else {
            stFail(sender, stats, hunterText, hunterDetail);
        }

        // ---- 更新检查开关 ----
        boolean updateCheck = plugin.getConfig().getBoolean("update-check.enabled", true);
        stPass(sender, stats, lbl(player, "selftest.update-check",
            lbl(player, updateCheck ? "selftest.enabled" : "selftest.disabled")));

        // ---- ActionBar 通道（只有玩家能测）----
        if (player != null) {
            try {
                io.Sriptirc_wp_1258.gost.managers.ActionBarManager.sendNow(
                    player, lbl(player, "selftest.actionbar") + " ✓");
                String strategy = io.Sriptirc_wp_1258.gost.managers.ActionBarManager.activeStrategy();
                String text = lbl(player, "selftest.actionbar") + " [" + strategy + "]";
                if ("chat-fallback".equals(strategy)) {
                    stWarn(sender, stats, text, "degraded to chat");
                } else {
                    stPass(sender, stats, text);
                }
            } catch (Throwable t) {
                stFail(sender, stats, lbl(player, "selftest.actionbar"), t.getClass().getSimpleName());
            }
        }

        if (player == null) {
            send(sender, "selftest.console-hint");
            finishSelfTest(sender, stats);
            return true;
        }

        // ---- 调度：延迟验证任务是否真的执行 ----
        final boolean[] ranLater = {false};
        ServerCompat.runLater(plugin, () -> ranLater[0] = true, 1L);

        final boolean[] timerRan = {false};
        final ServerCompat.Cancellable[] holder = new ServerCompat.Cancellable[1];
        holder[0] = ServerCompat.runTimerForEntity(player, plugin, () -> {
            timerRan[0] = true;
            if (holder[0] != null) holder[0].cancel();
        }, 0L, 2L);

        ServerCompat.runLater(plugin, () -> {
            if (ranLater[0]) {
                stPass(sender, stats, lbl(player, "selftest.scheduler"));
            } else {
                stFail(sender, stats, lbl(player, "selftest.scheduler"), "task never ran");
            }
            if (timerRan[0]) {
                stPass(sender, stats, lbl(player, "selftest.entity-timer"));
            } else {
                stFail(sender, stats, lbl(player, "selftest.entity-timer"), "timer never fired");
            }
            finishSelfTest(sender, stats);
        }, 30L);

        return true;
    }

    /** 取文案（玩家可为 null，此时用服务器默认语言） */
    private String lbl(Player player, String key, Object... args) {
        return plugin.getLanguageManager().getMessage(player, key, args);
    }

    /**
     * 检查 GHG 逃亡模式的配置是否可用。
     * @return null 表示没问题；否则返回一句可读的原因
     */
    private String hunterConfigProblem() {
        int altarCount = plugin.getConfigManager().getHunterAltarCount();
        int altarMax = plugin.getConfigManager().getHunterAltarMax();
        if (altarCount > 5) return "hunter.altar.count > 5";
        if (altarMax < 1) return "hunter.altar.max < 1";
        if (altarCount > altarMax) return "hunter.altar.count > hunter.altar.max";
        if (plugin.getConfigManager().getHunterMaxSurvivors() < 1) return "hunter.max-survivors < 1";

        int size = plugin.getConfigManager().getHunterAltarSize();
        if (size != 1 && size != 2) return "hunter.altar.size must be 1 or 2";

        String chestMaterial = plugin.getConfigManager().getHunterChestMaterial();
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(
            String.valueOf(chestMaterial).toUpperCase(java.util.Locale.ROOT));
        if (material == null || !material.isBlock()) return "hunter.chest.material invalid: " + chestMaterial;

        io.Sriptirc_wp_1258.gost.managers.MotherKind kind =
            io.Sriptirc_wp_1258.gost.managers.MotherKind.resolve(
                plugin.getConfigManager().getHunterMotherKind());
        if (io.Sriptirc_wp_1258.gost.managers.MotherSkills.byId(kind.getSkillId()) == null) {
            return "mother kind has no skill: " + kind.getSkillId();
        }
        if (plugin.getConfigManager().getHunterRescueSeconds() < 1) return "hunter.downed.rescue-seconds < 1";

        String motherType = plugin.getConfigManager().getHunterMotherType();
        if ("AUTO".equalsIgnoreCase(String.valueOf(motherType).trim())) return null;
        try {
            org.bukkit.entity.EntityType.valueOf(String.valueOf(motherType).toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            return "hunter.mother.type invalid: " + motherType;
        }
        return null;
    }

    private void stPass(CommandSender sender, int[] stats, String text) {
        stats[0]++;
        send(sender, "selftest.pass", text);
    }

    private void stFail(CommandSender sender, int[] stats, String text, String detail) {
        stats[1]++;
        send(sender, "selftest.fail", text, detail);
    }

    private void stWarn(CommandSender sender, int[] stats, String text, String detail) {
        stats[2]++;
        send(sender, "selftest.warn", text, detail);
    }

    private void finishSelfTest(CommandSender sender, int[] stats) {
        send(sender, "selftest.footer");
        send(sender, "selftest.summary", stats[0], stats[1], stats[2]);
        send(sender, stats[1] == 0 ? "selftest.ready" : "selftest.blocked");
    }

    
    
    
    private boolean handleEconomySet(CommandSender sender, String[] args) {
        if (args.length < 4) {
            send(sender, "admin.economy.set-usage");
            send(sender, "admin.economy.set-example-fee");
            send(sender, "admin.economy.set-example-bonus");
            return true;
        }
        
        String setting = args[2].toLowerCase();
        String valueStr = args[3];
        
        try {
            double value = Double.parseDouble(valueStr);
            
            if (value < 0) {
                send(sender, "admin.economy.negative");
                return true;
            }
            
            switch (setting) {
                case "entryfee":
                case "entry-fee":
                case "entry_fee":
                    plugin.getConfigManager().setEntryFee(value);
                    send(sender, "admin.economy.fee-set", value);
                    send(sender, "admin.economy.fee-set-hint");
                    break;
                    
                case "serverbonus":
                case "server-bonus":
                case "server_bonus":
                    plugin.getConfigManager().setServerBonus(value);
                    send(sender, "admin.economy.bonus-set", value);
                    send(sender, "admin.economy.bonus-set-hint");
                    break;
                    
                default:
                    send(sender, "admin.economy.unknown-key");
                    return true;
            }
            
        } catch (NumberFormatException e) {
            send(sender, "admin.economy.invalid-number");
            return true;
        }
        
        return true;
    }
    
    /** 多行文本按行拆开发，客户端才认换行 */
    private void sendBlock(CommandSender sender, String key) {
        Player player = sender instanceof Player ? (Player) sender : null;
        for (String line : plugin.getLanguageManager().getMessage(player, key).split("\n")) {
            sender.sendMessage(line);
        }
    }
    
    private void sendHelp(CommandSender sender) {
        // 一次性输出 GHG 自己的命令总览：对局 / 圈地 / 其它三组。
        // 不再逐条引用 admin.help.*——那批键的值是 Gost 的，里面全是 /gostadmin。
        sendBlock(sender, "ghg.help.admin");
        // 简写表单独一块：上面那份总览写的是 /ghga hunter xxx，这里补上省掉 hunter 的写法
        sendBlock(sender, "ghg.help.admin-short");
    }
    
    private String formatLocation(org.bukkit.Location location) {
        if (location == null) return plugin.getLanguageManager().getMessage("admin.location.unset");
        return String.format("(%d, %d, %d)", 
            (int) location.getX(), 
            (int) location.getY(), 
            (int) location.getZ()
        );
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            String[] subCommands = {"giveitem", "lang", "language", "pos1", "pos2", "save", "list",
                "load", "delete", "info", "tool", "clear", "reload", "status", "selftest", "hunter", "help",
                "start", "stop", "mother", "kind", "test", "testmother", "charge", "down", "kit", "stats",
                "queue", "mode"};
            for (String subCommand : subCommands) {
                if (subCommand.startsWith(args[0].toLowerCase())) {
                    completions.add(subCommand);
                }
            }
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            
            if (subCommand.equals("start") || subCommand.equals("load") || 
                subCommand.equals("delete") || subCommand.equals("info")) {
                // 区域名称自动补全
                for (String areaName : plugin.getAreaManager().getAreaNames()) {
                    if (areaName.toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(areaName);
                    }
                }

            } else if (subCommand.equals("hunter")) {
                String[] hunterSubs = {"start", "stop", "mother", "kind", "status", "stats",
                    "test", "testmother", "charge", "down", "kit", "queue", "mode"};
                for (String sub : hunterSubs) {
                    if (sub.startsWith(args[1].toLowerCase())) {
                        completions.add(sub);
                    }
                }
            } else if (subCommand.equals("dark")) {
                // dark子命令自动补全
                String[] darkSubCommands = {"on", "off", "status"};
                for (String darkSubCommand : darkSubCommands) {
                    if (darkSubCommand.startsWith(args[1].toLowerCase())) {
                        completions.add(darkSubCommand);
                    }
                }
            } else if (subCommand.equals("heartbeat")) {
                // heartbeat子命令自动补全
                String[] heartbeatSubCommands = {"on", "off", "status"};
                for (String heartbeatSubCommand : heartbeatSubCommands) {
                    if (heartbeatSubCommand.startsWith(args[1].toLowerCase())) {
                        completions.add(heartbeatSubCommand);
                    }
                }
            } else if (subCommand.equals("economy")) {
                // economy子命令自动补全
                String[] economySubCommands = {"set", "status"};
                for (String economySubCommand : economySubCommands) {
                    if (economySubCommand.startsWith(args[1].toLowerCase())) {
                        completions.add(economySubCommand);
                    }
                }
            } else if (subCommand.equals("charge")) {
                // /ghga charge <百分比>：常用档位
                String[] steps = {"10", "20", "40", "60", "80", "100"};
                for (String step : steps) {
                    if (step.startsWith(args[1].toLowerCase())) {
                        completions.add(step);
                    }
                }
            } else if (subCommand.equals("mode")) {
                // /ghga mode <ai|player>
                String[] modes = {"ai", "player"};
                for (String mode : modes) {
                    if (mode.startsWith(args[1].toLowerCase())) {
                        completions.add(mode);
                    }
                }
            }
        } else if (args.length == 3) {
            String subCommand = args[0].toLowerCase();
            
            if (subCommand.equals("load")) {
                // load命令的第三个参数补全：enable/disable
                String[] actions = {"enable", "disable"};
                for (String action : actions) {
                    if (action.startsWith(args[2].toLowerCase())) {
                        completions.add(action);
                    }
                }
            } else if (subCommand.equals("hunter") && args[1].equalsIgnoreCase("charge")) {
                String[] steps = {"10", "20", "40", "60", "80", "100"};
                for (String step : steps) {
                    if (step.startsWith(args[2].toLowerCase())) {
                        completions.add(step);
                    }
                }
            } else if (subCommand.equals("hunter") && args[1].equalsIgnoreCase("mode")) {
                String[] modes = {"ai", "player"};
                for (String mode : modes) {
                    if (mode.startsWith(args[2].toLowerCase())) {
                        completions.add(mode);
                    }
                }
            } else if (subCommand.equals("hunter") && args[1].equalsIgnoreCase("queue")) {
                String[] queueActions = {"start", "stop"};
                for (String action : queueActions) {
                    if (action.startsWith(args[2].toLowerCase())) {
                        completions.add(action);
                    }
                }
            } else if (subCommand.equals("economy") && args.length >= 2 && args[1].equalsIgnoreCase("set")) {
                // economy set命令的第三个参数补全：entryfee, serverbonus
                String[] settings = {"entryfee", "serverbonus"};
                for (String setting : settings) {
                    if (setting.startsWith(args[2].toLowerCase())) {
                        completions.add(setting);
                    }
                }
            }
        }
        
        return completions;
    }
    
    
    private boolean handleGiveItem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, "error.player-only");
            return true;
        }
        
        if (args.length < 2) {
            send(sender, "admin.giveitem.usage");
            send(sender, "admin.giveitem.available", String.join(", ", io.Sriptirc_wp_1258.gost.managers.ItemIds.allIds()));
            return true;
        }
        
        Player player = (Player) sender;
        String arg = args[1];
        // 参数可以是稳定 ID，也兼容老习惯的中文名（内部转成 ID）
        String itemId = io.Sriptirc_wp_1258.gost.managers.ItemIds.isValid(arg)
            ? arg
            : io.Sriptirc_wp_1258.gost.managers.ItemIds.legacyIdOf(arg);
        org.bukkit.inventory.ItemStack item =
            itemId == null ? null : plugin.getItemManager().createById(itemId);
        
        if (item == null || item.getType() == org.bukkit.Material.AIR) {
            if (io.Sriptirc_wp_1258.gost.managers.ItemIds.SPEAR_RUSH.equals(itemId)) {
                plugin.getLanguageManager().sendMessage(player, "item.spear-unavailable");
            } else {
                send(player, "admin.giveitem.unknown",
                    String.join(", ", io.Sriptirc_wp_1258.gost.managers.ItemIds.allIds()));
            }
            return true;
        }
        
        player.getInventory().addItem(item);
        send(player, "admin.giveitem.received", itemId);
        return true;
    }
    
    // ==================== GHG 逃亡模式 ====================

    /**
     * 切换「谁来当母体」：AI 母体 / 玩家母体。
     *
     * <p>改的是 {@code hunter.queue.mother}，写盘且立刻生效（队列每次判定都现读配置）。
     * 对局进行中不让切——那会让这一局的规则中途变掉；等这局结束（或先 stop）再切。</p>
     */
    private boolean handleMode(CommandSender sender, String[] args) {
        io.Sriptirc_wp_1258.gost.managers.ConfigManager cfg = plugin.getConfigManager();
        String current = cfg.getHunterQueueMother();
        boolean currentIsPlayer = "PLAYER".equals(current);

        if (args.length < 3) {
            send(sender, "hunter.mode.current", modeLabel(sender, currentIsPlayer));
            send(sender, "hunter.mode.usage");
            return true;
        }

        String want = args[2].toLowerCase();
        boolean toPlayer;
        if (want.equals("ai") || want.equals("robot")) {
            toPlayer = false;
        } else if (want.equals("player") || want.equals("human")) {
            toPlayer = true;
        } else {
            send(sender, "hunter.mode.unknown", args[2]);
            return true;
        }

        if (toPlayer == currentIsPlayer) {
            send(sender, "hunter.mode.same", modeLabel(sender, currentIsPlayer));
            return true;
        }
        if (plugin.getHunterGameManager().isRunning() || plugin.getHunterGameManager().isPreparing()) {
            send(sender, "hunter.mode.busy");
            return true;
        }
        if (toPlayer && !cfg.isHunterPlayerMotherEnabled()) {
            send(sender, "hunter.mode.player-disabled");
            return true;
        }

        int queued = plugin.getQueueManager().size();
        if (queued > 0) {
            // 排队的人得知道规则变了：PLAYER 模式下会随机抽一人当母体，原来的排队作废
            for (java.util.UUID id : new java.util.ArrayList<>(plugin.getQueueManager().queuedIds())) {
                Player member = Bukkit.getPlayer(id);
                if (member != null && member.isOnline()) {
                    plugin.getLanguageManager().sendMessage(member, "hunter.mode.queue-reset");
                }
            }
            plugin.getQueueManager().clear();
        }

        cfg.setHunterQueueMother(toPlayer ? "PLAYER" : "AI");
        send(sender, "hunter.mode.changed", modeLabel(sender, toPlayer));
        if (queued > 0) {
            send(sender, "hunter.mode.queue-cleared", String.valueOf(queued));
        }
        return true;
    }

    /** 模式的中文/本地化标签（AI 母体 / 玩家母体） */
    private String modeLabel(CommandSender sender, boolean playerMother) {
        Player player = sender instanceof Player ? (Player) sender : null;
        return plugin.getLanguageManager().getMessage(player,
            playerMother ? "hunter.mode.label-player" : "hunter.mode.label-ai");
    }

    private boolean handleHunterSub(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "hunter.cmd.help");
            send(sender, "hunter.cmd.mother-usage");
            send(sender, "hunter.cmd.kind-usage");
            send(sender, "hunter.cmd.stats-usage");
            send(sender, "hunter.cmd.test-usage");
            send(sender, "hunter.cmd.queue-usage");
            return true;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "start": {
                List<Player> participants = new ArrayList<>();
                for (int i = 2; i < args.length; i++) {
                    Player target = Bukkit.getPlayerExact(args[i]);
                    if (target != null) {
                        participants.add(target);
                    } else {
                        send(sender, "hunter.cmd.player-not-found", args[i]);
                    }
                }
                Player initiator = (sender instanceof Player) ? (Player) sender : null;
                if (plugin.getHunterGameManager().start(initiator, participants)) {
                    send(sender, "hunter.cmd.started");
                }
                return true;
            }
            case "stop":
                if (!plugin.getHunterGameManager().isRunning()) {
                    send(sender, "hunter.cmd.not-running");
                    return true;
                }
                plugin.getHunterGameManager().stop(false);
                send(sender, "hunter.cmd.stopped");
                return true;
            case "mother": {
                if (args.length < 3) {
                    send(sender, "hunter.cmd.mother-usage");
                    return true;
                }
                if (!plugin.getConfigManager().isHunterPlayerMotherEnabled()) {
                    send(sender, "hunter.mother.player-disabled");
                    return true;
                }
                Player motherPlayer = Bukkit.getPlayerExact(args[2]);
                if (motherPlayer == null) {
                    send(sender, "hunter.cmd.player-not-found", args[2]);
                    return true;
                }
                List<Player> participants = new ArrayList<>();
                for (int i = 3; i < args.length; i++) {
                    Player target = Bukkit.getPlayerExact(args[i]);
                    if (target != null) {
                        participants.add(target);
                    } else {
                        send(sender, "hunter.cmd.player-not-found", args[i]);
                    }
                }
                Player initiator = (sender instanceof Player) ? (Player) sender : null;
                if (plugin.getHunterGameManager().start(initiator, participants, motherPlayer)) {
                    send(sender, "hunter.cmd.started");
                }
                return true;
            }
            case "kind": {
                if (args.length < 3) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (plugin.getHunterGameManager().openKindMenu(player)) {
                            return true;
                        }
                        send(sender, "hunter.cmd.kind-too-late");
                    } else {
                        send(sender, "hunter.cmd.kind-usage");
                    }
                    return true;
                }
                io.Sriptirc_wp_1258.gost.managers.MotherKind kind =
                    io.Sriptirc_wp_1258.gost.managers.MotherKind.resolveOrNull(args[2]);
                if (kind == null) {
                    send(sender, "hunter.cmd.kind-unknown", args[2]);
                    return true;
                }
                if (plugin.getHunterGameManager().selectMotherKind(kind)) {
                    send(sender, "hunter.cmd.kind-set",
                        plugin.getLanguageManager().getMessage(kind.nameKey()));
                } else {
                    send(sender, "hunter.cmd.kind-too-late");
                }
                return true;
            }
            case "test": {
                if (!(sender instanceof Player)) {
                    send(sender, "error.player-only");
                    return true;
                }
                boolean asMother = args.length >= 3 && args[2].equalsIgnoreCase("mother");
                boolean ok = asMother
                    ? plugin.getHunterGameManager().startTestAsMother((Player) sender)
                    : plugin.getHunterGameManager().startTest((Player) sender);
                if (ok) {
                    send(sender, "hunter.test.hint");
                }
                return true;
            }
            case "testmother": {
                if (!plugin.getHunterGameManager().isTestMode()) {
                    send(sender, "hunter.test.not-test-mode");
                    return true;
                }
                boolean aggressive = plugin.getHunterGameManager().toggleTestMotherAggression();
                send(sender, aggressive ? "hunter.test.mother-on" : "hunter.test.mother-off");
                return true;
            }
            case "charge": {
                /* /ghga charge [百分比] —— 不带参数默认 10%；带不带 % 都认，100 = 直接充满 */
                int percent = 10;
                if (args.length >= 3) {
                    String raw = args[2].replace("%", "").trim();
                    try {
                        percent = Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        send(sender, "hunter.test.charge-usage");
                        return true;
                    }
                    if (percent < 0 || percent > 100) {
                        send(sender, "hunter.test.charge-usage");
                        return true;
                    }
                }
                int changed = plugin.getHunterGameManager().setAltarProgress(percent);
                if (changed <= 0) {
                    send(sender, "hunter.test.no-altar");
                } else {
                    send(sender, "hunter.test.charge-set",
                        String.valueOf(changed), String.valueOf(percent));
                }
                return true;
            }
            case "down": {
                if (!(sender instanceof Player)) {
                    send(sender, "error.player-only");
                    return true;
                }
                if (plugin.getHunterGameManager().forceDown((Player) sender)) {
                    send(sender, "hunter.test.downed");
                } else {
                    send(sender, "hunter.test.cannot-down");
                }
                return true;
            }
            case "kit": {
                if (!(sender instanceof Player)) {
                    send(sender, "error.player-only");
                    return true;
                }
                int given = plugin.getHunterGameManager().giveTestKit((Player) sender);
                send(sender, "hunter.test.kit", String.valueOf(given));
                return true;
            }
            case "stats": {
                int count = 5;
                if (args.length >= 3) {
                    try {
                        count = Math.max(1, Math.min(20, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                send(sender, "hunter.stats.header", plugin.getHunterStatsManager().getFileName());
                if (plugin.getHunterStatsManager().getTotalMatches() <= 0) {
                    send(sender, "hunter.stats.none");
                    return true;
                }
                send(sender, "hunter.stats.totals",
                    String.valueOf(plugin.getHunterStatsManager().getTotalMatches()),
                    String.valueOf(plugin.getHunterStatsManager().getSurvivorWins()),
                    String.valueOf(plugin.getHunterStatsManager().getMotherWins()),
                    String.valueOf(plugin.getHunterStatsManager().getSurvivorWinRate()),
                    String.valueOf(plugin.getHunterStatsManager().getAverageSeconds()),
                    String.valueOf(plugin.getHunterStatsManager().getDraws()));
                for (java.util.Map<?, ?> m : plugin.getHunterStatsManager().recent(count)) {
                    boolean win = "SURVIVOR_WIN".equals(m.get("result"));
                    sender.sendMessage(plugin.getLanguageManager().getMessage(
                        sender instanceof Player ? (Player) sender : null, "hunter.stats.line",
                        String.valueOf(m.get("time")),
                        String.valueOf(m.get("duration")),
                        String.valueOf(m.get("area")),
                        String.valueOf(m.get("charged")),
                        String.valueOf(m.get("altars")),
                        String.valueOf(m.get("downs")),
                        String.valueOf(m.get("rescues")),
                        String.valueOf(m.get("mother-hits")),
                        String.valueOf(m.get("heals")),
                        String.valueOf(m.get("vaults")),
                        plugin.getLanguageManager().getMessage(
                            "DRAW".equals(String.valueOf(m.get("result"))) ? "hunter.stats.result-draw"
                                : (win ? "hunter.stats.result-win" : "hunter.stats.result-lose"))));
                }
                return true;
            }
            case "queue": {
                String action = args.length >= 3 ? args[2].toLowerCase() : "";
                switch (action) {
                    case "start":
                        if (plugin.getQueueManager().forceStart(sender)) {
                            send(sender, "hunter.queue.force-started");
                        }
                        return true;
                    case "stop":
                    case "clear": {
                        int cleared = plugin.getQueueManager().size();
                        plugin.getQueueManager().clear();
                        send(sender, "hunter.queue.cleared", String.valueOf(cleared));
                        return true;
                    }
                    default:
                        for (String line : plugin.getQueueManager().describe()) {
                            sender.sendMessage(line);
                        }
                        send(sender, "hunter.queue.status-hint");
                        return true;
                }
            }
            case "mode":
                return handleMode(sender, args);
            case "status":
                for (String line : plugin.getHunterGameManager().describeStatus()) {
                    sender.sendMessage(ChatColor.GRAY + line);
                }
                return true;
            default:
                send(sender, "hunter.cmd.help");
                send(sender, "hunter.cmd.mother-usage");
                return true;
        }
    }

}