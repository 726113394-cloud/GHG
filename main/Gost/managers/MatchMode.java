package io.Sriptirc_wp_1258.gost.managers;

/**
 * 当前对局模式。
 *
 * <p>这是 Gost 与 GHG 两套玩法共存的关键：所有监听器入口先判模式再处理，
 * 否则两套规则的监听器会互相打架（敲西瓜被「禁止破坏方块」拦、感染逻辑在 PVE 里乱触发）。</p>
 */
public enum MatchMode {

    /** 没有对局在跑 */
    IDLE,

    /** Gost 本体：PVP 鬼抓人（神圣守护 / 救赎者 / 猎魔人） */
    CLASSIC,

    /** GHG：PVE 逃亡，AI 母体猎杀，破法阵撤离 */
    HUNTER
}
