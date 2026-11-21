package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /ghg} 打开的图形菜单。
 *
 * <p>结构是「主菜单 → 板块 → 详细功能」：主菜单只放板块入口，每个板块再给一层能点的功能，
 * 所以玩家不用记命令也能开局、看职业、看种类、看玩法。能落到命令上的动作（排队 / 退出 / 状态）
 * 一律走 {@code performCommand}，行为和命令完全一致，不会出现「菜单能做但命令做不到」的第二套逻辑。</p>
 */
public class MenuManager {

    /** 板块。菜单页与点击处理共用同一套枚举，标题/内容都按它分发 */
    public enum Section {
        MAIN, BATTLE, ROLES, KINDS, FLOW, LANG, COMMANDS, ADMIN
    }

    /** 菜单持有者：靠它认「这是我们的界面」，并记住当前板块与页码 */
    public static final class Holder implements InventoryHolder {
        private final UUID ownerId;
        private final Section section;
        private final int page;

        public Holder(UUID ownerId, Section section, int page) {
            this.ownerId = ownerId;
            this.section = section;
            this.page = page;
        }

        public UUID getOwnerId() { return ownerId; }
        public Section getSection() { return section; }
        public int getPage() { return page; }

        @Override
        public Inventory getInventory() { return null; }
    }

    private static final int SIZE = 27;
    /** 一级/二级菜单的返回与关闭都放这一格（右下角，不挡内容） */
    private static final int NAV_SLOT = 22;
    /** 列表类板块的起始槽位 */
    private static final int LIST_START = 10;

    private static final int FLOW_PAGES = 4;

    /**
     * 管理员板块里能直接调的配置项。
     *
     * <p>只放**改完立刻生效或下一局生效**的纯数值键——改结构（比如新增段）不该在箱子里做。
     * 菜单里显示的是原始配置键路径，管理员本来就看这个。</p>
     */
    private static final class Tuneable {
        final String key;
        final boolean integral;
        final double min;
        final double max;
        final double step;

        Tuneable(String key, boolean integral, double min, double max, double step) {
            this.key = key;
            this.integral = integral;
            this.min = min;
            this.max = max;
            this.step = step;
        }
    }

    private static final List<Tuneable> TUNEABLES = new ArrayList<>();

    static {
        // 一局节奏
        TUNEABLES.add(new Tuneable("hunter.duration", true, 120, 3600, 60));
        TUNEABLES.add(new Tuneable("hunter.preparation-seconds", true, 5, 120, 5));
        TUNEABLES.add(new Tuneable("hunter.altar.count", true, 1, 8, 1));
        TUNEABLES.add(new Tuneable("hunter.altar.progress-per-second", false, 0.2, 5.0, 0.1));
        TUNEABLES.add(new Tuneable("hunter.altar.decay-per-second", false, 0.0, 5.0, 0.1));
        TUNEABLES.add(new Tuneable("hunter.altar.harden-seconds", true, 0, 300, 10));
        TUNEABLES.add(new Tuneable("hunter.escape.hold-seconds", true, 1, 30, 1));
        TUNEABLES.add(new Tuneable("hunter.endgame.seconds", true, 15, 300, 15));
        TUNEABLES.add(new Tuneable("hunter.chest.count", true, 0, 10, 1));
        TUNEABLES.add(new Tuneable("hunter.chest.wave-interval-seconds", true, 10, 300, 10));
        // 生存与母体
        TUNEABLES.add(new Tuneable("hunter.downed.hits-to-down", true, 1, 5, 1));
        TUNEABLES.add(new Tuneable("hunter.downed.recover-seconds", true, 0, 90, 5));
        TUNEABLES.add(new Tuneable("hunter.downed.rescue-seconds", true, 1, 20, 1));
        TUNEABLES.add(new Tuneable("hunter.downed.rescue-protect-seconds", true, 0, 20, 1));
        TUNEABLES.add(new Tuneable("hunter.heal.self-seconds", true, 5, 120, 5));
        TUNEABLES.add(new Tuneable("hunter.chair.first-seconds", true, 10, 300, 10));
        TUNEABLES.add(new Tuneable("hunter.chair.second-seconds", true, 5, 300, 5));
        TUNEABLES.add(new Tuneable("hunter.mother.speed", false, 0.10, 0.60, 0.01));
        TUNEABLES.add(new Tuneable("hunter.mother.attack-cooldown-seconds", true, 1, 10, 1));
        TUNEABLES.add(new Tuneable("hunter.mother.sense-range", false, 10.0, 120.0, 5.0));
        TUNEABLES.add(new Tuneable("hunter.heartbeat.range", false, 8.0, 96.0, 4.0));
        TUNEABLES.add(new Tuneable("hunter.reward.entry-fee", false, 0.0, 1000.0, 10.0));
        TUNEABLES.add(new Tuneable("hunter.reward.server-bonus", false, 0.0, 10000.0, 100.0));
        TUNEABLES.add(new Tuneable("hunter.queue.min-players", true, 1, 8, 1));
    }

    /** 管理员页每页放几项（两排各 7 格） */
    private static final int ADMIN_PER_PAGE = 14;

    private final GHG plugin;

    public MenuManager(GHG plugin) {
        this.plugin = plugin;
    }

    // ================================================================ 主菜单

    public void openMain(Player viewer) {
        Inventory menu = create(viewer, Section.MAIN, 0, "menu.main.title");

        menu.setItem(10, icon(Material.PLAYER_HEAD,
            msg(viewer, "menu.main.status"), stateLore(viewer)));
        menu.setItem(11, icon(Material.IRON_SWORD,
            msg(viewer, "menu.main.battle"), lines(viewer, "menu.main.lore.battle")));
        menu.setItem(12, icon(Material.SNOWBALL,
            msg(viewer, "menu.main.roles"), lines(viewer, "menu.main.lore.roles")));
        menu.setItem(13, icon(Material.ROTTEN_FLESH,
            msg(viewer, "menu.main.kinds"), lines(viewer, "menu.main.lore.kinds")));
        menu.setItem(14, icon(Material.BOOK,
            msg(viewer, "menu.main.flow"), lines(viewer, "menu.main.lore.flow")));
        menu.setItem(15, icon(Material.NAME_TAG,
            msg(viewer, "menu.main.lang"), lines(viewer, "menu.main.lore.lang")));
        menu.setItem(16, icon(Material.PAPER,
            msg(viewer, "menu.main.commands"), lines(viewer, "menu.main.lore.commands")));
        if (viewer.hasPermission("ghg.admin")) {
            menu.setItem(17, icon(Material.REDSTONE,
                msg(viewer, "menu.main.admin"), lines(viewer, "menu.main.lore.admin")));
        }

        menu.setItem(NAV_SLOT, icon(Material.BARRIER, msg(viewer, "menu.close"), new ArrayList<>()));
        viewer.openInventory(menu);
    }

    // ================================================================ 对战板块

    private void openBattle(Player viewer) {
        Inventory menu = create(viewer, Section.BATTLE, 0, "menu.battle.title");

        menu.setItem(10, icon(Material.LIME_DYE,
            msg(viewer, "menu.battle.join"), lines(viewer, "menu.battle.lore.join")));
        menu.setItem(11, icon(Material.RED_DYE,
            msg(viewer, "menu.battle.leave"), lines(viewer, "menu.battle.lore.leave")));
        menu.setItem(12, icon(Material.COMPASS,
            msg(viewer, "menu.battle.status"), lines(viewer, "menu.battle.lore.status")));

        if (viewer.hasPermission("ghg.admin")) {
            menu.setItem(13, icon(Material.CLOCK,
                msg(viewer, "menu.battle.queue"), lines(viewer, "menu.battle.lore.queue")));
            menu.setItem(14, icon(Material.BEACON,
                msg(viewer, "menu.battle.start"), lines(viewer, "menu.battle.lore.start")));
        }

        menu.setItem(NAV_SLOT, icon(Material.ARROW, msg(viewer, "menu.back"), new ArrayList<>()));
        viewer.openInventory(menu);
    }

    // ================================================================ 职业图鉴

    private void openRoles(Player viewer) {
        Inventory menu = create(viewer, Section.ROLES, 0, "menu.roles.title");
        HunterGameManager game = plugin.getHunterGameManager();
        /* ⚠️ 图鉴要**列全五种**，包括 AI 母体局用不了的冰球手 ——
           以前这里跟着「可选职业」走（menuRoles() 会把冰球手滤掉），
           结果在 AI 局里点开职业图鉴，压根找不到冰球手的介绍。 */
        List<HunterRole> roles = new java.util.ArrayList<>(java.util.Arrays.asList(HunterRole.values()));
        HunterRole current = game.getRole(viewer.getUniqueId());

        boolean hasMother = game.hasPlayerMother();
        boolean canSwitch = game.isPreparing();
        for (int i = 0; i < roles.size() && i < 7; i++) {
            HunterRole role = roles.get(i);
            ItemStack icon = plugin.getItemManager().createById(role.skillItemId());
            if (icon == null) icon = new ItemStack(Material.OAK_FENCE);
            icon.setAmount(1);
            ItemMeta meta = icon.getItemMeta();
            if (meta == null) {
                menu.setItem(LIST_START + i, icon);
                continue;
            }
            String itemName = meta.hasDisplayName() ? meta.getDisplayName() : role.skillItemId();
            meta.setDisplayName("§b" + msg(viewer, role.nameKey()));

            List<String> lore = new ArrayList<>();
            lore.add(msg(viewer, role.descKey()));
            lore.add(msg(viewer, "hunter.role.separator"));
            lore.add(msg(viewer, "hunter.role.menu-item", itemName));
            for (String line : lines(viewer, role.effectKey())) lore.add(msg(viewer, "hunter.kindmenu.lore-body", line));
            lore.add(msg(viewer, "hunter.role.menu-cooldown",
                String.valueOf(plugin.getConfigManager().getHunterRoleRefreshSeconds(role))));
            lore.add(msg(viewer, "hunter.role.menu-passive", msg(viewer, role.passiveKey())));
            lore.add(msg(viewer, "hunter.role.menu-fee",
                plugin.getEconomyManager().format(plugin.getConfigManager().getHunterRoleFee(role))));
            lore.add(msg(viewer, "hunter.role.separator"));
            if (role == current) {
                lore.add(msg(viewer, "hunter.role.menu-current"));
            } else if (role == HunterRole.ICER && !hasMother) {
                /* 图鉴照常展示，但说明清楚这局为什么选不了 */
                lore.add(msg(viewer, "hunter.role.icer-unavailable"));
            } else if (canSwitch) {
                lore.add(msg(viewer, "hunter.role.menu-click"));
            } else {
                lore.add(msg(viewer, "menu.roles.locked"));
            }
            meta.setLore(lore);
            icon.setItemMeta(meta);
            menu.setItem(LIST_START + i, icon);
        }
        menu.setItem(NAV_SLOT, icon(Material.ARROW, msg(viewer, "menu.back"), new ArrayList<>()));
        viewer.openInventory(menu);
    }

    // ================================================================ 母体种类图鉴

    private void openKinds(Player viewer) {
        Inventory menu = create(viewer, Section.KINDS, 0, "menu.kinds.title");
        HunterGameManager game = plugin.getHunterGameManager();
        List<MotherKind> kinds = game.menuKinds();
        MotherKind current = game.getMotherKind();
        boolean canPick = game.isPreparing() && game.isPlayerMother(viewer.getUniqueId());

        for (int i = 0; i < kinds.size() && i < 7; i++) {
            MotherKind kind = kinds.get(i);
            ItemStack icon = new ItemStack(plugin.getHunterGameManager().kindIcon(kind));
            ItemMeta meta = icon.getItemMeta();
            if (meta == null) {
                menu.setItem(LIST_START + i, icon);
                continue;
            }
            meta.setDisplayName(msg(viewer, kind.nameKey()));

            List<String> lore = new ArrayList<>();
            lore.add(msg(viewer, kind.descKey()));
            lore.add(msg(viewer, "hunter.kindmenu.separator"));
            lore.add(msg(viewer, "hunter.kindmenu.lore-skill", msg(viewer, kind.skillNameKey())));
            for (String line : lines(viewer, kind.skillDescKey())) {
                lore.add(msg(viewer, "hunter.kindmenu.lore-body", line));
            }
            MotherSkill skill = MotherSkills.byId(kind.getSkillId());
            int cooldown = skill == null ? 0 : game.skillCooldownSeconds(skill);
            lore.add(msg(viewer, cooldown > 0
                ? "hunter.kindmenu.lore-cooldown" : "hunter.kindmenu.lore-cooldown-none",
                String.valueOf(cooldown)));
            double cost = plugin.getConfigManager().getHunterKindCost(kind);
            lore.add(cost > 0
                ? msg(viewer, "hunter.kindmenu.lore-cost", plugin.getEconomyManager().format(cost))
                : msg(viewer, "hunter.kindmenu.lore-cost-free"));
            lore.add(msg(viewer, "hunter.kindmenu.separator"));
            lore.add(msg(viewer, "hunter.kindmenu.lore-passive",
                msg(viewer, "hunter.kindmenu.passive-common")));
            if (kind == current && game.isPlayerMother(viewer.getUniqueId())) {
                lore.add(msg(viewer, "hunter.kindmenu.lore-current"));
            } else if (canPick) {
                lore.add(msg(viewer, "hunter.kindmenu.lore-click"));
            } else {
                lore.add(msg(viewer, "menu.kinds.locked"));
            }
            meta.setLore(lore);
            icon.setItemMeta(meta);
            menu.setItem(LIST_START + i, icon);
        }
        menu.setItem(NAV_SLOT, icon(Material.ARROW, msg(viewer, "menu.back"), new ArrayList<>()));
        viewer.openInventory(menu);
    }

    // ================================================================ 游戏流程

    private void openFlow(Player viewer, int page) {
        int total = FLOW_PAGES;
        int current = Math.max(1, Math.min(total, page));
        Inventory menu = create(viewer, Section.FLOW, current, "menu.flow.title");

        List<String> lore = new ArrayList<>();
        lore.add(msg(viewer, "menu.flow.page", String.valueOf(current), String.valueOf(total)));
        lore.add(msg(viewer, "hunter.kindmenu.separator"));
        lore.addAll(lines(viewer, "menu.flow.p" + current));
        menu.setItem(13, icon(Material.WRITTEN_BOOK, msg(viewer, "menu.flow.book-name"), lore));

        if (current > 1) {
            menu.setItem(11, icon(Material.ARROW, msg(viewer, "menu.flow.prev"), new ArrayList<>()));
        }
        if (current < total) {
            menu.setItem(15, icon(Material.ARROW, msg(viewer, "menu.flow.next"), new ArrayList<>()));
        }
        menu.setItem(NAV_SLOT, icon(Material.ARROW, msg(viewer, "menu.back"), new ArrayList<>()));
        viewer.openInventory(menu);
    }

    // ================================================================ 语言

    private void openLang(Player viewer) {
        Inventory menu = create(viewer, Section.LANG, 0, "menu.lang.title");
        String currentLocale = plugin.getLanguageManager().getLanguage(viewer);

        int start = LIST_START;
        for (int i = 0; i < LanguageManager.SUPPORTED.length && i < 9; i++) {
            String locale = LanguageManager.SUPPORTED[i];
            boolean active = locale.equalsIgnoreCase(currentLocale);
            List<String> lore = new ArrayList<>();
            if (active) {
                lore.add(msg(viewer, "menu.lang.current"));
            } else {
                lore.add(msg(viewer, "menu.lang.click"));
            }
            // 菜单里直接用该语言自己写的名字，切错了也认得回来
            String name = (active ? "§a" : "§f") + localeName(locale);
            menu.setItem(start + i, icon(Material.NAME_TAG, name, lore));
        }
        menu.setItem(NAV_SLOT, icon(Material.ARROW, msg(viewer, "menu.back"), new ArrayList<>()));
        viewer.openInventory(menu);
    }

    /** 语言的自称（不用查语言文件，切过去之前也看得懂） */
    private String localeName(String locale) {
        switch (locale) {
            case "zh_CN": return "简体中文";
            case "en_US": return "English";
            case "ja_JP": return "日本語";
            case "es_ES": return "Español";
            case "fr_FR": return "Français";
            case "de_DE": return "Deutsch";
            default: return locale;
        }
    }

    // ================================================================ 命令一览

    private void openCommands(Player viewer) {
        Inventory menu = create(viewer, Section.COMMANDS, 0, "menu.commands.title");

        List<String> playerLore = new ArrayList<>(lines(viewer, "ghg.help.player"));
        menu.setItem(11, icon(Material.PAPER, msg(viewer, "menu.commands.player-name"), playerLore));

        if (viewer.hasPermission("ghg.admin")) {
            List<String> adminLore = new ArrayList<>(lines(viewer, "ghg.help.admin"));
            menu.setItem(15, icon(Material.BOOK, msg(viewer, "menu.commands.admin-name"), adminLore));
        }
        menu.setItem(NAV_SLOT, icon(Material.ARROW, msg(viewer, "menu.back"), new ArrayList<>()));
        viewer.openInventory(menu);
    }

    // ================================================================ 管理员板块

    private static final int ADMIN_SIZE = 45;
    /** 每页 14 格：第二排 7 个 + 第三排 7 个 */
    private static final int[] ADMIN_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    /**
     * 管理员板块：把最常调的那批数值做成能点的格子，左键加、右键减。
     *
     * <p>写的是**真实的 config.yml**（{@code ConfigManager.setValue} 会落盘），
     * 不是内存里的临时覆盖——所以改完重启也还在，也不会和自己的配置打架。</p>
     */
    private void openAdmin(Player viewer, int page) {
        int pages = Math.max(1, (TUNEABLES.size() + ADMIN_PER_PAGE - 1) / ADMIN_PER_PAGE);
        int current = Math.max(1, Math.min(pages, page));
        Inventory menu = Bukkit.createInventory(
            new Holder(viewer.getUniqueId(), Section.ADMIN, current), ADMIN_SIZE,
            msg(viewer, "menu.admin.title"));

        int from = (current - 1) * ADMIN_PER_PAGE;
        for (int i = 0; i < ADMIN_PER_PAGE && from + i < TUNEABLES.size(); i++) {
            Tuneable t = TUNEABLES.get(from + i);
            double value = valueOf(t);
            List<String> lore = new ArrayList<>();
            lore.add(msg(viewer, "menu.admin.lore-value", formatValue(t, value)));
            lore.add(msg(viewer, "menu.admin.lore-range",
                formatValue(t, t.min), formatValue(t, t.max), formatValue(t, t.step)));
            lore.add(msg(viewer, "menu.admin.lore-click", formatValue(t, t.step)));
            lore.add(msg(viewer, "menu.admin.live-hint"));
            menu.setItem(ADMIN_SLOTS[i], icon(materialFor(t), t.key, lore));
        }

        // 动作行：热重载 + 三个开关对局的快捷动作
        menu.setItem(28, icon(Material.BLAZE_POWDER,
            msg(viewer, "menu.admin.reload"), lines(viewer, "menu.admin.lore.reload")));
        menu.setItem(30, icon(Material.REDSTONE_BLOCK,
            msg(viewer, "menu.admin.stop"), lines(viewer, "menu.admin.lore.stop")));
        menu.setItem(32, icon(Material.CLOCK,
            msg(viewer, "menu.admin.queue-clear"), lines(viewer, "menu.admin.lore.queue-clear")));
        menu.setItem(34, icon(Material.BEACON,
            msg(viewer, "menu.admin.force-start"), lines(viewer, "menu.admin.lore.force-start")));

        // 翻页与返回
        if (current > 1) {
            menu.setItem(36, icon(Material.ARROW, msg(viewer, "menu.admin.prev"), new ArrayList<>()));
        }
        menu.setItem(38, icon(Material.PAPER,
            msg(viewer, "menu.admin.page", String.valueOf(current), String.valueOf(pages)),
            new ArrayList<>()));
        menu.setItem(40, icon(Material.HOPPER, msg(viewer, "menu.admin.refresh"), new ArrayList<>()));
        if (current < pages) {
            menu.setItem(42, icon(Material.ARROW, msg(viewer, "menu.admin.next"), new ArrayList<>()));
        }
        menu.setItem(44, icon(Material.ARROW, msg(viewer, "menu.back"), new ArrayList<>()));
        viewer.openInventory(menu);
    }

    private double valueOf(Tuneable t) {
        return plugin.getConfigManager().getValue(t.key,
            t.integral ? Math.round(t.min) : t.min);
    }

    /** 整数直接取整；小数按步长对齐后再保留两位，避免 0.30000000000000004 */
    private double normalize(Tuneable t, double raw) {
        double clamped = Math.max(t.min, Math.min(t.max, raw));
        double stepped = Math.round(clamped / t.step) * t.step;
        return Math.max(t.min, Math.min(t.max, stepped));
    }

    private String formatValue(Tuneable t, double value) {
        if (t.integral) return String.valueOf((int) Math.round(value));
        double rounded = Math.round(value * 100.0) / 100.0;
        if (rounded == Math.floor(rounded)) return String.valueOf((int) rounded);
        return String.valueOf(rounded);
    }

    /** 界面点击音（软一点，别吵） */
    private void clickSound(Player player) {
        try {
            player.playSound(player.getLocation(), SoundCompat.uiButtonClick(), 0.5f, 1.5f);
        } catch (Throwable ignored) {
        }
    }

    private void adjust(Player player, Tuneable t, double delta) {
        double next = normalize(t, valueOf(t) + delta);
        if (!t.integral) next = Math.round(next * 100.0) / 100.0;
        Object stored = t.integral ? (Object) (int) Math.round(next) : (Object) next;
        plugin.getConfigManager().setValue(t.key, stored);
        // 有些数值是开局时读进字段的，这里补一次，能热的立刻热起来
        plugin.getHunterGameManager().refreshLiveConfig();
        try {
            player.playSound(player.getLocation(), SoundCompat.notePling(), 0.7f, 1.3f);
        } catch (Throwable ignored) {
        }
        String shown = formatValue(t, next);
        plugin.getLanguageManager().sendMessage(player, "menu.admin.changed", t.key, shown);
    }

    private Material materialFor(Tuneable t) {
        String key = t.key;
        if (key.startsWith("hunter.altar")) return Material.ENDER_EYE;
        if (key.startsWith("hunter.mother")) return Material.ROTTEN_FLESH;
        if (key.startsWith("hunter.reward")) return Material.GOLD_INGOT;
        if (key.startsWith("hunter.queue")) return Material.LIME_DYE;
        if (key.startsWith("hunter.chest")) return Material.MELON;
        if (key.startsWith("hunter.chair")) return Material.CRYING_OBSIDIAN;
        if (key.startsWith("hunter.downed") || key.startsWith("hunter.heal")) return Material.GOLDEN_APPLE;
        if (key.startsWith("hunter.endgame") || key.startsWith("hunter.escape")) return Material.COMPASS;
        return Material.CLOCK;
    }

    // ================================================================ 点击分发

    /**
     * 处理一次点击。所有菜单的点击都从这里进，返回前已经打开了下一页或执行完动作。
     */
    public void handleClick(Player player, Holder holder, int slot,
                            org.bukkit.event.inventory.ClickType click) {
        if (player == null || holder == null) return;
        /* 界面点击的统一反馈音：之前整个 /ghg 菜单一点声音都没有，点上去像卡了 */
        clickSound(player);
        Section section = holder.getSection();

        // 返回 / 关闭：27 格板块统一放在 22 号格；管理员页是 45 格，22 是调参位，跳过
        if (slot == NAV_SLOT && section != Section.ADMIN) {
            if (section == Section.MAIN) {
                player.closeInventory();
            } else {
                openMain(player);
            }
            return;
        }

        switch (section) {
            case MAIN:
                switch (slot) {
                    case 10:
                        close(player, "ghg info");
                        return;
                    case 11:
                        openBattle(player);
                        return;
                    case 12:
                        openRoles(player);
                        return;
                    case 13:
                        openKinds(player);
                        return;
                    case 14:
                        openFlow(player, 1);
                        return;
                    case 15:
                        openLang(player);
                        return;
                    case 16:
                        openCommands(player);
                        return;
                    case 17:
                        if (player.hasPermission("ghg.admin")) openAdmin(player, 1);
                        return;
                    default:
                        return;
                }

            case BATTLE:
                switch (slot) {
                    case 10:
                        close(player, "ghg join");
                        return;
                    case 11:
                        close(player, "ghg leave");
                        return;
                    case 12:
                        close(player, "ghg info");
                        return;
                    case 13:
                        if (player.hasPermission("ghg.admin")) close(player, "ghga hunter queue");
                        return;
                    case 14:
                        if (player.hasPermission("ghg.admin")) close(player, "ghga hunter queue start");
                        return;
                    default:
                        return;
                }

            case ROLES: {
                List<HunterRole> roles = plugin.getHunterGameManager().menuRoles();
                int index = slot - LIST_START;
                if (index < 0 || index >= roles.size()) return;
                HunterRole role = roles.get(index);
                if (role == plugin.getHunterGameManager().getRole(player.getUniqueId())) return;
                if (!plugin.getHunterGameManager().selectRole(player, role)) {
                    return; // selectRole 自己会说原因（太晚 / 钱不够 / AI 局没有冰球手）
                }
                player.closeInventory();
                return;
            }

            case KINDS: {
                List<MotherKind> kinds = plugin.getHunterGameManager().menuKinds();
                int index = slot - LIST_START;
                if (index < 0 || index >= kinds.size()) return;
                MotherKind kind = kinds.get(index);
                HunterGameManager game = plugin.getHunterGameManager();
                if (!game.isPlayerMother(player.getUniqueId()) || !game.isPreparing()) {
                    plugin.getLanguageManager().sendMessage(player, "menu.kinds.locked");
                    return;
                }
                if (kind == game.getMotherKind()) return;
                if (game.selectMotherKind(kind)) {
                    player.closeInventory();
                }
                return;
            }

            case FLOW:
                if (slot == 11) openFlow(player, holder.getPage() - 1);
                else if (slot == 15) openFlow(player, holder.getPage() + 1);
                else if (slot == 13) openFlow(player, holder.getPage());
                return;

            case LANG: {
                int index = slot - LIST_START;
                if (index < 0 || index >= LanguageManager.SUPPORTED.length) return;
                String locale = LanguageManager.SUPPORTED[index];
                if (plugin.getLanguageManager().setLanguage(player, locale)) {
                    plugin.getLanguageManager().sendMessage(player, "command.lang.set", locale);
                }
                openLang(player);
                return;
            }

            case COMMANDS:
                return;

            case ADMIN: {
                if (!player.hasPermission("ghg.admin")) {
                    plugin.getLanguageManager().sendMessage(player, "menu.admin.only-admin");
                    return;
                }
                if (slot == 44) {
                    openMain(player);
                    return;
                }
                if (slot == 36) {
                    openAdmin(player, holder.getPage() - 1);
                    return;
                }
                if (slot == 42) {
                    openAdmin(player, holder.getPage() + 1);
                    return;
                }
                if (slot == 38 || slot == 40) {
                    openAdmin(player, holder.getPage());
                    return;
                }
                // 热重载 / 收局 / 清队 / 立即开局：都走原命令，行为与手打一致
                if (slot == 28) {
                    close(player, "ghga reload");
                    return;
                }
                if (slot == 30) {
                    close(player, "ghga stop");
                    return;
                }
                if (slot == 32) {
                    close(player, "ghga queue stop");
                    return;
                }
                if (slot == 34) {
                    close(player, "ghga queue start");
                    return;
                }

                int index = -1;
                for (int i = 0; i < ADMIN_SLOTS.length; i++) {
                    if (ADMIN_SLOTS[i] == slot) {
                        index = (holder.getPage() - 1) * ADMIN_PER_PAGE + i;
                        break;
                    }
                }
                if (index < 0 || index >= TUNEABLES.size()) return;

                Tuneable t = TUNEABLES.get(index);
                boolean right = click != null && click.isRightClick();
                boolean shift = click != null && click.isShiftClick();
                double step = t.step * (shift ? 5 : 1);
                adjust(player, t, right ? -step : step);
                openAdmin(player, holder.getPage());
                return;
            }

            default:
                return;
        }
    }

    // ================================================================ 小工具

    /** 关掉菜单再跑命令：命令自己的提示才不会被界面挡住 */
    private void close(Player player, String command) {
        player.closeInventory();
        player.performCommand(command);
    }

    private Inventory create(Player viewer, Section section, int page, String titleKey) {
        return Bukkit.createInventory(new Holder(viewer.getUniqueId(), section, page), SIZE,
            plugin.getLanguageManager().getMessage(viewer, titleKey));
    }

    /**
     * 取界面文案。
     *
     * <p>⚠️ 万一某个键真的没配（漏加 / 用户旧语言文件里没有），{@code LanguageManager} 会把
     * **键本身**返回回来（那是给开发看的信号）。但菜单是给玩家看的，不能把 `menu.main.xxx`
     * 这种裸键摆在格子上，所以这里兜一层：认出来是键就换成一个中性占位，并在控制台记一次。</p>
     */
    private String msg(Player viewer, String key, Object... args) {
        String text = plugin.getLanguageManager().getMessage(viewer, key, args);
        if (text != null && !text.equals(key)) return text;
        if (missingKeys.add(key)) {
            plugin.getLogger().warning("界面文案缺失: " + key + "（语言文件里没有这个键，菜单已用占位符代替）");
        }
        return "§8—";
    }

    /** 已经警告过的缺失键，别每帧刷屏 */
    private final java.util.Set<String> missingKeys = new java.util.HashSet<>();

    /** 取一条多行文案，逐行铺进 lore */
    private List<String> lines(Player viewer, String key) {
        List<String> result = new ArrayList<>();
        for (String line : msg(viewer, key).split("\n")) {
            if (line.isEmpty()) continue;
            result.add(line);
        }
        return result;
    }

    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 主菜单「我的状态」那块的内容 */
    private List<String> stateLore(Player viewer) {
        List<String> lore = new ArrayList<>();
        HunterGameManager game = plugin.getHunterGameManager();
        lore.add(msg(viewer, "menu.state.battle", msg(viewer, game.isRunning()
            ? "command.info.state-running"
            : game.isPreparing() ? "command.info.state-waiting" : "command.info.state-idle")));

        HunterRole role = game.getRole(viewer.getUniqueId());
        lore.add(msg(viewer, "menu.state.role",
            role == null ? msg(viewer, "menu.state.none") : msg(viewer, role.nameKey())));

        if (game.isPlayerMother(viewer.getUniqueId())) {
            lore.add(msg(viewer, "menu.state.kind", msg(viewer, game.getMotherKind().nameKey())));
        }
        lore.add(msg(viewer, "menu.state.queue", String.valueOf(plugin.getQueueManager().size())));
        if (plugin.getEconomyManager().isEconomyEnabled()) {
            lore.add(msg(viewer, "menu.state.pool",
                plugin.getEconomyManager().format(plugin.getEconomyManager().getPrizePool())));
        }
        return lore;
    }
}
