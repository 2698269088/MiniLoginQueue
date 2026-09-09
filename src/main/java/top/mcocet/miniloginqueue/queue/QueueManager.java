package top.mcocet.miniloginqueue.queue;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.miniloginqueue.MiniLoginQueue;
import top.mcocet.miniloginqueue.auth.AuthMeCompatManager;
import top.mcocet.miniloginqueue.bungee.BungeeMessenger;
import top.mcocet.miniloginqueue.util.LanguageManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * 排队核心管理器
 * <p>
 * 为每个已配置的目标服务器维护独立队列（按优先级 + 入队时间排序），
 * 服务器人数信息由 BungeeMessenger 通过 BungeeCord 原生通道周期刷新，
 * 负载低于阈值时按优先级依次放行队首玩家。
 * <p>
 * AuthMe 集成了认证门禁：要求认证时，未登录玩家不能入队也不能被放行；
 * 自动排队模式下未登录玩家会被登记，由主类刷新周期轮询检测，
 * 登录成功后自动补完入队流程（反射调用 AuthMe API，无编译期依赖）。
 */
public class QueueManager implements Listener {

    private final MiniLoginQueue plugin;
    private final BungeeMessenger messenger;
    private final AuthMeCompatManager authMeCompatManager;
    private final LanguageManager languageManager;
    private final PriorityManager priorityManager;

    // 每服务器独立等待队列：按优先级降序、同优先级按入队时间升序（FIFO）
    private final Map<String, PriorityQueue<QueueEntry>> serverQueues = new HashMap<>();
    // 玩家 UUID -> 目标服务器
    private final Map<UUID, String> playerTargetMap = new HashMap<>();
    // 已放行（正在转移）的玩家
    private final Set<UUID> allowedPlayers = new HashSet<>();
    // 自动排队模式下等待 AuthMe 登录后自动入队的玩家
    private final Set<UUID> pendingAuthJoin = new HashSet<>();
    // 队列手动暂停
    private boolean queuePaused = false;
    // 各服务器阈值提示节流时间戳（避免每轮刷新刷屏）
    private final Map<String, Long> lastThresholdNotice = new HashMap<>();

    // 配置缓存
    private double threshold;
    private boolean autoQueue;
    private long lockTimeTicks;
    private int releaseLimit;
    private boolean adminBypass;

    public QueueManager(MiniLoginQueue plugin, BungeeMessenger messenger, AuthMeCompatManager authMeCompatManager) {
        this.plugin = plugin;
        this.messenger = messenger;
        this.authMeCompatManager = authMeCompatManager;
        this.languageManager = plugin.getLanguageManager();
        this.priorityManager = new PriorityManager(plugin);
        loadConfig();
    }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.threshold = Math.max(0.0, Math.min(1.0, config.getDouble("queue.threshold", 0.8)));
        this.autoQueue = config.getBoolean("queue.auto-queue", false);
        long lockSeconds = Math.max(0, config.getLong("queue.lock-time", 3));
        this.lockTimeTicks = lockSeconds * 20L;
        this.releaseLimit = Math.max(0, config.getInt("queue.release-limit", 0));
        this.adminBypass = config.getBoolean("queue.admin-bypass", true);

        // 清理已从配置中移除的服务器队列，避免队列冻结
        List<String> configured = plugin.getConfiguredServers();
        for (String queuedServer : new ArrayList<>(serverQueues.keySet())) {
            if (configured.contains(queuedServer)) {
                continue;
            }
            PriorityQueue<QueueEntry> queue = serverQueues.remove(queuedServer);
            if (queue == null) {
                continue;
            }
            for (QueueEntry entry : queue) {
                playerTargetMap.remove(entry.getUuid());
                Player player = plugin.getServer().getPlayer(entry.getUuid());
                if (player != null && player.isOnline()) {
                    player.sendMessage(languageManager.getMessage("server-removed", "server", queuedServer));
                }
            }
        }
    }

    public void reloadPriority() {
        priorityManager.reload();
    }

    // ==================== 玩家加入 / 退出 ====================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        applyGameMode(player);
        teleportToSpawn(player);

        // 已被管理员直接放行（如转移失败重进）的玩家不再进入排队流程
        if (allowedPlayers.contains(uuid)) {
            return;
        }

        if (!autoQueue) {
            // 手动模式：提示玩家使用物品栏中的“加入游戏”按钮或 /mlq join
            player.sendMessage(languageManager.getMessage("manual-queue-hint"));
            return;
        }

        // 自动模式：锁定延迟后自动入队（多服务器时打开选择菜单）
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || allowedPlayers.contains(uuid) || isInQueue(uuid)) {
                    return;
                }
                requestJoin(player);
            }
        }.runTaskLater(plugin, lockTimeTicks);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        pendingAuthJoin.remove(uuid);
        String targetServer = playerTargetMap.remove(uuid);
        if (targetServer != null) {
            PriorityQueue<QueueEntry> queue = serverQueues.get(targetServer);
            if (queue != null) {
                queue.removeIf(entry -> entry.getUuid().equals(uuid));
            }
        } else {
            removeFromAllQueues(uuid);
        }
        allowedPlayers.remove(uuid);
        processQueue();
    }

    private void removeFromAllQueues(UUID uuid) {
        for (PriorityQueue<QueueEntry> queue : serverQueues.values()) {
            queue.removeIf(entry -> entry.getUuid().equals(uuid));
        }
    }

    // ==================== 加入 / 退出队列 ====================

    /**
     * AuthMe 认证门禁：要求认证时未登录玩家禁止入队
     * 自动排队模式下登记待认证玩家，登录成功后自动继续入队流程
     */
    private boolean checkAuthRequired(Player player) {
        if (!authMeCompatManager.isRequiringAuth() || authMeCompatManager.isAuthenticated(player)) {
            return true;
        }
        player.sendMessage(languageManager.getMessage("authme-please-login-first"));
        if (autoQueue) {
            pendingAuthJoin.add(player.getUniqueId());
        }
        return false;
    }

    /**
     * 周期检测已登录的待认证玩家（由主类刷新任务每轮调用）
     * 原 AuthMe 事件监听器依赖编译期引入 LoginEvent 类，已改为反射集成下的轮询：
     * 无待认证玩家时直接返回（零开销），登录成功后补完未完成的入队请求。
     */
    public void tickPendingAuthJoin() {
        if (pendingAuthJoin.isEmpty()) {
            return;
        }
        for (UUID uuid : new ArrayList<>(pendingAuthJoin)) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                pendingAuthJoin.remove(uuid);
                continue;
            }
            if (authMeCompatManager.isAuthenticated(player)) {
                pendingAuthJoin.remove(uuid);
                requestJoin(player);
            }
        }
    }

    /**
     * 玩家发起“加入游戏”请求
     * 多服务器选择菜单启用时弹出菜单，否则直接加入默认服务器队列
     */
    public void requestJoin(Player player) {
        UUID uuid = player.getUniqueId();
        if (!checkAuthRequired(player)) {
            return;
        }
        if (allowedPlayers.contains(uuid)) {
            return;
        }
        String currentTarget = playerTargetMap.get(uuid);
        if (currentTarget != null) {
            player.sendMessage(languageManager.getMessage("already-in-queue", "server", currentTarget));
            return;
        }
        if (plugin.isSelectorEnabled()) {
            plugin.getServerSelectorMenu().open(player);
            return;
        }
        joinServer(player, plugin.getDefaultServerName());
    }

    /**
     * 将玩家加入指定服务器的队列
     */
    public void joinServer(Player player, String serverName) {
        UUID uuid = player.getUniqueId();

        if (!checkAuthRequired(player)) {
            return;
        }
        if (allowedPlayers.contains(uuid)) {
            return;
        }
        if (!plugin.isServerConfigured(serverName)) {
            player.sendMessage(languageManager.getMessage("unknown-server", "server", serverName));
            return;
        }
        // 玩家已排在某个服务器队列中，需先退出
        String currentTarget = playerTargetMap.get(uuid);
        if (currentTarget != null) {
            player.sendMessage(languageManager.getMessage("already-in-queue", "server", currentTarget));
            return;
        }

        if (!messenger.isServerOnline(serverName)) {
            if (messenger.getServerStatus(serverName) == null) {
                // 尚无任何状态缓存：触发一次即时刷新并提示稍后
                player.sendMessage(languageManager.getMessage("server-unknown", "server", serverName));
                messenger.refresh();
            } else {
                player.sendMessage(languageManager.getMessage("server-offline", "server", serverName));
            }
            return;
        }

        int priority = priorityManager.calculatePriority(player);
        PriorityQueue<QueueEntry> queue = serverQueues.computeIfAbsent(serverName, k -> newPriorityQueue());
        queue.offer(new QueueEntry(uuid, priority, System.currentTimeMillis()));
        playerTargetMap.put(uuid, serverName);
        if (plugin.isDebug()) {
            plugin.getLogger().info(player.getName() + " 已加入 [" + serverName + "] 队列，优先级 " + priority);
        }

        sendQueueStatus(player, uuid);
        processQueue();
    }

    /**
     * 玩家主动退出队列
     */
    public void leaveQueue(Player player) {
        UUID uuid = player.getUniqueId();
        if (!isInQueue(uuid)) {
            player.sendMessage(languageManager.getMessage("not-in-queue"));
            return;
        }
        String targetServer = playerTargetMap.remove(uuid);
        if (targetServer != null) {
            PriorityQueue<QueueEntry> queue = serverQueues.get(targetServer);
            if (queue != null) {
                queue.removeIf(entry -> entry.getUuid().equals(uuid));
            }
        } else {
            removeFromAllQueues(uuid);
        }
        player.sendMessage(languageManager.getMessage("leave-success"));
        processQueue();
    }

    // ==================== 放行 ====================

    /**
     * 管理员直接放行玩家（跳过排队）
     *
     * @param targetServer 目标服务器，为空时使用玩家所在队列目标；不在队列时使用默认服务器
     * @return 实际使用的目标服务器；服务器未配置时返回 null
     */
    public String allowPlayerDirectly(Player player, String targetServer) {
        UUID uuid = player.getUniqueId();
        String usedServer = targetServer;
        if (isInQueue(uuid)) {
            String queuedServer = playerTargetMap.remove(uuid);
            if (queuedServer != null) {
                PriorityQueue<QueueEntry> queue = serverQueues.get(queuedServer);
                if (queue != null) {
                    queue.removeIf(entry -> entry.getUuid().equals(uuid));
                }
            }
            if (usedServer == null) {
                usedServer = queuedServer;
            }
        }
        if (usedServer == null) {
            usedServer = plugin.getDefaultServerName();
        }

        if (!plugin.isServerConfigured(usedServer)) {
            player.sendMessage(languageManager.getMessage("unknown-server", "server", usedServer));
            return null;
        }
        allowAndConnect(player, usedServer);
        return usedServer;
    }

    private void allowAndConnect(Player player, String serverName) {
        if (!plugin.isServerConfigured(serverName)) {
            player.sendMessage(languageManager.getMessage("unknown-server", "server", serverName));
            return;
        }
        allowedPlayers.add(player.getUniqueId());
        player.sendMessage(languageManager.getMessage("entering", "server", serverName));

        // 清除队列物品（加入游戏按钮），恢复正常物品栏
        if (plugin.getQueueItemListener() != null) {
            plugin.getQueueItemListener().removeAllQueueItems(player);
        }
        messenger.connectPlayerToServer(player, serverName);
    }

    // ==================== 队列处理 ====================

    /**
     * 尝试放行各服务器队列中的玩家
     * 服务器在线且负载低于阈值时，按优先级依次放行
     */
    public void processQueue() {
        if (queuePaused) {
            return;
        }

        for (Map.Entry<String, PriorityQueue<QueueEntry>> entry : serverQueues.entrySet()) {
            String serverName = entry.getKey();
            PriorityQueue<QueueEntry> queue = entry.getValue();
            if (queue.isEmpty()) {
                continue;
            }
            if (!messenger.isServerOnline(serverName)) {
                continue;
            }

            // 在线人数/容量由 BungeeMessenger 合并 MSLP 直连探测与 BC 通道数据
            int maxOnline = messenger.getMaxOnline(serverName);
            int online = messenger.getOnlinePlayers(serverName);

            if (maxOnline > 0 && (double) online / maxOnline >= threshold) {
                notifyQueuePlayers(serverName, languageManager.getMessage("threshold-reached", "server", serverName));
                continue;
            }

            int availableSlots = Math.max(0, maxOnline - online);
            int released = 0;
            while (availableSlots > 0 && !queue.isEmpty()) {
                QueueEntry queueEntry = queue.poll();
                if (queueEntry == null) {
                    break;
                }
                if (releaseLimit > 0 && released >= releaseLimit) {
                    // 本轮放行数量已达上限，放回队首
                    queue.offer(queueEntry);
                    break;
                }

                Player player = plugin.getServer().getPlayer(queueEntry.getUuid());
                if (player == null || !player.isOnline()) {
                    playerTargetMap.remove(queueEntry.getUuid());
                    continue;
                }
                // 放行前终验 AuthMe 登录状态（防御：排队期间状态异常变化）
                if (!authMeCompatManager.isAuthenticated(player)) {
                    playerTargetMap.remove(queueEntry.getUuid());
                    player.sendMessage(languageManager.getMessage("authme-please-login-first"));
                    continue;
                }
                playerTargetMap.remove(queueEntry.getUuid());
                if (plugin.isDebug()) {
                    plugin.getLogger().info("放行玩家 " + player.getName() + " -> " + serverName);
                }
                allowAndConnect(player, serverName);
                availableSlots--;
                released++;
            }
        }
    }

    /**
     * 服务器状态刷新完成后的回调（收到新的 PlayerCount 响应时触发）
     */
    public void onServerStatusUpdated() {
        processQueue();
    }

    public void pauseQueue() {
        queuePaused = true;
        notifyAllQueuePlayers(languageManager.getMessage("queue-paused-notify"));
    }

    public void resumeQueue() {
        queuePaused = false;
        notifyAllQueuePlayers(languageManager.getMessage("queue-resumed-notify"));
        processQueue();
    }

    public boolean isQueuePaused() {
        return queuePaused;
    }

    // ==================== 查询 ====================

    public boolean isInQueue(UUID uuid) {
        return playerTargetMap.containsKey(uuid);
    }

    public boolean isInQueue(UUID uuid, String serverName) {
        return serverName != null && serverName.equals(playerTargetMap.get(uuid));
    }

    /**
     * 获取玩家当前的目标服务器
     */
    public String getPlayerTargetServer(UUID uuid) {
        return playerTargetMap.get(uuid);
    }

    /**
     * 获取玩家在队列中的位置（1 起），不在队列返回 0
     */
    public int getPosition(UUID uuid) {
        String targetServer = playerTargetMap.get(uuid);
        if (targetServer == null) {
            return 0;
        }
        PriorityQueue<QueueEntry> queue = serverQueues.get(targetServer);
        if (queue == null) {
            return 0;
        }
        int position = 1;
        for (QueueEntry entry : orderedSnapshot(queue)) {
            if (entry.getUuid().equals(uuid)) {
                return position;
            }
            position++;
        }
        return 0;
    }

    /**
     * 获取指定服务器队列中的玩家名（按排队顺序）
     */
    public List<String> getQueuePlayerNames(String serverName) {
        List<String> names = new ArrayList<>();
        PriorityQueue<QueueEntry> queue = serverQueues.get(serverName);
        if (queue == null) {
            return names;
        }
        List<QueueEntry> snapshot = orderedSnapshot(queue);
        for (QueueEntry entry : snapshot) {
            Player player = plugin.getServer().getPlayer(entry.getUuid());
            if (player != null) {
                names.add(player.getName());
            }
        }
        return names;
    }

    public int getQueueSize(String serverName) {
        PriorityQueue<QueueEntry> queue = serverQueues.get(serverName);
        return queue != null ? queue.size() : 0;
    }

    public int getTotalQueueSize() {
        int total = 0;
        for (PriorityQueue<QueueEntry> queue : serverQueues.values()) {
            total += queue.size();
        }
        return total;
    }

    public Set<UUID> getAllowedPlayers() {
        return allowedPlayers;
    }

    public Map<String, Integer> getServerQueueSizes() {
        Map<String, Integer> sizes = new HashMap<>();
        for (Map.Entry<String, PriorityQueue<QueueEntry>> entry : serverQueues.entrySet()) {
            sizes.put(entry.getKey(), entry.getValue().size());
        }
        return sizes;
    }

    // ==================== 提示消息 ====================

    private void sendQueueStatus(Player player, UUID uuid) {
        int position = getPosition(uuid);
        String serverName = playerTargetMap.get(uuid);
        if (serverName == null) {
            return;
        }
        int maxOnline = messenger.getMaxOnline(serverName);
        int online = messenger.getOnlinePlayers(serverName);
        player.sendMessage(languageManager.getMessage("waiting",
                "server", serverName,
                "position", String.valueOf(position),
                "online", String.valueOf(online),
                "max", String.valueOf(maxOnline)));
    }

    /**
     * 向指定服务器队列中的玩家发送提示（带节流）
     */
    private void notifyQueuePlayers(String serverName, String message) {
        long now = System.currentTimeMillis();
        Long last = lastThresholdNotice.get(serverName);
        if (last != null && now - last < 15000L) {
            return;
        }
        lastThresholdNotice.put(serverName, now);

        PriorityQueue<QueueEntry> queue = serverQueues.get(serverName);
        if (queue == null) {
            return;
        }
        for (QueueEntry entry : queue) {
            Player player = plugin.getServer().getPlayer(entry.getUuid());
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private void notifyAllQueuePlayers(String message) {
        for (PriorityQueue<QueueEntry> queue : serverQueues.values()) {
            for (QueueEntry entry : queue) {
                Player player = plugin.getServer().getPlayer(entry.getUuid());
                if (player != null && player.isOnline()) {
                    player.sendMessage(message);
                }
            }
        }
    }

    // ==================== 玩家状态设置 ====================

    private void applyGameMode(Player player) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("queue.set-gamemode", true)) {
            return;
        }
        // 管理员（bypass 权限）保持原游戏模式，不做强制设置
        if (adminBypass && player.hasPermission("miniloginqueue.admin.bypass")) {
            return;
        }

        String modeName = config.getString("queue.gamemode", "ADVENTURE");
        GameMode gameMode;
        try {
            gameMode = GameMode.valueOf(modeName.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            plugin.getLogger().warning("无效的游戏模式配置: " + modeName);
            gameMode = GameMode.ADVENTURE;
        }
        player.setGameMode(gameMode);
    }

    /**
     * 传送到配置的出生点（延迟 1 tick 执行，等待玩家完全进入服务器）
     */
    private void teleportToSpawn(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                FileConfiguration config = plugin.getConfig();
                String worldName = config.getString("queue.spawn.world", "world");
                World world = plugin.getServer().getWorld(worldName);
                if (world == null) {
                    world = player.getWorld();
                }

                double centerX = config.getDouble("queue.spawn.x", 0.0);
                double centerY = config.getDouble("queue.spawn.y", 64.0);
                double centerZ = config.getDouble("queue.spawn.z", 0.0);
                float pitch = (float) config.getDouble("queue.spawn.pitch", 0.0);
                float yaw = (float) config.getDouble("queue.spawn.yaw", 0.0);
                double radius = config.getDouble("queue.spawn.radius", 5.0);

                double x = centerX;
                double y = centerY;
                double z = centerZ;
                if (radius > 0) {
                    double angle = Math.random() * 2 * Math.PI;
                    double distance = Math.random() * radius;
                    x = centerX + distance * Math.cos(angle);
                    z = centerZ + distance * Math.sin(angle);
                }

                player.teleport(new Location(world, x, y, z, yaw, pitch));
            }
        }.runTaskLater(plugin, 1L);
    }

    // ==================== 内部工具 ====================

    private PriorityQueue<QueueEntry> newPriorityQueue() {
        // 优先级数字越大越靠前，同优先级按时间戳升序（先来的在前）
        return new PriorityQueue<>(
                Comparator.comparingInt(QueueEntry::getPriority).reversed()
                        .thenComparingLong(QueueEntry::getTimestamp)
        );
    }

    private List<QueueEntry> orderedSnapshot(PriorityQueue<QueueEntry> queue) {
        List<QueueEntry> snapshot = new ArrayList<>(queue);
        snapshot.sort(Comparator.comparingInt(QueueEntry::getPriority).reversed()
                .thenComparingLong(QueueEntry::getTimestamp));
        return snapshot;
    }

    /**
     * 队列条目
     */
    private static class QueueEntry {
        private final UUID uuid;
        private final int priority;
        private final long timestamp;

        QueueEntry(UUID uuid, int priority, long timestamp) {
            this.uuid = uuid;
            this.priority = priority;
            this.timestamp = timestamp;
        }

        UUID getUuid() {
            return uuid;
        }

        int getPriority() {
            return priority;
        }

        long getTimestamp() {
            return timestamp;
        }
    }
}
