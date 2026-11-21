package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 排队开局（{@code /ghg join}）。
 *
 * <p>照着 Gost 那套队列做的：玩家自己进队，屏幕上方一条 BossBar 显示「现在几个人 / 要几个人」，
 * 人凑够就进入开机倒计时，到点自动开局；凑人阶段超时还没凑够就取消队列。</p>
 *
 * <p>和 Gost 的区别只有一点：GHG 是 PvE，所以默认<b>队列里只排逃生者</b>，开局刷 AI 母体
 * （{@code hunter.queue.mother: AI}）。想让一个真人当母体就把它设成 {@code PLAYER}，
 * 那时**从队列里随机抽一人**当母体、其余人当逃生者 —— 谁当母体不随入队顺序，</p>
 * <p>大家进队前不会知道自己这局是猎人还是猎物。</p>
 *
 * <p>队列不收钱：入场费与职业券还是走 {@code HunterGameManager.start()} 原来那套。
 * 这样「排队 → 取消 → 再排」不会来回退钱，也不会重复扣。</p>
 */
public class QueueManager {

    private final GHG plugin;

    /** 入队顺序只影响「谁先看到队列人数」，不决定谁当母体（PLAYER 模式下随机抽） */
    private final List<UUID> queue = new ArrayList<>();
    private BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private ServerCompat.Cancellable task;

    /** true = 人已凑够，正在跑开机倒计时；false = 还在凑人 */
    private boolean countingDown;
    private int secondsLeft;

    public QueueManager(GHG plugin) {
        this.plugin = plugin;
    }

    // ================================================================ 查询

    public boolean isEnabled() {
        return plugin.getConfigManager().isHunterQueueEnabled();
    }

    public boolean isQueued(UUID playerId) {
        return playerId != null && queue.contains(playerId);
    }

    public int size() {
        return queue.size();
    }

    /** 队列里的玩家（只读快照）：管理员切换母体模式时要逐个通知并清空 */
    public java.util.List<UUID> queuedIds() {
        return new java.util.ArrayList<>(queue);
    }

    private boolean isPlayerMotherMode() {
        return "PLAYER".equals(plugin.getConfigManager().getHunterQueueMother());
    }

    /** 队列人数上限：不会超过单局逃生者上限（PLAYER 模式多出来的那个人是母体） */
    private int capacity() {
        int maxSurvivors = plugin.getConfigManager().getHunterMaxSurvivors();
        int configured = plugin.getConfigManager().getHunterQueueMaxPlayers();
        int max = Math.max(1, Math.min(configured, maxSurvivors));
        return isPlayerMotherMode() ? max + 1 : max;
    }

    /** 需要几个人才能开：PLAYER 模式至少「1 母体 + 1 逃生者」 */
    private int required() {
        int min = plugin.getConfigManager().getHunterQueueMinPlayers();
        if (isPlayerMotherMode()) min = Math.max(2, min);
        return Math.max(1, Math.min(min, capacity()));
    }

    // ================================================================ 进出队

    /**
     * 加入队列。所有拒绝原因都在这里直接把话说给玩家，调用方只看返回值。
     *
     * @return false 表示没进队（已给出原因）
     */
    public boolean join(Player player) {
        if (player == null || !player.isOnline()) return false;
        if (!isEnabled()) {
            plugin.getLanguageManager().sendMessage(player, "hunter.queue.disabled");
            return false;
        }
        if (plugin.getHunterGameManager().isRunning() || plugin.getHunterGameManager().isPreparing()) {
            /* 对局已经开了：别只说「忙」，直接告诉他可以观战 —— 这条提示原来会让人以为插件坏了 */
            plugin.getLanguageManager().sendMessage(player,
                plugin.getHunterGameManager().isRunning()
                    ? "hunter.queue.busy-spectate" : "hunter.queue.busy");
            return false;
        }
        if (isQueued(player.getUniqueId())) {
            plugin.getLanguageManager().sendMessage(player, "hunter.queue.already");
            return false;
        }
        if (queue.size() >= capacity()) {
            plugin.getLanguageManager().sendMessage(player, "hunter.queue.full", capacity());
            return false;
        }

        queue.add(player.getUniqueId());
        plugin.getLanguageManager().sendMessage(player, "hunter.queue.joined",
            queue.size(), capacity());
        // PLAYER 模式下**谁当母体是随机的**，入队时只提示「本局会抽一人当母体」，不点名
        if (isPlayerMotherMode() && queue.size() == 1) {
            plugin.getLanguageManager().sendMessage(player, "hunter.queue.mother-random");
        }
        broadcast("hunter.queue.joined-broadcast", player.getName(), queue.size(), capacity());

        playJoinSound(player);
        ensureTimer();
        // 人数刚好够了就立刻进倒计时，不用等下一拍；已经在倒计时就别重新计时
        if (!countingDown && queue.size() >= required()) beginCountdown();
        updateBar();
        return true;
    }

    /**
     * 离开队列。
     *
     * @param silent true 表示不向该玩家发提示（掉线用）
     * @return 是否本来就在队列里
     */
    public boolean leave(Player player, boolean silent) {
        if (player == null) return false;
        UUID id = player.getUniqueId();
        if (!queue.remove(id)) return false;

        if (!silent) {
            plugin.getLanguageManager().sendMessage(player, "command.leave.queue-success");
        }
        broadcast("hunter.queue.left-broadcast", player.getName(), queue.size(), capacity());

        if (queue.isEmpty()) {
            clear();
            return true;
        }
        // 倒计时中有人跑了、人数不够 → 退回凑人阶段重新等
        if (countingDown && queue.size() < required()) {
            countingDown = false;
            secondsLeft = plugin.getConfigManager().getHunterQueueWaitSeconds();
            broadcast("hunter.queue.countdown-cancel", required());
        }
        updateBar();
        return true;
    }

    /** 掉线：静默移出 */
    public void handleQuit(Player player) {
        leave(player, true);
    }

    /** 清空队列（不广播）：管理员手动开局、对局已经开起来、插件关闭时都用它 */
    public void clear() {
        queue.clear();
        countingDown = false;
        secondsLeft = 0;
        stopTimer();
        hideBar();
    }

    // ================================================================ 计时

    private void ensureTimer() {
        if (task != null) return;
        task = ServerCompat.runTimer(plugin, this::tick, 20L, 20L);
        if (secondsLeft <= 0) secondsLeft = plugin.getConfigManager().getHunterQueueWaitSeconds();
    }

    private void stopTimer() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void beginCountdown() {
        countingDown = true;
        secondsLeft = plugin.getConfigManager().getHunterQueueCountdownSeconds();
        broadcast("hunter.queue.countdown-start", secondsLeft);
        for (Player player : onlineMembers()) {
            player.playSound(player.getLocation(), SoundCompat.noteBell(), 0.9f, 1.2f);
        }
    }

    private void tick() {
        if (queue.isEmpty()) {
            clear();
            return;
        }
        pruneOffline();

        // 有人用 /ghga hunter start 手动开了局：排队作废，别让倒计时把第二局也拉起来
        if (plugin.getHunterGameManager().isRunning() || plugin.getHunterGameManager().isPreparing()) {
            broadcast("hunter.queue.cancelled-busy");
            clear();
            return;
        }
        if (queue.isEmpty()) {
            clear();
            return;
        }

        secondsLeft--;
        if (countingDown) {
            if (queue.size() < required()) {
                countingDown = false;
                secondsLeft = plugin.getConfigManager().getHunterQueueWaitSeconds();
                broadcast("hunter.queue.countdown-cancel", required());
            } else if (secondsLeft <= 0) {
                startGame();
                return;
            }
        } else if (queue.size() >= required()) {
            beginCountdown();
        } else if (secondsLeft <= 0) {
            broadcast("hunter.queue.cancelled-need", required());
            clear();
            return;
        }
        updateBar();
    }

    private void pruneOffline() {
        boolean removed = queue.removeIf(id -> {
            Player player = Bukkit.getPlayer(id);
            return player == null || !player.isOnline();
        });
        if (removed && queue.isEmpty()) clear();
    }

    /** 队列里的在线玩家，保持入队顺序 */
    private List<Player> onlineMembers() {
        List<Player> result = new ArrayList<>();
        for (UUID id : new ArrayList<>(queue)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) result.add(player);
        }
        return result;
    }

    // ================================================================ 开局

    /** 管理员强制开局：跳过倒计时，人够就直接开 */
    public boolean forceStart(org.bukkit.command.CommandSender sender) {
        if (queue.isEmpty()) {
            reply(sender, "hunter.queue.status-empty");
            return false;
        }
        if (queue.size() < required()) {
            reply(sender, "hunter.queue.need-more", required() - queue.size());
            return false;
        }
        startGame();
        return true;
    }

    /** 回一句给执行者（控制台也要能看见原因） */
    private void reply(org.bukkit.command.CommandSender sender, String key, Object... args) {
        if (sender == null) return;
        if (sender instanceof Player) {
            plugin.getLanguageManager().sendMessage((Player) sender, key, args);
        } else {
            sender.sendMessage(plugin.getLanguageManager().getMessage(key, args));
        }
    }

    private void startGame() {
        List<Player> players = onlineMembers();
        if (players.isEmpty()) {
            clear();
            return;
        }

        /* PLAYER 模式：**从队列里随机抽一人**当母体。
           原来固定取 players.get(0)（第一个入队的人），于是谁先进队谁注定当母体，
           等于把「随机分工」变成了「抢第一个」——改成随机后，进队顺序不再有任何优势。 */
        Player mother = null;
        if (isPlayerMotherMode()) {
            mother = players.get(java.util.concurrent.ThreadLocalRandom
                .current().nextInt(players.size()));
        }
        List<Player> survivors = new ArrayList<>(players);
        if (mother != null) survivors.remove(mother);
        // PLAYER 模式只剩一个人：没有逃生者可追，退回 AI 母体，别让这局开成空局
        if (survivors.isEmpty()) {
            mother = null;
            survivors = new ArrayList<>(players);
        }

        String motherLabel = mother == null
            ? plugin.getLanguageManager().getMessage("hunter.queue.mother-ai")
            : mother.getName();
        boolean ok = plugin.getHunterGameManager().start(players.get(0), survivors, mother);

        // start() 自己会把队列清掉（避免手动开局时残留倒计时），这里只负责播报
        clear();
        if (ok) {
            broadcast("hunter.queue.started", survivors.size(), motherLabel);
        } else {
            broadcast("hunter.queue.start-failed", required());
        }
    }

    // ================================================================ 进度条 / 播报

    private void updateBar() {
        if (queue.isEmpty()) {
            hideBar();
            return;
        }
        if (bar == null) {
            try {
                bar = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID);
            } catch (Throwable t) {
                return; // 没有 BossBar 就只靠聊天提示
            }
        }
        int cap = capacity();
        try {
            bar.setVisible(true);
            if (countingDown) {
                bar.setColor(BarColor.GREEN);
                bar.setProgress(Math.max(0.0, Math.min(1.0,
                    secondsLeft / (double) Math.max(1, plugin.getConfigManager().getHunterQueueCountdownSeconds()))));
                bar.setTitle(plugin.getLanguageManager().getMessage("hunter.queue.bossbar-countdown",
                    queue.size(), cap, Math.max(0, secondsLeft)));
            } else {
                bar.setColor(BarColor.BLUE);
                bar.setProgress(Math.max(0.0, Math.min(1.0, queue.size() / (double) Math.max(1, required()))));
                bar.setTitle(plugin.getLanguageManager().getMessage("hunter.queue.bossbar-waiting",
                    queue.size(), cap, Math.max(0, secondsLeft)));
            }
        } catch (Throwable ignored) {
            return;
        }
        syncViewers();
    }

    private void syncViewers() {
        if (bar == null) return;
        for (UUID id : new ArrayList<>(viewers)) {
            if (queue.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                try {
                    bar.removePlayer(player);
                } catch (Throwable ignored) {
                }
            }
            viewers.remove(id);
        }
        for (UUID id : queue) {
            if (viewers.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) continue;
            try {
                bar.addPlayer(player);
                viewers.add(id);
            } catch (Throwable ignored) {
            }
        }
    }

    private void hideBar() {
        if (bar == null) return;
        try {
            bar.removeAll();
        } catch (Throwable ignored) {
        }
        try {
            bar.setVisible(false);
        } catch (Throwable ignored) {
        }
        viewers.clear();
    }

    private void playJoinSound(Player player) {
        try {
            player.playSound(player.getLocation(), SoundCompat.notePling(), 0.7f, 1.4f);
        } catch (Throwable ignored) {
        }
    }

    /** 全服播报（按每个人自己的语言） */
    private void broadcast(String key, Object... args) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getLanguageManager().sendMessage(player, key, args);
        }
        plugin.getLogger().info("[队列] " + plugin.getLanguageManager().getMessage(key, args));
    }

    // ================================================================ 状态

    /** /ghga hunter queue 的输出 */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add(plugin.getLanguageManager().getMessage("hunter.queue.status-header",
            queue.size(), capacity(), required()));
        if (queue.isEmpty()) {
            lines.add(plugin.getLanguageManager().getMessage("hunter.queue.status-empty"));
            return lines;
        }
        lines.add(plugin.getLanguageManager().getMessage(
            countingDown ? "hunter.queue.status-phase-count" : "hunter.queue.status-phase-wait",
            Math.max(0, secondsLeft)));
        int index = 1;
        for (UUID id : queue) {
            Player player = Bukkit.getPlayer(id);
            String name = player != null ? player.getName() : id.toString().substring(0, 8);
            lines.add(plugin.getLanguageManager().getMessage("hunter.queue.status-line", index++, name));
        }
        return lines;
    }
}
