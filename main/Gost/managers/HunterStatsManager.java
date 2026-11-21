package io.Sriptirc_wp_1258.gost.managers;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每局数据统计。
 *
 * <p>没有这一层，所有平衡参数都只能靠感觉调——「跑 10 局看胜率与平均时长」这句话
 * 就落不到实处。这里把每局的关键数字记到 {@code plugins/GHG/hunter-stats.yml}，
 * 并在结束时广播一段摘要。</p>
 *
 * <p>只保留最近 {@link #MAX_RECORDS} 局，避免文件无限长大。</p>
 */
public class HunterStatsManager {

    private static final int MAX_RECORDS = 200;

    private final GHG plugin;
    private final File file;
    private YamlConfiguration data;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // ---- 本局累加器 ----
    private boolean active;
    private String areaName = "-";
    private long startedAt;
    private int survivors;
    private int altarsTotal;
    private int altarsCharged;
    private int downs;
    private int rescues;
    private int eliminations;
    private int motherHits;
    private int heals;
    private int selfRescues;
    private int vaults;
    private final Map<String, Integer> itemsUsed = new LinkedHashMap<>();
    private int chestsOpened;
    private int itemsGiven;
    private String motherKind = "-";
    private boolean playerMother;

    public HunterStatsManager(GHG plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "hunter-stats.yml");
        load();
    }

    private void load() {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            plugin.getLogger().warning("无法创建 GHG 数据目录");
        }
        data = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private void save() {
        try {
            data.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("保存对局数据失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 生命周期

    public void begin(AreaManager.GameArea area, int survivorCount, int altarCount,
                      String kind, boolean isPlayerMother) {
        active = true;
        areaName = area == null ? "-" : area.getName();
        startedAt = System.currentTimeMillis();
        survivors = survivorCount;
        altarsTotal = altarCount;
        altarsCharged = 0;
        downs = 0;
        rescues = 0;
        eliminations = 0;
        motherHits = 0;
        heals = 0;
        selfRescues = 0;
        vaults = 0;
        itemsUsed.clear();
        chestsOpened = 0;
        itemsGiven = 0;
        motherKind = kind == null ? "-" : kind;
        playerMother = isPlayerMother;
    }

    public boolean isActive() {
        return active;
    }

    public void countAltarCharged() { if (active) altarsCharged++; }
    public void countDown()         { if (active) downs++; }
    public void countRescue()       { if (active) rescues++; }
    public void countElimination()  { if (active) eliminations++; }
    public void countHit()          { if (active) motherHits++; }
    public void countHeal()         { if (active) heals++; }
    public void countSelfRescue()   { if (active) selfRescues++; }
    public void countVault()        { if (active) vaults++; }

    /** 道具使用次数（按 ID 累计，用来看哪些道具真的被用） */
    public void countItemUsed(String itemId) {
        if (!active || itemId == null) return;
        itemsUsed.merge(itemId, 1, Integer::sum);
    }
    public void countChest()        { if (active) chestsOpened++; }
    public void countItem()         { if (active) itemsGiven++; }

    /** 本局是不是平局（时间到）——平局不计入任何一方的胜场 */
    private boolean draw;

    public void markDraw() { this.draw = true; }

    // ---------------------------------------------------------------- 本局累计数（结算提示用）
    public int getAltarsCharged()  { return altarsCharged; }
    public int getAltarsTotal()    { return altarsTotal; }
    public int getDowns()          { return downs; }
    public int getRescues()        { return rescues; }
    public int getEliminations()   { return eliminations; }
    public int getMotherHits()     { return motherHits; }

    /**
     * 收局并落盘。
     *
     * @param survivorsWon 逃生者是否撤离成功
     * @param durationSeconds 本局实际时长
     * @param remainingSurvivors 结算时还站着的逃生者
     * @return 用于广播的摘要文本（调用方决定怎么发）
     */
    public Map<String, Object> finish(boolean survivorsWon, int durationSeconds, int remainingSurvivors) {
        if (!active) return null;
        active = false;

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("time", timeFormat.format(new Date(startedAt)));
        record.put("area", areaName);
        record.put("duration", durationSeconds);
        record.put("survivors", survivors);
        record.put("remaining", remainingSurvivors);
        record.put("mother-kind", motherKind);
        record.put("player-mother", playerMother);
        record.put("altars", altarsTotal);
        record.put("charged", altarsCharged);
        record.put("downs", downs);
        record.put("rescues", rescues);
        record.put("eliminated", eliminations);
        record.put("mother-hits", motherHits);
        record.put("heals", heals);
        record.put("self-rescues", selfRescues);
        record.put("vaults", vaults);
        record.put("items-used", new LinkedHashMap<>(itemsUsed));
        record.put("chests", chestsOpened);
        record.put("items", itemsGiven);
        record.put("result", draw ? "DRAW" : (survivorsWon ? "SURVIVOR_WIN" : "MOTHER_WIN"));
        draw = false;

        List<Map<?, ?>> matches = new ArrayList<>(data.getMapList("matches"));
        matches.add(record);
        while (matches.size() > MAX_RECORDS) {
            matches.remove(0);
        }
        data.set("matches", matches);

        // 汇总
        int total = matches.size();
        int survivorWins = 0;
        int motherWins = 0;
        long totalDuration = 0;
        for (Map<?, ?> m : matches) {
            Object result = m.get("result");
            if ("SURVIVOR_WIN".equals(result)) {
                survivorWins++;
            } else if ("MOTHER_WIN".equals(result)) {
                motherWins++;
            }
            Object duration = m.get("duration");
            if (duration instanceof Number) totalDuration += ((Number) duration).longValue();
        }
        data.set("totals.matches", total);
        data.set("totals.survivor-wins", survivorWins);
        data.set("totals.mother-wins", motherWins);
        data.set("totals.draws", total - survivorWins - motherWins);
        data.set("totals.average-seconds", total == 0 ? 0 : (int) (totalDuration / total));
        save();

        return record;
    }

    // ---------------------------------------------------------------- 查询

    /** 最近 n 局的记录（新的在前） */
    public List<Map<?, ?>> recent(int count) {
        List<Map<?, ?>> matches = data.getMapList("matches");
        List<Map<?, ?>> result = new ArrayList<>();
        for (int i = matches.size() - 1; i >= 0 && result.size() < count; i--) {
            result.add(matches.get(i));
        }
        return result;
    }

    public int getTotalMatches() {
        return data.getInt("totals.matches", 0);
    }

    public int getSurvivorWins() {
        return data.getInt("totals.survivor-wins", 0);
    }

    public int getMotherWins() {
        return data.getInt("totals.mother-wins", 0);
    }

    /** 平局局数（时间到） */
    public int getDraws() {
        return data.getInt("totals.draws", 0);
    }

    public int getAverageSeconds() {
        return data.getInt("totals.average-seconds", 0);
    }

    /** 逃生者胜率（0-100，整数）；没有对局时返回 -1 */
    public int getSurvivorWinRate() {
        int total = getTotalMatches();
        if (total <= 0) return -1;
        return (int) Math.round(getSurvivorWins() * 100.0 / total);
    }

    public String getFileName() {
        return file.getName();
    }
}
