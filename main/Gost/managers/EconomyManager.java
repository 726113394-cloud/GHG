package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GHG 的经济系统（Vault）。
 *
 * <p>从 Gost 那套「人类奖池 / 鬼奖池 / 个人奖金 / 排行榜」里只留下有用的部分，
 * 按 GHG 的胜负重做成一套：</p>
 *
 * <ol>
 *   <li><b>入场费</b>：每个逃生者交一份进奖池；玩家母体按种类另交门票（在种类选择里扣）</li>
 *   <li><b>保底奖金</b>：服务器额外投一笔进奖池，避免小局没油水</li>
 *   <li><b>结算</b>：撤离成功 → 奖池按**贡献**分给逃生者（破阵时长 + 救援次数 + 活到最后）；
 *       玩家母体获胜 → 奖池给母体；AI 母体获胜 → 入场费全额退还（不坑真人）</li>
 *   <li><b>中断</b>：管理员 forceStop / 插件禁用 → 全额退还，不吞钱</li>
 * </ol>
 */
public class EconomyManager {

    private final GHG plugin;

    private Economy vault;
    private double prizePool;
    /** 逃生者入场费（只分给逃生者） */
    private double entryPool;
    /** 服务器保底（公共池：母体也能按表现分一份） */
    private double bonusPool;

    public EconomyManager(GHG plugin) {
        this.plugin = plugin;
        setupEconomy();
    }

    private void setupEconomy() {
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                plugin.getLogger().info("未找到 Vault，经济功能关闭（其余玩法不受影响）");
                return;
            }
            RegisteredServiceProvider<Economy> provider =
                Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider == null || provider.getProvider() == null) {
                plugin.getLogger().info("Vault 未提供经济实现，经济功能关闭");
                return;
            }
            vault = provider.getProvider();
        } catch (Throwable t) {
            plugin.getLogger().warning("初始化经济系统失败: " + t.getMessage());
        }
    }
    
    /** 接入的经济实现名；未接入时返回 null（启动报告用） */
    public String getEconomyName() {
        return vault == null ? null : vault.getName();
    }

    // ---------------------------------------------------------------- 基础能力

    public boolean isEconomyEnabled() {
        return vault != null;
    }

    public boolean hasBalance(Player player, double amount) {
        if (player == null || vault == null || amount <= 0) return false;
        try {
            return vault.has(player, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 扣钱（不碰奖池，用于母体种类门票） */
    public boolean charge(Player player, double amount) {
        if (player == null || amount <= 0) return true;
        if (vault == null) return false;
        try {
            return vault.withdrawPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            plugin.getLogger().warning("扣款失败: " + t.getMessage());
            return false;
        }
    }

    /** 退钱（不碰奖池） */
    public void refund(Player player, double amount) {
        if (player == null || amount <= 0 || vault == null) return;
        try {
            vault.depositPlayer(player, amount);
        } catch (Throwable t) {
            plugin.getLogger().warning("退款失败: " + t.getMessage());
        }
    }

    public void giveMoney(Player player, double amount) {
        refund(player, amount);
    }

    public String format(double amount) {
        return String.valueOf(Math.round(amount));
    }

    // ---------------------------------------------------------------- 奖池

    public void resetPool() {
        prizePool = 0;
        entryPool = 0;
        bonusPool = 0;
    }

    public double getPrizePool() {
        return prizePool;
    }

    /**
     * 从奖池里取出一笔钱（提前结算用）。
     *
     * <p>先扣服务器保底池、不够再扣入场费池 —— 入场费是逃生者自己交的，
     * 提前结算本来就该先花「服务器的钱」。取不满就只取到池子见底，绝不会变成负数。</p>
     *
     * @return 实际取出的金额
     */
    public double withdrawFromPool(double amount) {
        if (amount <= 0 || prizePool <= 0) return 0;
        double taken = 0;
        double fromBonus = Math.min(bonusPool, amount);
        bonusPool -= fromBonus;
        prizePool -= fromBonus;
        taken += fromBonus;

        double rest = amount - taken;
        if (rest > 0) {
            double fromEntry = Math.min(entryPool, rest);
            entryPool -= fromEntry;
            prizePool -= fromEntry;
            taken += fromEntry;
        }
        if (prizePool < 0.0001) prizePool = 0;
        if (bonusPool < 0.0001) bonusPool = 0;
        if (entryPool < 0.0001) entryPool = 0;
        return taken;
    }

    /**
     * 逃生者入场：扣一份入场费并投进奖池。
     *
     * @return false 表示余额不足（调用方决定要不要拦人）
     */
    public boolean chargeEntryFee(Player player) {
        double fee = plugin.getConfigManager().getHunterRewardEntryFee();
        if (vault == null || fee <= 0) return true;
        if (!hasBalance(player, fee)) {
            plugin.getLanguageManager().sendMessage(player, "hunter.reward.no-money", format(fee));
            return false;
        }
        if (!charge(player, fee)) return false;
        prizePool += fee;
        entryPool += fee;
        plugin.getLanguageManager().sendMessage(player, "hunter.reward.paid", format(fee));
        return true;
    }

    /** 服务器保底奖金 */
    public void addServerBonus() {
        double bonus = plugin.getConfigManager().getHunterRewardServerBonus();
        if (vault == null || bonus <= 0) return;
        prizePool += bonus;
        bonusPool += bonus;
    }




    /**
     * **两池制**结算：逃生者的入场费只在逃生者之间分，母体只分服务器保底。
     *
     * <p>原来一个池子，等于「逃生者凑钱、母体按表现分走一大块」——钱的方向很难看。
     * 现在母体从公共池（保底）里按她的行动分占比拿，最多 50%；剩下的公共池并回逃生者那份。</p>
     */
    public void settleTwoPools(java.util.Map<UUID, Double> survivorScores,
                               UUID motherId, double motherScore) {
        if (vault == null) { prizePool = 0; entryPool = 0; bonusPool = 0; return; }

        double survivorTotal = 0;
        if (survivorScores != null) {
            for (double value : survivorScores.values()) survivorTotal += Math.max(0.0, value);
        }

        // 母体能分到的公共池比例：按她的表现分占比，封顶 50%
        double motherRatio = 0;
        if (motherId != null && motherScore > 0 && survivorTotal + motherScore > 0) {
            motherRatio = Math.min(0.5, motherScore / (survivorTotal + motherScore));
        }
        double motherMoney = bonusPool * motherRatio;
        double survivorMoney = entryPool + bonusPool * (1.0 - motherRatio);

        if (motherId != null && motherMoney > 0) {
            Player mother = Bukkit.getPlayer(motherId);
            if (mother != null && mother.isOnline()) {
                refund(mother, motherMoney);
                plugin.getLanguageManager().sendMessage(mother, "hunter.reward.action-paid",
                    format(motherMoney), format(motherScore));
            }
        }

        if (survivorScores != null && survivorTotal > 0 && survivorMoney > 0) {
            for (java.util.Map.Entry<UUID, Double> entry : survivorScores.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null || !player.isOnline()) continue;
                double weight = Math.max(0.0, entry.getValue());
                if (weight <= 0) continue;
                double share = survivorMoney * (weight / survivorTotal);
                if (share <= 0) continue;
                refund(player, share);
                plugin.getLanguageManager().sendMessage(player, "hunter.reward.action-paid",
                    format(share), format(weight));
            }
        } else if (survivorScores != null && survivorMoney > 0) {
            // 逃生者一分没拿：入场费原样还回去，不吞
            double fee = plugin.getConfigManager().getHunterRewardEntryFee();
            for (UUID id : survivorScores.keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) continue;
                if (fee > 0) refund(player, fee);
                plugin.getLanguageManager().sendMessage(player, "hunter.reward.refunded", format(fee));
            }
        }

        Bukkit.broadcastMessage(plugin.getLanguageManager().getMessage(
            "hunter.reward.pool-paid-actions", format(prizePool)));
        prizePool = 0;
        entryPool = 0;
        bonusPool = 0;
    }

    /** AI 母体获胜 / 对局中断：把入场费退还给逃生者，不吞钱 */
    public void refundAll(Collection<Player> players, String reasonKey) {
        if (vault == null || prizePool <= 0 || players == null || players.isEmpty()) {
            prizePool = 0;
            return;
        }
        double fee = plugin.getConfigManager().getHunterRewardEntryFee();
        if (fee <= 0) {
            prizePool = 0;
            return;
        }
        for (Player player : players) {
            if (player == null || !player.isOnline()) continue;
            refund(player, fee);
            plugin.getLanguageManager().sendMessage(player, reasonKey, format(fee));
        }
        prizePool = 0;
    }
}
