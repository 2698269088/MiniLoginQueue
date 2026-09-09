package top.mcocet.miniloginqueue;

import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.miniloginqueue.auth.AuthMeCompatManager;
import top.mcocet.miniloginqueue.bungee.BungeeMessenger;
import top.mcocet.miniloginqueue.command.MiniLoginQueueCommand;
import top.mcocet.miniloginqueue.gui.ServerSelectorMenu;
import top.mcocet.miniloginqueue.listener.QueueAreaListener;
import top.mcocet.miniloginqueue.listener.QueueItemListener;
import top.mcocet.miniloginqueue.listener.RestrictionListener;
import top.mcocet.miniloginqueue.queue.QueueManager;
import top.mcocet.miniloginqueue.util.LanguageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MiniLoginQueue - 轻量版多服务器登录排队插件
 * <p>
 * 部署在 BungeeCord 代理下的登录排队服（需在 spigot.yml 开启 bungeecord: true）。
 * 仅依赖 BungeeCord 原生通道（PlayerCount / ServerIP / Connect 等）即可工作：
 * - 为每个配置的目标服务器维护独立排队队列（优先级排序）
 * - 周期查询各服务器在线人数，负载低于阈值时按序放行
 * - 多服务器模式通过 GUI 菜单选择目标服务器
 * - 可选集成 AuthMe：要求玩家先登录（AuthMe）才能加入排队队列
 */
public final class MiniLoginQueue extends JavaPlugin {

    private LanguageManager languageManager;
    private AuthMeCompatManager authMeCompatManager;
    private BungeeMessenger messenger;
    private QueueManager queueManager;
    private ServerSelectorMenu serverSelectorMenu;
    private QueueItemListener queueItemListener;
    private RestrictionListener restrictionListener;
    private QueueAreaListener queueAreaListener;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.debug = getConfig().getBoolean("debug", false);
        this.languageManager = new LanguageManager(this);

        // 服务器列表配置缓存（首次解析并校验）
        List<String> servers = getConfiguredServers();
        if (servers.isEmpty()) {
            getLogger().warning("未配置任何目标服务器（servers），请在 config.yml 中配置！");
        } else {
            getLogger().info("已配置目标服务器: " + String.join(", ", servers));
        }

        // AuthMe 登录验证（可选软依赖，反射集成：编译期不引用 AuthMe 类，未安装自动跳过）
        this.authMeCompatManager = new AuthMeCompatManager(this);

        // BungeeCord 原生通道通信
        this.messenger = new BungeeMessenger(this);

        // 排队核心
        this.queueManager = new QueueManager(this, messenger, authMeCompatManager);
        getServer().getPluginManager().registerEvents(queueManager, this);

        // 等待区限制（移动/交互/容器等）
        this.restrictionListener = new RestrictionListener(this, queueManager);
        getServer().getPluginManager().registerEvents(restrictionListener, this);

        // 排队区域（活动范围限制 + 区域内方块/伤害保护）
        this.queueAreaListener = new QueueAreaListener(this, restrictionListener);
        getServer().getPluginManager().registerEvents(queueAreaListener, this);

        // 服务器选择菜单
        this.serverSelectorMenu = new ServerSelectorMenu(this, queueManager);
        getServer().getPluginManager().registerEvents(serverSelectorMenu, this);

        // “加入游戏”按钮物品（手动入队模式）
        this.queueItemListener = new QueueItemListener(this, queueManager);
        getServer().getPluginManager().registerEvents(queueItemListener, this);

        // 命令
        MiniLoginQueueCommand commandExecutor = new MiniLoginQueueCommand(this, queueManager);
        getCommand("mlq").setExecutor(commandExecutor);
        getCommand("mlq").setTabCompleter(commandExecutor);
        getCommand("join").setExecutor(commandExecutor);
        getCommand("join").setTabCompleter(commandExecutor);

        // 周期刷新服务器状态并尝试放行队列
        startRefreshTask();

        // 显示启用信息
        boolean selectorEnabled = isSelectorEnabled();
        getLogger().info(languageManager.getLogMessage(selectorEnabled ? "selector-mode-enabled" : "single-mode-enabled"));
        if (selectorEnabled) {
            getLogger().info(languageManager.getLogMessage("selector-mode-hint", "count", String.valueOf(getConfiguredServers().size())));
        }

        sayLog();
        getLogger().info(languageManager.getLogMessage("plugin-enabled"));
    }

    @Override
    public void onDisable() {
        if (messenger != null) {
            messenger.shutdown();
        }
        getLogger().info(languageManager != null ? languageManager.getLogMessage("plugin-disabled") : "MiniLoginQueue disabled");
    }

    private void startRefreshTask() {
        long interval = Math.max(1, Math.min(60, getConfig().getLong("queue.refresh-interval", 3)));
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isEnabled()) {
                    return;
                }
                // 请求各服务器在线人数；响应到达时自动触发队列处理与菜单刷新
                messenger.refresh();
                queueManager.processQueue();
                // 轮询检测已通过 AuthMe 登录的待认证玩家（无等待玩家时零开销）
                queueManager.tickPendingAuthJoin();
                serverSelectorMenu.updateOpenMenus();
            }
        }.runTaskTimer(this, 40L, interval * 20L);
    }

    private void sayLog() {
        getLogger().info(ChatColor.AQUA + " __  __ _       _     " + ChatColor.YELLOW + "  __");
        getLogger().info(ChatColor.AQUA + "|  \\/  (_)_ __ (_)_  " + ChatColor.YELLOW + " |_ \\");
        getLogger().info(ChatColor.AQUA + "| |\\/| | | '_ \\| \\ \\ " + ChatColor.YELLOW + "  / /");
        getLogger().info(ChatColor.AQUA + "|_|  |_|_|_| |_|_|\\_\\" + ChatColor.YELLOW + " |_|");
    }

    /**
     * 收到某个服务器的 PlayerCount 响应回调（主线程）
     */
    public void onServerStatusUpdated(String server) {
        queueManager.onServerStatusUpdated();
        serverSelectorMenu.updateOpenMenus();
    }

    // ==================== 服务器配置解析 ====================

    /**
     * 获取配置的所有目标服务器名（按配置顺序，去重）
     */
    public List<String> getConfiguredServers() {
        List<String> servers = new ArrayList<>();
        List<Map<?, ?>> serverList = getConfig().getMapList("servers");
        for (Map<?, ?> map : serverList) {
            Object nameObj = map.get("name");
            if (nameObj == null || String.valueOf(nameObj).trim().isEmpty()) {
                continue;
            }
            String name = String.valueOf(nameObj).trim();
            if (!servers.contains(name)) {
                servers.add(name);
            }
        }
        // 配置为空时回退到默认主服务器名
        if (servers.isEmpty()) {
            servers.add("main");
        }
        return servers;
    }

    /**
     * 判断服务器是否在配置列表中
     */
    public boolean isServerConfigured(String serverName) {
        if (serverName == null) {
            return false;
        }
        return getConfiguredServers().contains(serverName);
    }

    /**
     * 获取服务器在菜单中显示的名称（servers[].display-name，支持颜色代码），
     * 未配置或留空时返回内部名本身。
     */
    public String getServerDisplayName(String serverName) {
        List<Map<?, ?>> serverList = getConfig().getMapList("servers");
        for (Map<?, ?> map : serverList) {
            Object nameObj = map.get("name");
            if (nameObj != null && serverName.equals(String.valueOf(nameObj))) {
                Object displayObj = map.get("display-name");
                if (displayObj != null && !String.valueOf(displayObj).trim().isEmpty()) {
                    return String.valueOf(displayObj);
                }
                return serverName;
            }
        }
        return serverName;
    }

    /**
     * 默认目标服务器（列表第一项）
     */
    public String getDefaultServerName() {
        return getConfiguredServers().get(0);
    }

    /**
     * 是否启用多服务器选择菜单（配置多个服务器且菜单开启时启用）
     */
    public boolean isSelectorEnabled() {
        return getConfig().getBoolean("server-selector.enabled", true)
                && getConfiguredServers().size() > 1;
    }

    // ==================== 重载 ====================

    /**
     * 重载配置文件并刷新各组件
     */
    public void reloadAll() {
        reloadConfig();
        saveDefaultConfig();

        if (languageManager != null) {
            languageManager.reload();
        }
        if (authMeCompatManager != null) {
            authMeCompatManager.loadConfig();
        }
        if (messenger != null) {
            messenger.loadConfig();
        }
        if (queueManager != null) {
            queueManager.loadConfig();
            queueManager.reloadPriority();
        }
        if (serverSelectorMenu != null) {
            serverSelectorMenu.loadConfig();
        }
        if (queueItemListener != null) {
            queueItemListener.loadConfig();
        }
        if (restrictionListener != null) {
            restrictionListener.loadConfig();
        }
        if (queueAreaListener != null) {
            queueAreaListener.loadConfig();
        }
    }

    // ==================== 访问器 ====================

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
        getConfig().set("debug", debug);
        saveConfig();
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public AuthMeCompatManager getAuthMeCompatManager() {
        return authMeCompatManager;
    }

    public BungeeMessenger getMessenger() {
        return messenger;
    }

    public QueueManager getQueueManager() {
        return queueManager;
    }

    public ServerSelectorMenu getServerSelectorMenu() {
        return serverSelectorMenu;
    }

    public QueueItemListener getQueueItemListener() {
        return queueItemListener;
    }

    public RestrictionListener getRestrictionListener() {
        return restrictionListener;
    }

    public QueueAreaListener getQueueAreaListener() {
        return queueAreaListener;
    }
}
