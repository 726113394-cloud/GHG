package io.Sriptirc_wp_1258.gost.managers;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 七种母体技能的实现与注册表。
 *
 * <p>技能不绑定载体：AI 母体（Mob）与玩家母体（Player）走同一套代码。</p>
 *
 * <p>说明两点设计：<b>「站着不动 3 秒让逃生者发光」是所有母体的被动</b>（不在这个注册表里，
 * 由 {@code HunterGameManager} 统一跑），窥视者自己的技能换成了隐身 + 加速。</p>
 */
public final class MotherSkills {

    /** 射手骨矢的标记：命中判定在 HunterListener */
    public static final String ARROW_META = "ghg_mother_arrow";
    /** 炼金者弩箭的标记：命中后原地炸出一瓶喷溅减速药水 */
    public static final String BOLT_META = "ghg_mother_bolt";

    private static final Map<String, MotherSkill> SKILLS = new LinkedHashMap<>();

    static {
        register(new Sprint());
        register(new Dash());
        register(new ArrowShot());
        register(new Bolt());
        register(new Teleport());
        register(new Vanish());
        register(new Snare());
        register(new PearlTeleport());
    }

    private MotherSkills() {}

    private static void register(MotherSkill skill) {
        SKILLS.put(skill.id(), skill);
    }

    public static MotherSkill byId(String id) {
        return id == null ? null : SKILLS.get(id);
    }

    public static Collection<MotherSkill> all() {
        return Collections.unmodifiableCollection(SKILLS.values());
    }

    // ---------------------------------------------------------------- 工具

    private static ConfigManager config(HunterGameManager game) {
        return game.getPlugin().getConfigManager();
    }

    /** 范围内的技能目标：真人逃生者 + （母体测试局的）测试假人 */
    private static List<Entity> targetsInRange(HunterGameManager game, Location center, double range) {
        return game.skillTargets(center, range);
    }

    private static void sound(Location location, org.bukkit.Sound sound, float volume) {
        if (location == null || location.getWorld() == null || sound == null) return;
        try {
            location.getWorld().playSound(location, sound, volume, 1.0f);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 玩家母体的「朝准星落点」：沿视线水平方向从 {@code maxDistance} 往近退，取第一个站得住的位置。
     *
     * <p>直接取最远点很容易落在墙里 / 半空；退到能站的地方就够了 ——
     * 传送的代价本来就是那 2 秒硬直，不是距离。</p>
     */
    private static Location aimSpot(Location from, double maxDistance) {
        if (from == null || from.getWorld() == null) return null;
        Vector dir = from.getDirection().clone();
        dir.setY(0);
        if (dir.lengthSquared() < 0.01) return null;
        dir.normalize();
        World world = from.getWorld();
        for (double d = Math.max(2.0, maxDistance); d >= 1.5; d -= 0.5) {
            Location probe = from.clone().add(dir.clone().multiply(d));
            Location stand = safeStand(world, probe);
            if (stand != null) return stand;
        }
        return null;
    }

    /** 这一列能不能站人：脚与头顶两格非实心、脚下实心（先往上找台阶，再往下找地面） */
    private static Location safeStand(World world, Location probe) {
        int x = probe.getBlockX();
        int z = probe.getBlockZ();
        int baseY = probe.getBlockY();
        for (int dy = 1; dy >= -3; dy--) {
            int y = baseY + dy;
            try {
                if (world.getBlockAt(x, y, z).getType().isSolid()) continue;
                if (world.getBlockAt(x, y + 1, z).getType().isSolid()) continue;
                if (!world.getBlockAt(x, y - 1, z).getType().isSolid()) continue;
                return new Location(world, x + 0.5, y, z + 0.5, probe.getYaw(), probe.getPitch());
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Location eyeOf(Entity caster) {
        return (caster instanceof LivingEntity)
            ? ((LivingEntity) caster).getEyeLocation()
            : caster.getLocation().add(0, 1.2, 0);
    }

    /** 喷溅药水的视觉：地面上炸开一圈水花 */
    private static void splashVisual(Location center, double radius) {
        if (center == null || center.getWorld() == null) return;
        try {
            center.getWorld().spawnParticle(ParticleCompat.cloud(), center.clone().add(0, 0.4, 0),
                28, radius * 0.6, 0.25, radius * 0.6);
            center.getWorld().spawnParticle(ParticleCompat.electricSpark(), center.clone().add(0, 0.6, 0),
                12, radius * 0.4, 0.2, radius * 0.4);
        } catch (Throwable ignored) {
        }
        sound(center, SoundCompat.playerSplash(), 1.0f);
    }

    // ================================================================ 疾跑

    /** 疾行者：短时间给自己加速，用来强行贴脸 */
    private static final class Sprint implements MotherSkill {

        @Override public String id() { return "sprint"; }
        @Override public double range(HunterGameManager game) { return 24.0; }

        @Override
        public int cooldownSeconds(HunterGameManager game) {
            return config(game).getHunterSkillSprintCooldown();
        }

        @Override
        public boolean cast(HunterGameManager game, Entity caster, Entity target) {
            if (!(caster instanceof LivingEntity)) return false;
            int seconds = config(game).getHunterSkillSprintSeconds();
            int level = config(game).getHunterSkillSprintLevel();
            try {
                ((LivingEntity) caster).addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, seconds * 20, Math.max(0, level - 1), true, true));
            } catch (Throwable t) {
                return false;
            }
            sound(caster.getLocation(), SoundCompat.enderDragonGrowl(), 0.6f);
            if (target instanceof Player) {
                game.getPlugin().getLanguageManager().sendMessage((Player) target, "hunter.skill.sprint-target");
            }
            game.notifyMotherPlayer("hunter.skill.sprint-cast", seconds);
            return true;
        }
    }

    // ================================================================ 冲刺

    /**
     * 冲撞者：瞬时冲刺。
     *
     * <p>方向分两种：<b>玩家母体朝自己瞄准的方向冲</b>（指哪打哪，右键即放），
     * <b>AI 母体朝目标冲</b>（MotherBrain 传进来的那个逃生者，也就是最近的目标）。
     * 玩家母体不再被「最近的逃生者」代劳转向——想冲哪就先把视角转过去。</p>
     */
    private static final class Dash implements MotherSkill {

        @Override public String id() { return "dash"; }
        @Override public double range(HunterGameManager game) { return 20.0; }

        /** 冲刺是位移技能：玩家母体瞄着空处也能冲，不要求附近有人 */
        @Override public boolean requiresTarget() { return false; }

        @Override
        public int cooldownSeconds(HunterGameManager game) {
            return config(game).getHunterSkillDashCooldown();
        }

        @Override
        public boolean cast(HunterGameManager game, Entity caster, Entity target) {
            if (caster == null) return false;
            Location from = caster.getLocation();

            Vector direction;
            if (caster instanceof Player) {
                direction = from.getDirection();
            } else if (target != null) {
                if (from.getWorld() != target.getWorld()) return false;
                direction = target.getLocation().toVector().subtract(from.toVector());
            } else {
                direction = from.getDirection();
            }
            direction.setY(0);
            if (direction.lengthSquared() < 0.01) return false;

            double power = config(game).getHunterSkillDashPower();
            direction.normalize().multiply(power).setY(0.28);
            try {
                caster.setVelocity(direction);
            } catch (Throwable t) {
                return false;
            }

            sound(from, SoundCompat.playerAttackSweep(), 0.8f);
            // 玩家母体是靠自己瞄的，不该给「最近的那个人」推一条被锁定的提示
            if (!(caster instanceof Player) && target instanceof Player) {
                game.getPlugin().getLanguageManager().sendMessage((Player) target, "hunter.skill.dash-target");
            }
            // 冲出去之后开一段撞击窗口：撞到谁就晕谁
            game.startDashStun(caster);
            game.notifyMotherPlayer("hunter.skill.dash-cast");
            return true;
        }
    }

    // ================================================================ 箭矢（射手）

    /** 射手：发射骨矢，命中后**长时间重减速**（失能型） */
    private static final class ArrowShot implements MotherSkill {

        @Override public String id() { return "arrow"; }
        @Override public double range(HunterGameManager game) { return 20.0; }

        /** 玩家母体瞄着空处也能射（方向判定），AI 才有「必须有目标」 */
        @Override public boolean requiresTarget() { return false; }

        @Override
        public int cooldownSeconds(HunterGameManager game) {
            return config(game).getHunterSkillArrowCooldown();
        }

        @Override
        public boolean cast(HunterGameManager game, Entity caster, Entity target) {
            if (caster == null) return false;
            World world = caster.getWorld();
            if (world == null) return false;

            Location eye = eyeOf(caster);
            Vector direction;
            if (caster instanceof Player) {
                /* 玩家母体：**朝准星方向**射，不替你锁定最近的人 —— 打不打得中看瞄准 */
                direction = eye.getDirection().clone();
            } else if (target != null) {
                if (target.getWorld() != world) return false;
                direction = target.getLocation().add(0, 1.0, 0).toVector().subtract(eye.toVector());
            } else {
                direction = eye.getDirection().clone();
            }
            if (direction.lengthSquared() < 0.01) return false;
            direction.normalize();

            try {
                Arrow arrow = world.spawnArrow(eye, direction, 2.0f, 0.6f);
                arrow.setDamage(0.0);
                arrow.setMetadata(ARROW_META, new FixedMetadataValue(game.getPlugin(), true));
            } catch (Throwable t) {
                return false;
            }

            sound(eye, SoundCompat.witherShoot(), 0.8f);
            /* 「母体朝你射箭」只在 AI 真瞄准这个人时发；玩家自己瞄的，不该给最近的人推提示 */
            if (!(caster instanceof Player) && target instanceof Player) {
                game.getPlugin().getLanguageManager().sendMessage((Player) target, "hunter.skill.arrow-target");
            }
            game.notifyMotherPlayer("hunter.skill.arrow-cast");
            return true;
        }
    }

    // ================================================================ 弩箭（炼金者）

    /**
     * 炼金者：用**弩箭**射出，命中位置炸出一瓶喷溅减速药水。
     * 减速比射手的弱一档、时间也短——它是范围型的，单体压制不该比射手强。
     */
    private static final class Bolt implements MotherSkill {

        @Override public String id() { return "bolt"; }
        @Override public double range(HunterGameManager game) { return 18.0; }

        /** 玩家母体瞄着空处也能射（方向判定） */
        @Override public boolean requiresTarget() { return false; }

        @Override
        public int cooldownSeconds(HunterGameManager game) {
            return config(game).getHunterSkillPotionCooldown();
        }

        @Override
        public boolean cast(HunterGameManager game, Entity caster, Entity target) {
            if (caster == null) return false;
            World world = caster.getWorld();
            if (world == null) return false;

            Location eye = eyeOf(caster);
            Vector direction;
            if (caster instanceof Player) {
                /* 玩家母体：朝准星方向，自己瞄 */
                direction = eye.getDirection().clone();
            } else if (target != null) {
                if (target.getWorld() != world) return false;
                direction = target.getLocation().add(0, 1.0, 0).toVector().subtract(eye.toVector());
            } else {
                direction = eye.getDirection().clone();
            }
            if (direction.lengthSquared() < 0.01) return false;
            direction.normalize();

            try {
                // 用弩射一支箭（velocity 更快、散布更小），命中后由监听器炸药水
                Arrow arrow = world.spawnArrow(eye, direction, 2.6f, 0.15f);
                arrow.setDamage(0.0);
                arrow.setMetadata(BOLT_META, new FixedMetadataValue(game.getPlugin(), true));
            } catch (Throwable t) {
                return false;
            }

            sound(eye, SoundCompat.witherShoot(), 0.7f);
            game.notifyMotherPlayer("hunter.skill.bolt-cast");
            return true;
        }
    }

    // ================================================================ 传送

    /**
     * 穿隙者：直接传送到目标身边，但**自己会硬直**（禁止移动与互动）。
     * 硬直是传送的代价，否则这个技能没有反制空间。
     */
    private static final class Teleport implements MotherSkill {

        @Override public String id() { return "teleport"; }
        @Override public double range(HunterGameManager game) { return 40.0; }

        /** 玩家母体朝准星闪（方向判定），不需要先有目标 */
        @Override public boolean requiresTarget() { return false; }

        @Override
        public int cooldownSeconds(HunterGameManager game) {
            return config(game).getHunterSkillTeleportCooldown();
        }

        @Override
        public boolean cast(HunterGameManager game, Entity caster, Entity target) {
            if (caster == null) return false;

            Location destination;
            if (caster instanceof Player) {
                /* 玩家母体：**朝准星方向闪**（方向判定）—— 闪多远看瞄准，不替你贴到人背后。
                   落点会从最远往近找，取第一个站得住的位置；全都不行就这一下白放。 */
                Player mother = (Player) caster;
                destination = aimSpot(mother.getLocation(),
                    config(game).getHunterSkillTeleportDistance());
                if (destination == null) {
                    game.getPlugin().getLanguageManager().sendMessage(mother, "hunter.skill.teleport-blocked");
                    return false;
                }
                destination.setYaw(mother.getLocation().getYaw());
                destination.setPitch(mother.getLocation().getPitch());
            } else if (target != null) {
                World world = target.getWorld();
                if (world == null || caster.getWorld() != world) return false;
                Vector behind = target.getLocation().getDirection().setY(0);
                if (behind.lengthSquared() < 0.01) behind = new Vector(1, 0, 0);
                behind.normalize().multiply(1.8);
                destination = target.getLocation().clone().subtract(behind);
                destination.setYaw(target.getLocation().getYaw() + 180f);
            } else {
                // 没有目标时朝面向方向送 6 格，测试局用来看传送与硬直
                Vector forward = caster.getLocation().getDirection().setY(0);
                if (forward.lengthSquared() < 0.01) return false;
                destination = caster.getLocation().clone().add(forward.normalize().multiply(6));
            }

            try {
                caster.teleport(destination);
            } catch (Throwable t) {
                return false;
            }

            sound(destination, SoundCompat.endermanTeleport(), 0.9f);
            /* 「母体传送到你背后」只在 AI 真朝这个人闪时发 */
            if (!(caster instanceof Player) && target instanceof Player) {
                game.getPlugin().getLanguageManager().sendMessage((Player) target, "hunter.skill.teleport-target");
            }
            game.freezeMother(config(game).getHunterSkillTeleportFreezeSeconds());
            game.notifyMotherPlayer("hunter.skill.teleport-cast");
            return true;
        }
    }

    // ============================================================ 珍珠传送（珍珠女士）

    /**
     * 珍珠女士：母体版的传送珍珠 —— 朝准星方向闪一段距离。
     *
     * <p>代价和人类那瓶不一样：落地后**自己吃一段缓慢Ⅰ**（不是硬直），
     * 只要这段时间里**打中人**，{@code clearPearlSlow()} 会立刻抹掉它，
     * 换成母体每次攻击自带的 2 秒后摇。所以她是「闪过去就得马上接刀」的节奏型母体。</p>
     */
    private static final class PearlTeleport implements MotherSkill {

        @Override public String id() { return "pearl"; }
        @Override public double range(HunterGameManager game) { return 30.0; }

        /** 玩家母体朝准星闪，不要求附近有人 */
        @Override public boolean requiresTarget() { return false; }

        @Override
        public int cooldownSeconds(HunterGameManager game) {
            return config(game).getHunterSkillPearlCooldown();
        }

        @Override
        public boolean cast(HunterGameManager game, Entity caster, Entity target) {
            if (caster == null) return false;

            Location destination;
            if (caster instanceof Player) {
                Player mother = (Player) caster;
                destination = aimSpot(mother.getLocation(), config(game).getHunterSkillPearlDistance());
                if (destination == null) {
                    game.getPlugin().getLanguageManager()
                        .sendMessage(mother, "hunter.skill.teleport-blocked");
                    return false;
                }
                destination.setYaw(mother.getLocation().getYaw());
                destination.setPitch(mother.getLocation().getPitch());
            } else if (target != null && target.getWorld() == caster.getWorld()) {
                Vector behind = target.getLocation().getDirection().setY(0);
                if (behind.lengthSquared() < 0.01) behind = new Vector(1, 0, 0);
                behind.normalize().multiply(1.6);
                destination = target.getLocation().clone().subtract(behind);
                destination.setYaw(target.getLocation().getYaw() + 180f);
            } else {
                Vector forward = caster.getLocation().getDirection().setY(0);
                if (forward.lengthSquared() < 0.01) return false;
                destination = caster.getLocation().clone().add(forward.normalize().multiply(6));
            }

            try {
                caster.teleport(destination);
            } catch (Throwable t) {
                return false;
            }

            sound(destination, SoundCompat.enderPearlThrow(), 1.0f);
            game.applyPearlSlow(config(game).getHunterSkillPearlSlowSeconds(),
                config(game).getHunterSkillPearlSlowLevel());
            /* 「母体闪到你背后」只在 AI 真朝这个人闪时发 */
            if (!(caster instanceof Player) && target instanceof Player) {
                game.getPlugin().getLanguageManager()
                    .sendMessage((Player) target, "hunter.skill.pearl-target");
            }
            game.notifyMotherPlayer("hunter.skill.pearl-cast");
            return true;
        }
    }

    // ================================================================ 隐身（窥视者）

    /**
     * 窥视者：**隐身并加速**一段时间；一旦成功打中逃生者，**加速立刻取消**。
     *
     * <p>「站着不动就能透视」已经从窥视者的专属技能提升成**所有母体的被动**，
     * 所以这里只负责潜行突袭。</p>
     */
    private static final class Vanish implements MotherSkill {

        @Override public String id() { return "vanish"; }
        @Override public double range(HunterGameManager game) { return 30.0; }

        @Override
        public int cooldownSeconds(HunterGameManager game) {
            return config(game).getHunterSkillVanishCooldown();
        }

        @Override
        public boolean cast(HunterGameManager game, Entity caster, Entity target) {
            if (caster == null) return false;
            int seconds = config(game).getHunterSkillVanishSeconds();
            int level = config(game).getHunterSkillVanishSpeedLevel();
            if (!game.applyMotherVanish(seconds, level)) return false;

            sound(caster.getLocation(), SoundCompat.illusionerMirror(), 0.8f);
            game.notifyMotherPlayer("hunter.skill.vanish-cast", seconds);
            return true;
        }
    }

    // ================================================================ 缚网

    /**
     * 缚网者：以自身为中心甩出一瓶**喷溅减速药水**，落点范围内所有逃生者减速。
     */
    private static final class Snare implements MotherSkill {

        @Override public String id() { return "snare"; }
        @Override public int cooldownSeconds(HunterGameManager game) {
            return config(game).getHunterSkillSnareCooldown();
        }
        @Override public double range(HunterGameManager game) {
            return config(game).getHunterSkillSnareRadius();
        }
        @Override public boolean requiresTarget() { return false; }

        @Override
        public boolean cast(HunterGameManager game, Entity caster, Entity target) {
            if (caster == null) return false;
            Location center = caster.getLocation();
            double radius = range(game);
            List<Entity> caught = targetsInRange(game, center, radius);
            if (caught.isEmpty()) return false;

            int seconds = config(game).getHunterSkillSlowSeconds();
            int level = config(game).getHunterSkillSlowLevel();
            for (Entity victim : caught) {
                if (victim instanceof Player) {
                    Player player = (Player) victim;
                    game.applySlow(player, seconds, level);
                    game.getPlugin().getLanguageManager().sendMessage(player, "hunter.skill.snare-target", seconds);
                } else {
                    // 测试假人：算一次命中，缚网拍没拍到一眼就能看出来
                    game.hitTestDummy(victim, caster instanceof Player ? (Player) caster : null, false);
                }
            }

            // 视觉上用喷溅药水：原地炸开
            splashVisual(center, radius);
            game.notifyMotherPlayer("hunter.skill.snare-cast", caught.size());
            return true;
        }
    }
}
