package top.mcocet.miniloginqueue.bungee;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import top.mcocet.miniloginqueue.MiniLoginQueue;
import top.mcocet.miniloginqueue.util.MslpPinger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BungeeCord 原生通道通信与服务器状态中心
 * <p>
 * 使用 BungeeCord 代理自带的原生子通道（无需任何 BC/VC 扩展插件）：
 * - "PlayerCount"    查询目标服务器在线人数（BC 通道兜底数据源）
 * - "ServerIP"       解析目标服务器在内网/公网中的 IP 与游戏端口
 * - "Connect"        将玩家转移至目标服务器
 * - "ConnectOther"   将任意玩家（可在其他服务器上）转移至目标服务器
 * - "GetPlayerServer" 按名查询玩家当前所在服务器
 * - "IPOther"        按名查询玩家的真实 IP（代理视角）
 * <p>
 * 服务器在线状态采用双数据源：
 * 1. MSLP 直连探测（优先）：通过 ServerIP 解析出的地址，登录服直接以 Minecraft
 *    服务器列表协议探测子服，取得真实在线人数/最大容量/版本/延迟；
 * 2. BC PlayerCount（兜底）：ServerIP 尚未解析或直连不可用时降级使用，
 *    容量回退到本地配置 queue.max-online。
 * <p>
 * 注意：需要登录服在 spigot.yml 中开启 bungeecord: true，
 * 且代理端已配置对应的后端服务器。Velocity 需开启 bungee-plugin-message-channel。
 * MSLP 直连要求登录服与子服端口 TCP 互通；若网络隔离请关闭 queue.mslp-enabled。
 */
public class BungeeMessenger implements PluginMessageListener {

    /** BungeeCord 原生通道（Bukkit 内置，无需在 plugin.yml 声明） */
    public static final String CHANNEL_BUNGEE_CORD = "BungeeCord";

    private final MiniLoginQueue plugin;
    private final Map<String, ServerStatus> serverStatusCache = new HashMap<>();

    /**
     * 玩家查询等待表：key = "子通道名:玩家名小写"，value = 等待结果的命令发送者。
     * 原生通道的查询响应不含请求标识，只能按玩家名区分，故以玩家名聚合等待者，
     * 响应到达后统一通知并清除（一次性）。
     */
    private final Map<String, Set<CommandSender>> pendingLookups = new HashMap<>();

    // 直连探测配置
    private boolean mslpEnabled;
    private int mslpTimeoutMs;

    public BungeeMessenger(MiniLoginQueue plugin) {
        this.plugin = plugin;
        loadConfig();

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_BUNGEE_CORD);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_BUNGEE_CORD, this);
    }

    public void shutdown() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_BUNGEE_CORD);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL_BUNGEE_CORD, this);
    }

    public void loadConfig() {
        this.mslpEnabled = plugin.getConfig().getBoolean("queue.mslp-enabled", true);
        this.mslpTimeoutMs = Math.max(500, plugin.getConfig().getInt("queue.mslp-timeout", 2000));
    }

    /**
     * 向代理请求所有已配置服务器的在线人数与地址
     * 需要至少一名在线玩家作为消息载体；无人在线时跳过（此时也没有玩家需要排队）
     */
    public void refresh() {
        List<String> servers = plugin.getConfiguredServers();
        if (servers.isEmpty()) {
            return;
        }
        Player carrier = getAnyOnlinePlayer();
        if (carrier == null) {
            return;
        }
        for (String server : servers) {
            request(carrier, "PlayerCount", server);
            request(carrier, "ServerIP", server);
        }
    }

    /**
     * 将玩家通过代理转移至目标服务器
     */
    public void connectPlayerToServer(Player player, String server) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(plugin, CHANNEL_BUNGEE_CORD, bytes.toByteArray());
            plugin.getLogger().info("[" + player.getName() + "] -> " + server);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to send Connect request for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * 通过 ConnectOther 子通道将任意玩家转移至指定服务器（无需玩家在登录服）
     * 该子通道无响应回执，返回 false 仅表示无在线载体无法发送。
     */
    public boolean connectOther(String playerName, String server) {
        Player carrier = getAnyOnlinePlayer();
        if (carrier == null) {
            return false;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("ConnectOther");
            out.writeUTF(playerName);
            out.writeUTF(server);
            carrier.sendPluginMessage(plugin, CHANNEL_BUNGEE_CORD, bytes.toByteArray());
            plugin.getLogger().info("ConnectOther: " + playerName + " -> " + server);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to send ConnectOther request for " + playerName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 通过 GetPlayerServer 子通道查询玩家当前所在服务器，结果异步送达 requester
     * 返回 false 表示无在线载体无法发送
     */
    public boolean lookupPlayerServer(String playerName, CommandSender requester) {
        return registerLookup("GetPlayerServer", playerName, requester);
    }

    /**
     * 通过 IPOther 子通道查询玩家的真实 IP，结果异步送达 requester
     * 返回 false 表示无在线载体无法发送
     */
    public boolean lookupPlayerIp(String playerName, CommandSender requester) {
        return registerLookup("IPOther", playerName, requester);
    }

    /**
     * 注册查询等待者并发送请求；目标玩家不存在时代理不返回响应，
     * 因此附加超时任务清理等待表并提示。
     */
    private boolean registerLookup(String subchannel, String playerName, CommandSender requester) {
        Player carrier = getAnyOnlinePlayer();
        if (carrier == null) {
            return false;
        }
        String key = subchannel + ":" + playerName.toLowerCase();
        pendingLookups.computeIfAbsent(key, k -> new HashSet<>()).add(requester);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(subchannel);
            out.writeUTF(playerName);
            carrier.sendPluginMessage(plugin, CHANNEL_BUNGEE_CORD, bytes.toByteArray());
        } catch (IOException e) {
            pendingLookups.remove(key);
            plugin.getLogger().warning("Failed to send " + subchannel + " request for " + playerName + ": " + e.getMessage());
            return false;
        }
        // 5 秒超时兜底（如目标玩家不在线导致代理无响应）
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Set<CommandSender> waiters = pendingLookups.remove(key);
            if (waiters != null) {
                for (CommandSender waiter : waiters) {
                    waiter.sendMessage(plugin.getLanguageManager().getMessage("lookup-timeout", "player", playerName));
                }
            }
        }, 100L);
        return true;
    }

    /** 取出某查询的全部等待者并清空（一次性通知） */
    private Set<CommandSender> drainWaiters(String subchannel, String playerName) {
        return pendingLookups.remove(subchannel + ":" + playerName.toLowerCase());
    }

    private void request(Player carrier, String subchannel, String server) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(subchannel);
            out.writeUTF(server);
            carrier.sendPluginMessage(plugin, CHANNEL_BUNGEE_CORD, bytes.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to send " + subchannel + " request for " + server + ": " + e.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_BUNGEE_CORD.equals(channel)) {
            return;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            String subchannel = in.readUTF();
            if ("PlayerCount".equals(subchannel)) {
                String server = in.readUTF();
                int online = in.readInt();
                applyPlayerCount(server, online);
            } else if ("ServerIP".equals(subchannel)) {
                // 响应格式：服务器名 | IP | 端口（short）
                String server = in.readUTF();
                String ip = in.readUTF();
                int port = in.readUnsignedShort();
                applyServerAddress(server, ip, port);
            } else if ("GetPlayerServer".equals(subchannel)) {
                // 响应格式：服务器名回显 | 玩家名 | 所在服务器（不在线为空串）
                String name = in.readUTF();
                String locatedServer = in.readUTF();
                Set<CommandSender> waiters = drainWaiters("GetPlayerServer", name);
                if (waiters == null) {
                    return;
                }
                for (CommandSender waiter : waiters) {
                    if (locatedServer.isEmpty()) {
                        waiter.sendMessage(plugin.getLanguageManager().getMessage("where-offline", "player", name));
                    } else {
                        waiter.sendMessage(plugin.getLanguageManager().getMessage("where-result",
                                "player", name, "server", locatedServer));
                    }
                }
            } else if ("IPOther".equals(subchannel)) {
                // 响应格式：子通道回显 | 玩家名 | IP | 端口（int）
                String name = in.readUTF();
                String ip = in.readUTF();
                in.readInt(); // 客户端源端口，无展示意义
                Set<CommandSender> waiters = drainWaiters("IPOther", name);
                if (waiters == null) {
                    return;
                }
                for (CommandSender waiter : waiters) {
                    waiter.sendMessage(plugin.getLanguageManager().getMessage("ip-result",
                            "player", name, "ip", ip));
                }
            }
        } catch (IOException e) {
            // 非预期响应或数据不完整，直接忽略
        }
    }

    // ==================== 双源状态更新 ====================

    private ServerStatus getOrCreate(String server) {
        ServerStatus status = serverStatusCache.get(server);
        if (status == null) {
            status = new ServerStatus(server);
            serverStatusCache.put(server, status);
        }
        return status;
    }

    /** BC PlayerCount 响应落地 */
    private void applyPlayerCount(String server, int online) {
        ServerStatus status = getOrCreate(server);
        status.applyBcCount(online, System.currentTimeMillis());
        plugin.onServerStatusUpdated(server);
    }

    /** ServerIP 响应落地：缓存直连地址并触发 MSLP 异步探测 */
    private void applyServerAddress(String server, String ip, int port) {
        ServerStatus status = getOrCreate(server);
        status.applyAddress(ip, port);
        if (!mslpEnabled || status.isPingInFlight()) {
            return;
        }
        status.markPingStarted();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            MslpPinger.Result result = MslpPinger.ping(ip, port, mslpTimeoutMs);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                ServerStatus current = serverStatusCache.get(server);
                if (current == null) {
                    return;
                }
                current.applyMslpResult(result, System.currentTimeMillis());
                plugin.onServerStatusUpdated(server);
            });
        });
    }

    // ==================== 状态查询（组合数据源） ====================

    /**
     * 判断服务器是否在线
     * <p>
     * 直连探测启用时：
     * - 已完成过探测：以最近一次 MSLP 结果为准（成功=在线，失败=离线）
     * - 尚未完成首次探测：临时以 BC 通道响应为准（启动过渡期）
     * 直连探测关闭时：以 BC 通道响应为准（原逻辑）。
     */
    public boolean isServerOnline(String server) {
        ServerStatus status = serverStatusCache.get(server);
        if (status == null) {
            return false;
        }
        long timeout = getOfflineTimeoutMs();
        boolean bcFresh = status.isBcFresh(timeout);
        if (!mslpEnabled || !status.hasAddress()) {
            return bcFresh;
        }
        // 已有探测结论（数据新鲜或正在探测中）：以探测结果为准
        if (status.getLastPingTime() > 0 && (status.isMslpFresh(timeout) || status.isPingInFlight())) {
            return status.isMslpSuccess();
        }
        return bcFresh;
    }

    /**
     * 获取服务器当前在线人数：MSLP 结果新鲜时优先，否则回退 BC 通道数据
     */
    public int getOnlinePlayers(String server) {
        ServerStatus status = serverStatusCache.get(server);
        if (status == null) {
            return 0;
        }
        return hasActiveMslpData(status) ? status.getMslpOnlinePlayers() : status.getBcOnlinePlayers();
    }

    /**
     * 获取服务器容量：MSLP 直连探测到真实容量时优先，否则回退 queue.max-online
     */
    public int getMaxOnline(String server) {
        ServerStatus status = serverStatusCache.get(server);
        if (status != null && hasActiveMslpData(status) && status.getMslpMaxPlayers() > 0) {
            return status.getMslpMaxPlayers();
        }
        return Math.max(1, plugin.getConfig().getInt("queue.max-online", 50));
    }

    /**
     * 获取服务器负载（在线 / 容量），无容量数据时返回 1.0
     */
    public double getLoadRatio(String server) {
        int max = getMaxOnline(server);
        if (max <= 0) {
            return 1.0;
        }
        return (double) getOnlinePlayers(server) / max;
    }

    /** MSLP 数据是否处于活跃可用状态 */
    private boolean hasActiveMslpData(ServerStatus status) {
        if (!mslpEnabled || !status.hasAddress() || !status.isMslpSuccess()) {
            return false;
        }
        long timeout = getOfflineTimeoutMs();
        return status.isMslpFresh(timeout) || status.isPingInFlight();
    }

    /**
     * 获取服务器直连地址（"ip:port"），尚未解析返回 null
     */
    public String getServerAddress(String server) {
        ServerStatus status = serverStatusCache.get(server);
        if (status == null || !status.hasAddress()) {
            return null;
        }
        return status.getAddressHost() + ":" + status.getAddressPort();
    }

    /**
     * 获取指定服务器状态缓存（无缓存返回 null）
     */
    public ServerStatus getServerStatus(String server) {
        return serverStatusCache.get(server);
    }

    public Map<String, ServerStatus> getAllServerStatus() {
        return new HashMap<>(serverStatusCache);
    }

    /**
     * 状态新鲜度窗口：约 3 个刷新周期，容忍偶发丢包
     * 超时时间随刷新周期动态计算（重载配置后依然准确）
     */
    private long getOfflineTimeoutMs() {
        int refreshInterval = Math.max(1, Math.min(60, plugin.getConfig().getInt("queue.refresh-interval", 3)));
        return refreshInterval * 3L * 1000L;
    }

    private Player getAnyOnlinePlayer() {
        if (plugin.getServer().getOnlinePlayers().isEmpty()) {
            return null;
        }
        return plugin.getServer().getOnlinePlayers().iterator().next();
    }

    /**
     * 服务器状态缓存（双数据源）
     * <p>
     * BC 源：PlayerCount 通道响应（在线人数，仅证明服务器在代理中已配置）；
     * 地址源：ServerIP 通道响应（直连 IP 与端口）；
     * MSLP 源：登录服直连探测结果（真实在线/容量/版本/延迟）。
     */
    public static class ServerStatus {

        private final String serverName;

        // BC 源（PlayerCount）
        private int bcOnlinePlayers;
        private long bcLastUpdate;

        // 地址源（ServerIP）
        private String addressHost;
        private int addressPort;

        // MSLP 源（直连探测）
        private boolean mslpSuccess;
        private int mslpOnlinePlayers;
        private int mslpMaxPlayers;
        private int latencyMs;
        private String versionName;
        private long mslpLastUpdate;
        private boolean pingInFlight;

        ServerStatus(String serverName) {
            this.serverName = serverName;
        }

        // ---- 更新 ----

        void applyBcCount(int online, long now) {
            this.bcOnlinePlayers = online;
            this.bcLastUpdate = now;
        }

        void applyAddress(String host, int port) {
            this.addressHost = host;
            this.addressPort = port;
        }

        void markPingStarted() {
            this.pingInFlight = true;
        }

        void applyMslpResult(MslpPinger.Result result, long now) {
            this.pingInFlight = false;
            this.mslpLastUpdate = now;
            this.mslpSuccess = result.isSuccess();
            if (result.isSuccess()) {
                this.mslpOnlinePlayers = result.getOnlinePlayers();
                this.mslpMaxPlayers = result.getMaxPlayers();
                this.latencyMs = result.getLatencyMs();
                this.versionName = result.getVersionName();
            }
        }

        // ---- 查询 ----

        public String getServerName() {
            return serverName;
        }

        // BC 源

        public int getBcOnlinePlayers() {
            return bcOnlinePlayers;
        }

        public boolean isBcFresh(long timeoutMs) {
            return bcLastUpdate > 0 && (System.currentTimeMillis() - bcLastUpdate) < timeoutMs;
        }

        // 地址源

        public boolean hasAddress() {
            return addressHost != null && !addressHost.isEmpty();
        }

        public String getAddressHost() {
            return addressHost;
        }

        public int getAddressPort() {
            return addressPort;
        }

        // MSLP 源

        public boolean isMslpSuccess() {
            return mslpSuccess;
        }

        public int getMslpOnlinePlayers() {
            return mslpOnlinePlayers;
        }

        public int getMslpMaxPlayers() {
            return mslpMaxPlayers;
        }

        public int getLatencyMs() {
            return latencyMs;
        }

        public String getVersionName() {
            return versionName;
        }

        public boolean isMslpFresh(long timeoutMs) {
            return mslpLastUpdate > 0 && (System.currentTimeMillis() - mslpLastUpdate) < timeoutMs;
        }

        /** 最近一次探测完成时间（0 表示从未完成过探测） */
        public long getLastPingTime() {
            return mslpLastUpdate;
        }

        public boolean isPingInFlight() {
            return pingInFlight;
        }
    }
}
