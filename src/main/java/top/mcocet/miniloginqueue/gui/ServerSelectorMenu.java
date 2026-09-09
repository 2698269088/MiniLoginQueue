package top.mcocet.miniloginqueue.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import top.mcocet.miniloginqueue.MiniLoginQueue;
import top.mcocet.miniloginqueue.bungee.BungeeMessenger;
import top.mcocet.miniloginqueue.queue.QueueManager;
import top.mcocet.miniloginqueue.util.LanguageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器选择菜单
 * 显示配置的目标服务器（名称取 servers[].display-name，未配置时用内部名）
 * 及其在线状态（人数由 MSLP 直连探测 + BC 原生通道组合查询），
 * 玩家点击在线服务器后加入该服务器的独立队列。
 */
public class ServerSelectorMenu implements Listener {

    private static final String LORE_ID_PREFIX = "ID:";
    private static final String ID_COLOR = "&0";

    private final MiniLoginQueue plugin;
    private final QueueManager queueManager;
    private final LanguageManager languageManager;

    private String title;
    private Material onlineMaterial;
    private Material offlineMaterial;
    private Material unknownMaterial;

    public ServerSelectorMenu(MiniLoginQueue plugin, QueueManager queueManager) {
        this.plugin = plugin;
        this.queueManager = queueManager;
        this.languageManager = plugin.getLanguageManager();
        loadConfig();
    }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.title = ChatColor.translateAlternateColorCodes('&',
                config.getString("server-selector.title", "&a选择要加入的服务器"));
        this.onlineMaterial = parseMaterial(config.getString("server-selector.online-material"),
                Material.LIME_STAINED_GLASS_PANE);
        this.offlineMaterial = parseMaterial(config.getString("server-selector.offline-material"),
                Material.RED_STAINED_GLASS_PANE);
        this.unknownMaterial = parseMaterial(config.getString("server-selector.unknown-material"),
                Material.GRAY_STAINED_GLASS_PANE);
    }

    /**
     * 打开服务器选择菜单
     */
    public void open(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        // 打开前触发一次状态刷新，保证显示的是最新状态
        plugin.getMessenger().refresh();

        List<String> servers = plugin.getConfiguredServers();
        int size = Math.max(9, Math.min(54, ((servers.size() + 8) / 9) * 9));

        Inventory inventory = Bukkit.createInventory(new ServerSelectorHolder(), size, title);
        renderServers(inventory);

        final Inventory target = inventory;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.openInventory(target);
            }
        });
    }

    /**
     * 根据当前状态缓存渲染菜单内容
     */
    private void renderServers(Inventory inventory) {
        List<String> servers = plugin.getConfiguredServers();
        for (int i = 0; i < servers.size() && i < inventory.getSize(); i++) {
            inventory.setItem(i, createServerItem(servers.get(i)));
        }
    }

    /**
     * 刷新所有在线玩家正打开的选择菜单（状态响应到达时调用）
     */
    public void updateOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory topInventory = player.getOpenInventory().getTopInventory();
            if (topInventory != null && topInventory.getHolder() instanceof ServerSelectorHolder) {
                renderServers(topInventory);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ServerSelectorHolder)) {
            return;
        }
        // 禁止在菜单中移动、拖拽任何物品
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        String serverName = extractServerName(event.getCurrentItem());
        if (serverName == null) {
            return;
        }

        // 已在队列中的玩家不可重复加入
        if (queueManager.isInQueue(player.getUniqueId())) {
            player.sendMessage(languageManager.getMessage("already-in-queue",
                    "server", queueManager.getPlayerTargetServer(player.getUniqueId())));
            player.closeInventory();
            return;
        }

        BungeeMessenger messenger = plugin.getMessenger();
        boolean online = messenger.isServerOnline(serverName);
        boolean unknown = messenger.getServerStatus(serverName) == null;

        if (unknown) {
            player.sendMessage(languageManager.getMessage("server-unknown", "server", serverName));
            messenger.refresh();
            return;
        }
        if (!online) {
            player.sendMessage(languageManager.getMessage("server-offline", "server", serverName));
            player.closeInventory();
            return;
        }

        player.closeInventory();
        queueManager.joinServer(player, serverName);
    }

    /**
     * 构造单个服务器的展示物品
     */
    private ItemStack createServerItem(String serverName) {
        BungeeMessenger messenger = plugin.getMessenger();
        BungeeMessenger.ServerStatus status = messenger.getServerStatus(serverName);
        boolean online = status != null && messenger.isServerOnline(serverName);

        Material material = status == null ? unknownMaterial : (online ? onlineMaterial : offlineMaterial);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                languageManager.getMessage("server-selector-item-name",
                        "server", plugin.getServerDisplayName(serverName),
                        "status", languageManager.getMessage(status == null ? "status-unknown"
                                : online ? "status-online" : "status-offline"))));

        List<String> lore = new ArrayList<>();
        if (status == null) {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    languageManager.getMessage("server-selector-status-unknown")));
        } else if (online) {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    languageManager.getMessage("server-selector-status-online",
                            "online", String.valueOf(messenger.getOnlinePlayers(serverName)),
                            "max", String.valueOf(messenger.getMaxOnline(serverName)))));
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    languageManager.getMessage("server-selector-load",
                            "ratio", String.format("%.1f", messenger.getLoadRatio(serverName) * 100))));
        } else {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    languageManager.getMessage("server-selector-status-offline")));
        }
        lore.add(ChatColor.translateAlternateColorCodes('&',
                languageManager.getMessage("server-selector-click-hint")));
        // 隐藏的 ID 标记，用于点击时识别服务器
        lore.add(ChatColor.translateAlternateColorCodes('&', ID_COLOR + LORE_ID_PREFIX + serverName));

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String extractServerName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return null;
        }
        List<String> lore = meta.getLore();
        if (lore == null) {
            return null;
        }
        for (String line : lore) {
            String plain = ChatColor.stripColor(line);
            if (plain.startsWith(LORE_ID_PREFIX)) {
                return plain.substring(LORE_ID_PREFIX.length());
            }
        }
        return null;
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        Material parsed = Material.getMaterial(name.toUpperCase());
        return parsed != null ? parsed : fallback;
    }

    /**
     * 自定义 InventoryHolder，用于识别服务器选择菜单
     */
    public static class ServerSelectorHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
