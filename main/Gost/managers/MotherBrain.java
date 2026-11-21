package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 母体 AI 的决策层。
 *
 * <p>原来的 AI 只会「索敌范围内找最近的直线追」——守机衰减机制做了它不会用，
 * 丢了目标也不会找，看起来不像在「玩」。这里补三件事：</p>
 * <ol>
 *   <li><b>目标优先级</b>：正在按 Shift 破阵的人优先，其次才看远近；再加一点黏性，
 *       避免它在两个人之间来回反复横跳。</li>
 *   <li><b>丢失目标搜索</b>：目标跑出感知范围后，先去最后已知位置找一段时间。</li>
 *   <li><b>守机巡逻</b>：实在没人，就去最近的未完成法阵蹲着（法阵全破后去撤离点）。</li>
 * </ol>
 */
public class MotherBrain {

    private final GHG plugin;

    private UUID lastTargetId;
    private Location lastKnown;
    private long searchingUntil;

    /* ---- 绕后 / 假动作（2026-11-12）----
       ⚠️ 关键设计：**移动只能有一个控制者**。
       原版 AI（mob.setTarget）和 Paper 的 Pathfinder（moveTo）都会抢着移动实体，
       同时开 = 抽搐。所以这里显式分两种模式：
         · NATIVE —— 只 setTarget，让原版追（默认）
         · PATH   —— setTarget(null) 停掉原版，改用 moveTo 走到 jukePoint
       绕后 / 假动作期间切到 PATH，走完（到位 / 超时 / 贴脸 / 丢视野）再切回 NATIVE。 */
    private enum MoveMode { NATIVE, PATH }

    private MoveMode mode = MoveMode.NATIVE;
    private Location jukePoint;
    private UUID jukeTargetId;
    private long jukeUntil;
    /** 已经对谁绕过后背：同一个目标不反复绕，否则会变成绕圈 */
    private UUID flankedId;
    private long flankReadyAt;
    private long feintReadyAt;
    private final java.util.Random random = new java.util.Random();

    public MotherBrain(GHG plugin) {
        this.plugin = plugin;
    }

    public void reset() {
        lastTargetId = null;
        lastKnown = null;
        searchingUntil = 0L;
        mode = MoveMode.NATIVE;
        jukePoint = null;
        jukeTargetId = null;
        jukeUntil = 0L;
        flankedId = null;
        flankReadyAt = 0L;
        feintReadyAt = 0L;
    }

    /**
     * 每秒跑一次。
     *
     * @return 当前要攻击/放技能的目标；null 表示正在搜索或巡逻
     */
    public Player tick(HunterGameManager game, Mob mob) {
        if (mob == null || !mob.isValid()) return null;

        ConfigManager cfg = game.getPlugin().getConfigManager();
        double senseRange = cfg.getHunterMotherSenseRange();
        double altarBonus = cfg.getHunterMotherAltarPriorityBonus();
        /* 卡视野：墙后看不见就真的找不到（破阵的声音除外）。关掉就退回旧的「半径内全知」。 */
        boolean useLos = cfg.isHunterMotherLineOfSight();
        double closeRange = cfg.getHunterMotherCloseRange();
        double hearRange = cfg.getHunterMotherHearRange();
        double maxClimb = cfg.getHunterMotherMaxClimb();

        // ---- 1. 选目标：正在破阵的优先，其次看距离，带一点黏性 ----
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        boolean bestVisible = false;
        for (Player candidate : game.getSurvivors()) {
            if (game.isDowned(candidate.getUniqueId())) continue;
            if (candidate.getWorld() != mob.getWorld()) continue;

            double distance = mob.getLocation().distance(candidate.getLocation());
            if (distance > senseRange) continue;

            boolean charging = game.isChargingAltar(candidate);
            boolean visible = !useLos || distance <= closeRange || canSee(mob, candidate);
            /* 看不见的人只有「正在破阵」的能被听见（破阵有声、有光柱），其余一律找不到 */
            boolean heard = !visible && charging && distance <= hearRange;
            if (!visible && !heard) continue;

            /* 站在够不着的高处（屋顶 / 断崖）：直接不当目标 —— 否则母体会在墙根一遍遍跳，
               看起来像卡住了。掉下来以后自然又会重新被选中。 */
            double climb = candidate.getLocation().getY() - mob.getLocation().getY();
            if (maxClimb > 0 && climb > maxClimb) continue;

            double score = distance;
            if (charging) score -= altarBonus;
            if (!visible) score += 8.0;                 /* 听到的不如看到的 */
            if (lastTargetId != null && lastTargetId.equals(candidate.getUniqueId())) {
                score -= altarBonus * 0.3;
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
                bestVisible = visible;
            }
        }

        if (best != null) {
            double bestDistance = mob.getLocation().distance(best.getLocation());
            handleJuke(game, mob, best, bestDistance, bestVisible);
            if (mode == MoveMode.PATH && jukePoint != null) {
                /* 绕后 / 假动作中：原版 AI 停手，改由 Pathfinder 走侧翼点 */
                mob.setTarget(null);
                double speed = cfg.getHunterMotherJukeSpeed();
                moveTo(mob, jukePoint, speed);
            } else if (cfg.isHunterMotherChasePathfinder() && bestDistance > 3.0
                && chaseByPathfinder(mob, best, cfg)) {
                /* 常态追击也交给 Pathfinder：走向「目标 0.5 秒后的位置」而不是他脚下，
                   这样会自然切角、拐过墙角；原版 setTarget 只会死追当前坐标。 */
                mob.setTarget(null);
            } else {
                mob.setTarget(best);
            }
            lastTargetId = best.getUniqueId();
            /* 只有真的看见才刷新「最后已知位置」；只听到的话不刷新，
               否则等于隔着墙把坐标抄下来，卡视野就白做了 */
            if (bestVisible || !useLos) lastKnown = best.getLocation().clone();
            int searchSeconds = cfg.getHunterMotherSearchSeconds();
            searchingUntil = System.currentTimeMillis() + searchSeconds * 1000L;
            return best;
        }

        // ---- 2. 丢了目标：先去最后已知位置找 ----
        exitJuke();
        mob.setTarget(null);
        if (lastKnown != null && lastKnown.getWorld() == mob.getWorld()
            && System.currentTimeMillis() < searchingUntil) {
            moveTo(mob, lastKnown, 1.0);
            return null;
        }

        // ---- 3. 巡逻守机 ----
        if (!game.getPlugin().getConfigManager().isHunterMotherPatrolEnabled()) return null;
        Location waypoint = game.nextPatrolPoint();
        if (waypoint != null && waypoint.getWorld() == mob.getWorld()) {
            /* 到点就别再挪：守机站位是「阵心外侧 N 格」，一走近目标点就会跟着自己动，
               不设死区她会围着这个点来回蹭（看起来像抖） */
            if (mob.getLocation().distance(waypoint) > 1.5) {
                moveTo(mob, waypoint, game.getPlugin().getConfigManager().getHunterMotherPatrolSpeed());
            }
        }
        return null;
    }

    // ================================================================ 绕后 / 假动作

    /**
     * 绕后与假动作的状态机，每秒走一次。
     *
     * <p><b>绕后</b>：目标背对你、距离中等时，不从正面直冲，而是绕到侧后方再切回追击。
     * 目标是「正在破阵、背对着」的人时最明显 —— 他得停下手里的事转身。</p>
     *
     * <p><b>假动作</b>：目标正盯着你时，有小概率先横向窜一下再压上去，让他的走位猜错方向。</p>
     *
     * <p>两者都只改「怎么走过去」，不改「追谁」；命中判定照旧由距离 + 视线决定。</p>
     */
    private void handleJuke(HunterGameManager game, Mob mob, Player target, double distance, boolean visible) {
        ConfigManager cfg = plugin.getConfigManager();
        long now = System.currentTimeMillis();

        if (mode == MoveMode.PATH) {
            boolean sameTarget = jukeTargetId != null && jukeTargetId.equals(target.getUniqueId());
            boolean arrived = jukePoint != null && jukePoint.getWorld() == mob.getWorld()
                && mob.getLocation().distance(jukePoint) <= 1.6;
            boolean timeout = now >= jukeUntil;
            boolean tooClose = distance <= 2.6;      /* 已经能砍到了，没必要再绕 */
            if (!sameTarget || arrived || timeout || tooClose || !visible) exitJuke();
            return;
        }

        if (!visible) return;
        double near = cfg.getHunterMotherJukeRange();
        double far = cfg.getHunterMotherJukeMinRange();
        if (distance > near || distance < far) return;

        boolean flankOn = cfg.isHunterMotherFlankEnabled();
        boolean feintOn = cfg.isHunterMotherFeintEnabled();
        if (!flankOn && !feintOn) return;

        /* 目标朝向：dot = 目标视线 · 目标指向母体的方向。
           ≈ +1 = 他正看着你；≈ −1 = 他背对着你。 */
        double dot = facingDot(target, mob);

        if (flankOn && now >= flankReadyAt
            && (flankedId == null || !flankedId.equals(target.getUniqueId()))
            && dot <= -cfg.getHunterMotherFlankDot()) {
            /* 绕后点：目标侧后方（随机挑一侧，免得每次都被预判） */
            org.bukkit.util.Vector facing = flat(target.getLocation().getDirection());
            if (facing.lengthSquared() < 0.01) facing = new org.bukkit.util.Vector(1, 0, 0);
            org.bukkit.util.Vector side = perpendicular(facing);
            double offset = cfg.getHunterMotherFlankOffset();
            Location spot = target.getLocation().clone()
                .add(side.multiply(offset))
                .add(facing.normalize().multiply(-1.2));      /* 往他背后偏一点 */
            startJuke(mob, target, spot, cfg.getHunterMotherFlankMaxSeconds());
            flankedId = target.getUniqueId();
            flankReadyAt = now + cfg.getHunterMotherFlankCooldownSeconds() * 1000L;
            return;
        }

        if (feintOn && now >= feintReadyAt && dot >= cfg.getHunterMotherFlankDot()
            && random.nextInt(100) < cfg.getHunterMotherFeintChance()) {
            /* 假动作：贴着连线垂直方向窜一格，逼他往反方向躲 */
            org.bukkit.util.Vector toMother = flat(mob.getLocation().toVector()
                .subtract(target.getLocation().toVector()));
            if (toMother.lengthSquared() < 0.01) toMother = new org.bukkit.util.Vector(0, 0, 1);
            org.bukkit.util.Vector side = perpendicular(toMother.normalize());
            Location spot = mob.getLocation().clone()
                .add(side.multiply(cfg.getHunterMotherFlankOffset() * 0.8));
            startJuke(mob, target, spot, cfg.getHunterMotherFeintSeconds());
            feintReadyAt = now + cfg.getHunterMotherFeintCooldownSeconds() * 1000L;
        }
    }

    /**
     * 用 Pathfinder 追向目标的预判点。
     *
     * <p>预判点 = 目标当前位置 + 他的水平速度 × {@code lead-seconds}。走这个点会自然切角，
     * 目标拐弯时不用先跑到他原来站的地方再折返。</p>
     *
     * @return true 表示寻路已经接管移动（调用方应把原版 target 清掉，避免两个控制器打架）
     */
    private boolean chaseByPathfinder(Mob mob, Player target, ConfigManager cfg) {
        Location aim = leadPoint(target, cfg.getHunterMotherChaseLeadSeconds());
        if (aim == null) return false;
        return pathTo(mob, aim, cfg.getHunterMotherChaseSpeed());
    }

    /** 目标 {@code leadSeconds} 秒后的位置（只算水平速度，避免被跳跃/下落带偏） */
    private static Location leadPoint(Player target, double leadSeconds) {
        if (target == null || leadSeconds <= 0) return target == null ? null : target.getLocation();
        try {
            Vector velocity = target.getVelocity().clone();
            velocity.setY(0);
            if (velocity.lengthSquared() < 0.0004) return target.getLocation();
            /* 限制提前量：跑得再快也别预判到 4 格以外，否则会突然"绕过"目标 */
            Vector lead = velocity.multiply(leadSeconds);
            if (lead.length() > 4.0) lead = lead.normalize().multiply(4.0);
            return target.getLocation().clone().add(lead);
        } catch (Throwable t) {
            return target.getLocation();
        }
    }

    private void startJuke(Mob mob, Player target, Location spot, int seconds) {
        if (spot == null || spot.getWorld() != mob.getWorld()) return;
        /* 侧翼点可能被墙/树挡住（目标贴墙站时很常见）：站不住就往母体这侧缩，
           缩到能用为止；三档都不行就这次不绕，直接走原版追击 —— 不能为了绕后爬墙。 */
        Location safe = freeSpot(spot, mob.getLocation());
        if (safe == null) return;
        jukePoint = safe;
        jukeTargetId = target.getUniqueId();
        jukeUntil = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        mode = MoveMode.PATH;
    }

    /** 找一档站得住的侧翼点：脚下方块和头顶方块都不能是实体 */
    private static Location freeSpot(Location spot, Location from) {
        if (spot == null || spot.getWorld() == null) return null;
        org.bukkit.util.Vector back = from.toVector().subtract(spot.toVector());
        for (double shrink : new double[]{0.0, 0.4, 0.75}) {
            Location probe = spot.clone().add(back.clone().multiply(shrink));
            try {
                if (!probe.getBlock().getType().isSolid()
                    && !probe.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
                    return probe;
                }
            } catch (Throwable t) {
                return probe;                 /* 拿不到方块信息就别卡着，交给寻路兜底 */
            }
        }
        return null;
    }

    /** 结束绕后 / 假动作，把移动交还给原版 AI */
    private void exitJuke() {
        if (mode == MoveMode.PATH) {
            mode = MoveMode.NATIVE;
            jukePoint = null;
            jukeTargetId = null;
            jukeUntil = 0L;
        }
    }

    /** 目标算不算「背对着母体」：+1 = 正看着母体，−1 = 完全背对 */
    private static double facingDot(Player target, Mob mob) {
        try {
            org.bukkit.util.Vector facing = flat(target.getLocation().getDirection());
            org.bukkit.util.Vector toMother = flat(mob.getLocation().toVector()
                .subtract(target.getLocation().toVector()));
            if (facing.lengthSquared() < 0.01 || toMother.lengthSquared() < 0.01) return 0;
            return facing.normalize().dot(toMother.normalize());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static org.bukkit.util.Vector flat(org.bukkit.util.Vector v) {
        v.setY(0);
        return v;
    }

    /** 水平面内的垂直方向（随机取一侧） */
    private org.bukkit.util.Vector perpendicular(org.bukkit.util.Vector v) {
        org.bukkit.util.Vector p = new org.bukkit.util.Vector(-v.getZ(), 0, v.getX());
        if (p.lengthSquared() < 0.01) p = new org.bukkit.util.Vector(0, 0, 1);
        p.normalize();
        return random.nextBoolean() ? p : p.multiply(-1);
    }

    /** 母体能不能真的看到这个逃生者（视线被方块挡住就是看不到） */
    private static boolean canSee(Mob mob, Player target) {
        try {
            return mob.hasLineOfSight(target);
        } catch (Throwable t) {
            return true;                 /* 老服务端没有这个方法：退回「看得见」，别让 AI 变瞎 */
        }
    }

    /**
     * 让 Mob 走向某个点。
     *
     * <p>优先用 Paper 的 Pathfinder（真的会寻路、会绕障碍）；没有就退化成朝目标给一点速度，
     * 平地勉强能用，遇到墙会卡——但总比站着不动好。两条路都兜 Throwable。</p>
     */
    /**
     * 只走 Pathfinder，返回是否成功（失败时调用方自己决定要不要退回原版 AI）。
     *
     * <p>和 {@link #moveTo} 的区别：这里**不会**用「给一点速度」兜底。追击时如果寻路失败，
     * 更好的选择是把移动交还给原版 {@code setTarget}，而不是推一下速度就完事。</p>
     */
    public static boolean pathTo(Mob mob, Location target, double speed) {
        if (mob == null || target == null) return false;
        if (target.getWorld() != mob.getWorld()) return false;
        return tryPathfinder(mob, snapToGround(target), speed);
    }

    public static void moveTo(Mob mob, Location target, double speed) {
        if (target == null || mob == null) return;
        if (target.getWorld() != mob.getWorld()) return;

        // 目标点如果埋在方块里（人贴着墙站时很常见），寻路必然失败——先抬到落脚面
        Location goal = snapToGround(target);
        if (tryPathfinder(mob, goal, speed)) return;

        // 再试「朝目标 3 格的中间点」：卡在窄角 / 门口时，这个点往往能蹭出去
        Vector direction = goal.toVector().subtract(mob.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() > 1.0) {
            Location mid = mob.getLocation().clone()
                .add(direction.clone().normalize().multiply(3.0));
            if (tryPathfinder(mob, snapToGround(mid), speed)) return;
        }

        // 两条寻路都失败：给速度 + 跳一下，至少别站着不动
        if (direction.lengthSquared() < 1.0) return;
        direction.normalize().multiply(0.28);
        try {
            if (mob.getLocation().clone().add(0, 1, 0).getBlock().getType().isSolid()) {
                direction.setY(0.3);
            }
            mob.setVelocity(direction);
        } catch (Throwable ignored) {
        }
    }

    /** 把埋在方块里的坐标抬到上方第一个非固体格（最多抬 4 格） */
    private static Location snapToGround(Location target) {
        if (target == null || target.getWorld() == null) return target;
        try {
            Location probe = target.clone();
            for (int i = 0; i < 4; i++) {
                if (!probe.getBlock().getType().isSolid()) return probe;
                probe.add(0, 1, 0);
            }
        } catch (Throwable ignored) {
        }
        return target;
    }

    private static boolean tryPathfinder(Mob mob, Location target, double speed) {
        try {
            Object pathfinder = mob.getClass().getMethod("getPathfinder").invoke(mob);
            if (pathfinder == null) return false;
            Class<?> api = Class.forName("io.papermc.paper.entity.Pathfinder");
            Object result = api.getMethod("moveTo", Location.class, double.class)
                .invoke(pathfinder, target, speed);
            // 必须看返回值：目标不可达时 moveTo 返回 false，之前忽略它就会以为寻路成功、
            // 结果母体原地站着（这就是「卡墙」的主因之一）
            return !(result instanceof Boolean) || ((Boolean) result);
        } catch (Throwable ignored) {
            // Spigot / 老版本没有 Pathfinder，走速度兜底
        }
        return false;
    }
}
