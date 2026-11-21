package io.Sriptirc_wp_1258.gost.managers;

import org.bukkit.entity.Player;

/**
 * 单件道具的使用效果。
 *
 * <p>注册到 {@link ItemEffectRegistry} 之后，任何地方（右键监听、道具箱开箱、
 * 拾取即用、命令调试）都可以通过 {@code registry.use(...)} 单点触发，
 * 不需要复制效果逻辑。</p>
 */
public interface ItemEffect {

    /** 对应的 {@link ItemIds} 稳定 ID */
    String itemId();

    /**
     * 使用冷却（秒）。返回 0 表示无冷却。
     * 冷却提示统一用语言键 {@code item.<id>-cooldown}。
     */
    default int cooldownSeconds() {
        return 0;
    }

    /**
     * 这种点击方式能不能触发本效果。
     *
     * <p>⚠️ **默认只认右键**。原来默认是左右键都触发，于是「敲道具箱时多按的那一下左键」
     * 会把传送珍珠扔出去、把控魂术按掉、把漂浮药水喝掉 —— 玩家只是想继续砸箱子。
     * 需要左键的（准备阶段的选择面板）自己覆写成 {@code return true;}。</p>
     */
    default boolean accepts(boolean rightClick, boolean leftClick) {
        return rightClick;
    }

    /**
     * 执行效果。
     *
     * @return true 表示这次使用要开始计冷却
     */
    boolean apply(Player player, ItemUseContext ctx);
}
