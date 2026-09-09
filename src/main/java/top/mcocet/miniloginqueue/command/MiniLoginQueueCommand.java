package top.mcocet.miniloginqueue.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import top.mcocet.miniloginqueue.MiniLoginQueue;
import top.mcocet.miniloginqueue.bungee.BungeeMessenger;
import top.mcocet.miniloginqueue.queue.QueueManager;
import top.mcocet.miniloginqueue.util.LanguageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MiniLoginQueue 主命令
 * /mlq join [服务器] / leave / menu - 玩家指令
 * /mlq skip <玩家> [服务器] / send <玩家> <服务器> / where <玩家> / ip <玩家>
 * /mlq list [服务器] / status / refresh / pause / resume / reload - 管理指令
 * /join 为加入队列的快捷命令
 */
public class MiniLoginQueueCommand implements CommandExecutor, TabCompleter {

    private final MiniLoginQueue plugin;
    private final QueueManager queueManager;
    private final LanguageManager languageManager;

    public MiniLoginQueueCommand(MiniLoginQueue plugin, QueueManager queueManager) {
        this.plugin = plugin;
        this.queueManager = queueManager;
        this.languageManager = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /join 快捷命令等价于 /mlq join
        if ("join".equals(command.getName())) {
            return handleJoin(sender, args);
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "join":
                return handleJoin(sender, shift(args));
            case "leave":
                return handleLeave(sender);
            case "menu":
                return handleMenu(sender);
            case "skip":
                return handleSkip(sender, shift(args));
            case "list":
                return handleList(sender, shift(args));
            case "status":
                return handleStatus(sender);
            case "refresh":
                return handleRefresh(sender);
            case "pause":
                return handlePause(sender);
            case "resume":
                return handleResume(sender);
            case "reload":
                return handleReload(sender);
            case "send":
                return handleSend(sender, shift(args));
            case "where":
                return handleWhere(sender, shift(args));
            case "ip":
                return handleIp(sender, shift(args));
            case "help":
                sendHelp(sender);
                return true;
            default:
                sender.sendMessage(languageManager.getMessage("unknown-subcommand"));
                return true;
        }
    }

    // ==================== 玩家指令 ====================

    private boolean handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(languageManager.getMessage("command-player-only"));
            return true;
        }
        Player player = (Player) sender;

        // 指定了目标服务器：直接加入该服务器队列
        if (args.length > 0 && !args[0].isEmpty()) {
            queueManager.joinServer(player, args[0]);
            return true;
        }
        // 未指定：多服务器菜单模式弹出选择菜单，否则加入默认服务器
        queueManager.requestJoin(player);
        return true;
    }

    private boolean handleLeave(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(languageManager.getMessage("command-player-only"));
            return true;
        }
        queueManager.leaveQueue((Player) sender);
        return true;
    }

    private boolean handleMenu(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(languageManager.getMessage("command-player-only"));
            return true;
        }
        Player player = (Player) sender;
        if (!plugin.isSelectorEnabled()) {
            player.sendMessage(languageManager.getMessage("menu-disabled"));
            return true;
        }
        plugin.getServerSelectorMenu().open(player);
        return true;
    }

    // ==================== 管理指令 ====================

    private boolean handleSkip(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miniloginqueue.admin.skip")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(languageManager.getMessage("player-offline", "player", args[0]));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(languageManager.getMessage("console-specify-player"));
            return true;
        }

        String serverName = args.length >= 2 ? args[1] : null;
        if (serverName != null && !plugin.isServerConfigured(serverName)) {
            sender.sendMessage(languageManager.getMessage("unknown-server", "server", serverName));
            return true;
        }

        String actualServer = queueManager.allowPlayerDirectly(target, serverName);
        if (actualServer == null) {
            return true;
        }
        sender.sendMessage(languageManager.getMessage("skipped-player", "player", target.getName(), "server", actualServer));
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miniloginqueue.admin.list")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        String serverFilter = args.length >= 1 && !args[0].isEmpty() ? args[0] : null;
        if (serverFilter != null && !plugin.isServerConfigured(serverFilter)) {
            sender.sendMessage(languageManager.getMessage("unknown-server", "server", serverFilter));
            return true;
        }

        List<String> servers = serverFilter != null
                ? Collections.singletonList(serverFilter)
                : plugin.getConfiguredServers();

        sender.sendMessage(languageManager.getMessage("queue-list-header"));
        boolean anyPlayer = false;
        for (String serverName : servers) {
            List<String> names = queueManager.getQueuePlayerNames(serverName);
            sender.sendMessage(languageManager.getMessage("queue-list-server", "server", serverName));
            if (names.isEmpty()) {
                sender.sendMessage(languageManager.getMessage("queue-list-empty-server", "server", serverName));
                continue;
            }
            anyPlayer = true;
            for (int i = 0; i < names.size(); i++) {
                sender.sendMessage(languageManager.getMessage("queue-list-entry",
                        "position", String.valueOf(i + 1),
                        "name", names.get(i)));
            }
        }
        if (!anyPlayer) {
            sender.sendMessage(languageManager.getMessage("queue-list-empty"));
        }
        sender.sendMessage(languageManager.getMessage("queue-list-footer"));
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("miniloginqueue.admin.status")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        sender.sendMessage(languageManager.getMessage("status-header"));
        BungeeMessenger messenger = plugin.getMessenger();

        int totalOnline = 0;
        int totalMax = 0;
        for (String serverName : plugin.getConfiguredServers()) {
            BungeeMessenger.ServerStatus status = messenger.getServerStatus(serverName);
            if (status == null) {
                sender.sendMessage(languageManager.getMessage("server-line-unknown", "server", serverName));
                continue;
            }
            if (messenger.isServerOnline(serverName)) {
                totalOnline += messenger.getOnlinePlayers(serverName);
                totalMax += messenger.getMaxOnline(serverName);
                sender.sendMessage(languageManager.getMessage("server-line-online",
                        "server", serverName,
                        "online", String.valueOf(messenger.getOnlinePlayers(serverName)),
                        "max", String.valueOf(messenger.getMaxOnline(serverName)),
                        "ratio", String.format("%.1f", messenger.getLoadRatio(serverName) * 100)));
                // MSLP 直连探测明细（仅当探测成功时显示）
                if (status.isMslpSuccess()) {
                    sender.sendMessage(languageManager.getMessage("server-line-latency",
                            "latency", String.valueOf(status.getLatencyMs()),
                            "version", status.getVersionName() != null ? status.getVersionName() : "-"));
                }
            } else {
                sender.sendMessage(languageManager.getMessage("server-line-offline", "server", serverName));
            }
            // 直连地址（BC ServerIP 解析结果），在线与否均展示，便于排查
            String address = messenger.getServerAddress(serverName);
            if (address != null) {
                sender.sendMessage(languageManager.getMessage("server-line-address", "address", address));
            }
        }

        if (totalMax > 0) {
            sender.sendMessage(languageManager.getMessage("total-online",
                    "online", String.valueOf(totalOnline),
                    "max", String.valueOf(totalMax)));
        }
        sender.sendMessage(languageManager.getMessage("status-threshold",
                "ratio", String.format("%.1f", plugin.getConfig().getDouble("queue.threshold", 0.8) * 100)));

        Map<String, Integer> queueSizes = queueManager.getServerQueueSizes();
        for (String serverName : plugin.getConfiguredServers()) {
            sender.sendMessage(languageManager.getMessage("status-queue-line",
                    "server", serverName,
                    "size", String.valueOf(queueSizes.getOrDefault(serverName, 0))));
        }
        sender.sendMessage(languageManager.getMessage("status-queue-total",
                "size", String.valueOf(queueManager.getTotalQueueSize())));
        sender.sendMessage(languageManager.getMessage(queueManager.isQueuePaused() ? "queue-state-paused" : "queue-state-normal"));
        sender.sendMessage(languageManager.getMessage("status-footer"));
        return true;
    }

    private boolean handleRefresh(CommandSender sender) {
        if (!sender.hasPermission("miniloginqueue.admin.status")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }
        plugin.getMessenger().refresh();
        sender.sendMessage(languageManager.getMessage("refreshed"));
        return true;
    }

    private boolean handlePause(CommandSender sender) {
        if (!sender.hasPermission("miniloginqueue.admin.pause")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }
        if (queueManager.isQueuePaused()) {
            sender.sendMessage(languageManager.getMessage("queue-already-paused"));
            return true;
        }
        queueManager.pauseQueue();
        sender.sendMessage(languageManager.getMessage("queue-paused"));
        return true;
    }

    private boolean handleResume(CommandSender sender) {
        if (!sender.hasPermission("miniloginqueue.admin.pause")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }
        if (!queueManager.isQueuePaused()) {
            sender.sendMessage(languageManager.getMessage("queue-already-running"));
            return true;
        }
        queueManager.resumeQueue();
        sender.sendMessage(languageManager.getMessage("queue-resumed-admin"));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("miniloginqueue.admin.reload")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }
        plugin.reloadAll();
        sender.sendMessage(languageManager.getMessage("reloaded"));
        return true;
    }

    private boolean handleSend(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miniloginqueue.admin.send")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(languageManager.getMessage("help-send"));
            return true;
        }
        String serverName = args[1];
        if (!plugin.isServerConfigured(serverName)) {
            sender.sendMessage(languageManager.getMessage("unknown-server", "server", serverName));
            return true;
        }
        // ConnectOther 无响应回执，只能乐观提示；目标玩家是否在线由代理自行判定
        boolean sent = plugin.getMessenger().connectOther(args[0], serverName);
        if (sent) {
            sender.sendMessage(languageManager.getMessage("send-success", "player", args[0], "server", serverName));
        } else {
            sender.sendMessage(languageManager.getMessage("carrier-needed"));
        }
        return true;
    }

    private boolean handleWhere(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miniloginqueue.admin.where")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(languageManager.getMessage("help-where"));
            return true;
        }
        if (!plugin.getMessenger().lookupPlayerServer(args[0], sender)) {
            sender.sendMessage(languageManager.getMessage("carrier-needed"));
        }
        return true;
    }

    private boolean handleIp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miniloginqueue.admin.ip")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(languageManager.getMessage("help-ip"));
            return true;
        }
        if (!plugin.getMessenger().lookupPlayerIp(args[0], sender)) {
            sender.sendMessage(languageManager.getMessage("carrier-needed"));
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(languageManager.getMessage("help-header"));
        sender.sendMessage(languageManager.getMessage("help-join"));
        sender.sendMessage(languageManager.getMessage("help-leave"));
        if (plugin.isSelectorEnabled()) {
            sender.sendMessage(languageManager.getMessage("help-menu"));
        }
        sender.sendMessage(languageManager.getMessage("help-skip"));
        sender.sendMessage(languageManager.getMessage("help-send"));
        sender.sendMessage(languageManager.getMessage("help-where"));
        sender.sendMessage(languageManager.getMessage("help-ip"));
        sender.sendMessage(languageManager.getMessage("help-list"));
        sender.sendMessage(languageManager.getMessage("help-status"));
        sender.sendMessage(languageManager.getMessage("help-refresh"));
        sender.sendMessage(languageManager.getMessage("help-pause"));
        sender.sendMessage(languageManager.getMessage("help-resume"));
        sender.sendMessage(languageManager.getMessage("help-reload"));
        sender.sendMessage(languageManager.getMessage("help-help"));
        sender.sendMessage(languageManager.getMessage("help-footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // /join [服务器]
        if ("join".equals(command.getName())) {
            if (args.length == 1) {
                return filterServers(args[0]);
            }
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("join", "leave", "skip", "send", "where", "ip", "list", "status", "refresh", "pause", "resume", "reload", "help"));
            if (plugin.isSelectorEnabled()) {
                subs.add(0, "menu");
            }
            return filter(args[0], subs);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if ("join".equals(sub)) {
                return filterServers(args[1]);
            }
            if ("list".equals(sub) && sender.hasPermission("miniloginqueue.admin.list")) {
                return filterServers(args[1]);
            }
            if ("skip".equals(sub) && sender.hasPermission("miniloginqueue.admin.skip")) {
                return filterOnlinePlayers(args[1]);
            }
            if ("send".equals(sub) && sender.hasPermission("miniloginqueue.admin.send")) {
                return filterOnlinePlayers(args[1]);
            }
            if ("where".equals(sub) && sender.hasPermission("miniloginqueue.admin.where")) {
                return filterOnlinePlayers(args[1]);
            }
            if ("ip".equals(sub) && sender.hasPermission("miniloginqueue.admin.ip")) {
                return filterOnlinePlayers(args[1]);
            }
        }

        if (args.length == 3 && "send".equalsIgnoreCase(args[0])
                && sender.hasPermission("miniloginqueue.admin.send")) {
            return filterServers(args[2]);
        }
        return Collections.emptyList();
    }

    private String[] shift(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private List<String> filterServers(String prefix) {
        return filter(prefix, plugin.getConfiguredServers());
    }

    private List<String> filterOnlinePlayers(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase().startsWith(prefix.toLowerCase())) {
                names.add(player.getName());
            }
        }
        return names;
    }

    private List<String> filter(String prefix, List<String> options) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix.toLowerCase())) {
                result.add(option);
            }
        }
        return result;
    }
}
