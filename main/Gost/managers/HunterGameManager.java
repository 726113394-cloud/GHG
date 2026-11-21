package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * GHG 对局核心：PVE 逃亡。
 *
 * <p>最小闭环（第一版）：破法阵 → 全部破除后到逃脱点读条撤离；AI 母体追击，
 * 被打中两次会击倒并锁定坐标、持续发光，需要同伴在时限内救起。</p>
 *
 * <p>刻意「小」：1 个法阵 + 1 个逃脱点 + 1 种母体 + 1 种道具箱 + 击倒救援，
 * 不加技能、不加多母体。等跑够 10 局拿到胜率与时长数据再加。</p>
 */
public class HunterGameManager {

    public enum State {
        IDLE,
        /** 准备阶段：人类可自由移动，法阵不可破，母体定身，可选母体种类 */
        PREPARING,
        RUNNING,
        ENDING
    }

    /** 一个法阵：若干格 + 独立进度 */
    public static final class Altar {
        private final int index;
        private final List<Location> cells;
        private final Location center;
        private double progress;
        private boolean charged;
        /** 已经播报到哪个进度里程碑（25/50/75），避免每秒刷音效 */
        private int announcedStep;
        /** 是不是最后一个被破除的法阵（撤离点不能选它） */
        private boolean lastCharged;
        /** 母体加固到什么时候（毫秒时间戳）：这段时间人类破不了这座阵 */
        private long hardenedUntil;
        /** 瘫痪到什么时候（毫秒时间戳）：这期间既不能推也不能加固 */
        private long paralyzedUntil;
        /** 瘫痪是不是已经触发过（每座阵只瘫一次） */
        private boolean paralyzedOnce;
        /** 破阵石加速到什么时候（毫秒时间戳）：到期前涨速乘 boostMultiplier */
        private long boostUntil;
        /** 破阵石加速倍率（1.0 = 没加速） */
        private double boostMultiplier = 1.0;
        /** 上一次滴答声的时间（毫秒）：蓄力时按间隔「哒哒哒」 */
        private long lastTickAt;
        /** 被钉在这座法阵上的逃生者：非 null 时这座法阵**失效**（不能继续破除，进度冻结在当下） */
        private UUID occupant;

        boolean isSealed() { return occupant != null; }

        Altar(int index, List<Location> cells) {
            this.index = index;
            this.cells = cells;
            this.center = cells.get(0).clone().add(0, 0, 0);
        }

        public int getIndex() { return index; }
        public List<Location> getCells() { return Collections.unmodifiableList(cells); }
        public Location getCenter() { return center; }
        public double getProgress() { return progress; }
        public boolean isCharged() { return charged; }
        public int getProgressPercent() { return (int) Math.round(progress); }
    }

    /** 救援读条进度（key = 被救者） */
    private static final class RescueProgress {
        private final UUID rescuerId;
        private int ticks;

        RescueProgress(UUID rescuerId) {
            this.rescuerId = rescuerId;
            this.ticks = 0;
        }
    }

    /** 道具箱（可破坏的方块） */
    public static final class Chest {
        private final Location location;
        private int hits;
        private boolean opened;

        Chest(Location location) {
            this.location = location;
        }

        public Location getLocation() { return location; }
        public boolean isOpened() { return opened; }
    }

    /** 击倒状态 */
    public static final class DownedInfo {
        private final UUID playerId;
        private final Location lockedLocation;
        private final long downedAt;
        private int rescuesTaken;

        DownedInfo(UUID playerId, Location lockedLocation, long downedAt, int rescuesTaken) {
            this.playerId = playerId;
            this.lockedLocation = lockedLocation;
            this.downedAt = downedAt;
            this.rescuesTaken = rescuesTaken;
        }

        public UUID getPlayerId() { return playerId; }
        public Location getLockedLocation() { return lockedLocation; }
        public long getDownedAt() { return downedAt; }
        public int getRescuesTaken() { return rescuesTaken; }
    }

    private final GHG plugin;
    private final Random random = new Random();

    private State state = State.IDLE;
    private AreaManager.GameArea area;
    private final List<Altar> altars = new ArrayList<>();
    private final List<Chest> chests = new ArrayList<>();
    private final Map<String, Chest> chestIndex = new HashMap<>();

    /** 我们对世界改过的方块 → 原始材质。结束/插件禁用时全部还原，不留痕 */
    private final Map<Location, Material> placedBlocks = new LinkedHashMap<>();

    // 法阵/逃脱点的可视材质：1.16+ 全线存在、非朝向方块，跨版本安全
    /* 法阵「未充能」的地砖：粉红色染色玻璃。
       母体连打碎它 16 次就能加固这座阵（玻璃一碰就碎，手感是「拍碎」，不是「挖矿」）。
       之前用过樱花树叶，但树叶哪怕标了 persistent 也容易看着像「在掉」，所以换成玻璃 ——
       玻璃没有方块状态、不会自己消失，颜色也对得上法阵的紫粉调。 */
    private static final Material PAD_TILE_OFF = Material.PINK_STAINED_GLASS;
    private static final Material PAD_TILE_ON = Material.SEA_LANTERN;
    private static final Material PAD_FRAME = Material.CRYING_OBSIDIAN;
    /** 瘫痪中的地砖：灰色玻璃（一看就知道这座阵暂时没用） */
    private static final Material PAD_TILE_PARALYZED = Material.GRAY_STAINED_GLASS;

    private Location escapePoint;
    private boolean escapeActive;
    private int escapeTicks;

    /** 珍珠女士：珍珠传送带来的缓慢到什么时候（毫秒时间戳）；0 = 没有 */
    private long pearlSlowUntil;

    /** 观战席：谁在看 + 他入席前的状态（退出时还原） */
    private static final class SpectatorInfo {
        private final GameMode gameMode;
        private final Location location;

        SpectatorInfo(GameMode gameMode, Location location) {
            this.gameMode = gameMode;
            this.location = location == null ? null : location.clone();
        }
    }

    private final Map<UUID, SpectatorInfo> spectators = new HashMap<>();
    /** 观战越界提示的节流表 */
    private final Map<UUID, Long> spectatorWarnAt = new HashMap<>();
    /** 已经提前结算过的淘汰玩家（防止重复领） */
    private final Set<UUID> earlySettled = new HashSet<>();

    private Entity mother;
    private long lastMotherHitAt;
    /** 上一次补刷母体的时间（防止每 tick 重试刷屏） */
    private long lastMotherSpawnAt;
    /** 母体生成失败的告警只打一次，后面安静重试 */
    private boolean motherSpawnWarned;

    /** 玩家母体（非 null 时不刷 AI 母体） */
    private UUID motherPlayerId;
    private MotherKind motherKind = MotherKind.SPRINTER;

    private final Map<UUID, Long> skillCooldowns = new HashMap<>();
    private final Map<UUID, RescueProgress> rescueProgress = new LinkedHashMap<>();
    private final Map<UUID, Long> nextHeartbeatAt = new HashMap<>();
    private ServerCompat.Cancellable heartbeatTask;

    private final Map<UUID, Integer> hitCounts = new HashMap<>();
    private final Map<UUID, DownedInfo> downed = new LinkedHashMap<>();
    private final Map<UUID, Integer> rescueCounts = new HashMap<>();


    /** 每人上椅次数：第一次 first-seconds，第二次 second-seconds，第三次直接升天 */
    private final Map<UUID, Integer> chairCount = new HashMap<>();
    /** 椅子上剩余秒数 */
    private final Map<UUID, Integer> chairTicks = new HashMap<>();
    /** AI 母体上椅后还会在椅子边守多久（毫秒时间戳） */
    private long motherCampUntil;
    /** 母体正抱着谁（null = 没抱人） */
    private UUID carriedId;
    /** 骑乘是否真的挂上了（失败就退回每 tick 牵引） */
    private boolean carryRiding;
    /** 玩家母体的束抱进度起点（毫秒） */
    private long grabStartedAt;

    private ServerCompat.Cancellable tickTask;
    private int elapsedSeconds;
    private int durationSeconds;

    /** 准备阶段剩余秒数 */
    private int prepSecondsLeft;
    /** 传送技能留下的硬直（毫秒时间戳） */
    private long motherFreezeUntil;
    /** 命中后的后摇截止时间（毫秒时间戳）：这段时间里母体不追、不砍、不放技能 */
    private long motherRecoverUntil;
    /** 本局结束原因：ESCAPE / HATCH / WIPED / TIMEOUT / ABORT，结算提示按它选文案 */
    private String endReason = "ESCAPE";
    /** 上一次记录的母体位置，用来判断「有没有在移动」（视野技能用） */
    private Location lastMotherLocation;
    /** 每个人的跳跃时间戳（跳跃疲劳用） */
    private final Map<UUID, Deque<Long>> jumpTimes = new HashMap<>();

    /** 已淘汰（旁观）的玩家 */
    private final Set<UUID> eliminated = new HashSet<>();
    /** 放血倒计时（key = 被击倒者） */
    private final Map<UUID, Integer> bleedout = new HashMap<>();

    /** 独狼地窖 */
    private Location hatchPoint;
    private boolean hatchActive;
    /** 终局倒计时 */
    private boolean endgameActive;
    private int endgameSecondsLeft;

    /** 母体 AI 决策层 */
    private MotherBrain motherBrain;

    /** 单人测试模式 */
    private boolean testMode;
    /** 测试模式下母体是否主动追击（默认站桩） */
    private boolean testMotherAggressive;
    /** 开局时的逃生者人数（地窖判定要用：单人开局不该立刻开地窖） */
    private int initialSurvivors;



    /** 被撞晕的玩家（key = 玩家，value = 到期时间戳） */
    private final Map<UUID, Long> stunnedUntil = new HashMap<>();
    /** 冲撞的撞击判定任务 */
    private ServerCompat.Cancellable dashTask;
    private Entity dashCaster;
    /** 玩家母体这个种类已经付过的钱（换种类时先退再扣） */
    private double paidKindCost;

    /** 法阵破除进度条 */
    private org.bukkit.boss.BossBar altarBar;
    private final Set<UUID> altarBarViewers = new HashSet<>();

    /** 母体测试模式（管理员当母体，人类侧是假人） */
    private boolean motherTestMode;
    /** 测试假人 */
    private final List<UUID> dummies = new ArrayList<>();
    private final Map<UUID, Integer> dummyHits = new HashMap<>();
    /** 假人的游荡目标（母体不在附近时自己走动，别站桩） */
    private final Map<UUID, Location> dummyWander = new HashMap<>();
    private final Map<UUID, Long> dummyWanderUntil = new HashMap<>();
    private final Map<UUID, Location> dummyLastPos = new HashMap<>();
    /** 假人从什么时候开始连续逃跑（0 = 没在逃）；用来做体力耗尽 */
    private final Map<UUID, Long> dummyFleeSince = new HashMap<>();
    /** 假人体力耗尽、站着喘到什么时候 */
    private final Map<UUID, Long> dummyTiredUntil = new HashMap<>();
    /** 假人移动任务：4 刻一次，自己推速度（AI 已关） */
    private ServerCompat.Cancellable dummyTask;

    /** 假人逃跑 / 游荡的推进速度（格每刻，玩家步行约 0.21） */
    /* 每刻推一次的口径（原来 4 刻一次，0.30 相当于平均 0.07/刻，几乎不动）。
       逃跑速度已改为可配置（hunter.test.dummy-flee-speed），这里只留游荡速度。 */
    private static final double DUMMY_WANDER_SPEED = 0.10;

    /** 下次补假人的时间戳 */
    private long nextDummyTopUp;

    /** 已经提醒过「饿了」的人（避免刷屏） */
    private final Set<UUID> hungerWarned = new HashSet<>();

    /** 逃生者构筑（每人一个被动） */
    private final Map<UUID, HunterRole> perks = new HashMap<>();

    /** 贡献统计：破阵累计刻数 / 救援次数（结算按权重分奖池） */
    private final Map<UUID, Integer> chargeTicks = new HashMap<>();
    private final Map<UUID, Integer> rescueDone = new HashMap<>();
    /** 本局是否被强制中断（中断不结算，全额退款） */
    private boolean aborted;

    /** 腿软的到期时间（期间禁止起跳） */
    private final Map<UUID, Long> jumpFatigueUntil = new HashMap<>();

    /** 这个玩家现在是不是腿软中（禁止起跳） */
    public boolean isJumpFatigued(UUID playerId) {
        if (playerId == null) return false;
        Long until = jumpFatigueUntil.get(playerId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            jumpFatigueUntil.remove(playerId);
            return false;
        }
        return true;
    }

    /** 被动透视当前是否生效（用于状态切换反馈） */
    private boolean passiveSightActive;

    /** 站着不动的起始时刻（被动透视用） */
    private long stationarySince;
    /** 窥视者隐身期间的加速是否还挂着 */
    private boolean vanishSpeedActive;
    /** 自救读条（key = 被击倒者） */
    /** 倒地后「没人处理就自己爬起来」的剩余秒数 */
    private final Map<UUID, Integer> downedRecoverTicks = new HashMap<>();
    /** 职业技能道具的补发时间（毫秒） */
    private final Map<UUID, Long> roleItemReadyAt = new HashMap<>();
    /** 工程师放的栅栏方块（key = blockKey），拆掉/收局时清 */
    private final Set<String> fenceBlocks = new HashSet<>();
    /** 栅栏已经被打了几下（key = blockKey） */
    private final Map<String, Integer> fenceHits = new HashMap<>();
    /** 被抱着的人的挣扎进度 */
    private final Map<UUID, Integer> struggleTicks = new HashMap<>();
    /** AI 母体「卡住判定」的锚点与起始时间 */
    private final Map<UUID, Location> motherStuckAnchor = new HashMap<>();
    private final Map<UUID, Long> motherStuckSince = new HashMap<>();
    /** 共享进度播报到第几档（10% 一格），避免每刻刷音效 */
    private int sharedAnnouncedStep;
    /** **共享**的法阵总进度（0-100）：所有法阵共用一个进度条 */
    private double altarProgress;
    /** 各地砖「连续打碎」的计数 / 最后一次的时间戳（按法阵序号索引） */
    private final Map<Integer, Integer> altarHardenHits = new java.util.HashMap<>();
    private final Map<Integer, Long> altarHardenLastAt = new java.util.HashMap<>();
    /** 上一次加固成功的时间戳（加固冷却用） */
    private long lastHardenAt;
    /** 「法阵全破」的播报/标题/音效只做一次（光靠 escapeActive 不够稳） */
    private boolean allChargedCelebrated;
    /** 撤离点开不出来时的告警节流（别每 tick 刷） */
    private long lastEscapeWarnAt;
    /** 被救下后的受击保护截止时间（毫秒）：这段时间母体砍不动 */
    private final Map<UUID, Long> rescueProtectUntil = new HashMap<>();
    /** 救赎护符：救援加速的截止时间（毫秒） */
    private final Map<UUID, Long> rescueBoostUntil = new HashMap<>();
    /** 治疗进度（key = 被治疗者） */
    private final Map<UUID, Integer> healProgress = new HashMap<>();
    /** 道具箱补货任务 */
    private ServerCompat.Cancellable chestWaveTask;
    /** 法阵位置通报任务 */
    private ServerCompat.Cancellable altarLocatorTask;
    /** 法阵进度结算任务（4 刻一次，做实时进度条） */
    private ServerCompat.Cancellable altarTask;
    /** 倒地姿态重申任务（每 tick） */
    private ServerCompat.Cancellable downedPoseTask;
    /** 血量显示校正任务（健康 10 心 / 受伤 3 心 / 倒地 1 心） */
    private ServerCompat.Cancellable healthDisplayTask;
    /** 母体高频追击任务（10 刻） */
    private ServerCompat.Cancellable motherChaseTask;
    /** 高频任务选出的当前目标，1 秒那套直接读它 */
    private Player motherChaseTarget;
    /** 粒子表现任务（4 刻一次） */
    private ServerCompat.Cancellable particleTask;
    /** 粒子动画相位，用来让圈转起来 */
    private double particlePhase;
    private ServerCompat.Cancellable trailTask;

    public HunterGameManager(GHG plugin) {
        this.plugin = plugin;
        this.motherBrain = new MotherBrain(plugin);
    }

    // ================================================================ 状态查询

    public State getState() { return state; }

    public boolean isRunning() { return state == State.RUNNING; }

    public boolean isPreparing() { return state == State.PREPARING; }

    public AreaManager.GameArea getArea() { return area; }

    public List<Altar> getAltars() { return Collections.unmodifiableList(altars); }

    public List<Chest> getChests() { return Collections.unmodifiableList(chests); }

    public Location getEscapePoint() { return escapePoint; }

    public boolean isEscapeActive() { return escapeActive; }

    public Entity getMother() { return mother; }

    public int getElapsedSeconds() { return elapsedSeconds; }

    public int getRemainingSeconds() { return Math.max(0, durationSeconds - elapsedSeconds); }

    public boolean isDowned(UUID playerId) {
        return downed.containsKey(playerId);
    }

    public Location getDownedLocation(UUID playerId) {
        DownedInfo info = downed.get(playerId);
        return info == null ? null : info.getLockedLocation();
    }

    public int getHitCount(UUID playerId) {
        return hitCounts.getOrDefault(playerId, 0);
    }

    /** 法阵是否全部破除 */
    public boolean allAltarsCharged() {
        if (altars.isEmpty()) return false;
        for (Altar altar : altars) {
            if (!altar.isCharged()) return false;
        }
        return true;
    }

    // ================================================================ 开局

    /**
     * 开局。
     *
     * @param initiator    发起者（命令执行人），可为 null
     * @param participants 参与的逃生者；为空时取区域内的玩家
     * @return false 表示没能开局（已给出原因）
     */
    public boolean start(Player initiator, List<Player> participants) {
        return start(initiator, participants, null);
    }

    /**
     * 开局（可指定玩家母体）。
     *
     * @param motherPlayer 玩家母体；null 表示刷 AI 母体
     */
    public boolean start(Player initiator, List<Player> participants, Player motherPlayer) {
        return start(initiator, participants, motherPlayer, false, false);
    }

    /**
     * @param allowNoSurvivors 允许一个真人逃生者都没有（母体测试模式用假人代替）
     * @param testMatch        测试局（不收入场费、不自动收局、不进统计）
     */
    private boolean start(Player initiator, List<Player> participants,
                          Player motherPlayer, boolean allowNoSurvivors, boolean testMatch) {
        if (state != State.IDLE) {
            if (initiator != null) plugin.getLanguageManager().sendMessage(initiator, "hunter.error.already-running");
            return false;
        }
        if (!plugin.getModeManager().enter(MatchMode.HUNTER)) {
            if (initiator != null) plugin.getLanguageManager().sendMessage(initiator, "hunter.error.mode-busy");
            return false;
        }
        // 无论这局是排队开的还是管理员手动开的，队列都到此为止：
        // 否则排队倒计时会在下一拍把第二局也拉起来
        if (plugin.getQueueManager() != null) plugin.getQueueManager().clear();

        AreaManager.GameArea gameArea = plugin.getAreaManager().getSelectedArea();
        if (gameArea == null) gameArea = plugin.getAreaManager().autoSelectArea();
        if (gameArea == null || gameArea.getPos1() == null || gameArea.getPos1().getWorld() == null) {
            plugin.getModeManager().leave(MatchMode.HUNTER);
            if (initiator != null) plugin.getLanguageManager().sendMessage(initiator, "hunter.error.no-area");
            return false;
        }
        if (!gameArea.getPos1().getWorld().isChunkLoaded(
                gameArea.getCenter().getBlockX() >> 4, gameArea.getCenter().getBlockZ() >> 4)) {
            // 只是提示：Chunk 未加载不等于区域无效，传送时会加载
            plugin.getLogger().info("GHG 开局：区域中心所在区块尚未加载，将在传送时加载");
        }
        this.area = gameArea;

        List<Player> survivors = participants == null ? new ArrayList<>() : new ArrayList<>(participants);
        if (survivors.isEmpty()) {
            survivors.addAll(plugin.getAreaManager().getPlayersInArea(gameArea));
        }
        survivors.removeIf(p -> p == null || !p.isOnline());
        if (motherPlayer != null) {
            survivors.removeIf(p -> p.getUniqueId().equals(motherPlayer.getUniqueId()));
        }
        if (survivors.isEmpty() && !allowNoSurvivors) {
            plugin.getModeManager().leave(MatchMode.HUNTER);
            if (initiator != null) plugin.getLanguageManager().sendMessage(initiator, "hunter.error.no-players");
            return false;
        }

        // 单只 AI 母体压不住更多人：超过上限直接拒绝，别开局后再劝退
        int maxSurvivors = plugin.getConfigManager().getHunterMaxSurvivors();
        if (survivors.size() > maxSurvivors) {
            plugin.getModeManager().leave(MatchMode.HUNTER);
            if (initiator != null) {
                plugin.getLanguageManager().sendMessage(initiator, "hunter.error.too-many", maxSurvivors);
            }
            return false;
        }

        resetRuntimeState();
        durationSeconds = plugin.getConfigManager().getHunterDuration();

        // 以发起者（通常是管理员）站的那一层当参照，法阵不会掉到地下或长到屋顶
        buildReferenceY = Integer.MIN_VALUE;
        if (initiator != null && initiator.getWorld() == gameArea.getPos1().getWorld()) {
            buildReferenceY = initiator.getLocation().getBlockY();
        } else if (!survivors.isEmpty()) {
            buildReferenceY = survivors.get(0).getLocation().getBlockY();
        }
        buildAltars(survivors.size());
        buildEscapePoint();
        // 准备阶段就把道具箱撒出去：人类这段时间就该开始搜刮物资
        buildChests();

        startAltarBar();

        plugin.getHunterStatsManager().begin(gameArea, survivors.size(), altars.size(),
            motherKind.name(), motherPlayer != null);

        state = State.PREPARING;
        motherKind = resolveInitialKind(motherPlayer != null);

        // 测试标记必须在收钱之前定下来，否则测试局会扣了入场费却永远不结算
        testMode = testMatch;

        // 奖池：逃生者入场费 + 服务器保底（测试局不收费）
        if (!testMode && plugin.getConfigManager().isHunterRewardEnabled()) {
            plugin.getEconomyManager().resetPool();
            for (Player player : survivors) {
                plugin.getEconomyManager().chargeEntryFee(player);
            }
            plugin.getEconomyManager().addServerBonus();
            if (plugin.getEconomyManager().getPrizePool() > 0) {
                broadcast("hunter.reward.pool-open",
                    plugin.getEconomyManager().format(plugin.getEconomyManager().getPrizePool()));
            }
        }
        for (Player player : survivors) {
            plugin.getPlayerManager().joinHunterGame(player);
            player.setHealth(player.getMaxHealth());
        }
        assignPerks(survivors);

        if (motherPlayer != null) {
            setupPlayerMother(motherPlayer, survivors);
        } else {
            spawnMother(survivors);
            freezeAiMother(true);
        }

        initialSurvivors = survivors.size();
        prepSecondsLeft = plugin.getConfigManager().getHunterPreparationSeconds();
        tickTask = ServerCompat.runTimer(plugin, this::tick, 20L, 20L);
        // 母体追击单独跑 10 刻：1 秒一拍的决策对追击来说太慢
        if (motherChaseTask != null) {
            motherChaseTask.cancel();
            motherChaseTask = null;
        }
        motherChaseTask = ServerCompat.runTimer(plugin, this::tickMotherChase, 10L, 10L);
        startHeartbeat();
        startChestWaves();
        startAltarLocator();
        startAltarTask();
        startParticleTask();
        startTrails();

        // 准备阶段每人一块「选择面板」：关掉 UI 之后还能自己打开重选，不用记命令
        for (Player player : getAllParticipants()) giveMenuClock(player);

        broadcast("hunter.prep.broadcast", prepSecondsLeft);
        // 开局音：钟声 + 升级音叠一层，谁都听得见（不再只播一次轻微的 Pling）
        playToParticipants(SoundCompat.noteBell(), 1.0f, 1.0f);
        playToParticipants(SoundCompat.playerLevelup(), 1.0f, 1.2f);
        plugin.getLogger().info("GHG 开局：区域=" + gameArea.getName() + "，逃生者=" + survivors.size()
            + "，法阵=" + altars.size() + "，道具箱=" + chests.size());
        return true;
    }

    private void resetRuntimeState() {
        altarProgress = 0;
        altarHardenHits.clear();
        altarHardenLastAt.clear();
        lastHardenAt = 0L;
        allChargedCelebrated = false;
        motherChaseTarget = null;
        lastEscapeWarnAt = 0L;
        sharedAnnouncedStep = 0;
        altars.clear();
        chests.clear();
        chestIndex.clear();
        placedBlocks.clear();
        hitCounts.clear();
        downed.clear();
        rescueCounts.clear();
        earlySettled.clear();
        spectatorWarnAt.clear();
        dummyFleeSince.clear();
        dummyTiredUntil.clear();
        chairCount.clear();
        chairTicks.clear();
        carriedId = null;
        carryRiding = false;
        grabStartedAt = 0L;
        escapePoint = null;
        escapeActive = false;
        escapeTicks = 0;
        elapsedSeconds = 0;
        mother = null;
        motherPlayerId = null;
        lastMotherHitAt = 0L;
        lastMotherSpawnAt = 0L;
        motherSpawnWarned = false;
        prepSecondsLeft = 0;
        motherFreezeUntil = 0L;
        motherRecoverUntil = 0L;
        endReason = "ESCAPE";
        lastMotherLocation = null;
        jumpTimes.clear();
        eliminated.clear();
        bleedout.clear();
        hatchPoint = null;
        hatchActive = false;
        endgameActive = false;
        endgameSecondsLeft = 0;
        motherBrain.reset();
        testMode = false;
        testMotherAggressive = false;
        initialSurvivors = 0;
        healProgress.clear();
        rescueBoostUntil.clear();
        rescueProtectUntil.clear();
        roleItemReadyAt.clear();
        fenceBlocks.clear();
        fenceHits.clear();
        struggleTicks.clear();
        downedRecoverTicks.clear();
        stopAltarLocator();
        stopAltarTask();
        stopParticleTask();
        cancelDashStun();
        stunnedUntil.clear();
        paidKindCost = 0;
        motherTestMode = false;
        clearDummies();
        stopAltarBar();
        stationarySince = 0L;
        vanishSpeedActive = false;
        passiveSightActive = false;
        jumpFatigueUntil.clear();
        chargeTicks.clear();
        rescueDone.clear();
        perks.clear();
        hungerWarned.clear();
        aborted = false;
        skillCooldowns.clear();
        rescueProgress.clear();
        nextHeartbeatAt.clear();
    }

    // ================================================================ 收尾

    public void stop(boolean survivorsWin) {
        if (state == State.IDLE) return;
        state = State.ENDING;

        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (motherChaseTask != null) {
            motherChaseTask.cancel();
            motherChaseTask = null;
        }
        motherChaseTarget = null;
        stopHeartbeat();
        cancelDashStun();
        clearDummies();
        stopChestWaves();
        stopAltarLocator();
        stopAltarTask();
        stopParticleTask();
        stopTrails();
        closeKindMenus();
        stopAltarBar();
        refundMotherKindCost();
        stopCarry();
        removeMother();
        restorePlacedBlocks();
        int removedItems = clearGroundItems();
        if (removedItems > 0) {
            plugin.getLogger().info("GHG：已清理地面残留道具 " + removedItems + " 件");
        }

        // 对局没了，观战席全部退出并还原（他们不在 playerManager 里，得单独处理）
        clearSpectators();

        // 玩家离场后就数不到了，先取一次
        int remainingSurvivors = onlineSurvivors().size();
        Settlement settlement = captureSettlement(survivorsWin);

        for (UUID id : new ArrayList<>(plugin.getPlayerManager().getAllPlayers())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                clearDownedState(player);
                clearDarkness(player);
                // 攻击速度是母体专用的，离场要还原成原版 4.0，别把斧子冷却带到下一局/局外
                try {
                    org.bukkit.attribute.Attribute attackSpeed = ApiCompat.attackSpeed();
                    if (attackSpeed != null && player.getAttribute(attackSpeed) != null) {
                        player.getAttribute(attackSpeed).setBaseValue(4.0);
                    }
                } catch (Throwable ignored) {
                }
                plugin.getPlayerManager().leaveGame(player, true);
            }
        }

        if ("TIMEOUT".equals(endReason)) {
            // 时间到是平局，不能在这里先喊「母体胜利」再在结算块里说平局
            broadcast("hunter.end.draw");
        } else {
            broadcast(survivorsWin ? "hunter.end.survivors-win" : "hunter.end.mother-win");
        }

        // 结算提示：结果 / 用时 / 法阵 / 击倒命中 / 撤离与淘汰名单 / 奖池，一屏说完
        announceSettlement(settlement);

        // 测试局不进统计，免得污染胜率
        settleRewards(survivorsWin);
        if (!testMode) {
            plugin.getHunterStatsManager().finish(survivorsWin, elapsedSeconds, remainingSurvivors);
        }

        resetRuntimeState();
        state = State.IDLE;
        plugin.getModeManager().leave(MatchMode.HUNTER);
        plugin.getLogger().info("GHG 对局结束：逃生者" + (survivorsWin ? "胜利" : "失败"));
    }

    /** 插件禁用/管理员强制中断 */
    public void forceStop() {
        aborted = true;
        endReason = "ABORT";
        stop(false);
    }

    /**
     * 结算提示的数据快照。
     *
     * <p>必须在 {@code leaveGame} 之前取：玩家一离场，逃生者列表、击倒表就都空了，
     * 那时候再想「谁撤离了、谁被淘汰了」已经晚了。</p>
     */
    private static final class Settlement {
        boolean survivorsWin;
        String reason = "ESCAPE";
        int seconds;
        int duration;
        int charged;
        int altars;
        int downs;
        int hits;
        int rescues;
        double pool;
        final List<String> escaped = new ArrayList<>();
        final List<String> lost = new ArrayList<>();
    }

    private Settlement captureSettlement(boolean survivorsWin) {
        Settlement s = new Settlement();
        s.survivorsWin = survivorsWin;
        s.reason = endReason == null ? "ESCAPE" : endReason;
        s.seconds = elapsedSeconds;
        s.duration = durationSeconds;
        s.altars = plugin.getHunterStatsManager().getAltarsTotal() > 0
            ? plugin.getHunterStatsManager().getAltarsTotal() : altars.size();
        s.charged = plugin.getHunterStatsManager().getAltarsCharged();
        s.downs = plugin.getHunterStatsManager().getDowns();
        s.rescues = plugin.getHunterStatsManager().getRescues();
        s.hits = plugin.getHunterStatsManager().getMotherHits();
        s.pool = plugin.getEconomyManager().isEconomyEnabled()
            ? plugin.getEconomyManager().getPrizePool() : 0;

        for (UUID id : new ArrayList<>(plugin.getPlayerManager().getAllPlayers())) {
            if (motherPlayerId != null && motherPlayerId.equals(id)) continue;
            String name = nameOf(id);
            if (eliminated.contains(id) || downed.containsKey(id)) s.lost.add(name);
            else s.escaped.add(name);
        }
        return s;
    }

    private String nameOf(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }

    /** 一个 GameMode 一屏结算：结果 / 用时 / 法阵 / 击倒命中救援 / 名单 / 奖池 / 下一局 */
    private void announceSettlement(Settlement s) {
        if (s == null) return;

        // 结束音：赢了是通关提示音，输了是低沉的收尾，时间到另给一记龙吼
        org.bukkit.Sound endSound;
        if (s.survivorsWin) {
            endSound = SoundCompat.toastChallengeComplete();
        } else if ("TIMEOUT".equals(s.reason)) {
            endSound = SoundCompat.enderDragonGrowl();
        } else {
            endSound = SoundCompat.blockConduitDeactivate();
        }
        if (endSound != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                try {
                    online.playSound(online.getLocation(), endSound, 1.0f, 1.0f);
                } catch (Throwable ignored) {
                }
            }
        }

        if (testMode) {
            broadcast("ghg.settle.test-over", fmtClock(s.seconds));
            return;
        }

        broadcast("ghg.settle.header");
        switch (s.reason) {
            case "HATCH":
                broadcast("ghg.settle.title-hatch");
                break;
            case "WIPED":
                broadcast("ghg.settle.title-wiped");
                break;
            case "TIMEOUT":
                broadcast("ghg.settle.title-draw");
                break;
            case "ABORT":
                broadcast("ghg.settle.title-abort");
                break;
            default:
                broadcast("ghg.settle.title-escaped");
                break;
        }
        broadcast("ghg.settle.stats", fmtClock(s.seconds), fmtClock(s.duration),
            String.valueOf(s.charged), String.valueOf(s.altars),
            String.valueOf(s.downs), String.valueOf(s.hits), String.valueOf(s.rescues));
        if (!s.escaped.isEmpty()) {
            broadcast("ghg.settle.escaped", String.join("、", s.escaped));
        }
        if (!s.lost.isEmpty()) {
            broadcast("ghg.settle.lost", String.join("、", s.lost));
        }
        if (s.pool > 0) {
            broadcast("ghg.settle.pool", plugin.getEconomyManager().format(s.pool));
        }
        broadcast("ghg.settle.next");
        broadcast("ghg.settle.header");
    }

    /** 秒 → m:ss */
    private String fmtClock(int seconds) {
        int total = Math.max(0, seconds);
        return (total / 60) + ":" + String.format("%02d", total % 60);
    }

    // ================================================================ 主循环

    private void tick() {
        updateAltarBar();
        if (state == State.PREPARING) {
            tickPreparation();
            return;
        }
        if (state != State.RUNNING) return;

        elapsedSeconds++;
        enforceNoStacking();
        tickDarkness();
        enforceNoSprint();
        tickPassiveSight();
        tickHunger();
        tickMotherVitals();
        // 法阵全破会激活逃脱点，撤离读满会直接 stop()，后面几步就不用跑了
        if (state != State.RUNNING) return;
        tickEndgame();
        if (state != State.RUNNING) return;
        tickEscape();
        if (state != State.RUNNING) return;
        tickDowned();
        tickCarry();
        tickChairs();
        tickRescue();
        tickHeal();
        tickHatch();
        if (state != State.RUNNING) return;
        tickMother();
        tickMotherWatch();
        tickMotherJumpBoost();
        tickMotherSkillItem();
        tickRoles();
        tickDummies();
        tickEndConditions();
    }

    /** 准备阶段：人类随便跑，法阵点不动，母体被定住 */
    private void tickPreparation() {
        // 准备阶段也要锁满，不然进场时掉了半格会一路带到开打之后
        tickMotherVitals();
        /* 准备阶段人类也不掉饱食度：进对局的饱和度只有 5，走两步就开始掉，
           而这段时间玩家在记点位、翻道具箱，不该被饿着。开打时再按配置压回去。 */
        tickPrepVitals();
        // 准备阶段被清理插件清掉母体的话，这里补回来
        tickMotherWatch();
        tickDarkness();
        // 准备阶段法阵保持发光（粒子环 + 光柱），让人先把点位记住
        tickParticles();
        prepSecondsLeft--;
        if (prepSecondsLeft <= 0) {
            beginMatch();
            return;
        }

        int lockSeconds = plugin.getConfigManager().getHunterKindLockSeconds();
        // 最后几秒每秒一下滴答（放在循环外，否则每个参与者都会触发一次，变成连响）
        if (!testMode && prepSecondsLeft <= lockSeconds) {
            playToParticipants(SoundCompat.noteHat(), 0.9f, 1.2f);
        }
        for (Player player : getAllParticipants()) {
            ActionBarManager.sendNow(player, plugin.getLanguageManager()
                .getMessage(player, "hunter.prep.countdown", prepSecondsLeft));
            // 倒计时一开始（最后 lockSeconds 秒）就用居中大字幕，副标题是「母体：？？？」
            // —— 悬念从这里挂到开打，开打那一刻才揭晓真实种类
            if (!testMode && prepSecondsLeft <= lockSeconds) {
                player.sendTitle(
                    plugin.getLanguageManager().getMessage(player, "hunter.prep.lock-title", prepSecondsLeft),
                    plugin.getLanguageManager().getMessage(player, "hunter.prep.lock-subtitle-mystery"),
                    0, 25, 5);
            }
        }
    }

    /** 准备阶段把逃生者的饱食度与饱和度顶满（母体由 {@link #tickMotherVitals()} 负责） */
    private void tickPrepVitals() {
        for (Player player : onlineSurvivors()) {
            try {
                if (player.getFoodLevel() < 20) player.setFoodLevel(20);
                if (player.getSaturation() < 20.0f) player.setSaturation(20.0f);
                if (player.getExhaustion() > 0f) player.setExhaustion(0f);
                if (player.hasPotionEffect(PotionEffectType.HUNGER)) {
                    player.removePotionEffect(PotionEffectType.HUNGER);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 给一个人发一块选择面板（装备栏满就塞背包，再满就掉在原地由 {@link #removeMenuClocks()} 兜底） */
    public void giveMenuClock(Player player) {
        if (player == null || !player.isOnline()) return;
        for (ItemStack inv : player.getInventory().getContents()) {
            if (ItemIds.is(inv, ItemIds.MENU_CLOCK)) return;
        }
        ItemStack item = plugin.getItemManager().createById(ItemIds.MENU_CLOCK);
        if (item == null) return;
        item.setAmount(1);
        for (int i = 0; i < 9; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot == null || slot.getType().isAir()) {
                player.getInventory().setItem(i, item);
                player.updateInventory();
                plugin.getLanguageManager().sendMessage(player, "hunter.prep.menu-clock-hint");
                return;
            }
        }
        player.getInventory().addItem(item);
        player.updateInventory();
        plugin.getLanguageManager().sendMessage(player, "hunter.prep.menu-clock-hint");
    }

    /** 收走所有参与者的选择面板（开打 / 收局都调） */
    public void removeMenuClocks() {
        for (Player player : getAllParticipants()) {
            ItemStack[] contents = player.getInventory().getContents();
            boolean changed = false;
            for (int i = 0; i < contents.length; i++) {
                if (!ItemIds.is(contents[i], ItemIds.MENU_CLOCK)) continue;
                player.getInventory().setItem(i, null);
                changed = true;
            }
            if (changed) player.updateInventory();
        }
    }

    /** 准备阶段结束，正式开打 */
    private void beginMatch() {
        state = State.RUNNING;
        freezeAiMother(false);
        closeKindMenus();
        // 面板是准备阶段专属：开打统一收走，别占着物品栏
        removeMenuClocks();
        // 准备阶段是「不掉饱食度」的，从这里开始才真正按配置压饱和度
        double startSaturation = plugin.getConfigManager().getHunterHungerStartSaturation();
        for (Player player : onlineSurvivors()) {
            player.setFoodLevel(20);
            player.setSaturation((float) startSaturation);
            player.setExhaustion(0f);
        }
        // 准备阶段是亮的，正式开打后未完成的法阵熄回暗红（破除了才会再亮）
        for (Altar altar : altars) {
            if (altar.charged) continue;
            for (Location cell : altar.getCells()) {
                try {
                    Block block = cell.getWorld().getBlockAt(
                        cell.getBlockX(), cell.getBlockY() - 1, cell.getBlockZ());
                    if (placedBlocks.containsKey(block.getLocation())) {
                        block.setType(PAD_TILE_OFF, false);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        broadcast("hunter.start.broadcast", altars.size(), durationSeconds);

        // 公布本局母体种类：准备阶段玩家还能改，所以只能到这里才定稿
        String kindName = plugin.getLanguageManager().getMessage(motherKind.nameKey());
        String skillName = plugin.getLanguageManager().getMessage(motherKind.skillNameKey());
        broadcast("hunter.mother.kind-reveal", kindName, skillName);

        Object[] kindArgs = {kindName, skillName};
        for (Player player : getAllParticipants()) {
            plugin.getLanguageManager().sendTitle(player, "hunter.start.title",
                "hunter.start.subtitle-kind", new Object[0], kindArgs);
        }
        // 开打：给每个人一层龙吼 + 一层雷，远处也听得见；母体位置再补一记诅咒做定位
        playToParticipants(SoundCompat.enderDragonGrowl(), 1.2f, 0.9f);
        playToParticipants(SoundCompat.lightningThunder(), 0.7f, 1.1f);
        Location motherLocation = getMotherLocation();
        if (motherLocation != null) playSoundAt(motherLocation, SoundCompat.elderCurse());
        // 开打时再把技能道具刷一遍（冷却可能刚改，另外防它中途被清掉）
        if (motherPlayerId != null) {
            Player motherPlayer = Bukkit.getPlayer(motherPlayerId);
            if (motherPlayer != null && motherPlayer.isOnline()) giveMotherSkillItem(motherPlayer);
        }
    }

    /**
     * 准备阶段选母体种类。玩家母体自选；AI 母体则重生成对应外观的实体。
     *
     * @return false 表示当前不能改（已经开打，或没有对局）
     */
    public boolean selectMotherKind(MotherKind kind) {
        if (kind == null) return false;
        // 测试局是沙盒：开打之后也允许换，方便把七种母体挨个试一遍
        boolean sandbox = testMode;
        boolean allowedState = state == State.PREPARING || (sandbox && state == State.RUNNING);
        if (!allowedState) return false;

        Player motherPlayer = motherPlayerId == null ? null : Bukkit.getPlayer(motherPlayerId);
        int lockSeconds = plugin.getConfigManager().getHunterKindLockSeconds();
        if (!sandbox && prepSecondsLeft <= lockSeconds) {
            if (motherPlayer != null) {
                plugin.getLanguageManager().sendMessage(motherPlayer, "hunter.cmd.kind-locked");
            }
            return false;
        }
        if (motherPlayer != null && kind == motherKind) {
            return true; // 没换，不重复收费
        }
        if (motherPlayer != null) {
            try {
                motherPlayer.playSound(motherPlayer.getLocation(), SoundCompat.playerLevelup(), 0.8f, 1.25f);
            } catch (Throwable ignored) {
            }
        }

        // 玩家母体：按种类收入场费，换种类先退旧的
        if (motherPlayer != null) {
            double cost = plugin.getConfigManager().getHunterKindCost(kind);
            if (cost > 0) {
                if (!plugin.getEconomyManager().isEconomyEnabled()
                    || !plugin.getEconomyManager().hasBalance(motherPlayer, cost)) {
                    plugin.getLanguageManager().sendMessage(motherPlayer, "hunter.mother.no-money",
                        plugin.getLanguageManager().getMessage(motherPlayer, kind.nameKey()),
                        String.valueOf((long) cost));
                    return false;
                }
                plugin.getEconomyManager().charge(motherPlayer, cost);
                plugin.getLanguageManager().sendMessage(motherPlayer, "hunter.mother.paid",
                    plugin.getLanguageManager().getMessage(motherPlayer, kind.nameKey()),
                    String.valueOf((long) cost));
            }
            if (paidKindCost > 0) {
                plugin.getEconomyManager().refund(motherPlayer, paidKindCost);
                plugin.getLanguageManager().sendMessage(motherPlayer, "hunter.mother.refunded",
                    String.valueOf((long) paidKindCost));
            }
            paidKindCost = cost;
        }

        motherKind = kind;
        if (motherPlayerId == null) {
            removeMother();
            spawnMother(onlineSurvivors());
            freezeAiMother(true);
        }
        skillCooldowns.clear();
        // 玩家母体换了种类，技能道具跟着换（材质、名字、冷却都变）
        if (motherPlayer != null && motherPlayer.isOnline()) giveMotherSkillItem(motherPlayer);

        // 刻意不广播：种类要到开打那一刻才公布，这里先只通知母体自己
        notifyMotherPlayer("hunter.mother.player-kind-set",
            plugin.getLanguageManager().getMessage(kind.nameKey()),
            plugin.getLanguageManager().getMessage(kind.skillNameKey()));
        return true;
    }

    /** AI 母体定身/解定身（准备阶段与传送硬直都用它） */
    private void freezeAiMother(boolean frozen) {
        if (!(mother instanceof Mob)) return;
        try {
            ((Mob) mother).setAI(!frozen);
            mother.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 传送技能的代价：母体自己硬直，期间禁止移动与互动。
     */
    public void freezeMother(int seconds) {
        freezeMotherMillis(seconds * 1000L);
    }

    /**
     * 毫秒级定身。
     *
     * <p>眩晕药水只有 **1.5 秒**，按秒取整不是 1 秒就是 2 秒，手感都不对，所以时长以毫秒为准。</p>
     */
    public void freezeMotherMillis(long millis) {
        if (millis <= 0) return;
        long until = System.currentTimeMillis() + millis;
        if (until > motherFreezeUntil) motherFreezeUntil = until;
        if (motherPlayerId == null && mother instanceof Mob) {
            freezeAiMother(true);
            long delay = Math.max(1L, (long) Math.ceil(millis / 50.0));
            ServerCompat.runLater(plugin, () -> freezeAiMother(false), delay);
        }
    }

    /**
     * 眩晕药水命中：母体在 {@code center} 的 {@code radius} 内就立刻定身。
     *
     * <p>「定身」复用传送硬直那套：{@code motherFreezeUntil} 一立，{@link #isMotherFrozen()} 为真 ——
     * 移动被 {@code HunterListener.onPlayerMove} 按住、技能被 {@code onMotherInteract} 挡掉、
     * 开箱被 {@code onBlockBreak} 挡掉，所以是**真的不能动也不能交互**，而不是「走得很慢」。</p>
     *
     * @return false 表示这一瓶没砸到母体
     */
    public boolean stunMotherEntity(Location center, double radius, int ticks) {
        if (!isRunning() || center == null || ticks <= 0) return false;
        Location loc = getMotherLocation();
        if (loc == null || loc.getWorld() != center.getWorld()) return false;
        if (loc.distance(center) > radius) return false;

        long millis = Math.max(100L, ticks * 50L);
        freezeMotherMillis(millis);
        stunMotherVisual(millis);
        return true;
    }

    /** 眩晕的可见性：母体身上来一段重缓慢 + 头顶冒红叉，不然「她被定住了」看不出来 */
    private void stunMotherVisual(long millis) {
        int effectTicks = (int) Math.max(1L, Math.ceil(millis / 50.0));
        Location loc = getMotherLocation();
        if (loc != null && loc.getWorld() != null) {
            try {
                loc.getWorld().spawnParticle(ParticleCompat.damageIndicator(),
                    loc.clone().add(0, 1.4, 0), 12, 0.35, 0.5, 0.35);
                loc.getWorld().playSound(loc, SoundCompat.noteBell(), 1.0f, 0.6f);
            } catch (Throwable ignored) {
            }
        }

        LivingEntity target = null;
        if (motherPlayerId != null) {
            Player player = Bukkit.getPlayer(motherPlayerId);
            if (player != null && player.isOnline()) {
                target = player;
                ActionBarManager.sendNow(player, plugin.getLanguageManager()
                    .getMessage(player, "hunter.mother.stunned", effectTicks / 20.0));
            }
        } else if (mother instanceof LivingEntity) {
            target = (LivingEntity) mother;
        }
        if (target == null) return;
        try {
            target.addPotionEffect(new PotionEffect(ApiCompat.slowness(), effectTicks, 6, true, true));
        } catch (Throwable ignored) {
        }
    }

    /** 母体当前是否被定住（准备阶段 / 传送硬直） */
    public boolean isMotherFrozen() {
        if (state == State.PREPARING) return true;
        return System.currentTimeMillis() < motherFreezeUntil;
    }

    /** 所有对局参与者（逃生者 + 玩家母体） */
    public List<Player> getAllParticipants() {
        List<Player> result = new ArrayList<>(onlineSurvivors());
        if (motherPlayerId != null) {
            Player motherPlayer = Bukkit.getPlayer(motherPlayerId);
            if (motherPlayer != null && motherPlayer.isOnline()) result.add(motherPlayer);
        }
        return result;
    }

    /**
     * 兜底：把任何被叠起来的自定义道具拆回单件。
     *
     * <p>发放路径已经只走空格了，但原版拾取、其它插件塞物品仍可能合并成一堆，
     * 所以每秒扫一遍：多的那几件塞回空格，塞不下就消失（道具本来就是单件制）。</p>
     */
    private void enforceNoStacking() {
        for (Player player : getAllParticipants()) {
            ItemStack[] contents = player.getInventory().getContents();
            boolean changed = false;
            for (ItemStack stack : contents) {
                if (stack == null || stack.getAmount() <= 1) continue;
                if (ItemIds.idOf(stack) == null) continue;

                int extra = stack.getAmount() - 1;
                stack.setAmount(1);
                changed = true;
                for (int i = 0; i < extra; i++) {
                    ItemStack copy = stack.clone();
                    copy.setAmount(1);
                    if (!plugin.getItemManager().addUnstacked(player, copy)) break;
                }
            }
            if (changed) player.updateInventory();
        }
    }

    /** 对局中禁止疾跑（兜底，主要靠 PlayerToggleSprintEvent） */
    private void enforceNoSprint() {
        if (!plugin.getConfigManager().isHunterNoSprint()) return;
        for (Player player : getAllParticipants()) {
            if (player.isSprinting()) {
                try {
                    player.setSprinting(false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 黑暗效果：**只压逃生者，母体不受影响**。
     *
     * <p>每秒刷新一次、只给 2 秒持续，所以玩家一离开对局（被淘汰/结束）视野立刻恢复，
     * 不会留下「永远黑屏」的残留。法阵的外框与地砖本身发光，黑暗里仍然看得见。</p>
     */
    private void tickDarkness() {
        if (!plugin.getConfigManager().isHunterDarknessEnabled()) return;
        if (state == State.PREPARING && !plugin.getConfigManager().isHunterDarknessInPreparation()) return;
        if (state != State.PREPARING && state != State.RUNNING) return;

        PotionEffectType type = ApiCompat.darkness();
        if (type == null) type = ApiCompat.blindness();
        if (type == null) return;

        int amplifier = plugin.getConfigManager().getHunterDarknessAmplifier();
        for (Player player : onlineSurvivors()) {
            try {
                player.addPotionEffect(new PotionEffect(type, 40, amplifier, true, false, false));
            } catch (Throwable ignored) {
            }
        }
        // 母体身上不能有这个效果（玩家母体切进来之前可能带过）
        if (motherPlayerId != null) {
            Player motherPlayer = Bukkit.getPlayer(motherPlayerId);
            if (motherPlayer != null && motherPlayer.isOnline()) {
                try {
                    motherPlayer.removePotionEffect(type);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 记录一次跳跃，超出配额就给「腿软」。
     *
     * <p>不禁止跳跃——MC 的追逐本来就靠跑跳，硬禁会让手感很怪。改成配额：
     * {@code window-seconds} 秒内允许连跳 {@code max-jumps} 次，够了就减速一段。
     * 连跳逃命仍然可行，但要付代价，追逐从「无脑蹦」变成「什么时候值得跳」。</p>
     */
    public void registerJump(Player player) {
        if (player == null) return;
        if (!plugin.getConfigManager().isHunterJumpFatigueEnabled()) return;
        if (state != State.RUNNING) return;
        if (!getAllParticipants().contains(player)) return;

        // 母体（玩家扮演）比人类多一次跳跃宽容、腿软时间也短一秒
        boolean asMother = isPlayerMother(player.getUniqueId());
        int max = asMother
            ? plugin.getConfigManager().getHunterJumpFatigueMotherMax()
            : plugin.getConfigManager().getHunterJumpFatigueMax();
        int seconds = asMother
            ? plugin.getConfigManager().getHunterJumpFatigueMotherPenaltySeconds()
            : plugin.getConfigManager().getHunterJumpFatiguePenaltySeconds();
        long windowMs = plugin.getConfigManager().getHunterJumpFatigueWindowSeconds() * 1000L;
        long now = System.currentTimeMillis();

        Deque<Long> times = jumpTimes.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
            times.pollFirst();
        }
        times.addLast(now);
        if (times.size() <= max) return;

        times.clear();
        applySlow(player, seconds, plugin.getConfigManager().getHunterJumpFatiguePenaltyLevel());
        // 真的跳不动：只减速的话，身上带加速的人还能继续蹦，看起来就像「多给了几次」
        jumpFatigueUntil.put(player.getUniqueId(), now + seconds * 1000L);
        plugin.getLanguageManager().sendMessage(player, "hunter.jump.fatigue", seconds);
    }

    // ================================================================ 法阵进度条

    private void startAltarBar() {
        stopAltarBar();
        if (!plugin.getConfigManager().isHunterAlterBarEnabled()) return;
        try {
            altarBar = Bukkit.createBossBar(
                plugin.getLanguageManager().getMessage("hunter.bossbar.altars", 0, altars.size(), 0),
                org.bukkit.boss.BarColor.PINK,
                org.bukkit.boss.BarStyle.SOLID);
            altarBar.setProgress(0.0);
            plugin.getLogger().info("GHG：法阵进度条已创建（" + altars.size() + " 个法阵）");
        } catch (Throwable t) {
            plugin.getLogger().warning("创建法阵进度条失败（会自动降级成 ActionBar）: " + t.getMessage());
            altarBar = null;
        }
    }

    private void stopAltarBar() {
        if (altarBar == null) {
            altarBarViewers.clear();
            return;
        }
        try {
            altarBar.removeAll();
            altarBar.setVisible(false);
        } catch (Throwable ignored) {
        }
        altarBar = null;
        altarBarViewers.clear();
    }

    /**
     * 刷新法阵进度条：总进度 = 所有法阵的平均值（破除的按 100% 算）。
     *
     * <p>只给逃生者看——母体看不到这队伍推到哪了。</p>
     */
    private void updateAltarBar() {
        // 没创建成功（或被别的插件清掉）时每秒重试一次，实在不行退化成 ActionBar
        if (altarBar == null) {
            if (state == State.PREPARING || state == State.RUNNING) startAltarBar();
            if (altarBar == null) {
                sendAltarFallbackBar();
                return;
            }
        }

        List<Player> survivors = onlineSurvivors();
        int total = altars.size();
        int charged = 0;
        double sum = 0;
        for (Altar altar : altars) {
            if (altar.charged) {
                charged++;
                sum += 100;
            } else {
                sum += altar.progress;
            }
        }
        double aggregate = total == 0 ? 0 : sum / (total * 100.0);
        boolean done = total > 0 && charged >= total;

        try {
            altarBar.setProgress(Math.max(0.0, Math.min(1.0, aggregate)));
            altarBar.setColor(done ? org.bukkit.boss.BarColor.GREEN : org.bukkit.boss.BarColor.PINK);
            altarBar.setTitle(done
                ? plugin.getLanguageManager().getMessage("hunter.bossbar.altars-done")
                : plugin.getLanguageManager().getMessage("hunter.bossbar.altars",
                    charged, total, (int) Math.round(aggregate * 100)));
        } catch (Throwable ignored) {
        }

        // 观众同步：逃生者 + （可配）玩家母体
        Set<UUID> alive = new HashSet<>();
        for (Player player : survivors) alive.add(player.getUniqueId());
        if (motherPlayerId != null && plugin.getConfigManager().isHunterBossBarForMother()) {
            Player mother = Bukkit.getPlayer(motherPlayerId);
            if (mother != null && mother.isOnline()) alive.add(motherPlayerId);
        }
        for (UUID id : new ArrayList<>(altarBarViewers)) {
            if (alive.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                try {
                    altarBar.removePlayer(player);
                } catch (Throwable ignored) {
                }
            }
            altarBarViewers.remove(id);
        }
        for (UUID id : alive) {
            if (altarBarViewers.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) continue;
            try {
                altarBar.addPlayer(player);
                altarBarViewers.add(player.getUniqueId());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * BossBar 不可用时的降级显示：用 ActionBar 报一次总进度。
     *
     * <p>只发给离所有未完成法阵都超过 16 格的玩家——站在法阵里的人本来就有一行
     * 逐法阵的进度，别把它顶掉。</p>
     */
    private void sendAltarFallbackBar() {
        int total = altars.size();
        if (total == 0) return;

        int charged = 0;
        double sum = 0;
        for (Altar altar : altars) {
            if (altar.charged) {
                charged++;
                sum += 100;
            } else {
                sum += altar.progress;
            }
        }
        int percent = (int) Math.round(sum / (total * 100.0) * 100);

        for (Player player : onlineSurvivors()) {
            boolean nearAltar = false;
            for (Altar altar : altars) {
                if (altar.charged || altar.center.getWorld() != player.getWorld()) continue;
                if (player.getLocation().distance(altar.center) <= 16.0) {
                    nearAltar = true;
                    break;
                }
            }
            if (nearAltar) continue;
            ActionBarManager.sendNow(player, plugin.getLanguageManager()
                .getMessage(player, "hunter.bossbar.altars", charged, total, percent));
        }
    }

    // ================================================================ 冲撞眩晕

    /** 被冲撞撞晕：短暂无法移动 + 失明 */
    public void stunPlayer(Player player, int ticks) {
        if (player == null || ticks <= 0) return;
        stunnedUntil.put(player.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        try {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, true, true));
            player.addPotionEffect(new PotionEffect(ApiCompat.slowness(), ticks, 6, true, true));
        } catch (Throwable ignored) {
        }
        plugin.getLanguageManager().sendTitle(player, "hunter.stun.title", "hunter.stun.subtitle");
        notifyMotherPlayer("hunter.stun.landed", player.getName());
    }

    public boolean isStunned(UUID playerId) {
        if (playerId == null) return false;
        Long until = stunnedUntil.get(playerId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            stunnedUntil.remove(playerId);
            return false;
        }
        return true;
    }

    /**
     * 开一段「冲撞撞击窗口」：猛冲之后的一小段时间里，撞到谁就晕谁。
     *
     * <p>用 1 刻的任务而不是 1 秒的 tick：冲撞速度很快，按秒采样会整段穿过去。</p>
     */
    public void startDashStun(Entity caster) {
        cancelDashStun();
        if (caster == null) return;

        dashCaster = caster;
        double radius = plugin.getConfigManager().getHunterDashStunRadius();
        int stunTicks = plugin.getConfigManager().getHunterDashStunTicks();
        int window = plugin.getConfigManager().getHunterDashStunWindowTicks();
        final int[] elapsed = {0};

        dashTask = ServerCompat.runTimer(plugin, () -> {
            elapsed[0]++;
            if (dashCaster == null || !dashCaster.isValid() || elapsed[0] > window) {
                cancelDashStun();
                return;
            }
            for (Player player : onlineSurvivors()) {
                if (downed.containsKey(player.getUniqueId())) continue;
                if (isStunned(player.getUniqueId())) continue;
                if (player.getWorld() != dashCaster.getWorld()) continue;
                if (player.getLocation().distance(dashCaster.getLocation()) > radius) continue;
                stunPlayer(player, stunTicks);
            }
        }, 1L, 1L);
    }

    private void cancelDashStun() {
        if (dashTask != null) {
            dashTask.cancel();
            dashTask = null;
        }
        dashCaster = null;
    }

    /** 这个逃生者是否正按着 Shift 站在未完成的法阵里（AI 优先抓他） */
    public boolean isChargingAltar(Player player) {
        if (player == null) return false;
        if (!player.isSneaking()) return false;
        for (Altar altar : altars) {
            if (altar.charged || altar.isSealed()) continue;
            if (isAltarHardened(altar)) continue;
            if (isStandingOnAltar(player, altar)) return true;
        }
        return false;
    }

    /**
     * 巡逻目标（守机点）。
     *
     * <p>旧的实现只是「最近的未完成法阵」，于是有两个很蠢的行为：</p>
     * <ul>
     *   <li>刚自己砸红石块加固完的阵，人类根本破不了，她却还守着它</li>
     *   <li>有人正在另一座阵上推得起劲，她却在离得最近的那座空阵边站着</li>
     * </ul>
     * <p>现在的顺序：<b>有人正在推的阵</b> → 否则<b>最近的、没加固也没封印的阵</b> →
     * 法阵全破后去撤离点。守机站位还会偏到阵心外侧（{@code patrol.stand-offset} 格），
     * 不再像一根桩子钉在阵正中。</p>
     */
    public Location nextPatrolPoint() {
        Location motherLocation = getMotherLocation();
        if (motherLocation == null) return null;

        ConfigManager cfg = plugin.getConfigManager();
        boolean preferCharging = cfg.isHunterMotherPatrolPreferCharging();
        boolean skipHardened = cfg.isHunterMotherPatrolSkipHardened();

        Altar underAttack = null;
        double underDistance = Double.MAX_VALUE;
        Altar nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Altar altar : altars) {
            if (altar.charged || altar.isSealed()) continue;
            if (altar.center.getWorld() != motherLocation.getWorld()) continue;
            boolean hardened = skipHardened && isAltarHardened(altar);
            double distance = motherLocation.distance(altar.center);

            if (preferCharging && !hardened && altarHasCharger(altar)
                && distance < underDistance) {
                underDistance = distance;
                underAttack = altar;
            }
            if (hardened) continue;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = altar;
            }
        }

        Altar pick = underAttack != null ? underAttack : nearest;
        if (pick == null) return escapeActive ? escapePoint : null;
        return patrolStandPoint(pick.center, motherLocation);
    }

    /** 这座阵上有没有人正按住 Shift 在推（守机优先看它） */
    private boolean altarHasCharger(Altar altar) {
        if (altar == null) return false;
        for (Player player : onlineSurvivors()) {
            if (downed.containsKey(player.getUniqueId())) continue;
            if (!player.isSneaking()) continue;
            if (isStandingOnAltar(player, altar)) return true;
        }
        return false;
    }

    /**
     * 守机站位：从阵心往母体自己那一侧挪出 stand-offset 格（还在守机半径内）。
     *
     * <p>站正中会挡住自己视野、也看不出她在守哪座阵；靠边站一眼就明白，而且逃生者从另一侧
     * 靠近时要多走一段，守机压力更真实。</p>
     */
    private Location patrolStandPoint(Location center, Location motherLocation) {
        if (center == null || motherLocation == null) return center;
        double offset = plugin.getConfigManager().getHunterMotherPatrolStandOffset();
        if (offset <= 0.01) return center;

        org.bukkit.util.Vector away = motherLocation.toVector().subtract(center.toVector());
        away.setY(0);
        if (away.lengthSquared() < 0.01) away = new org.bukkit.util.Vector(1, 0, 0);

        /* 别挪出守机半径，否则「守机」这件事就不成立了 */
        double radius = plugin.getConfigManager().getHunterAltarDecayRadius();
        double step = Math.min(offset, Math.max(1.0, radius - 1.5));
        return center.clone().add(away.normalize().multiply(step));
    }

    /**
     * 奖池结算。
     *
     * <p>逃生者撤离 → 按贡献加权分；玩家母体胜 → 给母体；AI 母体胜或对局中断 → 全额退还入场费。</p>
     */
    /**
     * 对局被强制中断时，把玩家母体为种类交的门票退回去。
     * 正常打完不退——那是这一局的门票。
     */
    private void refundMotherKindCost() {
        if (!aborted || paidKindCost <= 0) return;
        if (motherPlayerId == null) return;
        Player mother = Bukkit.getPlayer(motherPlayerId);
        if (mother != null && mother.isOnline()) {
            plugin.getEconomyManager().refund(mother, paidKindCost);
            plugin.getLanguageManager().sendMessage(mother,
                "hunter.mother.refunded", String.valueOf((long) paidKindCost));
        }
        paidKindCost = 0;
    }

    /**
     * 结算奖金：**只看每个人干了什么**，不看哪一方赢。
     *
     * <p>原来逃生者赢就按贡献分、母体赢就整池给母体，等于奖励「阵容」而不是操作；
     * 现在双方都算行动分（破阵秒数 / 救援 / 撤离 · 命中 / 击倒 / 淘汰），
     * 奖池按分数占比分给所有真人，母体也在里面。</p>
     */
    private void settleRewards(boolean survivorsWin) {
        if (testMode || !plugin.getConfigManager().isHunterRewardEnabled()) return;
        if (!plugin.getEconomyManager().isEconomyEnabled()) return;

        // 结算名单要包含**已被淘汰的人**：他们可能破了大半座阵才被抓，用 onlineSurvivors()
        // 会把这些人整个跳过，等于贡献白干、钱还分给了别人
        List<Player> settlementPlayers = new ArrayList<>();
        for (UUID id : new ArrayList<>(plugin.getPlayerManager().getAllPlayers())) {
            if (motherPlayerId != null && motherPlayerId.equals(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) settlementPlayers.add(player);
        }

        if (aborted) {
            plugin.getEconomyManager().refundAll(settlementPlayers, "hunter.reward.refunded");
            // 入场费之外还收了职业券，中断时一并退还
            for (Player player : settlementPlayers) {
                HunterRole role = perks.get(player.getUniqueId());
                double roleFee = role == null ? 0 : plugin.getConfigManager().getHunterRoleFee(role);
                if (roleFee > 0) {
                    plugin.getEconomyManager().refund(player, roleFee);
                    plugin.getLanguageManager().sendMessage(player, "hunter.role.fee-refunded",
                        plugin.getEconomyManager().format(roleFee));
                }
            }
            return;
        }
        if (plugin.getEconomyManager().getPrizePool() <= 0) return;

        double altarWeight = plugin.getConfigManager().getHunterRewardActionAltarSecond();
        double rescueWeight = plugin.getConfigManager().getHunterRewardActionRescue();
        double escapeWeight = plugin.getConfigManager().getHunterRewardActionEscape();
        double hitWeight = plugin.getConfigManager().getHunterRewardActionMotherHit();
        double downWeight = plugin.getConfigManager().getHunterRewardActionMotherDown();
        double eliminateWeight = plugin.getConfigManager().getHunterRewardActionMotherEliminate();

        Map<UUID, Double> scores = new LinkedHashMap<>();

        // 逃生者：破阵秒数 + 救援次数 +（撤离成功才有的额外分）
        for (Player player : settlementPlayers) {
            UUID id = player.getUniqueId();
            double chargeSeconds = chargeTicks.getOrDefault(id, 0) / 20.0;
            double rescued = rescueDone.getOrDefault(id, 0);
            double score = chargeSeconds * altarWeight + rescued * rescueWeight;
            if (survivorsWin && !eliminated.contains(id) && !downed.containsKey(id)) {
                score += escapeWeight;
            }
            scores.put(id, score);
        }

        // 玩家母体：命中 / 击倒 / 淘汰（AI 母体没有真人，不参与分配）
        if (motherPlayerId != null) {
            Player motherPlayer = Bukkit.getPlayer(motherPlayerId);
            if (motherPlayer != null && motherPlayer.isOnline()) {
                plugin.getHunterStatsManager();
                double score = plugin.getHunterStatsManager().getMotherHits() * hitWeight
                    + plugin.getHunterStatsManager().getDowns() * downWeight
                    + plugin.getHunterStatsManager().getEliminations() * eliminateWeight;
                scores.put(motherPlayerId, score);
            }
        }

        if (scores.isEmpty()) {
            plugin.getEconomyManager().refundAll(settlementPlayers, "hunter.reward.refunded");
            return;
        }
        // 两池制：逃生者那份只按逃生者的分算，母体从公共池（保底）里拿
        double motherScore = 0;
        java.util.UUID motherId = null;
        if (motherPlayerId != null && scores.containsKey(motherPlayerId)) {
            motherScore = scores.remove(motherPlayerId);
            motherId = motherPlayerId;
        }
        plugin.getEconomyManager().settleTwoPools(scores, motherId, motherScore);
    }

    // ================================================================ 逃生者构筑

    /**
     * 分配职业：每个逃生者随机一个职业（AI 母体局排除冰球手），并立刻把职业技能道具塞进背包。
     *
     * <p>道具用掉之后才开始计时补发（见 {@link #tickRoles()}），所以「先用」是有代价的。</p>
     */
    private void assignPerks(List<Player> survivors) {
        if (!plugin.getConfigManager().isHunterRoleEnabled()) return;

        // AI 母体局里凝冰球没有目标可砸，冰球手职业直接排除
        List<HunterRole> pool = new ArrayList<>();
        for (HunterRole candidate : HunterRole.values()) {
            if (candidate == HunterRole.ICER && motherPlayerId == null) continue;
            pool.add(candidate);
        }
        if (pool.isEmpty()) return;

        for (Player player : survivors) {
            HunterRole role = pool.get(random.nextInt(pool.size()));
            perks.put(player.getUniqueId(), role);

            // 职业入场券（在基础入场费之外另收，AI 母体局不收）
            double roleFee = plugin.getConfigManager().getHunterRoleFee(role);
            if (!testMode && roleFee > 0 && plugin.getEconomyManager().isEconomyEnabled()) {
                if (plugin.getEconomyManager().charge(player, roleFee)) {
                    plugin.getLanguageManager().sendMessage(player, "hunter.role.fee-paid",
                        plugin.getEconomyManager().format(roleFee));
                }
            }
            plugin.getLanguageManager().sendMessage(player, "hunter.role.assigned",
                plugin.getLanguageManager().getMessage(player, role.nameKey()),
                plugin.getLanguageManager().getMessage(player, role.descKey()));
            giveRoleItem(player, role);
            roleItemReadyAt.remove(player.getUniqueId());
            // 准备阶段弹一次职业菜单（基岩版不用敲命令）
            final Player menuTarget = player;
            ServerCompat.runLater(plugin, () -> {
                if (menuTarget.isOnline() && (state == State.PREPARING || testMode)) {
                    openRoleMenu(menuTarget);
                }
            }, 20L);
        }
    }

    /** 把职业技能道具塞进背包（已有就不重复给，保证不可叠加） */
    private void giveRoleItem(Player player, HunterRole role) {
        if (player == null || !player.isOnline() || role == null) return;
        String itemId = role.skillItemId();
        ItemStack item = plugin.getItemManager().createById(itemId);
        if (item == null) return;
        for (ItemStack inv : player.getInventory().getContents()) {
            if (ItemIds.is(inv, itemId)) return;
        }
        item.setAmount(1);
        player.getInventory().addItem(item);
        player.updateInventory();
    }

    /** 职业技能道具被用掉了：开始计时补发 */
    public void onRoleItemUsed(Player player, String itemId) {
        if (player == null || itemId == null) return;
        HunterRole role = perks.get(player.getUniqueId());
        if (role == null || !itemId.equals(role.skillItemId())) return;
        int seconds = HunterRole.ENGINEER == role
            ? plugin.getConfigManager().getHunterRoleFenceRefreshSeconds()
            : plugin.getConfigManager().getHunterRoleRefreshSeconds();
        roleItemReadyAt.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }

    /** 每秒一次：时间到了就把职业技能道具补回背包 */
    private void tickRoles() {
        if (!plugin.getConfigManager().isHunterRoleEnabled()) return;
        long now = System.currentTimeMillis();
        for (Player player : onlineSurvivors()) {
            HunterRole role = perks.get(player.getUniqueId());
            if (role == null) continue;
            Long readyAt = roleItemReadyAt.get(player.getUniqueId());
            if (readyAt == null || now < readyAt) continue;
            boolean has = false;
            for (ItemStack inv : player.getInventory().getContents()) {
                if (ItemIds.is(inv, role.skillItemId())) {
                    has = true;
                    break;
                }
            }
            if (has) {
                roleItemReadyAt.remove(player.getUniqueId());
                continue;
            }
            giveRoleItem(player, role);
            roleItemReadyAt.remove(player.getUniqueId());
            try {
                player.playSound(player.getLocation(), SoundCompat.noteBell(), 1.0f, 1.4f);
                player.playSound(player.getLocation(), SoundCompat.playerLevelup(), 0.8f, 1.3f);
            } catch (Throwable ignored) {
            }
            plugin.getLanguageManager().sendMessage(player, "hunter.role.item-refreshed",
                plugin.getLanguageManager().getMessage(player, role.nameKey()));
        }
    }

    // ---------------------------------------------------------------- 职业菜单

    /** 菜单图标的起始槽位 */
    private static final int ROLE_MENU_START_SLOT = 10;

    /** 本局有没有真人母体（用来决定菜单里要不要排冰球手） */
    public boolean hasPlayerMother() {
        return motherPlayerId != null;
    }

    /** 菜单里列出来的职业（AI 母体局排除冰球手） */
    public List<HunterRole> menuRoles() {
        List<HunterRole> result = new ArrayList<>();
        for (HunterRole role : HunterRole.values()) {
            if (role == HunterRole.ICER && motherPlayerId == null) continue;
            result.add(role);
        }
        return result;
    }

    /** 槽位 → 职业（菜单与点击处理共用） */
    public HunterRole roleForSlot(int slot) {
        List<HunterRole> roles = menuRoles();
        int index = slot - ROLE_MENU_START_SLOT;
        if (index < 0 || index >= roles.size()) return null;
        return roles.get(index);
    }

    /** 打开职业菜单：只在准备阶段（测试局随时可开） */
    public boolean openRoleMenu(Player viewer) {
        if (viewer == null || !viewer.isOnline()) return false;
        if (state != State.PREPARING && !testMode) return false;

        List<HunterRole> roles = menuRoles();
        Inventory menu = Bukkit.createInventory(
            new io.Sriptirc_wp_1258.gost.listeners.RoleMenuListener.Holder(viewer.getUniqueId()),
            27,
            plugin.getLanguageManager().getMessage(viewer, "hunter.role.menu-title"));

        HunterRole current = perks.get(viewer.getUniqueId());
        for (int i = 0; i < roles.size() && i < 7; i++) {
            HunterRole role = roles.get(i);
            ItemStack icon = plugin.getItemManager().createById(role.skillItemId());
            if (icon == null) icon = new ItemStack(Material.OAK_FENCE);
            icon.setAmount(1);
            ItemMeta meta = icon.getItemMeta();
            // 道具自己的显示名要在覆盖 meta 之前取出来（菜单里要写「道具：凝冰球」）
            String itemName = meta != null && meta.hasDisplayName()
                ? meta.getDisplayName()
                : role.skillItemId();
            if (meta != null) {
                meta.setDisplayName("§b" + plugin.getLanguageManager().getMessage(viewer, role.nameKey()));
                List<String> lore = new ArrayList<>();
                // 定位句 → 道具与效果逐行说明 → 冷却/被动/券 → 操作提示
                lore.add(plugin.getLanguageManager().getMessage(viewer, role.descKey()));
                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.role.separator"));

                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.role.menu-item", itemName));
                for (String line : plugin.getLanguageManager()
                        .getMessage(viewer, role.effectKey()).split("\n")) {
                    if (line.isEmpty()) continue;
                    lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.lore-body", line));
                }

                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.role.menu-cooldown",
                    String.valueOf(plugin.getConfigManager().getHunterRoleRefreshSeconds(role))));
                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.role.menu-passive",
                    plugin.getLanguageManager().getMessage(viewer, role.passiveKey())));
                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.role.menu-fee",
                    plugin.getEconomyManager().format(plugin.getConfigManager().getHunterRoleFee(role))));
                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.role.separator"));
                lore.add(plugin.getLanguageManager().getMessage(viewer,
                    role == current ? "hunter.role.menu-current" : "hunter.role.menu-click"));
                if (role == current) {
                    meta.addEnchant(ApiCompat.lure(), 1, true);
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                }
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            menu.setItem(ROLE_MENU_START_SLOT + i, icon);
        }
        viewer.openInventory(menu);
        return true;
    }

    /**
     * 换职业：按差价补/退职业券，并把职业技能道具换掉。
     *
     * <p>只在准备阶段允许（AI 母体局不能选冰球手）。</p>
     */
    public boolean selectRole(Player player, HunterRole role) {
        if (player == null || !player.isOnline() || role == null) return false;
        if (state != State.PREPARING && !testMode) {
            plugin.getLanguageManager().sendMessage(player, "hunter.role.too-late");
            return false;
        }
        if (role == HunterRole.ICER && motherPlayerId == null) {
            plugin.getLanguageManager().sendMessage(player, "hunter.role.icer-unavailable");
            return false;
        }

        HunterRole current = perks.get(player.getUniqueId());
        if (current == role) return true;

        double newFee = plugin.getConfigManager().getHunterRoleFee(role);
        double oldFee = current == null ? 0 : plugin.getConfigManager().getHunterRoleFee(current);
        if (!testMode && plugin.getEconomyManager().isEconomyEnabled()) {
            if (newFee > oldFee) {
                double extra = newFee - oldFee;
                if (!plugin.getEconomyManager().hasBalance(player, extra)) {
                    plugin.getLanguageManager().sendMessage(player, "hunter.role.no-money",
                        plugin.getEconomyManager().format(extra));
                    return false;
                }
                plugin.getEconomyManager().charge(player, extra);
            } else if (oldFee > newFee) {
                plugin.getEconomyManager().refund(player, oldFee - newFee);
            }
        }

        perks.put(player.getUniqueId(), role);
        removeRoleItems(player);
        giveRoleItem(player, role);
        roleItemReadyAt.remove(player.getUniqueId());
        try {
            player.playSound(player.getLocation(), SoundCompat.playerLevelup(), 0.8f, 1.2f);
        } catch (Throwable ignored) {
        }
        plugin.getLanguageManager().sendMessage(player, "hunter.role.changed",
            plugin.getLanguageManager().getMessage(player, role.nameKey()));
        return true;
    }

    /** 清掉背包里所有职业技能道具（换职业时用） */
    private void removeRoleItems(Player player) {
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null) continue;
            String id = ItemIds.idOf(item);
            if (id == null) continue;
            for (HunterRole role : HunterRole.values()) {
                if (id.equals(role.skillItemId())) {
                    inv.setItem(slot, null);
                    break;
                }
            }
        }
        player.updateInventory();
    }

    /** 这个玩家是不是工程师 */
    public boolean isEngineer(Player player) {
        return player != null && HunterRole.ENGINEER == perks.get(player.getUniqueId());
    }

    /**
     * 工程师架栅栏：以准星为准，在目标方块上方铺 width 宽、height 高的一段木栅栏。
     *
     * <p>只能在猎场区域内放，只替换空气/水/草；方块记进 {@code placedBlocks}，
     * 对局结束统一还原。母体与任何工程师都能拆（见 {@link #breakFence}）。</p>
     */
    public boolean placeFence(Player player) {
        if (!isRunning() || player == null || area == null) return false;
        if (!isEngineer(player)) return false;

        Block target;
        try {
            target = player.getTargetBlockExact(6);
        } catch (Throwable t) {
            return false;
        }
        if (target == null) return false;

        int width = plugin.getConfigManager().getHunterRoleFenceWidth();
        int height = plugin.getConfigManager().getHunterRoleFenceHeight();
        Location base = target.getRelative(org.bukkit.block.BlockFace.UP).getLocation();

        // 垂直于视线方向铺开
        org.bukkit.util.Vector dir = player.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 0.01) dir = new org.bukkit.util.Vector(1, 0, 0);
        dir.normalize();
        int stepX = (int) Math.round(-dir.getZ());
        int stepZ = (int) Math.round(dir.getX());
        if (stepX == 0 && stepZ == 0) stepX = 1;

        int placed = 0;
        int half = width / 2;
        for (int i = -half; i <= half; i++) {
            for (int h = 0; h < height; h++) {
                int x = base.getBlockX() + stepX * i;
                int y = base.getBlockY() + h;
                int z = base.getBlockZ() + stepZ * i;
                if (!inGameArea(x, y, z)) continue;
                Block block = base.getWorld().getBlockAt(x, y, z);
                Material type = block.getType();
                if (!type.isAir() && type != Material.WATER && type != Material.SHORT_GRASS
                    && type != Material.TALL_GRASS) continue;
                placeBlock(base.getWorld(), x, y, z, Material.OAK_FENCE);
                fenceBlocks.add(blockKey(block.getLocation()));
                placed++;
            }
        }
        if (placed <= 0) return false;
        playSoundAt(base, SoundCompat.blockAnvilLand());
        return true;
    }

    private boolean inGameArea(int x, int y, int z) {
        if (area == null || area.getMinPoint() == null || area.getMaxPoint() == null) return false;
        Location min = area.getMinPoint();
        Location max = area.getMaxPoint();
        return x >= Math.min(min.getBlockX(), max.getBlockX()) && x <= Math.max(min.getBlockX(), max.getBlockX())
            && z >= Math.min(min.getBlockZ(), max.getBlockZ()) && z <= Math.max(min.getBlockZ(), max.getBlockZ())
            && y >= Math.min(min.getBlockY(), max.getBlockY()) - 2
            && y <= Math.max(min.getBlockY(), max.getBlockY()) + 4;
    }

    /** 这块是不是工程师放的栅栏 */
    public boolean isFenceBlock(Location location) {
        return location != null && fenceBlocks.contains(blockKey(location));
    }

    /**
     * 打栅栏一下：母体与任意工程师都能打，但要打 {@code fence-hits} 下才塌。
     *
     * <p>原来是一瞬间拆掉，母体拆工程师的栅栏太便宜——一堵墙连一秒都撑不住。</p>
     *
     * @return true 表示这一下把它打塌了
     */
    public boolean breakFence(Player player, Block block) {
        if (player == null || block == null) return false;
        if (!isFenceBlock(block.getLocation())) return false;
        boolean allowed = isPlayerMother(player.getUniqueId()) || isEngineer(player);
        if (!allowed) {
            plugin.getLanguageManager().sendMessage(player, "hunter.role.fence-locked");
            return false;
        }
        return damageFence(block, 1);
    }

    /** 对栅栏造成伤害；次数够了就塌 */
    private boolean damageFence(Block block, int amount) {
        String key = blockKey(block.getLocation());
        int hits = fenceHits.getOrDefault(key, 0) + amount;
        int need = plugin.getConfigManager().getHunterRoleFenceHits();
        playSoundAt(block.getLocation(), SoundCompat.blockAnvilLand());
        if (hits >= need) {
            fenceHits.remove(key);
            fenceBlocks.remove(key);
            try {
                block.getWorld().spawnParticle(ParticleCompat.cloud(),
                    block.getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3);
            } catch (Throwable ignored) {
            }
            block.setType(Material.AIR, false);
            return true;
        }
        fenceHits.put(key, hits);
        return false;
    }

    /**
     * AI 母体撞栅栏：被栅栏挡住时每半秒撞一下，够次数就撞塌。
     *
     * <p>AI 只会走 Pathfinder，没有拆方块的能力；不给它这条路，工程师一堵墙就能把它永久围死。</p>
     */
    private void tickMotherFenceHit() {
        if (motherPlayerId != null || !(mother instanceof Mob)) return;
        Mob mob = (Mob) mother;
        if (!mob.isValid()) return;
        Location center = mob.getLocation();
        // 找周围 2 格内的栅栏（前方优先）
        Block target = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block block = center.getWorld().getBlockAt(
                        center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                    if (!isFenceBlock(block.getLocation())) continue;
                    double score = (double) dx * dx + (double) dz * dz + dy;
                    if (score < bestScore) {
                        bestScore = score;
                        target = block;
                    }
                }
            }
        }
        if (target == null) return;
        if (damageFence(target, 1)) {
            broadcast("hunter.role.fence-broken-mother");
        }
    }

    public HunterRole getRole(UUID playerId) {
        return perks.get(playerId);
    }

    /** 破阵权重：工程师更快 */
    private double chargeWeight(Player player) {
        HunterRole perk = perks.get(player.getUniqueId());
        return perk == null ? 1.0 : perk.chargeWeight();
    }

    /** 治疗/救援速度倍率：医者更快 */
    private double actionSpeed(Player player) {
        if (player == null) return 1.0;
        HunterRole perk = perks.get(player.getUniqueId());
        double speed = perk == null ? 1.0 : perk.actionSpeed();
        return speed * rescueBoostMultiplier(player);
    }

    /**
     * 珍珠女士：传送落地后自己吃一段缓慢Ⅰ。
     *
     * <p>这是「闪过去但接不上刀」的代价；一旦真的打中人，{@link #clearPearlSlow()} 立刻抹掉，
     * 改吃母体每次攻击自带的 2 秒后摇（见 {@link #applyMotherRecover()}）。</p>
     */
    public void applyPearlSlow(int seconds, int level) {
        if (seconds <= 0) return;
        pearlSlowUntil = System.currentTimeMillis() + seconds * 1000L;
        LivingEntity target = motherLiving();
        if (target == null) return;
        try {
            target.addPotionEffect(new PotionEffect(ApiCompat.slowness(), seconds * 20,
                Math.max(0, level - 1), true, true));
        } catch (Throwable ignored) {
        }
        if (motherPlayerId != null) {
            Player player = Bukkit.getPlayer(motherPlayerId);
            if (player != null && player.isOnline()) {
                ActionBarManager.sendNow(player, plugin.getLanguageManager()
                    .getMessage(player, "hunter.skill.pearl-cast-hint", seconds));
            }
        }
    }

    /** 打中人了：抹掉珍珠的缓慢（后面紧接着由 applyMotherRecover 挂攻击后摇） */
    private void clearPearlSlow() {
        if (pearlSlowUntil <= 0) return;
        pearlSlowUntil = 0;
        LivingEntity target = motherLiving();
        if (target == null) return;
        try {
            target.removePotionEffect(ApiCompat.slowness());
        } catch (Throwable ignored) {
        }
    }

    /** 当前母体的 LivingEntity 载体（AI 或玩家），没有就 null */
    private LivingEntity motherLiving() {
        if (motherPlayerId != null) {
            Player player = Bukkit.getPlayer(motherPlayerId);
            if (player != null && player.isOnline()) return player;
            return null;
        }
        return mother instanceof LivingEntity ? (LivingEntity) mother : null;
    }

    /** 救赎护符还在生效就返回加速倍率，否则 1.0 */
    private double rescueBoostMultiplier(Player player) {
        if (player == null) return 1.0;
        Long until = rescueBoostUntil.get(player.getUniqueId());
        if (until == null) return 1.0;
        if (System.currentTimeMillis() >= until) {
            rescueBoostUntil.remove(player.getUniqueId());
            return 1.0;
        }
        return plugin.getConfigManager().getCharmSpeedMultiplier();
    }

    /**
     * 使用「救赎护符」：接下来一段时间内救人/救援读条按倍率加速。
     *
     * <p>它不替代「走到队友身边按住 Shift」——只是把读条时间缩短，
     * 所以护符是「抢时间」的道具，而不是远程救人。</p>
     */
    public boolean applyRescueBoost(Player player) {
        if (player == null || !player.isOnline()) return false;
        int seconds = plugin.getConfigManager().getCharmDurationSeconds();
        if (seconds <= 0) return false;
        rescueBoostUntil.put(player.getUniqueId(),
            System.currentTimeMillis() + seconds * 1000L);
        plugin.getLanguageManager().sendMessage(player, "item.charm-used",
            seconds, plugin.getConfigManager().getCharmSpeedMultiplier());
        playSoundAt(player.getLocation(), SoundCompat.notePling());
        return true;
    }

    // ================================================================ 单人测试

    /**
     * 单人测试开局：自己当唯一的逃生者，AI 母体默认**站桩不追**，
     * 准备阶段缩到几秒，并塞一套测试道具。
     */
    public boolean startTest(Player player) {
        if (player == null || !player.isOnline()) return false;

        List<Player> participants = new ArrayList<>();
        participants.add(player);
        if (!start(player, participants, null, false, true)) return false;

        // 默认就当正常对局打：测试要看的是真实攻击与追逐，
        // 想让母体站桩慢慢试法阵就 /ghga hunter testmother
        testMotherAggressive = true;
        // 覆盖成长度很短的准备时间，测试不用干等
        prepSecondsLeft = plugin.getConfigManager().getHunterTestPreparationSeconds();
        freezeAiMother(false);
        // 刷出来了就说清楚在哪：母体刷在掩体后面时很容易被当成「没生成」
        markMotherForTest();

        plugin.getLanguageManager().sendMessage(player, "hunter.test.started");
        return true;
    }

    public boolean isTestMode() {
        return testMode;
    }

    public boolean isMotherTestMode() {
        return motherTestMode;
    }

    /**
     * 母体测试开局：**管理员自己当母体**，人类侧用「会躲的测试假人」代替。
     *
     * <p>这样能真人验证追击手感、技能命中、冲撞眩晕这些东西，而不用拉第二个人进来。</p>
     */
    public boolean startTestAsMother(Player player) {
        if (player == null || !player.isOnline()) return false;
        if (!start(player, new ArrayList<>(), player, true, true)) return false;

        motherTestMode = true;
        testMotherAggressive = true;
        prepSecondsLeft = plugin.getConfigManager().getHunterTestPreparationSeconds();
        // start() 已经按正式时长播过一次，这里改成测试的实际准备时长
        broadcast("hunter.prep.broadcast", prepSecondsLeft);
        freezeAiMother(false);
        spawnTestDummies();

        plugin.getLanguageManager().sendMessage(player, "hunter.test.mother-started");
        return true;
    }

    // ================================================================ 测试假人

    /**
     * 测试假人：会朝离母体最远的方向跑，被打够次数就重置。
     *
     * <p>它不是玩家，所以不进 {@code onlineSurvivors()}，也不参与法阵/救援那套逻辑——
     * 只负责给母体一个「会跑的目标」。</p>
     */
    private void spawnTestDummies() {
        World world = area == null || area.getPos1() == null ? null : area.getPos1().getWorld();
        if (world == null) return;
        int count = plugin.getConfigManager().getHunterTestDummyCount();
        for (int i = 0; i < count; i++) {
            spawnOneDummy(world);
        }
        startDummyTask();
    }

    private void spawnOneDummy(World world) {
        if (world == null || area == null) return;

        // 贴着母体生成：测试时要能立刻看见目标，放在区域中心可能隔着半张图
        Location center = getMotherLocation();
        if (center == null || center.getWorld() != world) {
            center = area.getCenter();
        }
        if (center == null) return;

        int offsetX = random.nextInt(9) - 4;
        int offsetZ = random.nextInt(9) - 4;
        if (offsetX == 0 && offsetZ == 0) offsetX = 3;
        Location spawn = surfaceLocation(world,
            center.getBlockX() + offsetX, center.getBlockZ() + offsetZ);
        try {
            spawn.getChunk().load();
            Entity entity = world.spawnEntity(spawn, EntityType.VILLAGER);
            entity.setSilent(true);
            // 刻意**不给** invulnerable：无敌的实体吃不到伤害事件，
            // 母体就永远打不中它。真正「不掉血」靠 HunterListener 统一取消伤害。
            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                mob.setRemoveWhenFarAway(false);
                mob.setPersistent(true);
                // 原版 AI 会和我们的逃跑方向抢控制权，表现出来就是「站着不动」
                mob.setAI(false);
            }
            dummyHits.put(entity.getUniqueId(), plugin.getConfigManager().getHunterTestDummyHits());
            dummies.add(entity.getUniqueId());
            dummyWander.remove(entity.getUniqueId());
            dummyWanderUntil.remove(entity.getUniqueId());
            dummyLastPos.remove(entity.getUniqueId());
            renameDummy(entity);
            plugin.getLogger().info("GHG：测试假人生成于 "
                + spawn.getBlockX() + "," + spawn.getBlockY() + "," + spawn.getBlockZ());
            // 被打倒后补上来的假人也要有人推着走
            startDummyTask();
        } catch (Throwable t) {
            plugin.getLogger().warning("生成测试假人失败: " + t);
        }
    }

    private void renameDummy(Entity entity) {
        if (entity == null) return;
        int hits = dummyHits.getOrDefault(entity.getUniqueId(),
            plugin.getConfigManager().getHunterTestDummyHits());
        try {
            entity.setCustomName(plugin.getLanguageManager().getMessage("hunter.test.dummy-name", hits));
            entity.setCustomNameVisible(true);
        } catch (Throwable ignored) {
        }
    }

    public boolean isTestDummy(Entity entity) {
        return entity != null && dummies.contains(entity.getUniqueId());
    }

    /** 母体打中假人：扣一次，扣完就重置 */
    public void hitTestDummy(Entity dummy, Player attacker) {
        hitTestDummy(dummy, attacker, true);
    }

    /**
     * @param applyRecover 只有**近战砍击**才该吃母体后摇；技能命中（炼金者弩箭、缚网）给母体挂减速是错误的，
     *                     那会让人以为「放个技能自己先慢了」。测试局全靠假人验技能，所以这个参数很关键
     */
    public void hitTestDummy(Entity dummy, Player attacker, boolean applyRecover) {
        if (dummy == null) return;

        // 假人也要吃母体的攻击冷却与后摇，否则测试局里一秒能砍十下，
        // 既看不出真实节奏，也永远看不到后摇
        long cooldownMs = plugin.getConfigManager().getHunterMotherAttackCooldown() * 1000L;
        long sinceLast = System.currentTimeMillis() - lastMotherHitAt;
        if (sinceLast < cooldownMs) {
            // 冷却里也回一句，测试时才看得出「其实打中了、只是还在冷却」
            if (attacker != null) {
                ActionBarManager.sendNow(attacker, plugin.getLanguageManager()
                    .getMessage(attacker, "hunter.acc.cooldown",
                        Math.max(1, (int) Math.ceil((cooldownMs - sinceLast) / 1000.0))));
            }
            return;
        }
        lastMotherHitAt = System.currentTimeMillis();

        UUID id = dummy.getUniqueId();
        int left = dummyHits.getOrDefault(id, plugin.getConfigManager().getHunterTestDummyHits()) - 1;

        // 伤害事件被统一取消了，这里自己补一点反馈：击退 + 红叉，不然「打中了没打中」看不出来
        knockBackDummy(dummy, attacker);
        if (applyRecover) {
            clearPearlSlow();
            applyMotherRecover();
        }
        consumeMotherAxe(attacker);

        if (left > 0) {
            dummyHits.put(id, left);
            renameDummy(dummy);
            if (attacker != null) {
                plugin.getLanguageManager().sendMessage(attacker, "hunter.test.dummy-hit", left);
            }
            playSoundAt(dummy.getLocation(), SoundCompat.playerHurt());
            return;
        }

        if (attacker != null) {
            plugin.getLanguageManager().sendMessage(attacker, "hunter.test.dummy-down");
        }
        World world = dummy.getWorld();
        dummies.remove(id);
        dummyHits.remove(id);
        dummyWander.remove(id);
        dummyWanderUntil.remove(id);
        dummyLastPos.remove(id);
        dummyFleeSince.remove(id);
        dummyTiredUntil.remove(id);
        try {
            dummy.remove();
        } catch (Throwable ignored) {
        }
        ServerCompat.runLater(plugin, () -> spawnOneDummy(world), 20L);
    }

    private void knockBackDummy(Entity dummy, Player attacker) {
        if (dummy == null || attacker == null) return;
        try {
            org.bukkit.util.Vector push = dummy.getLocation().toVector()
                .subtract(attacker.getLocation().toVector());
            push.setY(0);
            if (push.lengthSquared() < 0.01) push = new org.bukkit.util.Vector(0, 0, 0);
            push.normalize().multiply(0.55);
            push.setY(0.28);
            dummy.setVelocity(push);
            dummy.getWorld().spawnParticle(
                ParticleCompat.damageIndicator(), dummy.getLocation().add(0, 1.0, 0), 4, 0.2, 0.2, 0.2);
        } catch (Throwable ignored) {
        }
    }

    /** 1 秒一次：补足数量、清理失效引用（移动在 4 刻一次的 moveDummies 里） */
    private void tickDummies() {
        if (!motherTestMode) return;
        if (state != State.RUNNING && state != State.PREPARING) return;

        // 被清理插件扫掉、或被打倒后没补上时，自动补回目标数量
        int wantCount = plugin.getConfigManager().getHunterTestDummyCount();
        long now = System.currentTimeMillis();
        if (dummies.size() < wantCount && now >= nextDummyTopUp) {
            nextDummyTopUp = now + 3000L;
            World world = area == null || area.getPos1() == null ? null : area.getPos1().getWorld();
            if (world != null) spawnOneDummy(world);
        }

        for (UUID id : new ArrayList<>(dummies)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid() || !(entity instanceof Mob)) {
                dummies.remove(id);
                dummyHits.remove(id);
                dummyWander.remove(id);
                dummyWanderUntil.remove(id);
                dummyLastPos.remove(id);
                dummyFleeSince.remove(id);
                dummyTiredUntil.remove(id);
            }
        }
    }

    private void startDummyTask() {
        if (dummyTask != null) return; // 已经在跑，别每次补假人都重开一个
        /* 每刻推一次。原来 4 刻推一次：速度每刻都要被地面摩擦吃掉一半，
           4 刻之后基本归零，表现出来就是「假人几乎不动」。 */
        dummyTask = ServerCompat.runTimer(plugin, this::moveDummies, 1L, 1L);
    }

    private void stopDummyTask() {
        if (dummyTask != null) {
            dummyTask.cancel();
            dummyTask = null;
        }
    }

    /**
     * 假人移动（4 刻一次）。
     *
     * <p>假人的原版 AI 是关掉的：以前用 {@code MotherBrain.moveTo} 给它寻路，可 villagers 自己的
     * AI 每一刻都会把速度覆盖回去，表现出来就是**站着不动**。现在直接算速度自己推：
     * 母体进了 {@code dummy-flee-range} 就背对着跑，平时在自己附近小范围游荡，顶到墙就跳一下。</p>
     */
    private void moveDummies() {
        if (dummies.isEmpty()) return;
        if (state != State.RUNNING && state != State.PREPARING) return;

        Location motherLocation = getMotherLocation();
        double fleeRange = plugin.getConfigManager().getHunterTestDummyFleeRange();
        double fleeDistance = plugin.getConfigManager().getHunterTestDummyFleeDistance();

        for (UUID id : new ArrayList<>(dummies)) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Mob) || !entity.isValid()) continue;
            Mob mob = (Mob) entity;

            // 卡住判定看**实际位移**：速度每刻都在衰减，用速度会一直判成卡住，结果是边走边跳
            Location current = mob.getLocation();
            Location last = dummyLastPos.get(id);
            boolean stuck = last != null && last.getWorld() == current.getWorld()
                && last.distanceSquared(current) < 0.0036;
            dummyLastPos.put(id, current.clone());

            boolean motherNear = motherLocation != null
                && mob.getWorld() == motherLocation.getWorld()
                && current.distance(motherLocation) <= fleeRange;

            /* 逃跑不能是「永久无解」：原来假人只要在 14 格内就朝反方向一直跑，
               而且速度几乎和母体一样快 —— 测试时永远追不上、一次都砍不到。
               现在给它体力：连逃 N 秒后站着喘 M 秒（这段时间就是给母体砍的窗口）。 */
            long now = System.currentTimeMillis();
            Long tired = dummyTiredUntil.get(id);
            boolean exhausted = tired != null && now < tired;

            if (motherNear && !exhausted) {
                long since = dummyFleeSince.getOrDefault(id, 0L);
                if (since == 0L) {
                    since = now;
                    dummyFleeSince.put(id, since);
                }
                int fleeSeconds = plugin.getConfigManager().getHunterTestDummyFleeSeconds();
                int tiredSeconds = plugin.getConfigManager().getHunterTestDummyTiredSeconds();
                boolean spent = fleeSeconds > 0 && now - since >= fleeSeconds * 1000L;

                if (spent) {
                    // 体力见底：停下喘气，清空计时，等上一会儿才能再跑
                    dummyFleeSince.remove(id);
                    dummyTiredUntil.put(id, now + tiredSeconds * 1000L);
                    steer(mob, current.clone(), 0.0, false);
                } else {
                    org.bukkit.util.Vector away = current.toVector()
                        .subtract(motherLocation.toVector());
                    away.setY(0);
                    if (away.lengthSquared() < 0.01) {
                        away = new org.bukkit.util.Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                    }
                    Location target = current.clone().add(away.normalize().multiply(fleeDistance));
                    dummyWander.remove(id);
                    dummyWanderUntil.remove(id);
                    double fleeSpeed = plugin.getConfigManager().getHunterTestDummyFleeSpeed();
                    steer(mob, target, fleeSpeed, stuck);
                    rescueStuckDummy(mob, target, fleeSpeed, stuck);
                }
            } else {
                if (!motherNear) {
                    dummyFleeSince.remove(id);
                    dummyTiredUntil.remove(id);
                }
                steer(mob, nextWanderTarget(mob, id), DUMMY_WANDER_SPEED, stuck);
            }
        }
    }

    /** 母体离得远时的小范围游荡目标，2.5~5 秒换一个，免得看起来像站桩 */
    private Location nextWanderTarget(Mob mob, UUID id) {
        Location current = dummyWander.get(id);
        long now = System.currentTimeMillis();
        long until = dummyWanderUntil.getOrDefault(id, 0L);
        if (current == null || now >= until
            || current.getWorld() != mob.getWorld()
            || current.distance(mob.getLocation()) < 1.5) {
            World world = mob.getWorld();
            int x = mob.getLocation().getBlockX() + random.nextInt(13) - 6;
            int z = mob.getLocation().getBlockZ() + random.nextInt(13) - 6;
            current = surfaceLocation(world, x, z);
            dummyWander.put(id, current);
            dummyWanderUntil.put(id, now + 2500L + random.nextInt(2500));
        }
        return current;
    }

    /**
     * 直接推速度并转向（每刻调用一次）。
     *
     * <p>假人的原版 AI 是关掉的，所以移动全靠这里给速度。两个坑：
     * ① **必须每刻给**，隔几刻给一次会被地面摩擦吃掉；
     * ② 顶到墙/台阶时单纯推速度没用，给一下竖直冲量才翻得过去。</p>
     */
    private void steer(Mob mob, Location target, double speed, boolean stuck) {
        if (target == null || target.getWorld() != mob.getWorld()) return;
        try {
            org.bukkit.util.Vector direction = target.toVector()
                .subtract(mob.getLocation().toVector());
            direction.setY(0);
            if (direction.lengthSquared() < 0.04) {
                mob.setVelocity(new org.bukkit.util.Vector(0, mob.getVelocity().getY(), 0));
                return;
            }
            double distance = direction.length();
            direction.normalize().multiply(speed);
            /* 卡住超过一拍就跳；快到位时不再给水平速度，免得贴着目标来回抖 */
            boolean jump = (stuck || blockedAhead(mob, direction)) && mob.isOnGround();
            if (jump) direction.setY(0.42);
            if (distance < 0.8 && !jump) {
                mob.setVelocity(new org.bukkit.util.Vector(0, mob.getVelocity().getY(), 0));
                return;
            }
            mob.setVelocity(direction);
            face(mob, direction);
        } catch (Throwable ignored) {
        }
    }

    /** 前方一格是不是被挡住（用来决定要不要跳） */
    private boolean blockedAhead(Mob mob, org.bukkit.util.Vector direction) {
        try {
            Location ahead = mob.getLocation().clone().add(direction.clone().normalize().multiply(0.7));
            org.bukkit.block.Block feet = ahead.getBlock();
            org.bukkit.block.Block head = ahead.clone().add(0, 1, 0).getBlock();
            return feet.getType().isSolid() || head.getType().isSolid();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 兜底：假人真的被卡死了（比如生成在石头缝里）就把它挪到旁边能站的地方。
     *
     * <p>测试假人唯一的职责就是「会跑」，卡在墙里等于这项测试完全失效，
     * 所以这里允许它位移一下（只对假人，对玩家和母体绝不做）。</p>
     */
    private void rescueStuckDummy(Mob mob, Location target, double speed, boolean stuck) {
        if (!stuck || target == null) return;
        try {
            org.bukkit.util.Vector direction = target.toVector().subtract(mob.getLocation().toVector());
            direction.setY(0);
            if (direction.lengthSquared() < 0.01) return;
            direction.normalize().multiply(speed * 4.0);
            Location spot = mob.getLocation().clone().add(direction);
            Location grounded = surfaceLocation(mob.getWorld(),
                spot.getBlockX(), spot.getBlockZ());
            if (grounded == null) return;
            if (mob.getLocation().distanceSquared(grounded) < 0.25) return;
            mob.teleport(grounded);
        } catch (Throwable ignored) {
        }
    }

    /** AI 关掉之后不会自己转头，得手动让它朝移动方向 */
    private void face(Mob mob, org.bukkit.util.Vector direction) {
        try {
            if (direction.lengthSquared() < 0.001) return;
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
            mob.setRotation(yaw, 0f);
        } catch (Throwable ignored) {
        }
    }

    private void clearDummies() {
        stopDummyTask();
        for (UUID id : new ArrayList<>(dummies)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) {
                try {
                    entity.remove();
                } catch (Throwable ignored) {
                }
            }
        }
        dummies.clear();
        dummyHits.clear();
        dummyWander.clear();
        dummyWanderUntil.clear();
        dummyLastPos.clear();
    }

    /** 切换测试模式里母体是否主动追击；返回切换后的状态 */
    public boolean toggleTestMotherAggression() {
        if (!testMode) return false;
        testMotherAggressive = !testMotherAggressive;
        freezeAiMother(!testMotherAggressive);
        return testMotherAggressive;
    }

    /**
     * 测试用：把所有未完成法阵的进度**设到** {@code percent}%（准备阶段也能用）。
     *
     * <p>{@code >= 100} 就等于「充满」：走正常的破除路径（地砖变色 + 计数），全破后开撤离点。
     * 低于 100 只改进度条，**不触发瘫痪** —— 瘫痪是「进度跨过阈值的那一下」，
     * 指令直接把数值改到 40% 不该顺手把那座阵瘫掉，否则想测高进度就测不了。</p>
     *
     * @return 改了几座法阵（0 = 没有可改的）
     */
    public int setAltarProgress(int percent) {
        if (state != State.RUNNING && state != State.PREPARING) return 0;
        int target = Math.max(0, Math.min(100, percent));
        int changed = 0;
        for (Altar altar : altars) {
            if (altar.charged || altar.isSealed()) continue;
            if (target >= 100) {
                altar.charged = true;
                altar.progress = 100;
                altar.boostUntil = 0;
                markLastChargedAltar(altar);
                lightPad(altar.getCells(), altar.center);
                plugin.getHunterStatsManager().countAltarCharged();
            } else {
                altar.progress = target;
            }
            changed++;
        }
        if (changed > 0) {
            altarProgress = target;
            updateAltarBar();
            if (target >= 100 && allAltarsCharged()) activateEscape();
        }
        return changed;
    }

    /** 把自己击倒，用来试救援/放血/淘汰流程 */
    public boolean forceDown(Player player) {
        if (!isRunning() || player == null) return false;
        if (!getAllParticipants().contains(player)) return false;
        if (downed.containsKey(player.getUniqueId())) return false;
        downPlayer(player);
        return true;
    }

    /**
     * 发一套当前对局可用的道具。
     *
     * <p>走 {@link #creatableItemIds()}，所以「AI 对局禁用凝冰球」这类过滤同样生效——
     * 之前直接遍历 survivorPool 会把它漏出来。</p>
     */
    public int giveTestKit(Player player) {
        if (player == null || !player.isOnline()) return 0;
        int given = 0;
        for (String id : creatableItemIds()) {
            ItemStack item = plugin.getItemManager().createById(id);
            if (item == null) continue;
            if (!plugin.getItemManager().addUnstacked(player, item)) break; // 背包满了
            given++;
        }
        player.updateInventory();
        return given;
    }

    // ================================================================ 受伤与治疗

    /**
     * 治疗：**受伤**（挨过一次但还没倒）的人才会被治。
     *
     * <p>队友蹲在旁边按住 Shift 就走「他人治疗」，自己蹲着按住 Shift 就是自愈（慢一倍）。
     * 和破阵/救援共用 Shift，但不冲突——倒地的人是「救援」，站着受伤的人是「治疗」。</p>
     */
    private void tickHeal() {
        if (!plugin.getConfigManager().isHunterHealEnabled()) return;

        double radius = plugin.getConfigManager().getHunterHealRadius();
        int otherSeconds = plugin.getConfigManager().getHunterHealOtherSeconds();
        int selfSeconds = plugin.getConfigManager().getHunterHealSelfSeconds();
        boolean decay = plugin.getConfigManager().isHunterHealDecay();

        for (Player player : onlineSurvivors()) {
            UUID id = player.getUniqueId();
            // 只有「受伤」状态才治：倒地的走救援，健康的不用管
            if (downed.containsKey(id) || getHitCount(id) != 1) {
                healProgress.remove(id);
                continue;
            }

            Player healer = findHealer(player, radius);
            // 自愈要有点力气：饿着的时候治不了伤（压低「边跑边自愈」的收益）
            boolean selfHeal = healer == null && player.isSneaking()
                && player.getFoodLevel() >= plugin.getConfigManager().getHunterHealMinFood();
            if (healer == null && player.isSneaking()
                && player.getFoodLevel() < plugin.getConfigManager().getHunterHealMinFood()) {
                ActionBarManager.sendNow(player, plugin.getLanguageManager()
                    .getMessage(player, "hunter.heal.need-food",
                        plugin.getConfigManager().getHunterHealMinFood()));
            }
            if (healer == null && !selfHeal) {
                if (decay) {
                    int left = healProgress.getOrDefault(id, 0) - 1;
                    if (left <= 0) {
                        healProgress.remove(id);
                    } else {
                        healProgress.put(id, left);
                    }
                }
                continue;
            }

            int need = healer != null ? otherSeconds : selfSeconds;
            if (healer != null) {
                need = Math.max(1, (int) Math.round(need / actionSpeed(healer)));
            }
            int progress = healProgress.getOrDefault(id, 0) + 1;
            if (progress >= need) {
                healProgress.remove(id);
                finishHeal(player, healer);
                continue;
            }

            healProgress.put(id, progress);
            String text = plugin.getLanguageManager().getMessage(player, "hunter.heal.progress", progress, need);
            ActionBarManager.sendNow(player, text);
            if (healer != null) ActionBarManager.sendNow(healer, text);
        }
    }

    /** 找一个蹲在旁边、能治人的队友 */
    private Player findHealer(Player target, double radius) {
        for (Player candidate : onlineSurvivors()) {
            if (candidate.getUniqueId().equals(target.getUniqueId())) continue;
            if (downed.containsKey(candidate.getUniqueId())) continue;
            if (!candidate.isSneaking()) continue;
            if (candidate.getWorld() != target.getWorld()) continue;
            if (candidate.getLocation().distance(target.getLocation()) > radius) continue;
            return candidate;
        }
        return null;
    }

    private void finishHeal(Player player, Player healer) {
        hitCounts.put(player.getUniqueId(), 0);
        plugin.getHunterStatsManager().countHeal();
        playSoundAt(player.getLocation(), SoundCompat.playerLevelup());
        if (healer != null) {
            plugin.getLanguageManager().sendMessage(player, "hunter.heal.done-by", healer.getName());
            plugin.getLanguageManager().sendMessage(healer, "hunter.heal.done-you", player.getName());
        } else {
            plugin.getLanguageManager().sendMessage(player, "hunter.heal.done-self");
        }
    }

    /** 治疗进度（供状态查询） */
    public int getHealProgress(UUID playerId) {
        return healProgress.getOrDefault(playerId, 0);
    }

    // ================================================================ 道具投放与痕迹

    /**
     * 道具只从道具箱里出，不再往地上丢。
     *
     * <p>每 {@code chest.wave-interval-seconds} 秒补一波西瓜，全场同时最多
     * {@code chest.max} 个；被敲破的位置空出来才会补新的。</p>
     */
    private void startChestWaves() {
        stopChestWaves();
        int interval = plugin.getConfigManager().getHunterChestWaveIntervalSeconds();
        chestWaveTask = ServerCompat.runTimer(plugin, this::spawnChestWave, interval * 20L, interval * 20L);
    }

    private void stopChestWaves() {
        if (chestWaveTask != null) {
            chestWaveTask.cancel();
            chestWaveTask = null;
        }
    }

    private void spawnChestWave() {
        if (state != State.RUNNING) return;
        World world = area == null || area.getPos1() == null ? null : area.getPos1().getWorld();
        if (world == null) return;

        int max = plugin.getConfigManager().getHunterChestMax();
        int perRound = plugin.getConfigManager().getHunterChestWavePerRound();
        int spawned = 0;
        for (int i = 0; i < perRound; i++) {
            if (chestIndex.size() >= max) break;
            if (spawnOneChest(world)) spawned++;
        }
        if (spawned > 0) {
            broadcast("hunter.chest.wave", chestIndex.size(), max);
            playToParticipants(SoundCompat.noteBell(), 1.0f, 1.3f);
            playToParticipants(SoundCompat.itemPickup(), 0.9f, 1.3f);
        }
    }

    /**
     * 清掉地面上还没被捡走的 GHG 道具。
     *
     * <p>方块（法阵地砖、外框、道具箱）走 {@code restorePlacedBlocks} 还原，
     * 但**掉落物是实体**，不删就会一直留在地图里。所以收局时：
     * 先删我们自己记过的那批，再把区域里残留的 GHG 自定义道具实体扫一遍兜底。</p>
     */
    private int clearGroundItems() {
        int removed = 0;

        // 兜底：区域内的 GHG 道具实体（比如被别人挪走、或列表漏记的）
        World world = area == null || area.getPos1() == null ? null : area.getPos1().getWorld();
        if (world == null) return removed;
        try {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof org.bukkit.entity.Item)) continue;
                if (!area.contains(entity.getLocation())) continue;
                ItemStack stack = ((org.bukkit.entity.Item) entity).getItemStack();
                if (!ItemIds.isCustom(stack)) continue;
                entity.remove();
                removed++;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("清理地面道具时出错: " + t.getMessage());
        }
        return removed;
    }

    private List<String> creatableItemIds() {
        List<String> pool = new ArrayList<>();
        for (String id : ItemIds.survivorPool()) {
            if (ItemIds.ICE_BALL.equals(id) && motherPlayerId == null) continue;
            // 口粮默认就在随机池里；只有老配置把 guaranteed-in-chest 打开时才走「必出」那条路
            if (ItemIds.RATION.equals(id)
                && plugin.getConfigManager().isHunterRationGuaranteed()) continue;
            if (plugin.getItemManager().createById(id) == null) continue;
            /* 加权：池子里一个 id 出现几次就是几倍权重。
               口粮和漂浮药水可以单独调高（16 分钟只开出 3 份口粮太少了）。 */
            int weight = chestWeight(id);
            for (int i = 0; i < weight; i++) pool.add(id);
        }
        return pool;
    }

    /** 道具箱随机池权重：口粮 / 漂浮药水可配，其余 1（等权）；0 = 不进池 */
    private int chestWeight(String id) {
        if (ItemIds.RATION.equals(id)) {
            return plugin.getConfigManager().getHunterRationChestWeight();
        }
        if (ItemIds.LEVITATION_POTION.equals(id)) {
            return plugin.getConfigManager().getLevitationChestWeight();
        }
        return 1;
    }

    private void startTrails() {
        stopTrails();
        if (!plugin.getConfigManager().isHunterTrailEnabled()) return;
        // 半秒一次，痕迹才是连续的
        trailTask = ServerCompat.runTimer(plugin, this::tickTrails, 10L, 10L);
    }

    private void stopTrails() {
        if (trailTask != null) {
            trailTask.cancel();
            trailTask = null;
        }
    }

    /**
     * 抓痕与血迹。
     *
     * <p>抓痕：没蹲着且在移动的逃生者脚下留灰白扬尘；血迹：受伤或倒地的人留暗红粒子。
     * 注意粒子是全服可见的——做不到「只有母体看得见」，这点和发光效果是同一个限制。</p>
     */
    private void tickTrails() {
        if (state != State.RUNNING) return;
        tickMotherFenceHit();

        for (Player player : onlineSurvivors()) {
            World world = player.getWorld();
            if (world == null) continue;
            Location feet = player.getLocation().add(0, 0.12, 0);
            UUID id = player.getUniqueId();

            try {
                if (getHitCount(id) >= 1 || downed.containsKey(id)) {
                    world.spawnParticle(ParticleCompat.damageIndicator(), feet, 2, 0.16, 0.05, 0.16);
                }
                if (!player.isSneaking() && player.getVelocity().lengthSquared() > 0.012) {
                    world.spawnParticle(ParticleCompat.cloud(), feet, 2, 0.12, 0.04, 0.12);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 母体饱食度始终拉满。
     *
     * <p>饥饿加压只针对人类阵营；母体是猎手，不该被饿拖住（玩家扮演母体时同样适用）。
     * 这里每秒把饱食度与饱和度顶回满值，并主动甩掉任何来源的饥饿效果，
     * 保证母体整局都不掉饱食度。</p>
     */
    private void tickMotherVitals() {
        if (motherPlayerId == null) return;
        Player mother = Bukkit.getPlayer(motherPlayerId);
        if (mother == null || !mother.isOnline()) return;
        try {
            if (mother.getFoodLevel() < 20) mother.setFoodLevel(20);
            if (mother.getSaturation() < 20.0f) mother.setSaturation(20.0f);
            // 清掉消耗度，否则两次 tick 之间跑几步就会掉半格再被补满，血条是跳的
            if (mother.getExhaustion() > 0f) mother.setExhaustion(0f);
            if (mother.hasPotionEffect(PotionEffectType.HUNGER)) {
                mother.removePotionEffect(PotionEffectType.HUNGER);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 人类阵营饱食度加压。
     *
     * <p>GHG 禁了疾跑、又压黑暗，逃生者本来就慢；这里再让**饥饿掉得更快**，
     * 逼他们分心去找吃的，而不是一直躲在法阵里。</p>
     *
     * <p>食物见底时追加缓慢——不然「饿」只是个界面数字，没有实际代价。</p>
     */
    private void tickHunger() {
        if (!plugin.getConfigManager().isHunterHungerEnabled()) return;

        int amplifier = plugin.getConfigManager().getHunterHungerAmplifier();
        int interval = plugin.getConfigManager().getHunterHungerIntervalSeconds();
        boolean lowFoodSlow = plugin.getConfigManager().isHunterLowFoodSlowness();
        int threshold = plugin.getConfigManager().getHunterLowFoodThreshold();

        for (Player player : onlineSurvivors()) {
            try {
                // 饥饿效果会加快消耗度累积，持续时间略长于间隔，保证不断档
                player.addPotionEffect(new PotionEffect(
                    PotionEffectType.HUNGER, interval * 20 + 40, amplifier, true, false));

                if (player.getFoodLevel() <= threshold) {
                    if (hungerWarned.add(player.getUniqueId())) {
                        plugin.getLanguageManager().sendMessage(player, "hunter.hunger.low");
                    }
                    if (lowFoodSlow) {
                        player.addPotionEffect(new PotionEffect(
                            ApiCompat.slowness(), 40, 0, true, false));
                    }
                } else if (player.getFoodLevel() > threshold + 2) {
                    hungerWarned.remove(player.getUniqueId());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * **所有母体共用的被动**：站着不动累计到 {@code stationary-seconds} 后，
     * 射程内的逃生者发光（透视）。一动就断，要重新攒。
     */
    private void tickPassiveSight() {
        if (!plugin.getConfigManager().isHunterPassiveSightEnabled()) return;

        Location current = getMotherLocation();
        if (current == null) {
            lastMotherLocation = null;
            stationarySince = 0L;
            setPassiveSightActive(false);
            return;
        }

        // 第一次采样直接当成「开始站着」，不然还要多等一个 tick 才起算
        if (lastMotherLocation == null) {
            lastMotherLocation = current.clone();
            stationarySince = System.currentTimeMillis();
            return;
        }

        boolean stationary = lastMotherLocation.getWorld() == current.getWorld()
            && lastMotherLocation.distance(current) <= 0.6;
        lastMotherLocation = current.clone();

        if (!stationary) {
            stationarySince = 0L;
            setPassiveSightActive(false);
            return;
        }
        if (stationarySince == 0L) stationarySince = System.currentTimeMillis();

        long needMs = plugin.getConfigManager().getHunterPassiveSightStationarySeconds() * 1000L;
        if (System.currentTimeMillis() - stationarySince < needMs) {
            setPassiveSightActive(false);
            return;
        }

        double range = plugin.getConfigManager().getHunterPassiveSightRange();
        boolean any = false;

        for (Player player : onlineSurvivors()) {
            if (player.getWorld() != current.getWorld()) continue;
            if (player.getLocation().distance(current) > range) continue;
            try {
                player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30, 0, true, true));
                any = true;
            } catch (Throwable ignored) {
            }
        }

        // 母体测试模式场上没有玩家，只有假人——不把它们算进来就完全看不到效果
        for (UUID id : new ArrayList<>(dummies)) {
            Entity dummy = Bukkit.getEntity(id);
            if (dummy == null || !dummy.isValid()) continue;
            if (dummy.getWorld() != current.getWorld()) continue;
            if (dummy.getLocation().distance(current) > range) {
                setDummyGlow(dummy, false);
                continue;
            }
            setDummyGlow(dummy, true);
            any = true;
        }

        setPassiveSightActive(any);
    }

    private void setDummyGlow(Entity entity, boolean glowing) {
        if (entity == null) return;
        try {
            entity.setGlowing(glowing);
        } catch (Throwable ignored) {
        }
    }

    /** 透视状态切换时给母体一次反馈，否则「有没有生效」完全看不出来 */
    private void setPassiveSightActive(boolean active) {
        if (passiveSightActive == active) return;
        passiveSightActive = active;

        if (!active) {
            // 关掉时把假人的发光一起收掉
            for (UUID id : new ArrayList<>(dummies)) {
                setDummyGlow(Bukkit.getEntity(id), false);
            }
            return;
        }
        if (motherPlayerId == null) return;
        Player motherPlayer = Bukkit.getPlayer(motherPlayerId);
        if (motherPlayer != null && motherPlayer.isOnline()) {
            plugin.getLanguageManager().sendMessage(motherPlayer, "hunter.sight.active");
        }
    }

    // ================================================================ 窥视者的隐身

    /**
     * 隐身 + 加速。成功打中逃生者后由 {@link #cancelVanishSpeed()} 立刻取消加速。
     */
    public boolean applyMotherVanish(int seconds, int speedLevel) {
        LivingEntity target = motherLivingEntity();
        if (target == null || seconds <= 0) return false;
        try {
            target.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                seconds * 20, 0, true, false));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                seconds * 20, Math.max(0, speedLevel - 1), true, false));
        } catch (Throwable t) {
            return false;
        }
        vanishSpeedActive = true;
        return true;
    }

    /** 打中人之后，隐身留着、加速立刻没 */
    private void cancelVanishSpeed() {
        if (!vanishSpeedActive) return;
        vanishSpeedActive = false;
        LivingEntity target = motherLivingEntity();
        if (target == null) return;
        try {
            target.removePotionEffect(PotionEffectType.SPEED);
        } catch (Throwable ignored) {
        }
        notifyMotherPlayer("hunter.skill.vanish-broken");
    }

    private LivingEntity motherLivingEntity() {
        if (motherPlayerId != null) {
            Player player = Bukkit.getPlayer(motherPlayerId);
            return (player != null && player.isOnline()) ? player : null;
        }
        return (mother instanceof LivingEntity) ? (LivingEntity) mother : null;
    }

    /**
     * 初始种类。
     *
     * <p>配置写死了就用它；写 RANDOM 时 AI 母体随机抽，**玩家母体给最便宜的那一档**——
     * 不然没进菜单就白拿一个付费种类。</p>
     */
    private MotherKind resolveInitialKind(boolean playerMother) {
        MotherKind fixed = MotherKind.resolveOrNull(plugin.getConfigManager().getHunterMotherKind());
        if (fixed != null) return fixed;

        List<MotherKind> pool = MotherKind.resolveEnabled(plugin.getConfigManager().getHunterEnabledKinds());
        if (playerMother) {
            MotherKind cheapest = pool.get(0);
            for (MotherKind kind : pool) {
                if (plugin.getConfigManager().getHunterKindCost(kind)
                    < plugin.getConfigManager().getHunterKindCost(cheapest)) {
                    cheapest = kind;
                }
            }
            return cheapest;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private void startParticleTask() {
        stopParticleTask();
        if (!plugin.getConfigManager().isHunterParticlesEnabled()) return;
        particleTask = ServerCompat.runTimer(plugin, this::tickParticles, 4L, 4L);
    }

    private void stopParticleTask() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
    }

    /** 画一个水平粒子圈（法阵用） */
    private void drawRing(World world, Location center, double radius, double yOffset,
                          int points, org.bukkit.Particle particle, double phase) {
        if (world == null || particle == null) return;
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2 * i) / points + phase;
            world.spawnParticle(particle,
                center.clone().add(Math.cos(angle) * radius, yOffset, Math.sin(angle) * radius),
                1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 信标光柱：往天上打一列 END_ROD。
     *
     * <p>真信标需要方块与金字塔，这里用粒子模拟——每隔几格打一撮，转着相位往上走，
     * 远处也能看见一根白光柱。</p>
     */
    private void drawBeam(World world, Location center, int height, double phase, int step) {
        if (world == null) return;
        org.bukkit.Particle beam = ParticleCompat.endRod();
        if (beam == null) return;
        int start = (int) (phase * step) % Math.max(1, step);
        for (int y = start; y <= height; y += step) {
            world.spawnParticle(beam, center.clone().add(0, y, 0), 2, 0.18, 0.18, 0.18, 0.0);
        }
    }

    /**
     * 方形边框：逃脱点专用。
     *
     * <p>法阵是「圆环 + 中心光柱」，逃脱点原来照抄同一套，远看两个点几乎一样。
     * 这里改画**方框**，形状与粒子都跟法阵区分开（电火花 vs 附魔 / 图腾）。</p>
     */
    private void drawSquareFrame(World world, Location center, double radius, double yOffset,
                                 org.bukkit.Particle particle, int perSide) {
        if (world == null || particle == null || perSide <= 0) return;
        for (int side = 0; side < 4; side++) {
            for (int i = 0; i <= perSide; i++) {
                double t = -radius + (2 * radius) * i / perSide;
                double x;
                double z;
                if (side == 0) { x = t; z = -radius; }
                else if (side == 1) { x = t; z = radius; }
                else if (side == 2) { x = -radius; z = t; }
                else { x = radius; z = t; }
                world.spawnParticle(particle, center.clone().add(x, yOffset, z), 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * 带颜色的粉尘粒子（DUST / REDSTONE）。
     *
     * <p>不传颜色的粉尘默认是亮红，想要「暗红压迫感」必须自己给 DustOptions；
     * 老版本没有这个类就退回不带 data 的写法，至少颜色不至于全丢。</p>
     */
    private void spawnDarkDust(World world, Location location, int count, double spread,
                               int red, int green, int blue, float size) {
        if (world == null || location == null) return;
        org.bukkit.Particle particle = ParticleCompat.dust();
        if (particle == null) return;
        try {
            world.spawnParticle(particle, location, count, spread, spread * 0.5, spread, 0.0,
                new org.bukkit.Particle.DustOptions(
                    org.bukkit.Color.fromRGB(red, green, blue), size));
            return;
        } catch (Throwable ignored) {
            // 老版本没有 DustOptions / 不支持 data 重载
        }
        try {
            world.spawnParticle(particle, location, count, spread, spread * 0.5, spread, 0.0);
        } catch (Throwable ignored) {
        }
    }

    /** 四角竖柱：像门的四根灯管，往上走 */
    private void drawCornerPillars(World world, Location center, double radius, int height, double phase) {
        if (world == null) return;
        org.bukkit.Particle spark = ParticleCompat.electricSpark();
        org.bukkit.Particle top = ParticleCompat.endRod();
        int step = 2;
        int start = (int) phase % step;
        for (int cx = -1; cx <= 1; cx += 2) {
            for (int cz = -1; cz <= 1; cz += 2) {
                Location corner = center.clone().add(cx * radius, 0, cz * radius);
                for (int y = start; y <= height; y += step) {
                    world.spawnParticle(spark, corner.clone().add(0, y, 0), 1, 0.0, 0.0, 0.0, 0.0);
                }
                if (top != null) {
                    world.spawnParticle(top, corner.clone().add(0, height + 0.6, 0), 1, 0.0, 0.0, 0.0, 0.0);
                }
            }
        }
    }

    /**
     * 附着在光柱上的火焰：几簇火苗沿着柱子往上爬、左右轻微摆动。
     *
     * <p>火苗只是**贴着柱身走**，横向偏移只有 0.16 格，不会糊成一团挡住光柱本身；
     * 相位错开之后看起来像柱子在烧。</p>
     */
    private void drawFlameColumn(World world, Location center, int height, double phase, int count) {
        if (world == null || count <= 0) return;
        org.bukkit.Particle flame = ParticleCompat.byName("FLAME");
        if (flame == null) return;
        for (int i = 0; i < count; i++) {
            double t = (phase * 0.12 + (double) i / count) % 1.0;
            double y = 0.3 + t * height;
            double sway = Math.sin(phase * 0.8 + i * 1.3) * 0.16;
            world.spawnParticle(flame, center.clone().add(sway, y, -sway),
                1, 0.04, 0.04, 0.04, 0.01);
        }
    }

    /** 向内收拢并上升的螺旋：像传送门在往里吸人 */
    private void drawVortex(World world, Location center, double radius, double phase) {
        if (world == null) return;
        org.bukkit.Particle spark = ParticleCompat.firework();
        if (spark == null) return;
        int steps = 10;
        for (int i = 0; i < steps; i++) {
            double t = i / (double) steps;
            double r = radius * (1.0 - t);
            double angle = phase * 0.6 + t * Math.PI * 2.0;
            double y = 0.3 + t * 3.2;
            world.spawnParticle(spark,
                center.clone().add(Math.cos(angle) * r, y, Math.sin(angle) * r),
                1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private void startAltarTask() {
        stopAltarTask();
        // 每 4 刻结算一次（每秒 5 次），进度条才是连续涨的，而不是一秒一跳
        altarTask = ServerCompat.runTimer(plugin, this::tickAltarsFine, 4L, 4L);

        // 倒地姿态必须每 tick 重申：服务端每 tick 会按实体状态重算 pose，设一次会被顶回去
        if (downedPoseTask != null) {
            downedPoseTask.cancel();
            downedPoseTask = null;
        }
        downedPoseTask = ServerCompat.runTimer(plugin, this::tickDownedPose, 1L, 1L);

        // 血量显示：健康 10 心 / 受伤 3 心 / 倒地 1 心（半秒校正一次，兜住原版自然回血）
        if (healthDisplayTask != null) {
            healthDisplayTask.cancel();
            healthDisplayTask = null;
        }
        healthDisplayTask = ServerCompat.runTimer(plugin, this::tickHealthDisplay, 10L, 10L);
    }

    private void stopAltarTask() {
        if (altarTask != null) {
            altarTask.cancel();
            altarTask = null;
        }
        if (downedPoseTask != null) {
            downedPoseTask.cancel();
            downedPoseTask = null;
        }
        if (healthDisplayTask != null) {
            healthDisplayTask.cancel();
            healthDisplayTask = null;
        }
    }

    /**
     * 血量显示：**健康满血（10 心）· 受伤 3 颗心 · 倒地 1 颗心**。
     *
     * <p>GHG 的伤害事件全被拦掉、改走击倒计数，所以血量只是个**状态指示器**。
     * 原来它一直是满的，玩家看不出自己「带伤」还是「倒地」。
     * 每秒刷两次是为了压掉原版自然回血（饱食度高时血会自己往上爬）。</p>
     */
    private void tickHealthDisplay() {
        if (state != State.RUNNING) return;
        for (Player player : onlineSurvivors()) {
            double target;
            if (downed.containsKey(player.getUniqueId())) {
                target = plugin.getConfigManager().getHunterDownedHealth();
            } else if (getHitCount(player.getUniqueId()) >= 1) {
                target = plugin.getConfigManager().getHunterDamagedHealth();
            } else {
                target = player.getMaxHealth();
            }
            double wanted = Math.max(1.0, Math.min(target, player.getMaxHealth()));
            if (Math.abs(player.getHealth() - wanted) < 0.5) continue;
            try {
                player.setHealth(wanted);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把百分比画成 10 格的文本进度条 */
    private String progressBar(int percent) {
        int filled = Math.max(0, Math.min(10, percent / 10));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? "§a█" : "§8░");
        }
        return sb.toString();
    }

    /**
     * 法阵进度实时结算（每 4 刻一次）。
     *
     * <p><b>每座法阵有自己的进度条</b>：站在这座阵上按住 Shift 就涨**它自己的**进度；
     * 母体进到这座阵的守机半径内会给它回落；涨满 100% 这座阵就破除，全部破除后开撤离点。</p>
     *
     * <p><b>瘫痪</b>：单座阵的进度**第一次**跨过 {@code paralyze.at}（默认 20%）时，这座阵瘫痪
     * {@code paralyze.seconds}（默认 60 秒）—— 期间谁站上去都不涨、破阵刀无效、母体也不能加固它。
     * 地砖会变成灰色玻璃，一眼能看出这座阵暂时没用。</p>
     */
    private void tickAltarsFine() {
        if (state != State.RUNNING || altars.isEmpty()) return;
        long now = System.currentTimeMillis();

        // 加固 / 瘫痪到期
        for (Altar altar : altars) {
            if (altar.hardenedUntil > 0 && now >= altar.hardenedUntil) {
                altar.hardenedUntil = 0;
                lightPadOff(altar);
                broadcast("hunter.altar.hardened-end", altar.index + 1);
            }
            if (altar.paralyzedUntil > 0 && now >= altar.paralyzedUntil) {
                altar.paralyzedUntil = 0;
                lightPadOff(altar);              /* 灰色玻璃恢复成粉色 */
                broadcast("hunter.altar.paralyzed-end", altar.index + 1);
            }
            /* 破阵石加速到期：这 20 秒里该瘫痪的不瘫痪，现在一次性补上 */
            if (altar.boostUntil > 0 && now >= altar.boostUntil) {
                altar.boostUntil = 0;
                altar.boostMultiplier = 1.0;
                if (!altar.charged && !altar.paralyzedOnce
                    && altar.progress >= plugin.getConfigManager().getHunterAltarParalyzeAt()) {
                    triggerParalyze(altar);
                }
            }
        }

        double factor = 4.0 / 20.0;
        double progressRate = plugin.getConfigManager().getHunterAltarProgressPerSecond() * factor;
        double decayRate = plugin.getConfigManager().getHunterAltarDecayPerSecond() * factor;
        double decayRadius = plugin.getConfigManager().getHunterAltarDecayRadius();

        List<Player> survivors = onlineSurvivors();
        Location motherLocation = getMotherLocation();
        double highest = 0;

        for (Altar altar : altars) {
            if (altar.charged || altar.isSealed()) continue;
            boolean hardened = now < altar.hardenedUntil;
            boolean paralyzed = now < altar.paralyzedUntil;

            /* 只统计「这一座阵」上的人：**最多 max-chargers 个人一起破**，
               这几个人的职业权重**相加**（2 个人就是翻倍）；超出的人不起算，只提示。 */
            int maxChargers = plugin.getConfigManager().getHunterAltarMaxChargers();
            int standing = 0;
            int standingIdle = 0;
            double totalWeight = 0.0;
            for (Player player : survivors) {
                if (downed.containsKey(player.getUniqueId())) continue;
                if (!isStandingOnAltar(player, altar)) continue;
                if (!player.isSneaking()) {
                    standingIdle++;
                    continue;
                }
                if (hardened || paralyzed) {
                    if (paralyzed) {
                        int left = Math.max(0, (int) Math.ceil((altar.paralyzedUntil - now) / 1000.0));
                        ActionBarManager.sendNow(player, plugin.getLanguageManager()
                            .getMessage(player, "hunter.altar.paralyzed-hint", left));
                    } else {
                        ActionBarManager.sendNow(player, plugin.getLanguageManager()
                            .getMessage(player, "hunter.altar.hardened-hint"));
                    }
                    continue;
                }
                if (maxChargers > 0 && standing >= maxChargers) {
                    ActionBarManager.sendNow(player, plugin.getLanguageManager()
                        .getMessage(player, "hunter.altar.charger-full", maxChargers));
                    continue;
                }
                standing++;
                totalWeight += chargeWeight(player);
                chargeTicks.merge(player.getUniqueId(), 1, Integer::sum);
                glowAltarCharger(player);
            }

            if (standing == 0 && standingIdle > 0) {
                for (Player player : survivors) {
                    if (!isStandingOnAltar(player, altar) || player.isSneaking()) continue;
                    ActionBarManager.sendNow(player, plugin.getLanguageManager()
                        .getMessage(player, hardened ? "hunter.altar.hardened-hint"
                            : "hunter.altar.sneak-hint"));
                }
            }
            if (standing == 0) {
                highest = Math.max(highest, altar.progress);
                continue;
            }

            /* 蓄力时的「哒哒哒」：间隔 hunter.altar.charge-tick-ticks 刻，音高随进度上抬 */
            tickAltarChargeSound(altar, now, hardened || paralyzed);

            /* 涨速：单个人类的 0.9 倍 × 这座阵上蓄力者的职业权重之和（最多 2 人）
               破阵石的加速再乘在这个结果上（×2 就是翻倍） */
            if (totalWeight <= 0) totalWeight = 1.0;
            double boost = now < altar.boostUntil ? altar.boostMultiplier : 1.0;
            double charge = progressRate * 0.9 * totalWeight * boost;
            boolean suppressed = motherLocation != null
                && motherLocation.getWorld() == altar.center.getWorld()
                && motherLocation.distance(altar.center) <= decayRadius;
            double delta = charge - (suppressed ? decayRate : 0);

            double before = altar.progress;
            altar.progress = Math.max(0, Math.min(100, before + delta));

            if (maybeParalyze(altar, before)) {
                highest = Math.max(highest, altar.progress);
                continue;
            }
            if (altar.progress >= 100) {
                chargeAltar(altar);
            } else {
                showAltarProgress(altar, true, suppressed && delta < 0);
            }
            highest = Math.max(highest, altar.progress);
        }

        /* altarProgress 现在只是「最高那座」的口径（status / 自检用），不再参与进度计算 */
        altarProgress = highest;

        int step = (int) (altarProgress / 10);
        if (step > sharedAnnouncedStep && step >= 1 && step <= 9) {
            sharedAnnouncedStep = step;
            playToParticipants(SoundCompat.notePling(), 0.8f, 0.8f + step * 0.12f);
        } else if (step < sharedAnnouncedStep) {
            sharedAnnouncedStep = step;
        }

        if (allAltarsCharged()) handleAllCharged();

        updateAltarBar();
    }

    /**
     * 蓄力时的「哒哒哒」。
     *
     * <p>每 {@code hunter.altar.charge-tick-ticks} 刻一记（默认 10 刻 = 0.5 秒一记，0 = 关），
     * 音高随这座阵的进度往上抬 —— 进度越接近 100%，节奏听起来越急。加固 / 瘫痪中不响。</p>
     */
    private void tickAltarChargeSound(Altar altar, long now, boolean blocked) {
        if (blocked || altar == null) return;
        int ticks = plugin.getConfigManager().getHunterAltarChargeTickTicks();
        if (ticks <= 0) return;
        long interval = ticks * 50L;
        if (now - altar.lastTickAt < interval) return;
        altar.lastTickAt = now;

        org.bukkit.World world = altar.center.getWorld();
        if (world == null) return;
        float pitch = 0.9f + (float) (altar.progress / 100.0) * 0.5f;
        try {
            world.playSound(altar.center, SoundCompat.noteHat(), 0.45f, pitch);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 所有法阵都破了：开撤离点 + 播报庆祝。
     *
     * <p>⚠️ **庆祝只做一次**（{@code allChargedCelebrated}）—— 之前这里的条件是
     * {@code !escapeActive && allAltarsCharged()}，一旦 {@link #activateEscape()} 因为任何原因
     * 没把 {@code escapeActive} 置成 true（异常 / 挑不到落点），下个 tick 又会整段重来，
     * 于是「法阵全部破除 · 撤离点已开启」每秒刷 5 行。现在播报走独立的闸门，
     * 撤离点开不出来时**只告警一次并带堆栈**，方便定位。</p>
     */
    private void handleAllCharged() {
        if (!escapeActive) {
            try {
                activateEscape();
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                if (now - lastEscapeWarnAt > 5000L) {
                    lastEscapeWarnAt = now;
                    plugin.getLogger().warning("开启撤离点失败: " + t);
                    t.printStackTrace();
                }
            }
        }
        if (!allChargedCelebrated) {
            allChargedCelebrated = true;
            celebrateAllAltarsCharged();
        }
    }

    /** 这座阵涨满 100%：破除 + 播报 + 音效（单座阵独立结算） */
    private void chargeAltar(Altar altar) {
        if (altar == null || altar.charged) return;
        altar.charged = true;
        altar.progress = 100;
        altar.paralyzedUntil = 0;
        altar.boostUntil = 0;
        lightPad(altar.getCells(), altar.center);
        plugin.getHunterStatsManager().countAltarCharged();
        broadcast("hunter.altar.charged", altar.index + 1);
        playAltarChargedSound(altar.center);
    }

    /**
     * 进度从 {@code before} 涨上来，若**第一次**跨过瘫痪阈值就让它瘫痪一分钟。
     *
     * @return true 表示这一下触发了瘫痪（调用方跳过后续的「破除 / 显示进度」）
     */
    private boolean maybeParalyze(Altar altar, double before) {
        if (altar == null || altar.paralyzedOnce) return false;
        if (!plugin.getConfigManager().isHunterAltarParalyzeEnabled()) return false;
        int at = plugin.getConfigManager().getHunterAltarParalyzeAt();
        if (at <= 0 || at >= 100) return false;
        if (before >= at || altar.progress < at) return false;
        /* 破阵石加速期间不瘫痪：这 20 秒是花一块石头买来的窗口，
           到期时由 tickAltarsFine 的到期检查补触发（不然这次瘫痪就永久丢了）。 */
        if (System.currentTimeMillis() < altar.boostUntil) return false;
        return triggerParalyze(altar);
    }

    /**
     * 让这座阵瘫痪（唯一落地点）。
     *
     * <p>调用方两处：进度**跨过阈值**的常态判定（{@link #maybeParalyze}），以及
     * **破阵石加速到期**时对被推迟的那一次补触发。已破除的阵不再瘫痪。</p>
     */
    private boolean triggerParalyze(Altar altar) {
        if (altar == null || altar.paralyzedOnce || altar.charged) return false;
        if (!plugin.getConfigManager().isHunterAltarParalyzeEnabled()) return false;
        int at = plugin.getConfigManager().getHunterAltarParalyzeAt();
        if (at <= 0 || at >= 100) return false;

        int seconds = plugin.getConfigManager().getHunterAltarParalyzeSeconds();
        altar.paralyzedOnce = true;
        altar.paralyzedUntil = System.currentTimeMillis() + seconds * 1000L;
        for (Location cell : altar.getCells()) {
            placeBlock(cell.getWorld(), cell.getBlockX(), cell.getBlockY() - 1,
                cell.getBlockZ(), PAD_TILE_PARALYZED);
        }
        broadcast("hunter.altar.paralyzed", altar.index + 1, seconds);
        playSoundAt(altar.center, SoundCompat.blockConduitDeactivate());
        return true;
    }

    /** 这座阵现在是不是瘫痪着（不能推、不能加固） */
    public boolean isAltarParalyzed(Altar altar) {
        return altar != null && System.currentTimeMillis() < altar.paralyzedUntil;
    }

    private void showAltarProgress(Altar altar, boolean charging, boolean decaying) {
        List<Player> nearby = new ArrayList<>();
        for (Player player : onlineSurvivors()) {
            if (player.getWorld() == altar.center.getWorld()
                && player.getLocation().distance(altar.center) <= 16.0) {
                nearby.add(player);
            }
        }
        if (nearby.isEmpty()) return;

        /* 加速中：进度条换成带「×倍率 + 剩余秒数」的那一条，玩家不用猜 buff 还在不在 */
        long now = System.currentTimeMillis();
        boolean boosted = now < altar.boostUntil;
        String key = decaying ? "hunter.altar.decaying"
            : (boosted ? "hunter.altar.progress-boost" : "hunter.altar.progress");
        String bar = progressBar(altar.getProgressPercent());
        int boostLeft = boosted
            ? Math.max(1, (int) Math.ceil((altar.boostUntil - now) / 1000.0)) : 0;
        String boostLabel = altar.boostMultiplier == Math.floor(altar.boostMultiplier)
            ? String.valueOf((int) altar.boostMultiplier)
            : String.valueOf(altar.boostMultiplier);

        for (Player player : nearby) {
            String text = boosted
                ? plugin.getLanguageManager().getMessage(player, key,
                    altar.index + 1, bar, altar.getProgressPercent(), boostLabel, boostLeft)
                : plugin.getLanguageManager().getMessage(player, key,
                    altar.index + 1, bar, altar.getProgressPercent());
            ActionBarManager.sendNow(player, text);
        }
        if (charging && random.nextInt(4) == 0) {
            altar.center.getWorld().spawnParticle(ParticleCompat.enchant(), altar.center.clone().add(0, 1, 0), 6, 0.5, 0.6, 0.5);
        }
    }

    /** 记录这是最后一个被破除的法阵（撤离点要排除它） */
    private void markLastChargedAltar(Altar justCharged) {
        for (Altar altar : altars) {
            altar.lastCharged = false;
        }
        if (justCharged != null) justCharged.lastCharged = true;
    }

    /**
     * 开启撤离点：从**已破除**的法阵里随机挑一座（优先排除「最后破除的那一座」）。
     *
     * <p>⚠️ 这条路径必须**一定能**把 {@code escapeActive} 置成 true，否则：
     * 全破之后没有撤离点、终局倒计时也不会开始，这一局就永远收不了尾；
     * 而且调用方每 tick 会重试，「法阵全部破除 · 撤离点已开启」就会刷屏。
     * 所以挑不到法阵时退到猎场中心，绝不空手返回。</p>
     */
    private void activateEscape() {
        if (escapeActive) return;

        List<Altar> pool = new ArrayList<>();
        for (Altar altar : altars) {
            if (altar.charged && !altar.lastCharged) pool.add(altar);
        }
        if (pool.isEmpty()) {
            for (Altar altar : altars) {
                if (altar.charged) pool.add(altar);
            }
        }

        Location point;
        if (!pool.isEmpty()) {
            point = pool.get(random.nextInt(pool.size())).center.clone();
        } else {
            /* 理论上不会走到这里（全破必然有已破除的阵）。真走到了也不能让这局烂尾。 */
            point = area == null ? null : area.getCenter();
            if (point != null) {
                plugin.getLogger().warning("所有法阵都显示已破除，但没有可用的撤离点落点 —— "
                    + "退到猎场中心开启（altars=" + altars.size() + "）");
            }
        }
        if (point == null) {
            plugin.getLogger().warning("无法开启撤离点：既没有已破除的法阵，也没有猎场中心");
            return;
        }

        escapePoint = point;
        escapeActive = true;
        escapeTicks = 0;
        lightPad(Collections.singletonList(escapePoint), escapePoint);
        broadcast("hunter.escape.activated");
        /* 撤离点是全局节点，不能按坐标播：站在远处的人也要听到 */
        playToParticipants(SoundCompat.blockConduitActivate(), 1.0f, 1.0f);
        playToParticipants(SoundCompat.enderDragonFlap(), 0.8f, 1.2f);
    }

    private void tickEscape() {
        if (!escapeActive || escapePoint == null) {
            // 还没开撤离：站在「已破除的法阵」上（就是将来可能的撤离点）时提示一句
            for (Player player : onlineSurvivors()) {
                for (Altar altar : altars) {
                    if (!altar.charged) continue;
                    if (player.getWorld() != altar.center.getWorld()) continue;
                    if (player.getLocation().distance(altar.center) > 3.0) continue;
                    ActionBarManager.sendNow(player, plugin.getLanguageManager()
                        .getMessage(player, "hunter.escape.not-yet"));
                    break;
                }
            }
            return;
        }

        boolean someoneInside = false;
        for (Player player : onlineSurvivors()) {
            if (downed.containsKey(player.getUniqueId())) continue;
            if (player.isSneaking()
                && player.getWorld() == escapePoint.getWorld()
                && player.getLocation().distance(escapePoint) <= plugin.getConfigManager().getHunterEscapeRadius()) {
                someoneInside = true;
                break;
            }
        }

        if (someoneInside) {
            escapeTicks++;
        } else if (escapeTicks > 0) {
            escapeTicks--;
        }

        escapePoint.getWorld().spawnParticle(ParticleCompat.electricSpark(),
            escapePoint.clone().add(0, 1, 0), 4, 0.6, 0.6, 0.6);

        int hold = plugin.getConfigManager().getHunterEscapeHoldSeconds();
        for (Player player : onlineSurvivors()) {
            if (player.getWorld() != escapePoint.getWorld()) continue;
            if (player.getLocation().distance(escapePoint) <= 12.0) {
                playSoundAt(player.getLocation(), SoundCompat.noteBell());
                ActionBarManager.sendNow(player,
                    plugin.getLanguageManager().getMessage(player, "hunter.escape.progress",
                        escapeTicks, hold));
            }
        }

        if (escapeTicks >= hold) {
            endReason = "ESCAPE";
            stop(true);
        }
    }

    /**
     * 法阵与逃脱点的常驻表现：地面地砖 + 旋转粒子环 + 向上光柱。
     *
     * <p>地砖负责「走近能看清要站哪」，光柱负责「远处能看见目标在哪」。
     * 粒子每失败都不该拖垮对局，全部兜 Throwable。</p>
     */
    /**
     * 粒子总表现（4 刻一次）。
     *
     * <ul>
     *   <li><b>法阵</b>：脚下转一个粒子圈（未充能紫色 / 已充能金色），加一层向上的小光柱；
     *       准备阶段额外打一根信标光柱，让人一眼看到点位</li>
     *   <li><b>逃脱点</b>：一直有个小圈提示位置；法阵全破后换成**常驻信标光柱**</li>
     *   <li><b>地窖</b>：开启后火焰圈 + 光柱</li>
     * </ul>
     */
    private void tickParticles() {
        if (state != State.PREPARING && state != State.RUNNING) return;
        if (!plugin.getConfigManager().isHunterParticlesEnabled()) return;

        particlePhase += 0.35;
        int ringPoints = plugin.getConfigManager().getHunterAltarRingPoints();
        int beamHeight = plugin.getConfigManager().getHunterBeamHeight();
        boolean beamInPrep = plugin.getConfigManager().isHunterAltarBeamInPreparation();

        for (Altar altar : altars) {
            World world = altar.center.getWorld();
            if (world == null) continue;
            Location base = altar.center.clone().add(0, 0.15, 0);
            try {
                org.bukkit.Particle ring = altar.charged
                    ? ParticleCompat.totem()
                    : ParticleCompat.enchant();

                // 双圈：贴地一圈 + 抬高半圈，转起来的观感更像法阵
                drawRing(world, base, 1.25, 0.05, ringPoints, ring, particlePhase);
                drawRing(world, base, 0.85, 0.75, ringPoints / 2 + 2, ring, -particlePhase * 0.7);

                // 中心的小光柱
                drawBeam(world, base, 6, particlePhase, 2);
                // 准备阶段：整根信标光柱标注位置
                if (beamInPrep && state == State.PREPARING) {
                    drawBeam(world, base, beamHeight, particlePhase, 3);
                }
            } catch (Throwable ignored) {
            }
        }

        // 逃脱点：**方形门框 + 四角电柱 + 向内收拢的螺旋**。
        // 原来这里跟法阵一样是「圆环 + 中心光柱」，远看两个点几乎分不出来。
        if (escapePoint != null && escapePoint.getWorld() != null) {
            World world = escapePoint.getWorld();
            Location base = escapePoint.clone().add(0, 0.15, 0);
            try {
                double radius = plugin.getConfigManager().getHunterEscapeRadius();
                org.bukkit.Particle spark = ParticleCompat.electricSpark();
                drawSquareFrame(world, base, radius, 0.05, spark, escapeActive ? 7 : 4);
                if (escapeActive) {
                    // 光柱保留，火焰沿着柱子往上爬——火焰是「附着」在柱子上，不另外堆一团
                    drawBeam(world, base, beamHeight, particlePhase, 2);
                    drawFlameColumn(world, base, Math.max(8, beamHeight), particlePhase, 8);
                    drawCornerPillars(world, base, radius, Math.max(6, beamHeight / 2), particlePhase);
                    drawVortex(world, base, radius * 0.85, particlePhase);
                } else {
                    // 未激活：矮光柱 + 少量火焰贴着柱子上窜
                    drawBeam(world, base, 4, particlePhase, 2);
                    drawFlameColumn(world, base, 4, particlePhase, 3);
                    world.spawnParticle(spark, base.clone().add(0, 0.25, 0), 2, 0.25, 0.1, 0.25, 0.0);
                }
            } catch (Throwable ignored) {
            }
        }

        // 母体头顶标记：让玩家一眼认出母体在哪（粒子是全球可见的，等于暴露她的位置）
        if (plugin.getConfigManager().isHunterMotherMarkerEnabled()) {
            Location motherLocation = getMotherLocation();
            if (motherLocation != null && motherLocation.getWorld() != null) {
                World world = motherLocation.getWorld();
                try {
                    Location above = motherLocation.clone().add(0, 2.3, 0);
                    // 主色：暗红粉尘（压迫感），叠一点暗紫吐息做阴影
                    spawnDarkDust(world, above, 4, 0.22, 120, 0, 0, 1.5f);
                    if (ParticleCompat.dragonBreath() != null) {
                        world.spawnParticle(ParticleCompat.dragonBreath(),
                            above.clone().add(0, 0.18, 0), 1, 0.18, 0.10, 0.18, 0.0);
                    }
                    // 亮色只留一点：一颗白色 END_ROD 保持远处可见
                    world.spawnParticle(ParticleCompat.endRod(),
                        above.clone().add(0, 0.5 + Math.sin(particlePhase) * 0.2, 0),
                        1, 0.10, 0.10, 0.10, 0.0);
                    // 一圈稀疏电火花（数量从 6 降到 4，避免抢掉暗色主调）
                    if (motherKind != null && motherKind.getSkillId() != null) {
                        drawRing(world, above, 0.42, 0.0, 4, ParticleCompat.electricSpark(),
                            -particlePhase);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // 被钉了人的法阵：改成暗红一圈 + 魂火，一眼看出「这座已经失效、破不了了」
        for (Altar altar : altars) {
            if (altar.occupant == null) continue;
            World world = altar.center.getWorld();
            if (world == null) continue;
            try {
                drawRing(world, altar.center.clone().add(0, 0.15, 0), 1.25, 0.05,
                    ringPoints, ParticleCompat.dust(), particlePhase);
                world.spawnParticle(ParticleCompat.soulFireFlame(),
                    altar.center.clone().add(0, 0.6, 0), 4, 0.4, 0.5, 0.4, 0.0);
            } catch (Throwable ignored) {
            }
        }

        // 地窖
        if (hatchActive && hatchPoint != null && hatchPoint.getWorld() != null) {
            World world = hatchPoint.getWorld();
            Location base = hatchPoint.clone().add(0, 0.15, 0);
            try {
                drawRing(world, base, plugin.getConfigManager().getHunterHatchRadius(),
                    0.05, ringPoints / 2 + 2, ParticleCompat.soulFireFlame(), particlePhase);
                drawBeam(world, base, Math.max(8, beamHeight / 2), particlePhase, 2);
                world.spawnParticle(ParticleCompat.soulFireFlame(), base.clone().add(0, 0.5, 0),
                    5, 0.5, 0.5, 0.5, 0.0);
            } catch (Throwable ignored) {
            }
        }
    }

    private void tickDowned() {
        int glowSeconds = plugin.getConfigManager().getHunterGlowSeconds();
        int bleedoutTotal = plugin.getConfigManager().getHunterBleedoutSeconds();

        boolean chairMode = isChairMode();

        for (DownedInfo info : new ArrayList<>(downed.values())) {
            Player player = Bukkit.getPlayer(info.playerId);
            if (player == null || !player.isOnline()) {
                downed.remove(info.playerId);
                bleedout.remove(info.playerId);
                continue;
            }
            // 正被母体抱着的人由载具决定位置，别再把他拽回击倒点
            if (carriedId != null && carriedId.equals(info.playerId)) continue;
            // 锁定坐标：位置被移动就拉回去（击倒期间不能自己走）
            Location locked = info.lockedLocation;
            if (player.getWorld() != locked.getWorld() || player.getLocation().distanceSquared(locked) > 0.25) {
                ServerCompat.teleportSafely(player, locked);
            }
            // 保持发光
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, true, true));
            // 姿态每秒重申一次：客户端/其他插件偶尔会把游泳姿态顶回去
            setDownedPose(player, true);

            // 没人处理就自己爬起来（回到「流血/受伤」状态），而不是躺着等结束
            int recoverTotal = plugin.getConfigManager().getHunterDownedRecoverSeconds();
            if (recoverTotal > 0) {
                int recoverLeft = downedRecoverTicks.getOrDefault(info.playerId, recoverTotal) - 1;
                if (recoverLeft <= 0) {
                    recoverFromDowned(player);
                    continue;
                }
                downedRecoverTicks.put(info.playerId, recoverLeft);
                ActionBarManager.sendNow(player, plugin.getLanguageManager()
                    .getMessage(player, "hunter.downed.recover-countdown", recoverLeft));
            }

            // 椅子玩法开着时，倒地不再自己放血淘汰——淘汰只剩「上椅倒计时走完」这一条路
            if (!chairMode) {
                int left = bleedout.getOrDefault(info.playerId, bleedoutTotal) - 1;
                if (left <= 0) {
                    eliminate(player);
                    continue;
                }
                bleedout.put(info.playerId, left);
                ActionBarManager.sendNow(player,
                    plugin.getLanguageManager().getMessage(player, "hunter.downed.bleedout", left));
            }

            // 自救已移除：现在只能等队友来救（或吃「救赎护符」加快救人）
        }
    }

    /**
     * 没人处理的倒地 → 自己爬起来，**回到「流血」（受伤）状态**。
     *
     * <p>攻击链是 健康 → 流血 → 倒地：倒地时流血已经结算掉了，所以爬起来不是满血，
     * 而是带伤（{@code hitCounts = 1}）——治疗这条线仍然是必要的。</p>
     */
    private void recoverFromDowned(Player player) {
        UUID id = player.getUniqueId();
        DownedInfo info = downed.remove(id);
        if (info == null) return;
        downedRecoverTicks.remove(id);
        bleedout.remove(id);
        rescueProgress.remove(id);

        // 回到「流血」：受伤但能走
        hitCounts.put(id, 1);
        setDownedPose(player, false);

        int glowSeconds = plugin.getConfigManager().getHunterGlowSeconds();
        try {
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                Math.max(2, glowSeconds / 2) * 20, 0, true, true));
        } catch (Throwable ignored) {
        }

        broadcast("hunter.downed.recovered", player.getName());
        plugin.getLanguageManager().sendMessage(player, "hunter.downed.recovered-self");
        playSoundAt(player.getLocation(), SoundCompat.playerLevelup());
        playSoundAt(player.getLocation(), SoundCompat.playerHurt());
    }


    /** 放血走完 / 无法再被救 → 淘汰并进旁观 */
    private void eliminate(Player player) {
        UUID id = player.getUniqueId();
        downed.remove(id);
        rescueProgress.remove(id);
        hitCounts.remove(id);
        bleedout.remove(id);
        chairTicks.remove(id);
        downedRecoverTicks.remove(id);
        Altar altar = altarOf(id);
        if (altar != null) altar.occupant = null;
        eliminated.add(id);

        try {
            player.removePotionEffect(PotionEffectType.GLOWING);
            setDownedPose(player, false);
            player.setGameMode(GameMode.SPECTATOR);
        } catch (Throwable ignored) {
        }

        plugin.getHunterStatsManager().countElimination();
        playSoundAt(player.getLocation(), SoundCompat.witherDeath());
        plugin.getLanguageManager().sendMessage(player, "hunter.eliminated.self");
        plugin.getLanguageManager().sendMessage(player, "hunter.eliminated.can-exit");
        broadcast("hunter.eliminated.broadcast", player.getName());
    }

    // ================================================================ 狂欢之椅（钉法阵）

    /** 椅子玩法是否生效：直接拿法阵当椅子用；配置关掉就退回放血淘汰 */
    private boolean isChairMode() {
        return plugin.getConfigManager().isHunterChairEnabled() && !altars.isEmpty();
    }

    /** 某人被钉在哪座法阵上（没被钉返回 null） */
    public Altar altarOf(UUID occupantId) {
        if (occupantId == null) return null;
        for (Altar altar : altars) {
            if (occupantId.equals(altar.occupant)) return altar;
        }
        return null;
    }

    /**
     * 拿来钉人的法阵：**优先没充能、也没钉人的**，全都占了才退而用已充能的。
     *
     * <p>已充能的法阵已经破除完，钉上去不会让它「失效」，但总比没地方钉强。</p>
     */
    private Altar nearestFreeAltar(Location center) {
        if (center == null || center.getWorld() == null) return null;
        Altar fallback = null;
        double bestDistance = Double.MAX_VALUE;
        Altar best = null;
        for (Altar altar : altars) {
            if (altar.isSealed()) continue;
            if (altar.center.getWorld() != center.getWorld()) continue;
            double distance = altar.center.distance(center);
            if (altar.charged) {
                if (distance < bestDistance && fallback == null) fallback = altar;
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = altar;
            }
        }
        return best != null ? best : fallback;
    }

    /** 最近的、还没被抱走的倒地者 */
    private Player nearestDowned(Location center, double maxDistance) {
        if (center == null || center.getWorld() == null) return null;
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (DownedInfo info : new ArrayList<>(downed.values())) {
            if (carriedId != null && carriedId.equals(info.playerId)) continue;
            if (altarOf(info.playerId) != null) continue;
            Player player = Bukkit.getPlayer(info.playerId);
            if (player == null || !player.isOnline()) continue;
            if (player.getWorld() != center.getWorld()) continue;
            double distance = player.getLocation().distance(center);
            if (distance <= maxDistance && distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    /**
     * 每 1 秒跑一次：玩家的束抱进度、母体抱着人往法阵走、AI 主动去抱。
     *
     * <p>玩家母体用「按住 Shift 贴在倒地者身边 grab-seconds 秒」抱起，抱起后走到法阵
     * 2.5 格内自动钉上——不额外加键位，跟破阵/救援/撤离一样都是 Shift。</p>
     */
    private void tickCarry() {
        if (!isChairMode() || state != State.RUNNING) return;
        Location motherLocation = getMotherLocation();
        if (motherLocation == null) return;

        // 已经抱着人：先让他有机会挣扎，再看要不要钉到法阵上
        if (carriedId != null) {
            Player carried = Bukkit.getPlayer(carriedId);
            if (carried == null || !carried.isOnline() || !downed.containsKey(carriedId)) {
                stopCarry();
                return;
            }
            if (!carryRiding) followCarried(carried, motherLocation);

            /* 挣扎：按住 Shift 攒满就脱手。
               ⚠️ 这段原来写在下面那个「if (carriedId != null)」里，而上面这个分支已经 return 了，
                  所以**永远是死代码，挣扎根本不生效**。现在挪进「抱着人」的分支里。 */
            UUID strugglingId = carriedId;
            if (carried.isSneaking()) {
                int need = plugin.getConfigManager().getHunterChairStruggleSeconds();
                int progress = struggleTicks.getOrDefault(strugglingId, 0) + 1;
                if (progress >= need) {
                    struggleTicks.remove(strugglingId);
                    Location at = carried.getLocation().clone();
                    stopCarry();                 /* 这里会把倒地坐标重新锚到「现在的位置」 */
                    downedRecoverTicks.put(strugglingId,
                        plugin.getConfigManager().getHunterDownedRecoverSeconds());
                    setDownedPose(carried, true);
                    stunMother(1);
                    broadcast("hunter.chair.struggled", carried.getName());
                    playSoundAt(at, SoundCompat.playerAttackSweep());
                    return;
                }
                struggleTicks.put(strugglingId, progress);
                ActionBarManager.sendNow(carried, plugin.getLanguageManager()
                    .getMessage(carried, "hunter.chair.struggle-progress", progress, need));
            } else {
                struggleTicks.remove(strugglingId);
            }

            Altar altar = nearestFreeAltar(motherLocation);
            if (altar == null) return;
            if (motherLocation.getWorld() == altar.center.getWorld()
                && motherLocation.distance(altar.center) <= 2.5) {
                bindToAltar(carried, altar);
            }
            return;
        }

        Player victim = nearestDowned(motherLocation, 3.0);
        if (victim == null) {
            grabStartedAt = 0L;
            return;
        }

        if (motherPlayerId == null) {
            // AI 母体：贴近就抱（走过去由 tickMother 负责）
            startCarry(victim);
            return;
        }

        Player mother = Bukkit.getPlayer(motherPlayerId);
        if (mother == null || !mother.isOnline() || !mother.isSneaking()) {
            grabStartedAt = 0L;
            return;
        }
        if (grabStartedAt == 0L) grabStartedAt = System.currentTimeMillis();
        double need = plugin.getConfigManager().getHunterChairGrabSeconds() * 1000.0;
        long elapsed = System.currentTimeMillis() - grabStartedAt;
        ActionBarManager.sendNow(mother, plugin.getLanguageManager().getMessage(mother,
            "hunter.chair.grab-progress",
            (int) Math.min(100, elapsed * 100 / need)));
        if (elapsed >= need) {
            grabStartedAt = 0L;
            startCarry(victim);
        }
    }

    /** 抱起：优先骑乘（真的「抱」），挂不上就退回牵引跟随 */
    private void startCarry(Player victim) {
        if (victim == null) return;

        // 注意：这里不能用局部变量 mother 遮蔽字段 mother，AI 母体走的就是字段那条路
        Entity carrier;
        if (motherPlayerId != null) {
            carrier = Bukkit.getPlayer(motherPlayerId);
        } else {
            carrier = this.mother;
        }
        if (carrier == null) return;

        carriedId = victim.getUniqueId();
        struggleTicks.remove(carriedId);
        carryRiding = false;
        try {
            carrier.addPassenger(victim);
            carryRiding = victim.getVehicle() != null && victim.getVehicle().equals(carrier);
        } catch (Throwable ignored) {
            carryRiding = false;
        }
        if (!carryRiding) followCarried(victim, carrier.getLocation());

        // 抱着人走不快：玩家母体压 walkSpeed，AI 母体压寻路倍率（见 tickMother）
        if (carrier instanceof Player) {
            try {
                ((Player) carrier).setWalkSpeed(
                    (float) (0.2 * plugin.getConfigManager().getHunterChairCarrySpeed()));
            } catch (Throwable ignored) {
            }
        }

        broadcast("hunter.chair.grabbed", victim.getName());
        playSoundAt(victim.getLocation(), SoundCompat.playerHurt());
    }

    /** 牵引兜底：让被抱的人跟在母体身后 */
    private void followCarried(Player carried, Location motherLocation) {
        if (carried == null || motherLocation == null) return;
        Location behind = motherLocation.clone().add(
            motherLocation.getDirection().normalize().multiply(-1.2));
        behind.setYaw(motherLocation.getYaw());
        ServerCompat.teleportSafely(carried, behind);
    }

    /** 放下（中途收手 / 对局结束） */
    private void stopCarry() {
        UUID id = carriedId;
        carriedId = null;
        if (id != null) struggleTicks.remove(id);
        carryRiding = false;
        grabStartedAt = 0L;
        // 玩家母体的移速恢复
        if (motherPlayerId != null) {
            Player mother = Bukkit.getPlayer(motherPlayerId);
            if (mother != null && mother.isOnline()) {
                try {
                    mother.setWalkSpeed(0.2f);
                } catch (Throwable ignored) {
                }
            }
        }
        if (id == null) return;
        Player carried = Bukkit.getPlayer(id);
        try {
            if (carried != null && carried.getVehicle() != null) carried.getVehicle().eject();
        } catch (Throwable ignored) {
        }

        /* ⚠️ 关键：倒地状态里存着一份「锁定坐标」，每秒会把偏离的人拉回去。
           抱起时这份坐标还停在**被击倒的地方**，所以一松手（挣扎成功 / 母体放弃）人就被拽回原地 ——
           表现就是「挣脱后动不了、坐标被钉在倒地点」。这里把锚点挪到他现在的位置。 */
        if (carried != null && carried.isOnline() && downed.containsKey(id)) {
            downed.put(id, new DownedInfo(id, carried.getLocation().clone(),
                downed.get(id).getDownedAt(), downed.get(id).getRescuesTaken()));
            setDownedPose(carried, true);
        }
    }

    /**
     * 钉在法阵上。
     *
     * <p>人一钉上去，这座法阵就**失效**：进度冻结在当下、不再涨也不再降，谁也破不了；
     * 人被救走或起飞之后，法阵恢复可用。上椅次数决定倒计时：第一次 first-seconds、
     * 第二次 second-seconds、第三次按 third-instant 直接升天。</p>
     */
    private void bindToAltar(Player victim, Altar altar) {
        stopCarry();
        UUID id = victim.getUniqueId();
        int times = chairCount.getOrDefault(id, 0);
        chairCount.put(id, times + 1);

        downed.remove(id);
        bleedout.remove(id);
        downedRecoverTicks.remove(id);
        altar.occupant = id;
        // 进度不清零：只是冻结在当下这个值，被救走后接着原来的进度继续破
        dimPad(altar.getCells(), altar.center);
        ServerCompat.teleportSafely(victim, altar.center.clone());
        setDownedPose(victim, true);

        int seconds;
        if (times == 0) {
            seconds = plugin.getConfigManager().getHunterChairFirstSeconds();
        } else if (times == 1) {
            seconds = plugin.getConfigManager().getHunterChairSecondSeconds();
        } else {
            // 第三次不再是「立刻」，改成固定的短倒计时（默认 5 秒），到点爆炸放飞
            seconds = plugin.getConfigManager().getHunterChairThirdSeconds();
        }

        if (seconds <= 0) {
            riseToSky(victim);
            return;
        }

        chairTicks.put(id, seconds);
        // AI 母体绑完人在椅子边守一小会儿，不然刚钉上就被救走，椅子形同虚设
        if (motherPlayerId == null) {
            motherCampUntil = System.currentTimeMillis()
                + plugin.getConfigManager().getHunterChairAiCampSeconds() * 1000L;
        }
        broadcast("hunter.chair.bound", victim.getName(), altar.index + 1);
        playSoundAt(altar.center, SoundCompat.blockBeaconActivate());
        playSoundAt(altar.center, SoundCompat.elderCurse());
        plugin.getLanguageManager().sendTitle(victim, "hunter.chair.title", "hunter.chair.subtitle");
    }

    /** 每秒一次：被钉着的人倒数、锁位、发光，并处理救援 */
    private void tickChairs() {
        if (altars.isEmpty()) return;

        for (Altar altar : altars) {
            if (altar.occupant == null) continue;
            Player occupant = Bukkit.getPlayer(altar.occupant);
            if (occupant == null || !occupant.isOnline() || eliminated.contains(altar.occupant)) {
                altar.occupant = null;
            }
        }

        for (UUID id : new ArrayList<>(chairTicks.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                chairTicks.remove(id);
                continue;
            }
            Altar altar = altarOf(id);
            if (altar == null) {
                chairTicks.remove(id);
                continue;
            }

            ServerCompat.teleportSafely(player, altar.center);
            try {
                player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, true, false));
            } catch (Throwable ignored) {
            }
            setDownedPose(player, true);

            int left = chairTicks.getOrDefault(id, 0) - 1;
            if (left <= 0) {
                chairTicks.remove(id);
                altar.occupant = null;
                riseToSky(player);
                continue;
            }
            chairTicks.put(id, left);
            ActionBarManager.sendNow(player, plugin.getLanguageManager()
                .getMessage(player, "hunter.chair.progress", left));
            if (left <= 5) {
                try {
                    player.playSound(player.getLocation(), SoundCompat.noteHat(), 1.0f, 1.5f);
                } catch (Throwable ignored) {
                }
            }

            for (Player other : onlineSurvivors()) {
                if (other.equals(player)) continue;
                if (other.getWorld() != altar.center.getWorld()) continue;
                if (other.getLocation().distance(altar.center) > 10.0) continue;
                ActionBarManager.sendNow(other, plugin.getLanguageManager()
                    .getMessage(other, "hunter.chair.progress-other", player.getName(), left));
            }

            tickChairRescue(player, altar);
        }
    }

    /** 法阵上的救援：队友按住 Shift 贴在法阵旁 rescue-seconds 秒 */
    private void tickChairRescue(Player victim, Altar altar) {
        int needSeconds = Math.max(1, plugin.getConfigManager().getHunterChairRescueSeconds());
        Player rescuer = findRescuer(victim, plugin.getConfigManager().getHunterRescueRadius());
        if (rescuer == null) {
            if (rescueProgress.remove(victim.getUniqueId()) != null) {
                plugin.getLanguageManager().sendMessage(victim, "hunter.rescue.interrupted-target");
            }
            return;
        }
        needSeconds = Math.max(1, (int) Math.round(needSeconds / actionSpeed(rescuer)));

        RescueProgress progress = rescueProgress.get(victim.getUniqueId());
        if (progress == null || !progress.rescuerId.equals(rescuer.getUniqueId())) {
            rescueProgress.put(victim.getUniqueId(), new RescueProgress(rescuer.getUniqueId()));
            plugin.getLanguageManager().sendMessage(rescuer, "hunter.chair.rescue-start", needSeconds);
            playSoundAt(altar.center, SoundCompat.noteBell());
            return;
        }

        progress.ticks++;
        String text = plugin.getLanguageManager().getMessage(rescuer,
            "hunter.rescue.progress", progress.ticks, needSeconds);
        ActionBarManager.sendNow(rescuer, text);
        ActionBarManager.sendNow(victim, text);
        if (progress.ticks < needSeconds) return;

        rescueProgress.remove(victim.getUniqueId());
        altar.occupant = null;
        chairTicks.remove(victim.getUniqueId());
        hitCounts.put(victim.getUniqueId(), plugin.getConfigManager().getHunterRescueHealth());
        rescueCounts.put(victim.getUniqueId(), rescueCounts.getOrDefault(victim.getUniqueId(), 0) + 1);
        rescueDone.merge(rescuer.getUniqueId(), 1, Integer::sum);
        plugin.getHunterStatsManager().countRescue();
        setDownedPose(victim, false);

        int glowSeconds = plugin.getConfigManager().getHunterGlowSeconds();
        victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, true, true));
        rescuer.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, true, true));

        broadcast("hunter.chair.rescued", rescuer.getName(), victim.getName());
        playSoundAt(altar.center, SoundCompat.totemUse());
        playSoundAt(altar.center, SoundCompat.playerLevelup());
        grantRescueProtection(victim);
        // 法阵恢复可用：切回**未充能**的地砖（lightPad 会把砖点亮成已充能的样子，不能用）
        dimPad(altar.getCells(), altar.center);
    }

    /** 升天：倒计时走完 → 淘汰并进旁观 */
    private void riseToSky(Player player) {
        Location at = player.getLocation().clone();
        try {
            // 放飞改成一记爆炸：先是爆炸本体，再补一圈魂火与上升的 END_ROD
            at.getWorld().spawnParticle(ParticleCompat.explosionEmitter(),
                at.clone().add(0, 0.6, 0), 1, 0, 0, 0, 0.0);
            at.getWorld().spawnParticle(ParticleCompat.explosion(),
                at.clone().add(0, 0.6, 0), 6, 0.8, 0.6, 0.8, 0.0);
            at.getWorld().spawnParticle(ParticleCompat.soulFireFlame(),
                at.clone().add(0, 1, 0), 50, 0.6, 1.2, 0.6, 0.02);
            at.getWorld().spawnParticle(ParticleCompat.endRod(),
                at.clone().add(0, 1, 0), 30, 0.4, 1.6, 0.4, 0.02);
        } catch (Throwable ignored) {
        }
        playSoundAt(at, SoundCompat.genericExplode());
        playSoundAt(at, SoundCompat.witherDeath());
        broadcast("hunter.chair.risen", player.getName());
        eliminate(player);
    }

    public boolean isEliminated(UUID playerId) {
        return eliminated.contains(playerId);
    }

    // ================================================================ 终局与地窖

    /** 法阵全破后开始终局倒计时：不走就一起被埋 */
    private void tickEndgame() {
        if (!plugin.getConfigManager().isHunterEndgameEnabled()) return;
        if (!escapeActive) return;

        if (!endgameActive) {
            endgameActive = true;
            endgameSecondsLeft = plugin.getConfigManager().getHunterEndgameSeconds();
            broadcast("hunter.endgame.start", endgameSecondsLeft);
            return;
        }

        endgameSecondsLeft--;
        if (endgameSecondsLeft <= 0) {
            broadcast("hunter.endgame.collapsed");
            endReason = "WIPED";
            stop(false);
            return;
        }
        for (Player player : getAllParticipants()) {
            ActionBarManager.sendNow(player, plugin.getLanguageManager()
                .getMessage(player, "hunter.endgame.countdown", endgameSecondsLeft));
        }
    }

    /**
     * 独狼地窖：只剩 1 名逃生者时开放，按住 Shift 在圈内即可独自撤离。
     *
     * <p>不然 4 人死到剩 1 个，那个人还要破完所有法阵再走同一个逃脱点，等于没救。</p>
     */
    private void tickHatch() {
        if (!plugin.getConfigManager().isHunterHatchEnabled() || hatchPoint == null) return;

        List<Player> alive = onlineSurvivors();
        if (!hatchActive) {
            // 必须「本来有人、现在只剩 1 个」才开地窖；单人测试开局不算
            if (alive.size() != 1 || initialSurvivors <= 1) return;
            hatchActive = true;
            lightPad(Collections.singletonList(hatchPoint), hatchPoint);
            broadcast("hunter.hatch.opened", hatchPoint.getBlockX(), hatchPoint.getBlockZ());
            playSoundAt(hatchPoint, SoundCompat.enderDragonFlap());
            return;
        }

        if (alive.size() != 1) return;
        Player last = alive.get(0);
        if (!last.isSneaking()) return;
        if (last.getWorld() != hatchPoint.getWorld()) return;
        if (last.getLocation().distance(hatchPoint) > plugin.getConfigManager().getHunterHatchRadius()) return;

        broadcast("hunter.hatch.escaped", last.getName());
        endReason = "HATCH";
        stop(true);
    }

    public boolean isHatchActive() {
        return hatchActive;
    }

    public Location getHatchPoint() {
        return hatchPoint;
    }

    private void tickMother() {
        // 玩家母体：追人靠玩家自己，攻击走 EntityDamageByEntityEvent，技能走右键
        if (motherPlayerId != null) {
            return;
        }
        // 单人测试：母体默认站桩，方便慢慢试法阵/救援/撤离
        if (testMode && !testMotherAggressive) {
            return;
        }
        // 命中后的后摇：这段时间不追、不砍、不放技能
        if (System.currentTimeMillis() < motherRecoverUntil) {
            if (mother instanceof Mob) {
                try {
                    ((Mob) mother).setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (!(mother instanceof Mob) || !mother.isValid() || mother.isDead()) {
            return;
        }
        Mob mob = (Mob) mother;

        // 椅子玩法：抱着人时直奔最近的空椅子；看到倒地者先去抱起来
        if (isChairMode()) {
            // 守着刚钉上去的人：在椅子周围晃，时间到才回去追人
            if (System.currentTimeMillis() < motherCampUntil) {
                Altar sealed = null;
                for (Altar altar : altars) {
                    if (altar.occupant != null) {
                        sealed = altar;
                        break;
                    }
                }
                if (sealed != null) {
                    if (mob.getLocation().distance(sealed.center) > 4.0) {
                        MotherBrain.moveTo(mob, sealed.center, 1.0);
                    } else {
                        mob.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    }
                    return;
                }
            }
            if (carriedId != null) {
                Altar altar = nearestFreeAltar(mob.getLocation());
                if (altar != null) {
                    MotherBrain.moveTo(mob, altar.center,
                        plugin.getConfigManager().getHunterChairCarrySpeed());
                }
                return;
            }
            Player downedTarget = nearestDowned(mob.getLocation(), 24.0);
            if (downedTarget != null) {
                if (mob.getLocation().distance(downedTarget.getLocation()) <= 2.5) {
                    startCarry(downedTarget);
                } else {
                    MotherBrain.moveTo(mob, downedTarget.getLocation(), 1.0);
                }
                return;
            }
        }
        // 先判一下是不是卡住了（在固定小范围里待太久就传送脱困）
        tickMotherStuck(mob);

        // 目标选择、丢失搜索、守机巡逻都交给 MotherBrain。
        // 平时的刷新在 10 刻一次的 tickMotherChase() 里，这里取缓存；
        // 缓存为空（刚开局/刚切目标）时补跑一次，保证攻击判定不会因为时序落空。
        Player nearest = motherChaseTarget;
        if (nearest == null || !nearest.isOnline()) {
            nearest = motherBrain.tick(this, mob);
            motherChaseTarget = nearest;
        }
        if (nearest == null) {
            return;
        }

        double distance = nearest.getWorld() == mob.getWorld()
            ? mob.getLocation().distance(nearest.getLocation())
            : Double.MAX_VALUE;

        double attackRange = plugin.getConfigManager().getHunterMotherAttackRange();
        long cooldownMs = plugin.getConfigManager().getHunterMotherAttackCooldown() * 1000L;
        /* 一斧子要砍到人，得先看得见：隔着墙/栅栏不该凭空打中（卡视野的另一半） */
        if (distance <= attackRange && motherCanSee(mob, nearest)
            && System.currentTimeMillis() - lastMotherHitAt >= cooldownMs) {
            lastMotherHitAt = System.currentTimeMillis();
            registerMotherHit(nearest);
        }

        tryCastSkill(mob, nearest, distance);
    }

    /**
     * 母体这一下能不能真的命中：开了 {@code hunter.mother.line-of-sight} 就要求视线畅通。
     *
     * <p>没有这个方法的老服务端一律当「看得见」，否则 AI 会变成瞎子。</p>
     */
    private boolean motherCanSee(Mob mob, Player target) {
        if (mob == null || target == null) return false;
        if (!plugin.getConfigManager().isHunterMotherLineOfSight()) return true;
        try {
            return mob.hasLineOfSight(target);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 高频追击（10 刻一次）。
     *
     * <p>主循环 {@code tick()} 是 1 秒一拍，母体因此「一秒才想一次」：目标跑出去半秒她才开始挪，
     * 看起来就是发呆。这个方法只负责**刷新目标与追击路径**（不砍人、不放技能，那些留给 1 秒那套），
     * 让她的转向和切角跟得上人的走位。</p>
     */
    private void tickMotherChase() {
        if (state != State.RUNNING) return;
        if (motherPlayerId != null) return;                       // 玩家母体自己走
        if (testMode && !testMotherAggressive) return;            // 测试局站桩
        if (isMotherFrozen()) return;                             // 定身 / 眩晕 / 传送硬直
        if (System.currentTimeMillis() < motherRecoverUntil) return;  // 命中后摇
        if (!(mother instanceof Mob) || !mother.isValid() || mother.isDead()) return;
        Mob mob = (Mob) mother;

        // 抱人、守椅、去抱倒地者时，移动由 tickMother 里的椅子逻辑独占 —— 别抢
        if (carriedId != null) return;
        if (isChairMode()) {
            if (System.currentTimeMillis() < motherCampUntil) return;
            if (nearestDowned(mob.getLocation(), 24.0) != null) return;
        }

        motherChaseTarget = motherBrain.tick(this, mob);
    }

    /** AI 母体按种类自动放技能 */
    private void tryCastSkill(Mob mob, Player target, double distance) {
        MotherSkill skill = MotherSkills.byId(motherKind.getSkillId());
        if (skill == null || skill.passive()) return;
        if (isSkillOnCooldown(mob.getUniqueId(), skill)) return;
        if (distance > skill.range(this)) return;
        if (skill.requiresTarget() && target == null) return;
        castMotherSkill(mob, target);
    }

    private void tickEndConditions() {
        // 测试局由管理员自己 /stop，不自动结束
        if (testMode) return;
        if (elapsedSeconds >= durationSeconds) {
            broadcast("hunter.end.timeout");
            endReason = "TIMEOUT";
            // 时间到不再算母体赢：改成平局，胜负只由「撤离成功」和「全灭」决定
            plugin.getHunterStatsManager().markDraw();
            stop(false);
            return;
        }
        List<Player> survivors = onlineSurvivors();
        boolean anyFree = false;
        for (Player player : survivors) {
            if (!downed.containsKey(player.getUniqueId())) {
                anyFree = true;
                break;
            }
        }
        if (survivors.isEmpty() || !anyFree) {
            endReason = "WIPED";
            stop(false);
        }
    }

    // ================================================================ 击倒与救援

    /** 被打中后的状态链：加速 → 力竭缓慢（都不在命中瞬间减速） */
    private void applyHitChain(Player victim) {
        if (victim == null) return;
        int speedTicks = plugin.getConfigManager().getHunterHitSpeedTicks();
        int speedLevel = plugin.getConfigManager().getHunterHitSpeedLevel();
        int slowTicks = plugin.getConfigManager().getHunterPostSlowTicks();
        int slowLevel = plugin.getConfigManager().getHunterPostSlowLevel();

        try {
            if (speedTicks > 0) {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    speedTicks, Math.max(0, speedLevel - 1), true, true));
                plugin.getLanguageManager().sendMessage(victim, "hunter.hit.speed",
                    Math.round(speedTicks / 20.0));
            }
        } catch (Throwable ignored) {
        }

        if (slowTicks <= 0) return;
        ServerCompat.runLater(plugin, () -> {
            if (!isRunning() || downed.containsKey(victim.getUniqueId())) return;
            if (!victim.isOnline()) return;
            try {
                victim.addPotionEffect(new PotionEffect(ApiCompat.slowness(),
                    slowTicks, Math.max(0, slowLevel - 1), true, true));
                plugin.getLanguageManager().sendMessage(victim, "hunter.hit.post-slow",
                    Math.round(slowTicks / 20.0));
            } catch (Throwable ignored) {
            }
        }, speedTicks);
    }

    /** 救人后的短暂保护：这段时间内母体的近战砍不动 */
    public boolean isRescueProtected(Player player) {
        if (player == null) return false;
        Long until = rescueProtectUntil.get(player.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            rescueProtectUntil.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    /** 救人成功时给被救者一段保护（第五人格的「擦刀/保护」） */
    private void grantRescueProtection(Player rescued) {
        int seconds = plugin.getConfigManager().getHunterRescueProtectSeconds();
        if (rescued == null || seconds <= 0) return;
        rescueProtectUntil.put(rescued.getUniqueId(),
            System.currentTimeMillis() + seconds * 1000L);
        plugin.getLanguageManager().sendMessage(rescued, "hunter.item.rescue-protected-self", seconds);
    }

    /** 母体打中一次 */
    public void registerMotherHit(Player victim) {
        if (!isRunning() || downed.containsKey(victim.getUniqueId())) return;
        // 刚被救下来的人有一小段打不动的保护
        if (isRescueProtected(victim)) {
            notifyMotherPlayer("hunter.item.rescue-protected");
            playSoundAt(victim.getLocation(), SoundCompat.noteHat());
            return;
        }

        int hits = hitCounts.getOrDefault(victim.getUniqueId(), 0) + 1;
        hitCounts.put(victim.getUniqueId(), hits);
        plugin.getHunterStatsManager().countHit();
        playSoundAt(victim.getLocation(), SoundCompat.playerHurt());

        // 被打中**不减速**：先给一段短加速（挣脱），加速结束再力竭缓慢
        applyHitChain(victim);
        // 窥视者的隐身加速：打中一个人就立刻没了
        cancelVanishSpeed();
        // 珍珠女士：这一刀打中了 → 珍珠的缓慢立刻解除，改吃攻击后自带的 2 秒后摇
        clearPearlSlow();
        // 母体打完之后自己有后摇，不然贴上去就是必杀
        applyMotherRecover();

        int needed = plugin.getConfigManager().getHunterHitsToDown();
        if (hits < needed) {
            plugin.getLanguageManager().sendMessage(victim, "hunter.hit.warn", needed - hits);
            return;
        }
        downPlayer(victim);
    }

    /**
     * 挣脱爆发：减速结束之后给一段加速。
     *
     * <p>刻意等减速走完再给，而不是同时叠加——同时给的话 Speed 与 Slowness 会互相抵消，
     * 玩家既没有「被打懵」的沉重感，也感受不到「挣脱」的转折。</p>
     */
    private void scheduleHitBurst(Player victim) {
        int burstSeconds = plugin.getConfigManager().getHunterHitBurstSeconds();
        if (burstSeconds <= 0 || victim == null) return;
        int burstLevel = plugin.getConfigManager().getHunterHitBurstLevel();
        int delayTicks = plugin.getConfigManager().getHunterHitSlowSeconds() * 20;
        UUID victimId = victim.getUniqueId();

        ServerCompat.runLater(plugin, () -> {
            if (!isRunning() || downed.containsKey(victimId)) return;
            Player player = Bukkit.getPlayer(victimId);
            if (player == null || !player.isOnline()) return;
            try {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    burstSeconds * 20, Math.max(0, burstLevel - 1), true, true));
                plugin.getLanguageManager().sendMessage(player, "hunter.hit.burst", burstSeconds);
            } catch (Throwable ignored) {
            }
        }, delayTicks);
    }

    /**
     * 母体命中后的后摇：自己吃一段减速。
     *
     * <p>没有这个，母体贴上来就是连续两下的必杀；有了它，被打中的人才有机会拉开距离。
     * 后摇期间 {@link #tickMother()} 直接不跑，AI 母体还会被定住——不然「缓慢」在寻路面前
     * 几乎看不出来，玩家根本感觉不到母体有停顿。</p>
     */
    private void applyMotherRecover() {
        int seconds = plugin.getConfigManager().getHunterMotherHitRecoverSeconds();
        if (seconds <= 0) return;
        int level = plugin.getConfigManager().getHunterMotherHitRecoverLevel();
        motherRecoverUntil = System.currentTimeMillis() + seconds * 1000L;

        if (motherPlayerId != null) {
            Player player = Bukkit.getPlayer(motherPlayerId);
            if (player == null || !player.isOnline()) return;
            try {
                player.addPotionEffect(new PotionEffect(ApiCompat.slowness(), seconds * 20,
                    Math.max(0, level - 1), true, true));
            } catch (Throwable ignored) {
            }
            // 玩家母体：动作栏说清楚为什么突然变慢，不然只会以为是自己卡了
            ActionBarManager.sendNow(player, plugin.getLanguageManager()
                .getMessage(player, "ghg.mother.recover", seconds));
            return;
        }

        if (!(mother instanceof LivingEntity)) return;
        LivingEntity mob = (LivingEntity) mother;
        try {
            mob.addPotionEffect(new PotionEffect(ApiCompat.slowness(), seconds * 20,
                Math.max(0, level - 1), true, true));
            mob.swingMainHand();
        } catch (Throwable ignored) {
        }
        // AI 母体：直接定住才是看得见的后摇
        freezeAiMother(true);
        ServerCompat.runLater(plugin, () -> {
            if (state == State.RUNNING || state == State.PREPARING) freezeAiMother(false);
        }, seconds * 20L);
    }

    private void downPlayer(Player player) {
        UUID id = player.getUniqueId();
        Location locked = player.getLocation().clone();
        int rescuesTaken = rescueCounts.getOrDefault(id, 0);
        downed.put(id, new DownedInfo(id, locked, System.currentTimeMillis(), rescuesTaken));
        bleedout.put(id, plugin.getConfigManager().getHunterBleedoutSeconds());
        plugin.getHunterStatsManager().countDown();
        // 倒地那一刻「流血」就算结算掉了：没人处理就开始计时，到点自己爬回流血状态
        downedRecoverTicks.put(player.getUniqueId(),
            plugin.getConfigManager().getHunterDownedRecoverSeconds());
        // 倒地就趴下：站着被坐标锁死看着像卡住，趴下来才有「被击倒」的样子
        setDownedPose(player, true);

        int glowSeconds = plugin.getConfigManager().getHunterGlowSeconds();
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, true, true));

        // 倒地来一记低沉的诅咒音，配合趴下姿态，才知道自己真的倒了
        playSoundAt(player.getLocation(), SoundCompat.elderCurse());
        if (isChairMode()) {
            plugin.getLanguageManager().sendMessage(player, "hunter.chair.downed-hint");
        }
        plugin.getLanguageManager().sendTitle(player, "hunter.downed.title", "hunter.downed.subtitle");
        plugin.getLanguageManager().sendMessage(player, "hunter.downed.self");
        broadcast("hunter.downed.broadcast", player.getName());
    }

    /**
     * 救援：**蹲在倒地的队友旁边按住 Shift** 就开始读条（互通服基岩版也能操作）。
     *
     * <p>要在 {@code hunter.downed.rescue-seconds} 秒内一直蹲在旁边；松手、走开、
     * 自己也倒下都会中断并重来——救援因此是有风险的行动。</p>
     */
    private void tickRescue() {
        int baseNeedSeconds = plugin.getConfigManager().getHunterRescueSeconds();
        double radius = plugin.getConfigManager().getHunterRescueRadius();
        int limit = plugin.getConfigManager().getHunterRescueLimit();

        for (UUID downedId : new ArrayList<>(downed.keySet())) {
            Player target = Bukkit.getPlayer(downedId);
            DownedInfo info = downed.get(downedId);
            if (target == null || !target.isOnline() || info == null) {
                rescueProgress.remove(downedId);
                continue;
            }

            Player rescuer = findRescuer(target, radius);
            if (rescuer == null) {
                if (rescueProgress.remove(downedId) != null) {
                    plugin.getLanguageManager().sendMessage(target, "hunter.rescue.interrupted-target");
                }
                continue;
            }

            if (info.rescuesTaken >= limit) {
                if (rescueProgress.get(downedId) == null) {
                    rescueProgress.put(downedId, new RescueProgress(rescuer.getUniqueId()));
                    plugin.getLanguageManager().sendMessage(rescuer, "hunter.rescue.limit", limit);
                }
                continue;
            }

            int needSeconds = Math.max(1, (int) Math.round(baseNeedSeconds / actionSpeed(rescuer)));

            RescueProgress progress = rescueProgress.get(downedId);
            if (progress == null || !progress.rescuerId.equals(rescuer.getUniqueId())) {
                rescueProgress.put(downedId, new RescueProgress(rescuer.getUniqueId()));
                plugin.getLanguageManager().sendMessage(rescuer, "hunter.rescue.start", target.getName(), needSeconds);
                plugin.getLanguageManager().sendMessage(target, "hunter.rescue.start-target", rescuer.getName(), needSeconds);
                playSoundAt(target.getLocation(), SoundCompat.noteBell());
                continue;
            }

            progress.ticks++;
            String text = plugin.getLanguageManager().getMessage(rescuer,
                "hunter.rescue.progress", progress.ticks, needSeconds);
            ActionBarManager.sendNow(rescuer, text);
            ActionBarManager.sendNow(target, text);

            if (progress.ticks >= needSeconds) {
                rescueProgress.remove(downedId);
                finishRescue(rescuer, target, info);
            }
        }
    }

    /** 找一个蹲在旁边、还能救人的逃生者 */
    private Player findRescuer(Player target, double radius) {
        for (Player candidate : onlineSurvivors()) {
            if (candidate.getUniqueId().equals(target.getUniqueId())) continue;
            if (downed.containsKey(candidate.getUniqueId())) continue;
            if (!candidate.isSneaking()) continue;
            if (candidate.getWorld() != target.getWorld()) continue;
            if (candidate.getLocation().distance(target.getLocation()) > radius) continue;
            return candidate;
        }
        return null;
    }

    private void finishRescue(Player rescuer, Player target, DownedInfo info) {
        downed.remove(target.getUniqueId());
        bleedout.remove(target.getUniqueId());
        // 救起来是「受伤」而不是满血——治疗这条线才有意义
        hitCounts.put(target.getUniqueId(), plugin.getConfigManager().getHunterRescueHealth());
        rescueCounts.put(target.getUniqueId(), info.rescuesTaken + 1);
        plugin.getHunterStatsManager().countRescue();
        rescueDone.merge(rescuer.getUniqueId(), 1, Integer::sum);

        int glowSeconds = plugin.getConfigManager().getHunterGlowSeconds();
        target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, true, true));
        rescuer.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, true, true));

        plugin.getLanguageManager().sendMessage(target, "hunter.rescued.self", rescuer.getName());
        plugin.getLanguageManager().sendMessage(rescuer, "hunter.rescued.by-you", target.getName());
        broadcast("hunter.rescued.broadcast", rescuer.getName(), target.getName());
        playSoundAt(target.getLocation(), SoundCompat.totemUse());
        playSoundAt(target.getLocation(), SoundCompat.playerLevelup());
        grantRescueProtection(target);
    }

    /** 清掉黑暗效果（黑暗/失明两种都清，避免跨版本残留） */
    private void clearDarkness(Player player) {
        if (player == null || !player.isOnline()) return;
        try {
            PotionEffectType darkness = ApiCompat.darkness();
            if (darkness != null) player.removePotionEffect(darkness);
            PotionEffectType blindness = ApiCompat.blindness();
            if (blindness != null) player.removePotionEffect(blindness);
        } catch (Throwable ignored) {
        }
    }

    /** 医疗包：立刻把受伤治好 */
    public boolean instantHeal(Player player) {
        if (player == null) return false;
        if (getHitCount(player.getUniqueId()) != 1) return false;
        hitCounts.put(player.getUniqueId(), 0);
        healProgress.remove(player.getUniqueId());
        playSoundAt(player.getLocation(), SoundCompat.playerLevelup());
        return true;
    }

    // ================================================================ 观战席

    /** 这个人是不是在观战（不含已被淘汰的本局玩家） */
    public boolean isSpectator(UUID playerId) {
        return playerId != null && spectators.containsKey(playerId);
    }

    /**
     * 加入观战：**对局已开始**、且不是本局参与者才能进。
     *
     * <p>进来就切旁观 + 传送到猎场中心，同时记下原来的游戏模式与位置，退出观战时原样还回去。
     * 观战席有 X/Z 边界（见 {@link #clampSpectator}），防止有人借旁观飞出去开图。</p>
     */
    public boolean joinSpectate(Player player) {
        if (player == null || !player.isOnline()) return false;
        if (!plugin.getConfigManager().isHunterSpectateEnabled()) {
            plugin.getLanguageManager().sendMessage(player, "hunter.spectate.disabled");
            return false;
        }
        if (spectators.containsKey(player.getUniqueId())) {
            plugin.getLanguageManager().sendMessage(player, "hunter.spectate.already");
            return true;
        }
        if (!isRunning()) {
            plugin.getLanguageManager().sendMessage(player, "hunter.spectate.not-running");
            return false;
        }
        if (plugin.getPlayerManager().getAllPlayers().contains(player.getUniqueId())) {
            plugin.getLanguageManager().sendMessage(player, "hunter.spectate.in-game");
            return false;
        }
        // 在排队就先退出队列，别让「观战」和「等下一局」两个身份叠在一起
        if (plugin.getQueueManager().isQueued(player.getUniqueId())) {
            plugin.getQueueManager().leave(player, false);
        }

        spectators.put(player.getUniqueId(), new SpectatorInfo(player.getGameMode(), player.getLocation()));
        try {
            player.setGameMode(GameMode.SPECTATOR);
        } catch (Throwable ignored) {
        }
        if (area != null && area.getCenter() != null && area.getCenter().getWorld() != null) {
            try {
                player.teleport(area.getCenter().clone().add(0, 3, 0));
            } catch (Throwable ignored) {
            }
        }
        plugin.getLanguageManager().sendMessage(player, "hunter.spectate.join-success");
        return true;
    }

    /** 退出观战：还原游戏模式与原来的位置 */
    public boolean leaveSpectate(Player player, boolean silent) {
        if (player == null) return false;
        SpectatorInfo info = spectators.remove(player.getUniqueId());
        spectatorWarnAt.remove(player.getUniqueId());
        if (info == null) return false;
        try {
            GameMode mode = info.gameMode == null || info.gameMode == GameMode.SPECTATOR
                ? GameMode.SURVIVAL : info.gameMode;
            player.setGameMode(mode);
        } catch (Throwable ignored) {
        }
        if (info.location != null && info.location.getWorld() != null) {
            try {
                player.teleport(info.location);
            } catch (Throwable ignored) {
            }
        }
        if (!silent) plugin.getLanguageManager().sendMessage(player, "hunter.spectate.leave-success");
        return true;
    }

    /** 收局：所有观战者自动退出（对局都没了，没什么可看的） */
    public void clearSpectators() {
        for (UUID id : new ArrayList<>(spectators.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                leaveSpectate(player, true);
            } else {
                spectators.remove(id);
                spectatorWarnAt.remove(id);
            }
        }
    }

    /** 观战席人数（状态输出用） */
    public int spectatorCount() {
        return spectators.size();
    }

    /**
     * 观战席 / 已被淘汰者的活动范围钳制。
     *
     * <p>只限制 **X/Z**（Y 不管，旁观本来就能上下飞）：允许范围是猎场边界向外
     * {@code hunter.spectate.margin}（默认 10）格。越界就按住不动，并每 3 秒提醒一次。</p>
     *
     * @return null 表示这次移动没问题；非 null 表示应该把玩家拉回这个位置
     */
    public Location clampSpectator(Player player, Location from, Location to) {
        if (player == null || to == null || from == null) return null;
        UUID id = player.getUniqueId();
        if (!spectators.containsKey(id) && !eliminated.contains(id)) return null;
        if (area == null || area.getPos1() == null || area.getPos2() == null) return null;

        World world = area.getPos1().getWorld();
        if (world == null) return null;
        if (to.getWorld() != world) return from;

        double margin = plugin.getConfigManager().getHunterSpectateMargin();
        Location min = area.getMinPoint();
        Location max = area.getMaxPoint();
        if (min == null || max == null) return null;

        boolean inside = to.getX() >= min.getX() - margin && to.getX() <= max.getX() + margin
            && to.getZ() >= min.getZ() - margin && to.getZ() <= max.getZ() + margin;
        if (inside) return null;

        long now = System.currentTimeMillis();
        Long last = spectatorWarnAt.get(id);
        if (last == null || now - last > 3000L) {
            spectatorWarnAt.put(id, now);
            plugin.getLanguageManager().sendMessage(player, "hunter.spectate.boundary", (int) margin);
        }
        return from;
    }

    // ================================================================ 提前结算

    /**
     * 已被淘汰的人提前退出对局时，按**当前贡献分**从奖池里即时结算一份。
     *
     * <p>算法和终局结算一致：自己这一份 = 奖池 × 自己的贡献分 / 全场逃生者贡献分之和；
     * 钱从奖池里**扣掉**，所以之后终局结算不会重复发给他（那时他早就离场了）。
     * 没有贡献分（一秒阵没破、一次人没救）就只提示「没有可结算的贡献」，不吞奖池。</p>
     */
    public boolean settleEarlyExit(Player player) {
        if (player == null || !isRunning()) return false;
        UUID id = player.getUniqueId();
        if (!eliminated.contains(id)) return false;
        if (earlySettled.contains(id)) return false;
        earlySettled.add(id);

        if (!plugin.getConfigManager().isHunterEarlyExitSettleEnabled()) return false;
        if (!plugin.getConfigManager().isHunterRewardEnabled()) return false;
        if (!plugin.getEconomyManager().isEconomyEnabled()) return false;

        double pool = plugin.getEconomyManager().getPrizePool();
        if (pool <= 0) return false;

        double score = survivorScore(id);
        if (score <= 0) {
            plugin.getLanguageManager().sendMessage(player, "hunter.reward.early-none");
            return false;
        }

        double total = 0;
        for (UUID other : new ArrayList<>(plugin.getPlayerManager().getAllPlayers())) {
            if (motherPlayerId != null && motherPlayerId.equals(other)) continue;
            if (earlySettled.contains(other)) continue;
            total += survivorScore(other);
        }
        if (total <= 0) total = score;

        double share = pool * (score / total);
        double paid = plugin.getEconomyManager().withdrawFromPool(share);
        if (paid <= 0) {
            plugin.getLanguageManager().sendMessage(player, "hunter.reward.early-none");
            return false;
        }
        plugin.getEconomyManager().giveMoney(player, paid);
        plugin.getLanguageManager().sendMessage(player, "hunter.reward.early-paid",
            plugin.getEconomyManager().format(paid),
            plugin.getEconomyManager().format(score));
        return true;
    }

    /** 逃生者的贡献分（和终局结算同一个公式，不含撤离奖励） */
    private double survivorScore(UUID id) {
        if (id == null) return 0;
        double altarWeight = plugin.getConfigManager().getHunterRewardActionAltarSecond();
        double rescueWeight = plugin.getConfigManager().getHunterRewardActionRescue();
        double chargeSeconds = chargeTicks.getOrDefault(id, 0) / 20.0;
        double rescued = rescueDone.getOrDefault(id, 0);
        return Math.max(0.0, chargeSeconds * altarWeight + rescued * rescueWeight);
    }

    /**
     * 破阵石：给**脚下这一座**法阵挂「破解速度 ×倍率」buff。
     *
     * <p>不再直接推进度 —— 直接加进度等于「点一下就跳 10%」，用完就没手感；
     * 现在是一次性 buff：{@code items.ritual-knife.boost-seconds} 秒内这座阵的涨速乘
     * {@code boost-multiplier}（默认 ×2，持续 20 秒），可以叠在职业权重上。</p>
     *
     * @return 法阵序号（1 起）；0 = 脚下没有可用的法阵；-1 = 这座阵加固 / 瘫痪中（已提示玩家）
     */
    public int boostAltar(Player player) {
        if (player == null || state != State.RUNNING) return 0;
        long now = System.currentTimeMillis();
        for (Altar altar : altars) {
            if (altar.charged || altar.isSealed()) continue;
            if (!isStandingOnAltar(player, altar)) continue;

            if (isAltarParalyzed(altar)) {
                int left = Math.max(1, (int) Math.ceil((altar.paralyzedUntil - now) / 1000.0));
                plugin.getLanguageManager().sendMessage(player, "hunter.altar.paralyzed-hint", left);
                return -1;
            }
            if (isAltarHardened(altar)) {
                plugin.getLanguageManager().sendMessage(player, "hunter.altar.hardened-hint");
                return -1;
            }

            int seconds = plugin.getConfigManager().getRitualKnifeBoostSeconds();
            double multiplier = plugin.getConfigManager().getRitualKnifeBoostMultiplier();
            altar.boostMultiplier = multiplier;
            altar.boostUntil = now + seconds * 1000L;

            String label = multiplier == Math.floor(multiplier)
                ? String.valueOf((int) multiplier)
                : String.valueOf(multiplier);
            broadcast("hunter.item.knife-used", altar.index + 1, label, seconds);
            playSoundAt(altar.center, SoundCompat.blockBeaconActivate());
            return altar.index + 1;
        }
        return 0;
    }

    /**
     * 定身/减速母体（凝冰球、控魂术在 PVE 里的落点）。
     *
     * @return false 表示当前没有母体可作用
     */
    public boolean freezeEnemyMother(int seconds) {
        if (!isRunning() || seconds <= 0) return false;
        if (getMotherLocation() == null) return false;

        // 抱着人被打中就松手：倒地者重新落地，母体吃一段硬直
        boolean dropped = carriedId != null;
        if (dropped) stopCarry();

        freezeMother(seconds);
        stunMother(seconds);

        if (dropped) {
            broadcast("hunter.item.dropped-carry");
        }
        return true;
    }

    /**
     * 母体眩晕：玩家母体吃重度缓慢（几乎走不动），AI 母体直接定身。
     *
     * <p>和 {@link #freezeMother(int)} 的区别是后者只是让 AI 停 AI/禁止技能，
     * 对玩家母体看不出「被晕住」。</p>
     */
    public void stunMother(int seconds) {
        if (seconds <= 0) return;
        if (motherPlayerId != null) {
            Player motherPlayer = Bukkit.getPlayer(motherPlayerId);
            if (motherPlayer == null || !motherPlayer.isOnline()) return;
            try {
                motherPlayer.addPotionEffect(new PotionEffect(ApiCompat.slowness(),
                    seconds * 20, 6, true, true));
                motherPlayer.addPotionEffect(new PotionEffect(ApiCompat.miningFatigue(),
                    seconds * 20, 2, true, true));
            } catch (Throwable ignored) {
            }
            playSoundAt(motherPlayer.getLocation(), SoundCompat.ironGolemDamage());
            return;
        }
        if (mother instanceof Mob) {
            freezeAiMother(true);
            ServerCompat.runLater(plugin, () -> {
                if (state == State.RUNNING || state == State.PREPARING) freezeAiMother(false);
            }, seconds * 20L);
            playSoundAt(mother.getLocation(), SoundCompat.ironGolemDamage());
        }
    }

    /** 母体是不是这个实体（凝冰球判定用） */
    public boolean isMotherEntity(Entity entity) {
        if (entity == null) return false;
        if (motherPlayerId != null) return motherPlayerId.equals(entity.getUniqueId());
        return mother != null && mother.getUniqueId().equals(entity.getUniqueId());
    }

    private void clearDownedState(Player player) {
        downed.remove(player.getUniqueId());
        downedRecoverTicks.remove(player.getUniqueId());
        hitCounts.remove(player.getUniqueId());
        rescueCounts.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.removePotionEffect(PotionEffectType.GLOWING);
            setDownedPose(player, false);
        }
    }

    /**
     * 趴下 / 站起的姿态切换。
     *
     * <p>MC 的玩家没有「趴」这个姿态，最接近的是 {@code SWIMMING}（贴地爬行）。
     * Paper 有 {@code Player#setSwimming}，没有就用 {@code setPose} 反射兜底，
     * 两条都失败才退回潜行——总之不能让人站着被坐标锁死，那看起来像卡住。</p>
     */
    /* 姿态用的反射句柄：每秒要被调 20 次 ×N 人，不能每次都 getMethod */
    private static Object poseSwimmingValue;
    private static Object poseStandingValue;
    private static java.lang.reflect.Method setPoseMethod;
    /** setPose(Pose, boolean fixed)：fixed=true 时服务端**不再重算**姿态，趴下才真的趴住 */
    private static java.lang.reflect.Method setPoseFixedMethod;
    private static boolean poseResolved;
    private static java.lang.reflect.Method setSwimmingMethod;
    private static boolean swimmingResolved;

    /**
     * 倒地/爬行姿态。
     *
     * <p>⚠️ 服务端每 tick 都会按实体真实状态**重算 pose**，所以「只在倒地那一刻设一次」等于没设
     * （下一 tick 就被顶回 STANDING，看起来根本没趴下）。真正让它保持住的只有两种办法：
     * ① 每 tick 重申一次（本方法由 {@code tickDownedPose} 每 tick 调用）；
     * ② 用 NMS 直接改实体状态（跨版本代价太大，不用）。</p>
     *
     * <p>三级回退：{@code setPose(Pose.SWIMMING)} → {@code setSwimming(true)} → 蹲下。</p>
     */
    public void setDownedPose(Player player, boolean downed) {
        if (player == null || !player.isOnline()) return;

        resolvePoseApis();
        Object pose = downed ? poseSwimmingValue : poseStandingValue;
        /* 优先用「固定姿态」重载：fixed=true 锁住，服务端每 tick 的姿势重算就动不了它 */
        if (setPoseFixedMethod != null && pose != null) {
            try {
                setPoseFixedMethod.invoke(player, pose, downed);
                return;
            } catch (Throwable ignored) {
            }
        }
        if (setPoseMethod != null && pose != null) {
            try {
                setPoseMethod.invoke(player, pose);
                return;
            } catch (Throwable ignored) {
            }
        }
        if (setSwimmingMethod != null) {
            try {
                setSwimmingMethod.invoke(player, downed);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            player.setSneaking(downed);
        } catch (Throwable ignored) {
        }
    }

    /** 反射一次、缓存起来（老服务端没有这些方法就留 null，走路退分支） */
    private static void resolvePoseApis() {
        if (!poseResolved) {
            poseResolved = true;
            try {
                Class<?> poseClass = Class.forName("org.bukkit.entity.Pose");
                poseSwimmingValue = poseClass.getMethod("valueOf", String.class).invoke(null, "SWIMMING");
                poseStandingValue = poseClass.getMethod("valueOf", String.class).invoke(null, "STANDING");
                setPoseMethod = org.bukkit.entity.Entity.class.getMethod("setPose", poseClass);
                try {
                    setPoseFixedMethod = org.bukkit.entity.Entity.class
                        .getMethod("setPose", poseClass, boolean.class);
                } catch (Throwable ignored) {
                    setPoseFixedMethod = null;
                }
            } catch (Throwable ignored) {
                setPoseMethod = null;
                setPoseFixedMethod = null;
            }
        }
        if (!swimmingResolved) {
            swimmingResolved = true;
            try {
                setSwimmingMethod = Player.class.getMethod("setSwimming", boolean.class);
            } catch (Throwable ignored) {
                setSwimmingMethod = null;
            }
        }
    }

    /**
     * 每 tick 重申一次倒地姿态（倒地的人 + 被抱着的人）。
     *
     * <p>只在 {@code state == RUNNING} 且有倒地者时动，平时几乎零开销。</p>
     */
    private void tickDownedPose() {
        if (state != State.RUNNING) return;
        if (downed.isEmpty()) return;
        for (UUID id : new ArrayList<>(downed.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) continue;
            setDownedPose(player, true);
        }
    }

    // ================================================================ 道具箱

    public boolean isChestBlock(Location location) {
        return location != null && chestIndex.containsKey(blockKey(location));
    }

    /**
     * 敲一次道具箱。
     *
     * @return true 表示这是道具箱，破坏事件应被取消（由我们决定何时真的破）
     */
    public boolean handleChestHit(Player player, Location location) {
        Chest chest = chestIndex.get(blockKey(location));
        if (chest == null || chest.opened) return false;

        boolean openerIsMother = isPlayerMother(player.getUniqueId());

        chest.hits++;
        int needed = plugin.getConfigManager().getHunterChestHitsRequired();
        playSoundAt(location.clone().add(0.5, 0.5, 0.5), SoundCompat.blockAnvilLand());
        playSoundAt(location.clone().add(0.5, 0.5, 0.5), SoundCompat.notePling());

        if (chest.hits < needed) {
            int percent = (int) Math.round(chest.hits * 100.0 / needed);
            ActionBarManager.sendNow(player, plugin.getLanguageManager()
                .getMessage(player, "hunter.chest.hitting-bar",
                    chest.hits, needed, progressBar(percent)));
            try {
                location.getWorld().spawnParticle(ParticleCompat.electricSpark(),
                    location.clone().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3);
            } catch (Throwable ignored) {
            }
            return true;
        }

        chest.opened = true;
        plugin.getHunterStatsManager().countChest();
        chestIndex.remove(blockKey(location));
        location.getBlock().setType(Material.AIR);

        /* ⚠️ 这里是二选一，不能两条都走：
           母体只拿**母体专属池**（狂暴药水 / 灵魂探测器），人类那套（口粮、破阵刀、控魂术…）
           她拿到也放不出效果，会占满背包。以前 finish 之后没 return，直接又跑了一遍
           giveRandomItems，于是母体开箱会同时拿到母体道具 + 两件人类道具。 */
        if (openerIsMother) {
            giveMotherChestLoot(player);
        } else {
            giveRandomItems(player, plugin.getConfigManager().getHunterChestDrops());
        }
        plugin.getLanguageManager().sendTitle(player, "hunter.chest.opened-title", "hunter.chest.opened-subtitle");
        playSoundAt(location.clone().add(0.5, 0.5, 0.5), SoundCompat.playerBurp());
        playSoundAt(location.clone().add(0.5, 0.5, 0.5), SoundCompat.itemPickup());
        return true;
    }

    /**
     * 母体开箱能抽到的东西：**母体专属**（狂暴药水 / 灵魂探测器）+
     * **人鬼通用的几件战斗道具**（冲刺矛 / 凝冰球 / 传送珍珠 / 漂浮药水）。
     *
     * <p>每个箱子仍然**只给一件**（{@code giveMotherChestLoot} 只抽一次），
     * 不会出现「开一个箱子拿到母体道具 + 两个人类道具」那种情况。</p>
     */
    private static final List<String> MOTHER_CHEST_UNIVERSAL = java.util.Collections.unmodifiableList(
        java.util.Arrays.asList(ItemIds.SPEAR_RUSH, ItemIds.ICE_BALL_COMMON,
            ItemIds.TELEPORT_PEARL, ItemIds.LEVITATION_POTION));

    private void giveMotherChestLoot(Player player) {
        List<String> pool = new ArrayList<>();
        for (String id : ItemIds.allIds()) {
            if (ItemIds.MOTHER_SKILL.equals(id) || ItemIds.MOTHER_AXE.equals(id)) continue;
            if (!ItemIds.isMotherItem(id) && !MOTHER_CHEST_UNIVERSAL.contains(id)) continue;
            if (plugin.getItemManager().createById(id) != null) pool.add(id);
        }
        if (pool.isEmpty()) return;
        String id = pool.get(random.nextInt(pool.size()));
        ItemStack item = plugin.getItemManager().createById(id);
        if (item == null) return;
        plugin.getHunterStatsManager().countItem();
        String name = plugin.getLanguageManager().getMessage(player, ItemIds.nameKey(id));
        if (plugin.getItemManager().addUnstacked(player, item)) {
            plugin.getLanguageManager().sendMessage(player, "hunter.chest.item", name);
        }
        player.updateInventory();
    }

    private void giveRandomItems(Player player, int count) {
        List<String> pool = creatableItemIds();
        if (pool.isEmpty()) return;

        // 老配置若仍开着「口粮必出」，就先给一份，再从随机池里抽剩下的
        if (plugin.getConfigManager().isHunterRationGuaranteed()
            && giveOneChestItem(player, ItemIds.RATION)) {
            count = Math.max(0, count - 1);
        }

        for (int i = 0; i < count; i++) {
            giveOneChestItem(player, pool.get(random.nextInt(pool.size())));
        }
        player.updateInventory();
    }

    /**
     * 从道具箱里给一件。
     *
     * <p>可叠加的（应急口粮）并进已有的同 ID 堆，其余仍然单件发放 ——
     * 单件发放是为了不和已有同类合并成一组，破坏「一件一件用」的手感。</p>
     *
     * @return 是否真的给出去了（物品栏满时 false）
     */
    private boolean giveOneChestItem(Player player, String id) {
        ItemStack item = plugin.getItemManager().createById(id);
        if (item == null) return false;
        plugin.getHunterStatsManager().countItem();
        String name = plugin.getLanguageManager().getMessage(player, ItemIds.nameKey(id));
        boolean ok = ItemIds.isStackable(id)
            ? plugin.getItemManager().addStackable(player, item)
            : plugin.getItemManager().addUnstacked(player, item);
        plugin.getLanguageManager().sendMessage(player,
            ok ? "hunter.chest.item" : "hunter.chest.inventory-full", name);
        return ok;
    }

    // ================================================================ 场景搭建

    /**
     * 建法阵。
     *
     * <p>点位从「包围盒切 3×3 九宫格」的候选池里随机抽——局内固定（玩家能记住、
     * 母体能预判），局间不重复；服主开着 {@code hunter.points.randomize: false}
     * 时退化成固定矩阵，方便调试。</p>
     */
    private void buildAltars(int survivorCount) {
        int size = plugin.getConfigManager().getHunterAltarSize();
        World world = area.getPos1().getWorld();
        if (world == null) return;

        int count = resolveAltarCount(survivorCount);
        // 法阵不许出现在区域顶部 2 格以内（也不许跑到区域上面）：
        // standY 的「高度图 +1」兜底会让选区顶部有屋顶/平台时把法阵顶到够不着的高度
        int ceiling = altarCeilingY(world);
        List<Location> pool = candidatePoints(world, size, ceiling);
        if (plugin.getConfigManager().isHunterRandomizePoints()) {
            Collections.shuffle(pool, random);
        } else {
            // 固定点位时按「中心 → 四角 → 四边」取，否则 2~3 个法阵会全挤在一条边上
            pool = spreadOrder(pool);
        }
        // 再筛一遍「高度够得着」：与参照 Y 差太远的排到后面，实在没有才退回全部
        List<Location> reachable = new ArrayList<>();
        for (Location candidate : pool) {
            if (withinReach(candidate.getBlockY())) reachable.add(candidate);
        }
        if (!reachable.isEmpty()) pool = reachable;

        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            Location base = pool.remove(0);
            List<Location> cells = new ArrayList<>();
            for (int dx = 0; dx < size; dx++) {
                for (int dz = 0; dz < size; dz++) {
                    cells.add(cellNear(world, base.getBlockX() + dx,
                        base.getBlockZ() + dz, base.getBlockY(), ceiling));
                }
            }
            Altar altar = new Altar(i, cells);
            altars.add(altar);
            buildAltarPad(altar, size);
        }
        for (Altar altar : altars) {
            Location center = altar.center;
            plugin.getLogger().info("GHG：法阵 " + (altar.index + 1) + " 位于 X="
                + center.getBlockX() + " Y=" + center.getBlockY()
                + " Z=" + center.getBlockZ() + "（" + size + "x" + size + "）");
        }
        plugin.getLogger().info("GHG：生成 " + altars.size() + " 个法阵（逃生者 " + survivorCount
            + " 人，参照 Y=" + buildReferenceY + "，Y 上限=" + ceiling + "）");
    }

    /** 与参照 Y（管理员/逃生者站位）高差不超过 12 格，才算「够得着」 */
    /**
     * 候选 Y 是不是「够得着」。
     *
     * <p>单层图沿用老规则：离发起者站位不超过 12 格。<b>多层图</b>（`points.multi-floor`）
     * 里二层往往就在 12 格开外，再按这个筛会把整层点位全丢掉 —— 所以多层模式下按
     * `points.max-height-diff` 来（默认 0 = 不限）。</p>
     */
    private boolean withinReach(int y) {
        if (buildReferenceY == Integer.MIN_VALUE) return true;
        int limit;
        if (plugin.getConfigManager().isHunterPointsMultiFloor()) {
            limit = plugin.getConfigManager().getHunterPointsMaxHeightDiff();
            if (limit <= 0) return true;      /* 0 = 不限高度 */
        } else {
            limit = 12;
        }
        return Math.abs(y - buildReferenceY) <= limit;
    }

    /**
     * 法阵数量：配置里 count &gt; 0 就是硬指定（调试用）；否则随人数走
     * {@code ceil(人数 / 2)}，上限 {@code hunter.altar.max}。
     */
    private int resolveAltarCount(int survivorCount) {
        int configured = plugin.getConfigManager().getHunterAltarCount();
        if (configured > 0) return Math.min(5, configured);
        int max = plugin.getConfigManager().getHunterAltarMax();
        int auto = (survivorCount + 1) / 2;
        return Math.max(1, Math.min(max, auto));
    }

    /**
     * 固定点位模式下的取点顺序：中心 → 四角 → 四边。
     *
     * <p>{@link #candidatePoints} 生成的是 3×3 网格（下标 0-8，行优先）。直接取前 N 个
     * 会得到同一列上的点，所以这里按分散程度重排。</p>
     */
    private List<Location> spreadOrder(List<Location> pool) {
        int[] order = {4, 0, 2, 6, 8, 1, 3, 5, 7};
        List<Location> result = new ArrayList<>();
        for (int index : order) {
            if (index < pool.size()) result.add(pool.get(index));
        }
        for (Location location : pool) {
            if (!result.contains(location)) result.add(location);
        }
        return result;
    }

    /** 包围盒按 3×3 切出来的候选点位（按 footprint 收缩边界，保证法阵整块落在区域内） */
    private List<Location> candidatePoints(World world, int footprint) {
        return candidatePoints(world, footprint, Integer.MAX_VALUE);
    }

    /** 同上，但落脚 Y 不超过 {@code maxY}（法阵用，防止长到够不着的高度） */
    private List<Location> candidatePoints(World world, int footprint, int maxY) {
        Location min = area.getMinPoint();
        Location max = area.getMaxPoint();
        int minX = min.getBlockX();
        int minZ = min.getBlockZ();
        int usableX = Math.max(0, max.getBlockX() - minX - (footprint - 1));
        int usableZ = Math.max(0, max.getBlockZ() - minZ - (footprint - 1));

        double[] ratios = {1.0 / 6, 3.0 / 6, 5.0 / 6};
        List<Location> points = new ArrayList<>();
        for (double rx : ratios) {
            for (double rz : ratios) {
                int x = minX + (int) Math.round(usableX * rx);
                int z = minZ + (int) Math.round(usableZ * rz);
                boolean multi = plugin.getConfigManager().isHunterPointsMultiFloor();
                points.add(multi ? surfaceLocationAnyFloor(world, x, z, maxY)
                                 : surfaceLocation(world, x, z, maxY));
            }
        }
        return points;
    }

    /**
     * 逃脱点：同样从候选池里抽，但要离已生成的法阵远一点。
     * 全破法阵后才激活。
     */
    private void buildEscapePoint() {
        World world = area.getPos1().getWorld();
        if (world == null) return;

        // 逃脱点同样不许长在区域顶部 2 格内——它是胜负关键，够不着等于没法赢
        List<Location> pool = candidatePoints(world, 1, altarCeilingY(world));
        if (plugin.getConfigManager().isHunterRandomizePoints()) {
            Collections.shuffle(pool, random);
        }

        // 撤离点**开局不固定**：法阵全破时随机挑一个已破除的法阵当撤离点（见 activateEscape）。
        // 这样母体没法提前蹲点，逃生者也不用一开局就盯着某个固定位置。
        Location chosen = null;
        if (chosen == null && !pool.isEmpty()) chosen = pool.get(0);
        if (chosen == null) return;

        escapePoint = chosen;
        buildEscapePad(chosen);
        buildHatch(world);
    }

    /** 地窖点位：避开法阵与逃脱点，单独找一个候选点 */
    private void buildHatch(World world) {
        if (!plugin.getConfigManager().isHunterHatchEnabled()) return;
        if (altars.isEmpty()) return;

        // 地窖不再单独找点位：**直接用法阵**（随机一座），
        // 这样玩家不用记两套点位，也不会有人固定往某个角落跑
        List<Altar> candidates = new ArrayList<>();
        for (Altar altar : altars) {
            if (altar.center.getWorld() == world) candidates.add(altar);
        }
        if (candidates.isEmpty()) return;
        Altar chosen = candidates.get(random.nextInt(candidates.size()));
        hatchPoint = chosen.center.clone();
        plugin.getLogger().info("GHG：地窖设在 " + (chosen.index + 1) + " 号法阵");
    }

    private boolean farFromAltars(Location location, double minDistance) {
        for (Altar altar : altars) {
            for (Location cell : altar.getCells()) {
                if (cell.getWorld() == location.getWorld() && cell.distance(location) < minDistance) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 法阵的地面表现：法阵格铺成地砖（暗红），外面围一圈深紫边框。
     * 玩家一眼就能看出「要站这里」，远处也能认出这是法阵。
     */
    private void buildAltarPad(Altar altar, int size) {
        World world = altar.center.getWorld();
        if (world == null) return;

        // 准备阶段先点亮：开局一眼就能看到法阵在哪
        for (Location cell : altar.getCells()) {
            placeBlock(world, cell.getBlockX(), cell.getBlockY() - 1, cell.getBlockZ(), PAD_TILE_ON);
        }

        int x0 = altar.center.getBlockX() - 1;
        int z0 = altar.center.getBlockZ() - 1;
        int x1 = x0 + size + 1;
        int z1 = z0 + size + 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                placeBlock(world, x, groundY(world, x, z), z, PAD_FRAME);
            }
        }
    }

    /** 逃脱点的地面表现：3×3 边框 + 中心地砖，激活后中心转为亮色 */
    private void buildEscapePad(Location center) {
        World world = center.getWorld();
        if (world == null) return;

        int bx = center.getBlockX();
        int bz = center.getBlockZ();
        placeBlock(world, bx, center.getBlockY() - 1, bz, PAD_TILE_OFF);
        for (int x = bx - 1; x <= bx + 1; x++) {
            for (int z = bz - 1; z <= bz + 1; z++) {
                if (x == bx && z == bz) continue;
                placeBlock(world, x, groundY(world, x, z), z, PAD_FRAME);
            }
        }
    }

    /** 法阵充能 / 逃脱点激活后把地砖点亮 */
    private void lightPad(List<Location> cells, Location center) {
        World world = center.getWorld();
        if (world == null) return;
        for (Location cell : cells) {
            Block block = world.getBlockAt(cell.getBlockX(), cell.getBlockY() - 1, cell.getBlockZ());
            if (!placedBlocks.containsKey(block.getLocation())) continue;
            try {
                block.setType(PAD_TILE_ON, false);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把法阵地砖切回「未充能」（未充能 = 红石块，母体砸的就是它） */
    private void lightPadOff(Altar altar) {
        if (altar == null) return;
        World world = altar.center.getWorld();
        if (world == null) return;
        for (Location cell : altar.getCells()) {
            Block block = world.getBlockAt(cell.getBlockX(), cell.getBlockY() - 1, cell.getBlockZ());
            if (!placedBlocks.containsKey(block.getLocation())) continue;
            try {
                block.setType(PAD_TILE_OFF, false);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 母体**连续打碎法阵地砖（樱花树叶）{@code harden-hits}（默认 7）次** → 这座法阵加固，
     * `harden-seconds`（默认 50）内人类破不了。
     *
     * <p>为什么是树叶：叶子一碰就碎，打一下就算一次「拍碎」，不像红石块那样要挖好几秒；
     * 而且看起来就该被拍掉，不会让人以为「这块红砖是能拆的机关」。
     * 断档超过 {@code harden-reset-seconds}（默认 10 秒）就重新从 0 数 —— 这就是「连续」。</p>
     *
     * @return true 表示这一下被我们处理了（调用方应取消方块破坏事件，地砖不真的消失）
     */
    public boolean tryHardenAltar(Player player, Location blockLocation) {
        if (player == null || blockLocation == null) return false;
        if (!isPlayerMother(player.getUniqueId())) return false;
        for (Altar altar : altars) {
            boolean matched = false;
            for (Location cell : altar.getCells()) {
                Location below = new Location(cell.getWorld(), cell.getBlockX(),
                    cell.getBlockY() - 1, cell.getBlockZ());
                if (below.getBlockX() == blockLocation.getBlockX()
                    && below.getBlockY() == blockLocation.getBlockY()
                    && below.getBlockZ() == blockLocation.getBlockZ()
                    && below.getWorld() == blockLocation.getWorld()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) continue;
            if (altar.charged) return false;
            /* 已经加固着的就当作「这一下没用」：否则连点会把时长一直续下去 */
            if (isAltarHardened(altar)) return false;
            /* 瘫痪中不能加固（这是「瘫痪」的另一半：既不能推也不能加固） */
            if (isAltarParalyzed(altar)) {
                int left = Math.max(1, (int) Math.ceil((altar.paralyzedUntil - System.currentTimeMillis()) / 1000.0));
                ActionBarManager.sendNow(player, plugin.getLanguageManager()
                    .getMessage(player, "hunter.altar.paralyzed-hint", left));
                return true;
            }

            long now = System.currentTimeMillis();

            /* 加固冷却：刚加固过一座阵，接下来这段时间里打哪座阵都不计数（0 = 不限） */
            int cooldown = plugin.getConfigManager().getHunterAltarHardenCooldownSeconds();
            if (cooldown > 0 && now - lastHardenAt < cooldown * 1000L) {
                int left = (int) Math.ceil((cooldown * 1000L - (now - lastHardenAt)) / 1000.0);
                ActionBarManager.sendNow(player, plugin.getLanguageManager()
                    .getMessage(player, "hunter.altar.harden-cooldown", left));
                /* 这一下不计数，但要返回 true 把破坏事件吃掉：
                   否则会落到「只能敲道具箱」那句提示上，玩家听着莫名其妙 */
                return true;
            }

            /* 连续打碎计数：断档超过 harden-reset-seconds 就从 0 重新数（「连续」的落点） */
            long resetMs = plugin.getConfigManager().getHunterAltarHardenResetSeconds() * 1000L;
            if (resetMs > 0 && now - altarHardenLastAt.getOrDefault(altar.index, 0L) > resetMs) {
                altarHardenHits.remove(altar.index);
            }
            int need = plugin.getConfigManager().getHunterAltarHardenHits();
            int hits = altarHardenHits.merge(altar.index, 1, Integer::sum);
            altarHardenLastAt.put(altar.index, now);

            playSoundAt(altar.center, SoundCompat.playerAttackSweep());
            if (hits < need) {
                ActionBarManager.sendNow(player, plugin.getLanguageManager()
                    .getMessage(player, "hunter.altar.harden-progress", hits, need));
                return true;      /* 取消破坏：树叶不真的消失，否则地砖会缺一块 */
            }

            altarHardenHits.remove(altar.index);
            altarHardenLastAt.remove(altar.index);
            lastHardenAt = now;
            int seconds = plugin.getConfigManager().getHunterAltarHardenSeconds();
            altar.hardenedUntil = now + seconds * 1000L;
            // 加固的样子：地砖换成哭泣的黑曜石（和外框同材质），到期再铺回樱花树叶
            for (Location cell : altar.getCells()) {
                placeBlock(cell.getWorld(), cell.getBlockX(), cell.getBlockY() - 1,
                    cell.getBlockZ(), PAD_FRAME);
            }
            broadcast("hunter.altar.hardened", altar.index + 1, seconds);
            playSoundAt(altar.center, SoundCompat.ironGolemDamage());
            /* 给动手的人一条动作栏反馈：打满 7 下了，不用怀疑「到底生效没」 */
            ActionBarManager.sendNow(player, plugin.getLanguageManager()
                .getMessage(player, "hunter.altar.harden-done", seconds));
            return true;
        }
        return false;
    }

    /** 这座法阵现在是不是被母体加固着（人类破不了） */
    public boolean isAltarHardened(Altar altar) {
        return altar != null && System.currentTimeMillis() < altar.hardenedUntil;
    }

    /**
     * 配置在菜单里被改过之后调一次：把「开局时读进字段」的那几个数值重新拉一遍。
     *
     * <p>法阵涨速 / 守机速度 / 衰减半径这些是每 tick 现读的，本来就热；
     * 对局时长是开局时算好的，这里补一次让它在当前这局也生效。</p>
     */
    public void refreshLiveConfig() {
        if (state == State.PREPARING || state == State.RUNNING) {
            durationSeconds = plugin.getConfigManager().getHunterDuration();
        }
    }

    /** 共享进度（0-100），给 BossBar / status 用 */
    public double getSharedAltarProgress() {
        return altarProgress;
    }

    /** 把法阵地砖切回「未充能」（救走人质后法阵恢复可用时用） */
    private void dimPad(List<Location> cells, Location center) {
        World world = center.getWorld();
        if (world == null) return;
        for (Location cell : cells) {
            Block block = world.getBlockAt(cell.getBlockX(), cell.getBlockY() - 1, cell.getBlockZ());
            if (!placedBlocks.containsKey(block.getLocation())) continue;
            try {
                block.setType(PAD_TILE_OFF, false);
            } catch (Throwable ignored) {
            }
        }
    }

    private void buildChests() {
        int count = plugin.getConfigManager().getHunterChestCount();
        if (count <= 0) return;
        World world = area.getPos1().getWorld();
        if (world == null) return;

        Material material = resolveChestMaterial();
        int attempts = 0;
        while (chests.size() < count && attempts < count * 12) {
            attempts++;
            int x = ratioX(0.2 + random.nextDouble() * 0.6);
            int z = ratioZ(0.2 + random.nextDouble() * 0.6);
            Location location = surfaceLocation(world, x, z);
            if (chestIndex.containsKey(blockKey(location))) continue;

            Block block = location.getBlock();
            if (block.getType() != Material.AIR && block.getType().isSolid()) {
                location = location.clone().add(0, 1, 0);
            }
            Chest chest = new Chest(location);
            placeBlock(world, location.getBlockX(), location.getBlockY(), location.getBlockZ(), material);
            chests.add(chest);
            chestIndex.put(blockKey(location), chest);
        }
    }




    // ================================================================ 法阵位置通报

    private void startAltarLocator() {
        stopAltarLocator();
        if (!plugin.getConfigManager().isHunterAltarLocatorEnabled()) return;
        int interval = plugin.getConfigManager().getHunterAltarLocatorIntervalSeconds();
        altarLocatorTask = ServerCompat.runTimer(plugin, this::announceAltarLocations,
            interval * 20L, interval * 20L);
    }

    private void stopAltarLocator() {
        if (altarLocatorTask != null) {
            altarLocatorTask.cancel();
            altarLocatorTask = null;
        }
    }

    /**
     * 每分钟把未完成法阵的坐标报一遍给逃生者，并在法阵处打一道光柱。
     *
     * <p>法阵是随机点位，光靠「找」很容易绕圈；坐标 + 光柱能让队伍快速对齐，
     * 也让母体无法靠「没人找得到」白拿时间。</p>
     */
    private void announceAltarLocations() {
        if (state != State.RUNNING) return;

        List<Player> survivors = onlineSurvivors();
        if (survivors.isEmpty()) return;

        for (Altar altar : altars) {
            if (altar.charged) continue;
            Location center = altar.center;
            for (Player player : survivors) {
                plugin.getLanguageManager().sendMessage(player, "hunter.altar.locator",
                    altar.index + 1, center.getBlockX(), center.getBlockZ());
            }
            try {
                for (int y = 1; y <= 10; y++) {
                    center.getWorld().spawnParticle(ParticleCompat.endRod(),
                        center.clone().add(0, y, 0), 3, 0.25, 0.15, 0.25);
                }
            } catch (Throwable ignored) {
            }
            playSoundAt(center, SoundCompat.noteBell());
        }
    }

    /** 在随机位置生成一个道具箱；成功返回 true */
    private boolean spawnOneChest(World world) {
        if (world == null) return false;
        Material material = resolveChestMaterial();
        int attempts = 0;
        while (attempts < 24) {
            attempts++;
            int x = ratioX(0.15 + random.nextDouble() * 0.7);
            int z = ratioZ(0.15 + random.nextDouble() * 0.7);
            Location location = surfaceLocation(world, x, z);
            if (chestIndex.containsKey(blockKey(location))) continue;

            Block block = location.getBlock();
            if (block.getType() != Material.AIR && block.getType().isSolid()) {
                location = location.clone().add(0, 1, 0);
            }
            Chest chest = new Chest(location);
            placeBlock(world, location.getBlockX(), location.getBlockY(), location.getBlockZ(), material);
            chests.add(chest);
            chestIndex.put(blockKey(location), chest);
            return true;
        }
        return false;
    }

    private Material resolveChestMaterial() {
        String name = plugin.getConfigManager().getHunterChestMaterial();
        try {
            Material material = Material.matchMaterial(name == null ? "MELON" : name.toUpperCase(Locale.ROOT));
            if (material != null && material.isBlock()) return material;
        } catch (Throwable ignored) {
        }
        return Material.MELON;
    }

    /** 改世界方块并记下原始材质，结束时统一还原 */
    private void placeBlock(World world, int x, int y, int z, Material material) {
        if (world == null || material == null) return;
        try {
            Block block = world.getBlockAt(x, y, z);
            placedBlocks.putIfAbsent(block.getLocation(), block.getType());
            block.setType(material, false);
        } catch (Throwable t) {
            plugin.getLogger().warning("放置 GHG 方块失败 " + x + "," + y + "," + z + ": " + t.getMessage());
        }
    }

    /** 还原所有被我们改过的方块（法阵地砖、外框、道具箱） */
    private void restorePlacedBlocks() {
        if (placedBlocks.isEmpty()) return;
        int restored = 0;
        for (Map.Entry<Location, Material> entry : placedBlocks.entrySet()) {
            try {
                entry.getKey().getBlock().setType(entry.getValue(), false);
                restored++;
            } catch (Throwable t) {
                plugin.getLogger().warning("还原方块失败 " + entry.getKey() + ": " + t.getMessage());
            }
        }
        placedBlocks.clear();
        plugin.getLogger().info("GHG：已还原 " + restored + " 个方块");
    }
    /**
     * 刷 AI 母体。
     *
     * <p>原来只往锚点的 +X 方向丢 {@code hunter.mother.spawn-distance} 格，而且逃生者列表为空时
     * 直接返回——前者在「那一列是墙 / 屋顶」时会把母体顶到屋顶上，玩家根本看不见，
     * 后者让母体测试模式（没有真人逃生者）一只都刷不出来。现在绕锚点找一圈站得住的落点，
     * 并且核对真的生成成功了。</p>
     */
    private void spawnMother(List<Player> survivors) {
        World world = area == null || area.getPos1() == null ? null : area.getPos1().getWorld();
        if (world == null) return;

        Location anchor = null;
        if (survivors != null && !survivors.isEmpty()) {
            anchor = survivors.get(0).getLocation();
        }
        if (anchor == null || anchor.getWorld() != world) anchor = area.getCenter();
        if (anchor == null) return;

        if (motherKind == null) motherKind = resolveInitialKind(false);
        Location spawn = findMotherSpawn(world, anchor);
        EntityType type = resolveMotherType();
        String kindName = plugin.getLanguageManager().getMessage(motherKind.nameKey());

        try {
            // NoSpawnChunks 之类的插件会让远处区块没加载，spawnEntity 会直接抛错
            spawn.getChunk().load();
            Entity entity = world.spawnEntity(spawn, type);
            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                applyMotherAttributes(mob);
                if (survivors != null && !survivors.isEmpty()) mob.setTarget(survivors.get(0));
            }
            entity.setCustomName(kindName);
            entity.setCustomNameVisible(true);
            entity.setInvulnerable(true);
            // 别让「可清除」的标签落到母体身上，清理插件就不好判断了
            entity.setPersistent(true);
            mother = entity;
            broadcast("hunter.mother.spawned");
            plugin.getLogger().info("GHG：母体已生成 " + kindName + "（" + type.name() + "）于 "
                + spawn.getBlockX() + "," + spawn.getBlockY() + "," + spawn.getBlockZ()
                + "，距锚点 " + (int) Math.round(spawn.distance(anchor)) + " 格");
        } catch (Throwable t) {
            if (!motherSpawnWarned) {
                motherSpawnWarned = true;
                plugin.getLogger().warning("母体生成失败（之后会持续重试）: " + t);
            }
            mother = null;
        }
    }

    /**
     * 找一个「站得住、玩家大概能看见」的母体落点。
     *
     * <p>优先选离锚点 {@code spawn-distance} 格、且与锚点高差不超过 6 格的落脚点；
     * 八个方向都不行就减半再找，最后才退回原来的 +X 位置。</p>
     */
    private Location findMotherSpawn(World world, Location anchor) {
        double distance = Math.max(4.0, plugin.getConfigManager().getHunterMotherSpawnDistance());
        int ax = anchor.getBlockX();
        int az = anchor.getBlockZ();
        int ay = anchor.getBlockY();

        Location fallback = null;
        for (double radius : new double[]{distance, Math.max(3.0, distance / 2)}) {
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2 * i / 8;
                int x = ax + (int) Math.round(Math.cos(angle) * radius);
                int z = az + (int) Math.round(Math.sin(angle) * radius);
                Location candidate = surfaceLocation(world, x, z);
                if (fallback == null) fallback = candidate;
                if (Math.abs(candidate.getBlockY() - ay) > 6) continue;
                if (!isStandableAt(world, x, candidate.getBlockY(), z)) continue;
                return candidate;
            }
        }
        return fallback != null ? fallback : surfaceLocation(world, ax + (int) distance, az);
    }

    /**
     * AI 母体看门狗：被清理插件清掉、掉虚空、被 OP 杀掉之后补一只。
     *
     * <p>否则整局就没有猎手了，而玩家只会觉得「母体没生成」。只在有 AI 母体时生效，
     * 玩家母体掉线有自己的处理。</p>
     */
    private void tickMotherWatch() {
        if (motherPlayerId != null) return;
        if (mother != null && mother.isValid() && !mother.isDead()) return;

        long now = System.currentTimeMillis();
        if (now - lastMotherSpawnAt < 3000L) return;
        lastMotherSpawnAt = now;

        spawnMother(onlineSurvivors());
        if (mother == null) return;
        freezeAiMother(state == State.PREPARING);
        if (testMode) markMotherForTest();
    }

    /**
     * 测试模式把母体标出来：发光 5 秒 + 一根光柱。
     *
     * <p>母体可能刷在掩体或屋顶后面，没标记的话很容易被当成「没生成」。</p>
     */
    private void markMotherForTest() {
        if (!(mother instanceof Entity)) return;
        final Entity entity = mother;
        final Location at = entity.getLocation().clone();
        try {
            entity.setGlowing(true);
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < 5; i++) {
            final int phase = i;
            ServerCompat.runLater(plugin, () -> {
                if (!entity.isValid()) return;
                drawBeam(at.getWorld(), at, 12, phase * 0.2, 2);
            }, i * 8L);
        }
        ServerCompat.runLater(plugin, () -> {
            try {
                entity.setGlowing(false);
            } catch (Throwable ignored) {
            }
        }, 100L);
    }

    private void applyMotherAttributes(Mob mob) {
        try {
            // 直接设绝对值：不同实体（僵尸 0.23 / 蜘蛛 0.3 / 骷髅 0.25）基础速度不一样，
            // 用倍率会让每种母体手感飘。原版僵尸 = 0.23。
            AttributeInstance speed = mob.getAttribute(ApiCompat.movementSpeed());
            if (speed != null) speed.setBaseValue(plugin.getConfigManager().getHunterMotherSpeed());
            AttributeInstance health = mob.getAttribute(ApiCompat.maxHealth());
            if (health != null) health.setBaseValue(health.getBaseValue() * 2.0);
            mob.setHealth(mob.getMaxHealth());
        } catch (Throwable t) {
            plugin.getLogger().warning("设置母体属性失败: " + t.getMessage());
        }
    }

    /**
     * 母体外观：{@code hunter.mother.type} 填 AUTO 时用种类自带的默认实体，
     * 填具体实体名就锁死成那个外观（技能仍由种类决定）。
     */
    private EntityType resolveMotherType() {
        String name = plugin.getConfigManager().getHunterMotherType();
        if (name == null || name.trim().isEmpty() || "AUTO".equalsIgnoreCase(name.trim())) {
            return motherKind.getDefaultEntityType();
        }
        try {
            return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            plugin.getLogger().warning("母体实体类型无效: " + name + "，回退为 " + motherKind.getDefaultEntityType());
            return motherKind.getDefaultEntityType();
        }
    }

    private void removeMother() {
        if (mother != null) {
            try {
                mother.remove();
            } catch (Throwable ignored) {
            }
            mother = null;
        }
    }

    // ================================================================ 种类选择菜单

    /** 菜单里第一个图标所在的槽位 */
    private static final int MENU_START_SLOT = 10;

    /** 菜单里列出来的种类（按配置的启用列表） */
    /** 菜单里列出来的种类（种类菜单与 /ghg 图鉴共用） */
    public List<MotherKind> menuKinds() {
        return MotherKind.resolveEnabled(plugin.getConfigManager().getHunterEnabledKinds());
    }

    /** 槽位 → 种类（菜单与点击处理共用同一套布局） */
    public MotherKind kindForSlot(int slot) {
        List<MotherKind> kinds = menuKinds();
        int index = slot - MENU_START_SLOT;
        if (index < 0 || index >= kinds.size()) return null;
        return kinds.get(index);
    }

    /**
     * 打开种类选择菜单。
     *
     * @return false 表示当前不能选（已经开打 / 没有对局）
     */
    public boolean openKindMenu(Player viewer) {
        if (viewer == null || !viewer.isOnline()) return false;
        boolean sandbox = testMode;
        boolean allowedState = state == State.PREPARING || (sandbox && state == State.RUNNING);
        if (!allowedState) return false;
        if (!sandbox && prepSecondsLeft <= plugin.getConfigManager().getHunterKindLockSeconds()) {
            plugin.getLanguageManager().sendMessage(viewer, "hunter.cmd.kind-locked");
            return false;
        }

        List<MotherKind> kinds = menuKinds();
        Inventory menu = Bukkit.createInventory(
            new io.Sriptirc_wp_1258.gost.listeners.KindMenuListener.Holder(viewer.getUniqueId()),
            27,
            plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.title"));

        for (int i = 0; i < kinds.size() && i < 7; i++) {
            MotherKind kind = kinds.get(i);
            ItemStack icon = new ItemStack(kindIcon(kind));
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(plugin.getLanguageManager().getMessage(viewer, kind.nameKey()));
                List<String> lore = new ArrayList<>();
                // 定位句 → 技能逐行说明 → 冷却/门票 → 共用被动 → 操作提示
                lore.add(plugin.getLanguageManager().getMessage(viewer, kind.descKey()));
                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.separator"));

                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.lore-skill",
                    plugin.getLanguageManager().getMessage(viewer, kind.skillNameKey())));
                for (String line : plugin.getLanguageManager()
                        .getMessage(viewer, kind.skillDescKey()).split("\n")) {
                    if (line.isEmpty()) continue;
                    lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.lore-body", line));
                }

                MotherSkill skill = MotherSkills.byId(kind.getSkillId());
                int cooldown = skill == null ? 0 : skillCooldownSeconds(skill);
                lore.add(plugin.getLanguageManager().getMessage(viewer,
                    cooldown > 0 ? "hunter.kindmenu.lore-cooldown" : "hunter.kindmenu.lore-cooldown-none",
                    String.valueOf(cooldown)));

                double cost = plugin.getConfigManager().getHunterKindCost(kind);
                lore.add(cost > 0
                    ? plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.lore-cost",
                        plugin.getEconomyManager().format(cost))
                    : plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.lore-cost-free"));

                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.separator"));
                lore.add(plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.lore-passive",
                    plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.passive-common")));
                lore.add(kind == motherKind
                    ? plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.lore-current")
                    : plugin.getLanguageManager().getMessage(viewer, "hunter.kindmenu.lore-click"));
                if (kind == motherKind) {
                    meta.addEnchant(ApiCompat.lure(), 1, true);
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                }
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            menu.setItem(MENU_START_SLOT + i, icon);
        }

        viewer.openInventory(menu);
        return true;
    }

    /**
     * 聊天栏里的可点击入口：点一下直接打开种类菜单。
     *
     * <p>用 bungee 的 ClickEvent 而不是冒险组件——Spigot 上没有 adventure，
     * 这里兜 Throwable，点不了也至少留一条纯文本提示。</p>
     */
    private void sendKindMenuLink(Player player) {
        String text = plugin.getLanguageManager().getMessage(player, "hunter.kindmenu.clickable");
        try {
            net.md_5.bungee.api.chat.TextComponent clickable =
                new net.md_5.bungee.api.chat.TextComponent(text);
            clickable.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/ghg kind"));
            player.spigot().sendMessage(clickable);
        } catch (Throwable t) {
            player.sendMessage(text);
        }
    }

    /** 强制关掉所有人的种类菜单（准备阶段结束 / 收局） */
    private void closeKindMenus() {
        for (Player player : getAllParticipants()) {
            if (player == null || !player.isOnline()) continue;
            org.bukkit.inventory.InventoryHolder holder =
                player.getOpenInventory().getTopInventory().getHolder();
            if (!(holder instanceof io.Sriptirc_wp_1258.gost.listeners.KindMenuListener.Holder)) continue;
            ((io.Sriptirc_wp_1258.gost.listeners.KindMenuListener.Holder) holder).setSelectionMade(true);
            try {
                player.closeInventory();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 每种母体一个辨识度高的图标 */
    /** 种类图标材质（种类菜单与 /ghg 图鉴共用） */
    public Material kindIcon(MotherKind kind) {
        switch (kind) {
            case SPRINTER:
                return Material.FEATHER;
            case CHARGER:
                return Material.IRON_SWORD;
            case ARCHER:
                return Material.ARROW;
            case ALCHEMIST:
                return Material.POTION;
            case WALKER:
                return Material.ENDER_PEARL;
            case WATCHER:
                return Material.ENDER_EYE;
            case WEAVER:
                return Material.COBWEB;
            case PEARL_LADY:
                return Material.HEART_OF_THE_SEA;
            default:
                return Material.BOOK;
        }
    }

    // ================================================================ 玩家母体

    /**
     * 玩家扮演母体：走同一套技能实现，只是施法载体从 Mob 换成 Player。
     * 目的就是调试时能真人验证手感。
     */
    private void setupPlayerMother(Player motherPlayer, List<Player> survivors) {
        motherPlayerId = motherPlayer.getUniqueId();
        plugin.getPlayerManager().joinHunterGameAsMother(motherPlayer);
        motherPlayer.setHealth(motherPlayer.getMaxHealth());

        // 攻击速度属性直接决定原版战斗冷却条：冷却 X 秒 → 每秒 1/X 次
        try {
            org.bukkit.attribute.Attribute attackSpeed = ApiCompat.attackSpeed();
            if (attackSpeed != null && motherPlayer.getAttribute(attackSpeed) != null) {
                int cooldown = Math.max(1, plugin.getConfigManager().getHunterMotherAttackCooldown());
                motherPlayer.getAttribute(attackSpeed).setBaseValue(1.0 / cooldown);
            }
        } catch (Throwable ignored) {
        }

        int speedLevel = plugin.getConfigManager().getHunterPlayerMotherSpeedLevel();
        if (speedLevel > 0) {
            motherPlayer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                Math.max(200, durationSeconds * 20 + 40), speedLevel - 1, true, true));
        }

        spawnFarFromSurvivors(motherPlayer, survivors);
        // 技能不再靠「空手右键」，而是发一件对应种类的技能道具（不可丢弃）
        giveMotherSkillItem(motherPlayer);

        broadcast("hunter.mother.player-joined", motherPlayer.getName());
        notifyMotherPlayer("hunter.mother.player-kind-set",
            plugin.getLanguageManager().getMessage(motherKind.nameKey()),
            plugin.getLanguageManager().getMessage(motherKind.skillNameKey()));
        plugin.getLanguageManager().sendMessage(motherPlayer, "hunter.cmd.kind-usage");
        sendKindMenuLink(motherPlayer);
        // 准备阶段直接弹菜单，基岩版不用敲命令
        ServerCompat.runLater(plugin, () -> {
            if (isRunning() || state == State.PREPARING) {
                Player online = Bukkit.getPlayer(motherPlayer.getUniqueId());
                if (online != null && online.isOnline()) openKindMenu(online);
            }
        }, 10L);
    }

    // ---------------------------------------------------------------- 母体技能道具

    /** 这件东西是不是母体的技能道具 */
    public boolean isMotherSkillItem(ItemStack item) {
        return ItemIds.is(item, ItemIds.MOTHER_SKILL);
    }

    /**
     * 母体的技能道具。
     *
     * <p>材质跟种类菜单里的图标一致，名字就是技能名，所以换种类时一眼能看出换了什么。</p>
     */
    public ItemStack buildMotherSkillItem(Player viewer) {
        MotherKind kind = motherKind == null ? MotherKind.SPRINTER : motherKind;
        ItemStack item = new ItemStack(kindIcon(kind));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§d" + plugin.getLanguageManager().getMessage(viewer, kind.skillNameKey()));
            MotherSkill skill = MotherSkills.byId(kind.getSkillId());
            int cooldown = skill == null ? 0 : skillCooldownSeconds(skill);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.getLanguageManager().getMessage(viewer, "ghg.mother.skill-item-kind",
                plugin.getLanguageManager().getMessage(viewer, kind.nameKey())));
            lore.add(plugin.getLanguageManager().getMessage(viewer, "ghg.mother.skill-item-usage",
                String.valueOf(cooldown)));
            lore.add(plugin.getLanguageManager().getMessage(viewer, "ghg.mother.skill-item-nodrop"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return ItemIds.tag(item, ItemIds.MOTHER_SKILL);
    }

    /** 这件东西是不是母体的战斧 */
    public boolean isMotherAxe(ItemStack item) {
        return ItemIds.is(item, ItemIds.MOTHER_AXE);
    }

    /**
     * 母体的战斧：左键砍击用的武器。
     *
     * <p>它本身不造成伤害（伤害事件一律取消，改走击倒计数），作用是给母体一个「拿斧子」的
     * 明确武器身份——挥砍节奏由**原版战斗冷却**控制（攻击速度属性在 {@link #setupPlayerMother} 里设）。</p>
     */
    public ItemStack buildMotherAxe(Player viewer) {
        ItemStack item = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§c母体的战斧");
            List<String> lore = new ArrayList<>();
            lore.add(plugin.getLanguageManager().getMessage(viewer, "ghg.mother.axe-lore-1",
                String.valueOf(plugin.getConfigManager().getHunterMotherAttackCooldown())));
            lore.add(plugin.getLanguageManager().getMessage(viewer, "ghg.mother.axe-lore-2"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return ItemIds.tag(item, ItemIds.MOTHER_AXE);
    }

    /**
     * 这次挥砍算不算命中：**手上是战斧**，并且**原版攻击条已经走完**。
     *
     * <p>不这么卡的话，左键的点击频率就是攻击频率——原来「攻击冷却」只在服务端计数，
     * 客户端照样能一秒挥十下，只是有的不算数，手感很飘。</p>
     */
    public boolean canMotherMeleeNow(Player mother) {
        if (mother == null || !mother.isOnline()) return false;
        if (!isMotherAxe(mother.getInventory().getItemInMainHand())) return false;
        // 测试局是沙盒：只看「拿没拿斧子」，不再卡原版攻击条。
        // 原版每点一次左键都会把攻击条清零，测试时连点假人永远充不满 → 一下都打不中
        if (testMode) return true;
        try {
            return mother.getCooledAttackStrength(0.0f) >= 0.9f;
        } catch (Throwable t) {
            return true; // 老 API 没有这个方法就只吃服务端冷却
        }
    }

    /** 砍击被拦下来了，把原因说清楚（不然只会觉得「打不到假人」） */
    public void explainMeleeBlock(Player mother) {
        if (mother == null || !mother.isOnline()) return;
        if (!isMotherAxe(mother.getInventory().getItemInMainHand())) {
            plugin.getLanguageManager().sendMessage(mother, "hunter.acc.need-axe");
            return;
        }
        int cooldown = Math.max(1, plugin.getConfigManager().getHunterMotherAttackCooldown());
        int remaining = cooldown;
        try {
            float strength = mother.getCooledAttackStrength(0.0f);
            remaining = (int) Math.ceil(cooldown * (1.0f - strength));
        } catch (Throwable ignored) {
        }
        ActionBarManager.sendNow(mother, plugin.getLanguageManager()
            .getMessage(mother, "hunter.acc.not-charged", Math.max(1, remaining)));
    }

    /** 技能道具用掉：只清技能道具，不动战斧 */
    private void consumeMotherSkillItem(Player motherPlayer) {
        if (motherPlayer == null || !motherPlayer.isOnline()) return;
        try {
            Inventory inv = motherPlayer.getInventory();
            for (int slot = 0; slot < inv.getSize(); slot++) {
                if (isMotherSkillItem(inv.getItem(slot))) inv.setItem(slot, null);
            }
            motherPlayer.updateInventory();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 只补战斧，不碰技能道具。
     *
     * <p>斧头回收用它：技能道具要等冷却结束才该出现（见 {@link #tickMotherSkillItem()}），
     * 顺手把技能道具一起塞回来会让母体拿着一把「点了没反应」的武器。</p>
     */
    private void giveMotherAxeOnly(Player motherPlayer) {
        if (motherPlayer == null || !motherPlayer.isOnline()) return;
        Inventory inv = motherPlayer.getInventory();
        for (ItemStack existing : inv.getContents()) {
            if (isMotherAxe(existing)) return;      // 手上已经有了
        }
        ItemStack axe = buildMotherAxe(motherPlayer);
        int target = -1;
        for (int slot = 0; slot < 9 && target < 0; slot++) {
            ItemStack existing = inv.getItem(slot);
            if (existing == null || existing.getType().isAir()) target = slot;
        }
        for (int slot = 9; slot < inv.getSize() && target < 0; slot++) {
            ItemStack existing = inv.getItem(slot);
            if (existing == null || existing.getType().isAir()) target = slot;
        }
        if (target >= 0) {
            inv.setItem(target, axe);
        } else {
            inv.addItem(axe);
        }
        motherPlayer.updateInventory();
    }

    /** 只有技能道具（不含战斧），用于冷却结束后的补发 */
    private void giveMotherSkillItemOnly(Player motherPlayer) {
        if (motherPlayer == null || !motherPlayer.isOnline()) return;
        Inventory inv = motherPlayer.getInventory();
        for (ItemStack existing : inv.getContents()) {
            if (isMotherSkillItem(existing)) return;
        }
        ItemStack item = buildMotherSkillItem(motherPlayer);
        int target = -1;
        for (int slot = 0; slot < 9 && target < 0; slot++) {
            ItemStack existing = inv.getItem(slot);
            if (existing == null || existing.getType().isAir()) target = slot;
        }
        for (int slot = 9; slot < inv.getSize() && target < 0; slot++) {
            ItemStack existing = inv.getItem(slot);
            if (existing == null || existing.getType().isAir()) target = slot;
        }
        if (target >= 0) inv.setItem(target, item);
        else inv.addItem(item);
        motherPlayer.updateInventory();
    }

    /**
     * 给 **AI 母体**挂跳跃提升，让她能跳上 2~3 格高的台阶 / 矮墙。
     *
     * <p>只对 AI 生效：玩家母体跳 3 格的话，逃生者靠跑位换空间这套直接失效。
     * 每 3 秒续一次，效果时长 4 秒，保证不断档。</p>
     */
    private void tickMotherJumpBoost() {
        int level = plugin.getConfigManager().getHunterMotherJumpBoostLevel();
        if (level <= 0) return;
        if (motherPlayerId != null) return;          // 玩家母体不支持
        if (!(mother instanceof Mob) || !mother.isValid()) return;
        PotionEffectType jump = ApiCompat.jumpBoost();
        if (jump == null) return;
        try {
            ((Mob) mother).addPotionEffect(new PotionEffect(jump, 80,
                Math.max(0, level - 1), true, false, false));
        } catch (Throwable ignored) {
        }
    }

    /**
     * AI 母体防卡：**活动范围一直不超过 {@code stuck-box}（默认 4 格）** 且持续
     * {@code stuck-seconds}（默认 6 秒）→ 判定卡住，然后**只在原地附近挤出去**。
     *
     * <p>⚠️ 旧实现是「卡住就瞬移到最近逃生者旁边 6~8 格」 —— 那是作弊：母体明明被地形困住，
     * 却凭空出现在人身边，逃生者靠地形换来的时间白费了。现在改成：
     * 在 2~3 格内找一个**站得住的落脚点**（旁边一格、上一级台阶），挤过去继续追；
     * 找不到就只写日志 + 清一次路径，宁可她自己慢慢蹭出来，也不给她免费位移。</p>
     *
     * <p>另外还有两条不涉及瞬移的兜底：跳跃提升Ⅲ（AI 专属，能翻 2~3 格）和撞栅栏 3 下拆掉。</p>
     */
    private void tickMotherStuck(Mob mob) {
        if ("off".equals(plugin.getConfigManager().getHunterMotherStuckEscape())) return;
        UUID id = mob.getUniqueId();
        Location now = mob.getLocation();
        Location anchor = motherStuckAnchor.get(id);
        long t = System.currentTimeMillis();
        double box = plugin.getConfigManager().getHunterMotherStuckBox();

        if (anchor == null || anchor.getWorld() != now.getWorld() || anchor.distance(now) > box) {
            motherStuckAnchor.put(id, now.clone());
            motherStuckSince.put(id, t);
            return;
        }
        long need = plugin.getConfigManager().getHunterMotherStuckSeconds() * 1000L;
        if (motherStuckSince.getOrDefault(id, t) + need > t) return;

        motherStuckAnchor.remove(id);
        motherStuckSince.remove(id);

        Location dest = findNudgeSpot(mob, now);
        if (dest == null) {
            /* 原地挤不出去：只清一次路径让她重新寻路，不给她任何位移 */
            plugin.getLogger().info("GHG：母体卡在 " + describeLocation(now) + "，附近没有可站立的落脚点，仅重置路径");
            try {
                mob.setVelocity(new org.bukkit.util.Vector(0, 0.28, 0));
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            mob.teleport(dest);                   /* ≤ 2.6 格：自己从缝里挪出来，不是瞬移追人 */
            plugin.getLogger().info("GHG：母体卡在 " + describeLocation(now) + "，已挪到 "
                + describeLocation(dest) + " 脱困（位移 "
                + String.format(java.util.Locale.ROOT, "%.1f", now.distance(dest)) + " 格）");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 找一个「就在旁边」的落脚点：水平 1~2.6 格、垂直 −1~+2 格，脚上方两格要空、脚下要有实心块。
     *
     * <p>两条硬约束：① 位移不超过 {@code stuck-escape-range}（默认 2.6 格）；
     * ② **绝不能比现在更靠近最近的逃生者** —— 卡住脱困不该顺带拉近距离。</p>
     */
    private Location findNudgeSpot(Mob mob, Location from) {
        if (from == null || from.getWorld() == null) return null;
        World world = from.getWorld();
        double range = plugin.getConfigManager().getHunterMotherStuckEscapeRange();

        /* 离她最近的逃生者：用来判断「这一步有没有朝人靠」 */
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player player : onlineSurvivors()) {
            if (downed.containsKey(player.getUniqueId())) continue;
            if (player.getWorld() != world) continue;
            double d = player.getLocation().distance(from);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = player;
            }
        }

        double[] radii = {1.0, 1.4, 2.0, 2.6};   /* 由近到远：能挪一格就不挪三格 */
        int[] ups = {0, 1, 2, -1};
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (double radius : radii) {
            if (radius > range + 0.01) continue;
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2 * i / 8.0;
                for (int up : ups) {
                    int x = from.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
                    int z = from.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
                    int y = from.getBlockY() + up;
                    if (!isStandable(world, x, y, z)) continue;
                    Location candidate = new Location(world, x + 0.5, y, z + 0.5,
                        from.getYaw(), from.getPitch());
                    /* 不允许朝逃生者靠近（留 0.5 格容差，免得因为浮点误差全被否掉） */
                    if (nearest != null && candidate.distance(nearest.getLocation()) < nearestDist - 0.5) {
                        continue;
                    }
                    double score = candidate.distance(from) + Math.abs(up) * 0.3;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            if (best != null) return best;            /* 近的优先：找到就收 */
        }
        return best;
    }

    /** 这一格能不能站人：脚下实心、脚和头顶两格都不是实心 */
    private boolean isStandable(World world, int x, int y, int z) {
        try {
            org.bukkit.block.Block feet = world.getBlockAt(x, y, z);
            if (feet.getType().isSolid()) return false;
            if (world.getBlockAt(x, y + 1, z).getType().isSolid()) return false;
            org.bukkit.block.Block below = world.getBlockAt(x, y - 1, z);
            return below.getType().isSolid();
        } catch (Throwable t) {
            return false;
        }
    }

    private String describeLocation(Location location) {
        if (location == null || location.getWorld() == null) return "?";
        return location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    /** 每秒一次：玩家母体的技能冷却走完就把技能道具补回来 */
    private void tickMotherSkillItem() {
        if (motherPlayerId == null) return;
        if (state != State.RUNNING && state != State.PREPARING) return;
        Player mother = Bukkit.getPlayer(motherPlayerId);
        if (mother == null || !mother.isOnline()) return;
        MotherSkill skill = MotherSkills.byId(motherKind.getSkillId());
        if (skill == null || skill.passive()) return;
        if (isSkillOnCooldown(mother.getUniqueId(), skill)) return;
        giveMotherSkillItemOnly(mother);
    }

    /**
     * 发（或刷新）母体的一套装备：技能道具 + 战斧。旧的先清掉。
     *
     * <p>⚠️ **技能道具只在冷却外发**。这是全局规则：不管是斧头回收、开打刷新还是测试局中途换种类，
     * 只要技能还在冷却就不塞技能道具 —— 否则母体会拿着一件「点了没反应」的道具，
     * 看起来像插件坏了。战斧不受影响（它有自己的 {@code axe-recover-seconds} 节奏）。</p>
     */
    public void giveMotherSkillItem(Player motherPlayer) {
        if (motherPlayer == null || !motherPlayer.isOnline()) return;

        org.bukkit.inventory.Inventory inv = motherPlayer.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (isMotherSkillItem(inv.getItem(slot)) || isMotherAxe(inv.getItem(slot))) {
                inv.setItem(slot, null);
            }
        }

        MotherSkill skill = MotherSkills.byId(motherKind == null ? null : motherKind.getSkillId());
        boolean skillReady = skill == null || skill.passive()
            || !isSkillOnCooldown(motherPlayer.getUniqueId(), skill);
        if (skillReady) {
            ItemStack item = buildMotherSkillItem(motherPlayer);
            int target = -1;
            for (int slot = 0; slot < 9 && target < 0; slot++) {
                ItemStack existing = inv.getItem(slot);
                if (existing == null || existing.getType().isAir()) target = slot;
            }
            for (int slot = 9; slot < inv.getSize() && target < 0; slot++) {
                ItemStack existing = inv.getItem(slot);
                if (existing == null || existing.getType().isAir()) target = slot;
            }
            if (target >= 0) inv.setItem(target, item);
            else inv.addItem(item);
        }

        // 战斧也放快捷栏：拿着它左键才是「砍击」，节奏吃原版战斗冷却
        ItemStack axe = buildMotherAxe(motherPlayer);
        int axeSlot = -1;
        for (int slot = 0; slot < 9 && axeSlot < 0; slot++) {
            ItemStack existing = inv.getItem(slot);
            if (existing == null || existing.getType().isAir()) axeSlot = slot;
        }
        if (axeSlot >= 0) inv.setItem(axeSlot, axe);
        else inv.addItem(axe);

        motherPlayer.updateInventory();
    }

    private void spawnFarFromSurvivors(Player motherPlayer, List<Player> survivors) {        World world = area == null || area.getPos1() == null ? null : area.getPos1().getWorld();
        if (world == null) return;
        // 母体测试局没有真人逃生者，此时以区域中心为锚点；
        // 原来这里是 survivors.isEmpty() 直接 return，导致玩家根本不会被传送进猎场
        Location anchor = (survivors == null || survivors.isEmpty())
            ? area.getCenter() : survivors.get(0).getLocation();
        if (anchor == null) return;
        ServerCompat.teleportSafely(motherPlayer, findMotherSpawn(world, anchor));
    }

    public boolean isPlayerMother(UUID playerId) {
        return playerId != null && playerId.equals(motherPlayerId);
    }

    public UUID getMotherPlayerId() {
        return motherPlayerId;
    }

    public MotherKind getMotherKind() {
        return motherKind;
    }

    /** 母体当前坐标（AI 或玩家母体），心跳声与索敌都用它 */
    public Location getMotherLocation() {
        if (motherPlayerId != null) {
            Player player = Bukkit.getPlayer(motherPlayerId);
            return (player != null && player.isOnline()) ? player.getLocation() : null;
        }
        if (mother != null && mother.isValid()) return mother.getLocation();
        return null;
    }

    /** 玩家母体的攻击：与 AI 母体共用同一套攻击冷却 */
    public void registerPlayerMotherHit(Player motherPlayer, Player victim) {
        if (!isRunning() || motherPlayer == null || victim == null) return;
        long cooldownMs = plugin.getConfigManager().getHunterMotherAttackCooldown() * 1000L;
        if (System.currentTimeMillis() - lastMotherHitAt < cooldownMs) return;
        lastMotherHitAt = System.currentTimeMillis();
        registerMotherHit(victim);
        consumeMotherAxe(motherPlayer);
    }

    /**
     * 斧头砍出去就没了：`axe-recover-seconds`（默认 3 秒）后**只把斧头**补回手上。
     *
     * <p>这样母体的攻击节奏是「一刀 → 空手 → 斧子回来」，而不是举着斧子连砍。</p>
     *
     * <p>⚠️ 这里以前调的是 {@link #giveMotherSkillItem(Player)}（整套：技能道具 + 战斧），
     * 于是**技能还在冷却时也会把技能道具塞回手里** —— 手上同时出现斧子和技能道具，
     * 但技能点了没反应，看起来像「道具坏了」。技能道具的补发只归 {@link #tickMotherSkillItem()}，
     * 那里会等冷却真的走完。</p>
     */
    private void consumeMotherAxe(Player motherPlayer) {
        if (motherPlayer == null || !motherPlayer.isOnline()) return;
        try {
            ItemStack hand = motherPlayer.getInventory().getItemInMainHand();
            if (!isMotherAxe(hand)) return;
            motherPlayer.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            motherPlayer.updateInventory();
        } catch (Throwable ignored) {
        }
        int seconds = plugin.getConfigManager().getHunterAxeRecoverSeconds();
        if (seconds <= 0) return;
        ServerCompat.runLater(plugin, () -> {
            if (state != State.RUNNING && state != State.PREPARING) return;
            Player online = Bukkit.getPlayer(motherPlayer.getUniqueId());
            if (online == null || !online.isOnline()) return;
            giveMotherAxeOnly(online);
            playSoundAt(online.getLocation(), SoundCompat.blockAnvilLand());
        }, seconds * 20L);
    }

    // ================================================================ 母体技能

    /**
     * 释放母体技能。{@code caster} 可以是 AI 母体（Mob）也可以是玩家母体（Player）。
     *
     * @param target 指定目标；null 时自动找最近的逃生者
     * @return 是否真的放出来了
     */
    public boolean castMotherSkill(Entity caster, Entity target) {
        if (!isRunning() || caster == null) return false;
        MotherSkill skill = MotherSkills.byId(motherKind.getSkillId());
        if (skill == null) return false;

        Player casterPlayer = caster instanceof Player ? (Player) caster : null;
        if (skill.passive()) {
            if (casterPlayer != null) plugin.getLanguageManager().sendMessage(casterPlayer, "hunter.skill.passive");
            return false;
        }

        // 测试局忽略冷却**只对玩家母体**生效：那是管理员在试手感。
        // AI 母体哪怕在测试局也必须吃冷却，否则 tickMother 每秒调一次 tryCastSkill，
        // 就变成「无限放技能」了。
        boolean ignoreCooldown = testMode
            && casterPlayer != null
            && plugin.getConfigManager().isHunterTestIgnoreSkillCooldown();
        if (!ignoreCooldown && isSkillOnCooldown(caster.getUniqueId(), skill)) {
            if (casterPlayer != null) {
                int remaining = getSkillCooldownRemaining(caster.getUniqueId(), skill);
                plugin.getLanguageManager().sendMessage(casterPlayer, "hunter.skill.cooldown", remaining);
                ActionBarManager.sendNow(casterPlayer, plugin.getLanguageManager()
                    .getMessage(casterPlayer, "hunter.skill.cooldown", remaining));
            }
            return false;
        }

        Entity actual = target;
        if (actual == null) actual = nearestSkillTarget(caster.getLocation(), skill.range(this));
        // 正式对局必须有目标；测试局把 null 交给技能，它会朝面向方向放出来，
        // 至少能看见弹道与命中效果
        if (skill.requiresTarget() && actual == null && !testMode) {
            if (casterPlayer != null) {
                plugin.getLanguageManager().sendMessage(casterPlayer, "hunter.skill.no-target");
            }
            return false;
        }
        if (!skill.cast(this, caster, actual)) {
            /* 只有「本来就没有目标」才提示没目标。技能自己有更具体的原因
               （比如闪现前方没有落脚点），那种情况它自己会说，别再糊一句「附近没有目标」 */
            if (casterPlayer != null && actual == null) {
                plugin.getLanguageManager().sendMessage(casterPlayer, "hunter.skill.no-target");
            }
            return false;
        }

        if (!ignoreCooldown) {
            skillCooldowns.put(caster.getUniqueId(),
                System.currentTimeMillis() + skillCooldownSeconds(skill) * 1000L);
        }
        // 玩家母体：技能道具用掉就消失，冷却结束再补回来（和人类职业一致）
        if (casterPlayer != null) consumeMotherSkillItem(casterPlayer);
        return true;
    }

    /** 技能实际冷却秒数（已应用 hunter.mother.skill-cooldown 覆盖）；菜单也用它 */
    public int skillCooldownSeconds(MotherSkill skill) {
        int configured = plugin.getConfigManager().getHunterMotherSkillCooldown();
        return configured > 0 ? configured : skill.cooldownSeconds(this);
    }

    private boolean isSkillOnCooldown(UUID casterId, MotherSkill skill) {
        Long end = skillCooldowns.get(casterId);
        return end != null && System.currentTimeMillis() < end;
    }

    private int getSkillCooldownRemaining(UUID casterId, MotherSkill skill) {
        Long end = skillCooldowns.get(casterId);
        if (end == null) return 0;
        long remaining = end - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (int) Math.ceil(remaining / 1000.0);
    }

    /**
     * 技能可作用的实体：真人逃生者；**母体测试局里再加上测试假人**。
     *
     * <p>测试局的猎物是假人（villager）。技能如果只认玩家，那些「必须有目标」的技能
     * 在测试局里就全是「没目标」——右键按下去毫无反应，看起来像技能坏了。</p>
     */
    public List<Entity> skillTargets(Location center, double range) {
        List<Entity> result = new ArrayList<>();
        if (center == null || center.getWorld() == null) return result;

        for (Player player : onlineSurvivors()) {
            if (downed.containsKey(player.getUniqueId())) continue;
            if (player.getWorld() != center.getWorld()) continue;
            if (player.getLocation().distance(center) <= range) result.add(player);
        }
        if (motherTestMode) {
            for (UUID id : new ArrayList<>(dummies)) {
                Entity dummy = Bukkit.getEntity(id);
                if (dummy == null || !dummy.isValid()) continue;
                if (dummy.getWorld() != center.getWorld()) continue;
                if (dummy.getLocation().distance(center) <= range) result.add(dummy);
            }
        }
        return result;
    }

    /** 最近的可作用目标（逃生者与测试假人都算） */
    public Entity nearestSkillTarget(Location center, double range) {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : skillTargets(center, range)) {
            double distance = candidate.getLocation().distance(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private Player nearestSurvivor(Location center, double range) {
        if (center == null || center.getWorld() == null) return null;
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : onlineSurvivors()) {
            if (downed.containsKey(player.getUniqueId())) continue;
            if (player.getWorld() != center.getWorld()) continue;
            double distance = player.getLocation().distance(center);
            if (distance <= range && distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 施加减速（level 从 1 开始，MC 的 amplifier 从 0 开始） */
    public void applySlow(Player player, int seconds, int level) {
        if (player == null || seconds <= 0) return;
        player.addPotionEffect(new PotionEffect(ApiCompat.slowness(), seconds * 20,
            Math.max(0, level - 1), true, true));
    }

    // ================================================================ 心跳声

    private void startHeartbeat() {
        stopHeartbeat();
        if (!plugin.getConfigManager().isHunterHeartbeatEnabled()) return;
        heartbeatTask = ServerCompat.runTimer(plugin, this::heartbeatTick, 10L, 10L);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        nextHeartbeatAt.clear();
    }

    /** 破阵期间保持发光（每 4 刻续一次，松手 12 刻后就散） */
    private void glowAltarCharger(Player player) {
        if (player == null || !player.isOnline()) return;
        try {
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 12, 0, true, false, false));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 法阵破除的音效：钟声 + 信标激活 + 一声短龙吼。
     *
     * <p>原来只有一记音符块「叮」，跟捡东西没区别；三层叠起来才有「破了一座阵」的分量。</p>
     */
    /**
     * 法阵全破的那一下：聊天栏 + 居中标题 + 音效，三样都**发给每个参与者**。
     *
     * <p>原来音效走的是 {@code playSoundAt(altars.get(0).center, ...)} —— 按坐标播，
     * 只有站在那一座阵附近的人才听得见；四座阵分散在场地里，推满的那一刻大部分人是
     * 「既没声音也没画面」。现在改成 {@code playToParticipants}（每人一份），再补一个标题。</p>
     */
    private void celebrateAllAltarsCharged() {
        broadcast("hunter.altar.all-charged");
        for (Player player : getAllParticipants()) {
            try {
                plugin.getLanguageManager().sendTitle(player,
                    "hunter.altar.break-title", "hunter.altar.break-subtitle");
            } catch (Throwable ignored) {
            }
        }
        playToParticipants(SoundCompat.noteBell(), 1.0f, 1.2f);
        playToParticipants(SoundCompat.blockBeaconActivate(), 0.9f, 1.0f);
        playToParticipants(SoundCompat.enderDragonGrowl(), 0.7f, 1.1f);
    }

    private void playAltarChargedSound(Location location) {
        if (location == null || location.getWorld() == null) return;
        playSoundAt(location, SoundCompat.noteBell());
        playSoundAt(location, SoundCompat.blockBeaconActivate());
        playSoundAt(location, SoundCompat.enderDragonGrowl());
    }

    /**
     * 心跳声：从**母体所在坐标**播放，越近越密、越响。
     *
     * <p>用 {@code player.playSound(location, ...)} 只发给这一个玩家，声音却定位在母体身上：
     * 收到包的逃生者会听到「从母体方向传来的心跳」，而母体本人收不到这个包——所以听不见自己的心跳。</p>
     */
    private void heartbeatTick() {
        if (state != State.RUNNING) return;
        Location motherLocation = getMotherLocation();
        if (motherLocation == null || motherLocation.getWorld() == null) return;
        // 实测 32 格太近了，基本贴脸才听得见：保底 48 格起
        double baseRange = Math.max(48.0, plugin.getConfigManager().getHunterHeartbeatRange());
        if (baseRange <= 0) return;

        long now = System.currentTimeMillis();
        for (Player player : onlineSurvivors()) {
            if (player.getWorld() != motherLocation.getWorld()) continue;
            HunterRole perk = perks.get(player.getUniqueId());
            double range = perk == null ? baseRange : baseRange * perk.heartbeatMultiplier();
            double distance = player.getLocation().distance(motherLocation);
            if (distance > range) continue;

            Long next = nextHeartbeatAt.get(player.getUniqueId());
            if (next != null && now < next) continue;

            double closeness = 1.0 - Math.min(1.0, distance / range);
            // 间隔收窄（1.5s → 0.23s）、音量抬高（0.9 → 1.8）、音调压沉，
            // 贴上来时是「咚咚咚」砸在耳朵里，而不是远处若有若无的一下
            long intervalMs = (long) (1500 - 1270 * closeness);
            nextHeartbeatAt.put(player.getUniqueId(), now + intervalMs);

            try {
                player.playSound(motherLocation, SoundCompat.heartbeat(),
                    (float) (0.9 + 0.9 * closeness),
                    (float) (0.65 + 0.5 * closeness));
            } catch (Throwable ignored) {
            }
        }
    }

    // ================================================================ 工具

    private List<Player> onlineSurvivors() {
        List<Player> result = new ArrayList<>();
        for (UUID id : plugin.getPlayerManager().getAllPlayers()) {
            if (motherPlayerId != null && motherPlayerId.equals(id)) continue;
            if (eliminated.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) result.add(player);
        }
        return result;
    }

    /** 逃生者列表（技能实现要用） */
    public List<Player> getSurvivors() {
        return onlineSurvivors();
    }

    public GHG getPlugin() {
        return plugin;
    }

    /** 给玩家母体发提示；AI 母体时什么也不做 */
    public void notifyMotherPlayer(String key, Object... args) {
        if (motherPlayerId == null) return;
        Player player = Bukkit.getPlayer(motherPlayerId);
        if (player != null && player.isOnline()) {
            plugin.getLanguageManager().sendMessage(player, key, args);
        }
    }

    /**
     * 玩家是否站在某个法阵格上。
     *
     * <p>按**格中心 ±0.9 格**判，而不是「方块坐标完全相等」——玩家站在砖边缘、
     * 或客户端位置有小数抖动时，坐标相等那条会莫名其妙判不中。</p>
     */
    private boolean isStandingOnAltar(Player player, Altar altar) {
        Location playerLocation = player.getLocation();
        for (Location cell : altar.getCells()) {
            if (playerLocation.getWorld() != cell.getWorld()) continue;
            if (Math.abs(playerLocation.getX() - cell.getX()) > 0.9) continue;
            if (Math.abs(playerLocation.getZ() - cell.getZ()) > 0.9) continue;
            if (Math.abs(playerLocation.getY() - cell.getY()) > 2.5) continue;
            return true;
        }
        return false;
    }

    private int ratioX(double ratio) {
        Location min = area.getMinPoint();
        Location max = area.getMaxPoint();
        return min.getBlockX() + (int) ((max.getBlockX() - min.getBlockX()) * ratio);
    }

    private int ratioZ(double ratio) {
        Location min = area.getMinPoint();
        Location max = area.getMaxPoint();
        return min.getBlockZ() + (int) ((max.getBlockZ() - min.getBlockZ()) * ratio);
    }

    /**
     * 取某个平面坐标的**落脚点**（玩家/方块该放的那个空气格）。
     *
     * <p>不能用 {@code getHighestBlockYAt}：它返回的是最高非空气方块，遇到树、屋顶、悬空结构
     * 就会把法阵和道具箱长到上面去。这里改成在**区域自己的 Y 范围**内从下往上扫，
     * 找第一个「脚下是实心方块、自己这一格和头顶一格都是空气」的位置——
     * 也就是人真正能站的那一层。如果把地下洞穴也框进选区了，请把选区底部抬到地面附近。</p>
     */
    private Location surfaceLocation(World world, int x, int z) {
        return new Location(world, x + 0.5, standY(world, x, z), z + 0.5);
    }

    /** 建造时参考的 Y（管理员/发起者站位），法阵与道具箱优先落在这层 */
    private int buildReferenceY = Integer.MIN_VALUE;

    /** 某个位置能不能站人：脚下实心、自己这格和头顶都是空的 */
    private boolean isStandableAt(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        if (feet.getType().isSolid() || !feet.isPassable()) return false;
        Block below = world.getBlockAt(x, y - 1, z);
        if (!below.getType().isSolid()) return false;
        Block head = world.getBlockAt(x, y + 1, z);
        return !head.getType().isSolid();
    }

    /** 落脚格的 Y（空气格），找不到时退回原版高度图 */
    private int standY(World world, int x, int z) {
        return standY(world, x, z, Integer.MAX_VALUE);
    }

    /**
     * 落脚格的 Y，**不超过 maxY**。
     *
     * <p>选区顶部如果是屋顶 / 悬空平台，原来那个「高度图 +1」的兜底会把法阵顶到
     * 玩家根本够不着的高度，所以法阵这类点位要传一个上限进来（见 {@link #altarCeilingY}）。</p>
     */
    private int standY(World world, int x, int z, int maxY) {
        int top = Integer.MIN_VALUE;
        int bottom = Integer.MAX_VALUE;
        if (area != null && area.getMinPoint() != null && area.getMaxPoint() != null) {
            top = Math.max(area.getMinPoint().getBlockY(), area.getMaxPoint().getBlockY());
            bottom = Math.min(area.getMinPoint().getBlockY(), area.getMaxPoint().getBlockY());
        }
        // 选区没框好 Y 时给一个兜底范围，别退化成全世界扫描
        if (top - bottom < 2) {
            bottom = Math.max(world.getMinHeight(), bottom - 24);
            top = Math.min(world.getMaxHeight() - 3, bottom + 96);
        }

        int found = scanStandable(world, x, z, bottom, Math.min(top, maxY));
        if (found != Integer.MIN_VALUE) return found;

        // 上限之内整列都没有落脚点（选区顶部正好是地面之类）：退一步再找一次，
        // 宁可破一次「不到顶部 2 格内」也不能把法阵埋进地里
        if (maxY < top) {
            found = scanStandable(world, x, z, bottom, top);
            if (found != Integer.MIN_VALUE) return found;
        }

        // 兜底：整列都不合格就用原版高度图 +1
        try {
            return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        } catch (Throwable t) {
            return world.getHighestBlockYAt(x, z) + 1;
        }
    }

    /**
     * 在 [bottom, top] 里找落脚格。
     *
     * <p>有参照 Y（管理员站位）就取同一层最近的可站点；没有参照就取「从下往上第一个」，
     * 这样既不会长在屋顶，也不会掉到地下。找不到返回 {@link Integer#MIN_VALUE}。</p>
     */
    private int scanStandable(World world, int x, int z, int bottom, int top) {
        if (top < bottom) return Integer.MIN_VALUE;

        if (buildReferenceY == Integer.MIN_VALUE) {
            for (int y = bottom; y <= top; y++) {
                if (isStandableAt(world, x, y, z)) return y;
            }
            return Integer.MIN_VALUE;
        }
        int best = Integer.MIN_VALUE;
        int bestDiff = Integer.MAX_VALUE;
        for (int y = bottom; y <= top; y++) {
            if (!isStandableAt(world, x, y, z)) continue;
            int diff = Math.abs(y - buildReferenceY);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = y;
            }
        }
        return best;
    }

    private Location surfaceLocation(World world, int x, int z, int maxY) {
        return new Location(world, x + 0.5, standY(world, x, z, maxY), z + 0.5);
    }

    /**
     * 多层猎场取点：把这一列从区域底部到 {@code maxY} 之间**所有**能站的地面都收进来，
     * 随机挑一层。这样二层、阁楼、平台都能成为法阵落点，而不是永远跟着发起者那一层。
     *
     * <p>注意 {@code maxY} 是「区域顶部再往下 2 格」那条上限（见 {@link #altarCeilingY}），
     * 所以屋顶/顶层木板仍然不会被选中。</p>
     */
    private Location surfaceLocationAnyFloor(World world, int x, int z, int maxY) {
        int top = Integer.MIN_VALUE;
        int bottom = Integer.MAX_VALUE;
        if (area != null && area.getMinPoint() != null && area.getMaxPoint() != null) {
            top = Math.max(area.getMinPoint().getBlockY(), area.getMaxPoint().getBlockY());
            bottom = Math.min(area.getMinPoint().getBlockY(), area.getMaxPoint().getBlockY());
        }
        if (top - bottom < 2) return surfaceLocation(world, x, z, maxY);

        List<Integer> levels = new ArrayList<>();
        for (int y = bottom; y <= Math.min(top, maxY); y++) {
            if (isStandableAt(world, x, y, z)) levels.add(y);
        }
        if (levels.isEmpty()) return surfaceLocation(world, x, z, maxY);
        int pick = levels.get(random.nextInt(levels.size()));
        return new Location(world, x + 0.5, pick, z + 0.5);
    }

    /**
     * 2x2 法阵铺格子时用：优先取和中心**同一层**的落脚格，别把一块阵拆到两层去。
     * ±2 格内找不到同层才退回普通扫描。
     */
    private Location cellNear(World world, int x, int z, int targetY, int maxY) {
        if (isStandableAt(world, x, targetY, z)) {
            return new Location(world, x + 0.5, targetY, z + 0.5);
        }
        for (int dy = 1; dy <= 2; dy++) {
            int up = targetY + dy;
            if (up <= maxY && isStandableAt(world, x, up, z)) {
                return new Location(world, x + 0.5, up, z + 0.5);
            }
            int down = targetY - dy;
            if (isStandableAt(world, x, down, z)) {
                return new Location(world, x + 0.5, down, z + 0.5);
            }
        }
        return surfaceLocation(world, x, z, maxY);
    }

    /**
     * 法阵允许的最高 Y：**区域顶部再往下 2 格**。
     *
     * <p>不许长在区域顶部两格内，也不许跑到区域上面去——那样玩家得搭方块才够得着。
     * 选区 Y 跨度小于 8 格时视为「只在地面上点了两块」，这时顶部就是地面本身，
     * 再砍 2 格会把法阵压进地里，所以不设上限。</p>
     */
    private int altarCeilingY(World world) {
        Location min = area == null ? null : area.getMinPoint();
        Location max = area == null ? null : area.getMaxPoint();
        if (min == null || max == null) return Integer.MAX_VALUE;
        int top = Math.max(min.getBlockY(), max.getBlockY());
        int bottom = Math.min(min.getBlockY(), max.getBlockY());
        if (top - bottom < 8) return Integer.MAX_VALUE;
        return top - 2;
    }

    /** 落脚点下面那块地（铺地砖、放外框用） */
    private int groundY(World world, int x, int z) {
        return standY(world, x, z) - 1;
    }

    private String blockKey(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX()
            + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void broadcast(String key, Object... args) {
        Bukkit.broadcastMessage(plugin.getLanguageManager().getMessage(key, args));
    }

    /**
     * 直接给**每个参与者**播放音效（不依赖距离）。
     *
     * <p>{@link #playSoundAt} 是按坐标播的，超出 volume 范围就听不见——开打那记龙吼
     * 原来放在母体脚下，离得远的逃生者根本没声音。开局/阶段切换这类全局节点必须走这条。</p>
     */
    private void playToParticipants(org.bukkit.Sound sound, float volume, float pitch) {
        if (sound == null) return;
        for (Player player : getAllParticipants()) {
            try {
                player.playSound(player.getLocation(), sound, volume, pitch);
            } catch (Throwable ignored) {
            }
        }
    }

    private void playSoundAt(Location location, org.bukkit.Sound sound) {
        if (location == null || location.getWorld() == null || sound == null) return;
        try {
            location.getWorld().playSound(location, sound, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
    }

    /** 管理员 status 用的一行摘要 */
    public List<String> describeStatus() {
        List<String> lines = new ArrayList<>();
        lines.add("mode=" + plugin.getModeManager().describe()
            + " state=" + state
            + " area=" + (area == null ? "-" : area.getName()));
        if (state == State.RUNNING) {
            lines.add("elapsed=" + elapsedSeconds + "/" + durationSeconds
                + " escapeActive=" + escapeActive + " escapeTicks=" + escapeTicks);
        }
        if (state == State.PREPARING) {
            lines.add("preparing=" + prepSecondsLeft + "s kind=" + motherKind.name()
                + (testMode ? " TEST" + (testMotherAggressive ? "(aggr)" : "(idle)") : "")
                + " skill=" + motherKind.getSkillId());
            for (Altar altar : altars) {
                lines.add("altar#" + (altar.index + 1) + " " + altar.getProgressPercent() + "%"
                    + (altar.charged ? " charged" : ""));
            }
            String motherInfo = motherPlayerId != null
                ? "player(" + motherPlayerId + ")"
                : (mother == null ? "-" : mother.getType().name());
            lines.add("chests=" + chestIndex.size() + " healing=" + healProgress.size());
            // 饱食度/饱和度：排查「饿不掉」时直接看这一行
            for (Player player : onlineSurvivors()) {
                lines.add("hunger " + player.getName() + " food=" + player.getFoodLevel()
                    + " sat=" + String.format("%.1f", player.getSaturation()));
            }
            lines.add("spectators=" + spectators.size());
            lines.add("eliminated=" + eliminated.size() + " hatch=" + hatchActive
                + " endgame=" + (endgameActive ? endgameSecondsLeft + "s" : "-"));
            lines.add("chests=" + chests.size() + " downed=" + downed.size()
                + " mother=" + motherInfo + " kind=" + motherKind.name()
                + " rescues=" + rescueProgress.size());
        }
        return lines;
    }

    /** 掉线：清掉该玩家的击倒与受击记录 */
    public void handleQuit(Player player) {
        if (player == null) return;
        clearDownedState(player);
        jumpTimes.remove(player.getUniqueId());
    }

    /** 当前被击倒玩家的 UUID 集合（只读） */
    public Set<UUID> getDownedPlayers() {
        return new HashSet<>(downed.keySet());
    }
}
