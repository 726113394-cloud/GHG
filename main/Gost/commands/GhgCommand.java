package io.Sriptirc_wp_1258.gost.commands;

import io.Sriptirc_wp_1258.gost.GHG;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class GhgCommand implements CommandExecutor, TabCompleter {
    
    private final GHG plugin;
    
    public GhgCommand(GHG plugin) {
        this.plugin = plugin;
    }
    
    /** 按玩家自己的语言发消息；控制台用服务器默认语言 */
    private void send(CommandSender sender, String key, Object... args) {
        Player player = sender instanceof Player ? (Player) sender : null;
        sender.sendMessage(plugin.getLanguageManager().getMessage(player, key, args));
    }
    
    /** 多行文本按行拆开发，客户端才认换行 */
    private void sendBlock(CommandSender sender, String key) {
        Player player = sender instanceof Player ? (Player) sender : null;
        for (String line : plugin.getLanguageManager().getMessage(player, key).split("\n")) {
            sender.sendMessage(line);
        }
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // 不带参数就开图形菜单：玩家不用记命令，控制台还是看文字帮助
            openMenuOrHelp(sender);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "spectate":
            case "spec":
            case "watch":
                if (!requirePlayer(sender)) return true;
                if (!sender.hasPermission("ghg.player")) {
                    send(sender, "error.no-permission");
                    return true;
                }
                handleSpectate((Player) sender);
                break;
            case "leave":
                if (!requirePlayer(sender)) return true;
                if (!sender.hasPermission("ghg.player")) {
                    send(sender, "error.no-permission");
                    return true;
                }
                handleLeave((Player) sender);
                break;
            case "join":
                if (!requirePlayer(sender)) return true;
                if (!sender.hasPermission("ghg.join")) {
                    send(sender, "command.join.no-permission");
                    return true;
                }
                handleJoin((Player) sender);
                break;
            case "info":
                if (!requirePlayer(sender)) return true;
                if (!sender.hasPermission("ghg.player")) {
                    send(sender, "error.no-permission");
                    return true;
                }
                handleInfo((Player) sender);
                break;
            case "kind":
                if (!requirePlayer(sender)) return true;
                if (!plugin.getHunterGameManager().openKindMenu((Player) sender)) {
                    send(sender, "hunter.cmd.kind-too-late");
                }
                break;
            case "role":
                if (!requirePlayer(sender)) return true;
                if (!plugin.getHunterGameManager().openRoleMenu((Player) sender)) {
                    send(sender, "hunter.role.too-late");
                }
                break;
            case "lang":
                handleLang(sender, args);
                break;
            case "menu":
            case "ui":
                openMenuOrHelp(sender);
                break;
            case "help":
                sendHelp(sender);
                break;
            default:
                sendHelp(sender);
                break;
        }
        
        return true;
    }
    
    /** 仅玩家可用的子命令，控制台执行时给出提示 */
    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) return true;
        send(sender, "error.player-only");
        return false;
    }
    
    
    /** 排队开局：人凑够自动开，凑不够到点自动取消 */
    private void handleJoin(Player player) {
        plugin.getQueueManager().join(player);
    }

    /**
     * 观战开关：同一条指令进出都用它。
     *
     * <p>对局已开始时 `/ghg join` 会被挡回来，这里给一个「进得去、看得见」的出口。</p>
     */
    private void handleSpectate(Player player) {
        io.Sriptirc_wp_1258.gost.managers.HunterGameManager game = plugin.getHunterGameManager();
        if (game.leaveSpectate(player, false)) return;
        game.joinSpectate(player);
    }

    private void handleLeave(Player player) {
        if (!player.hasPermission("ghg.leave")) {
            send(player, "command.leave.no-permission");
            return;
        }
        // 在排队就只退队，别把「不在对局里」当成失败
        if (plugin.getQueueManager().leave(player, false)) {
            return;
        }
        // 在观战：退出观战即可（还原模式与位置）
        if (plugin.getHunterGameManager().leaveSpectate(player, false)) {
            return;
        }
        /* 已被淘汰的人提前退出：先按当前贡献分结算一份奖励，再正常离场。
           没被淘汰（还在场上）的人走不到这一步结算，避免「打到一半跑路还拿钱」。 */
        plugin.getHunterGameManager().settleEarlyExit(player);
        if (plugin.getPlayerManager().leaveGame(player)) {
            send(player, "command.leave.success");
            return;
        }
        send(player, "command.leave.not-in");
    }
    
    private void handleInfo(Player player) {
        io.Sriptirc_wp_1258.gost.managers.HunterGameManager game = plugin.getHunterGameManager();
        send(player, "command.info.header");
        send(player, "command.info.state", plugin.getLanguageManager().getMessage(player,
            game.isRunning() ? "command.info.state-running"
                : game.isPreparing() ? "command.info.state-waiting" : "command.info.state-idle"));

        if (plugin.getQueueManager().isQueued(player.getUniqueId())) {
            for (String line : plugin.getQueueManager().describe()) {
                player.sendMessage(line);
            }
        }
        if (game.isRunning() || game.isPreparing()) {
            send(player, "command.info.humans", plugin.getPlayerManager().getHumanPlayers().size());
            if (plugin.getEconomyManager().isEconomyEnabled()) {
                send(player, "command.info.prize-pool", plugin.getEconomyManager().getPrizePool());
            }
        }
    }
    
    private void handleLang(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "ghg.help.lang-usage", plugin.getLanguageManager().listSupported());
            return;
        }
        
        String requested = args[1];
        if ("reset".equalsIgnoreCase(requested)) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                plugin.getLanguageManager().clearLanguage(player);
                send(player, "command.lang.reset", plugin.getLanguageManager().getCurrentLanguage());
            } else {
                send(sender, "ghg.help.lang-usage", plugin.getLanguageManager().listSupported());
            }
            return;
        }
        
        String locale = plugin.getLanguageManager().resolveLocale(requested);
        if (locale == null) {
            send(sender, "command.lang.invalid", requested, plugin.getLanguageManager().listSupported());
            return;
        }
        
        if (sender instanceof Player) {
            Player player = (Player) sender;
            plugin.getLanguageManager().setLanguage(player, locale);
            send(player, "command.lang.set", locale);
            return;
        }
        
        // 控制台无法单独设语言，提示用法即可
        send(sender, "ghg.help.lang-usage", plugin.getLanguageManager().listSupported());
    }
    
    /** 玩家开菜单，控制台（或没权限）退回文字帮助 */
    private void openMenuOrHelp(CommandSender sender) {
        if (sender instanceof Player && sender.hasPermission("ghg.use")
                && plugin.getMenuManager() != null) {
            plugin.getMenuManager().openMain((Player) sender);
            return;
        }
        sendHelp(sender);
    }

    private void sendHelp(CommandSender sender) {
        // GHG 自己的玩家命令总览（整块文本，一次列出）
        sendBlock(sender, "ghg.help.player");
    }
    
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.add("menu");
            completions.add("join");
            completions.add("spectate");
            completions.add("leave");
            completions.add("info");
            completions.add("kind");
            completions.add("role");
            completions.add("lang");
            completions.add("help");
        } else if (args.length == 2 && "lang".equalsIgnoreCase(args[0])) {
            for (String locale : io.Sriptirc_wp_1258.gost.managers.LanguageManager.SUPPORTED) {
                completions.add(locale);
            }
        }
        
        return completions;
    }
}
