package top.mcocet.miniloginqueue.listener;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import top.mcocet.miniloginqueue.MiniLoginQueue;
import top.mcocet.miniloginqueue.queue.QueueManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * “加入游戏”按钮物品管理
 * 手动入队模式下玩家加入服务器时发放按钮物品，交互/点击触发入队请求；
 * 物品不可丢弃、不可放入容器，放行时自动清除并恢复槽位原有物品。
 */
public class QueueItemListener implements Listener {

    private final MiniLoginQueue plugin;
    private final QueueManager queueManager;
    private boolean autoQueue;
    private int slot;
    private Material material;
    private String itemName;

    private final Map<UUID, ItemStack> savedItems = new HashMap<>();

    public QueueItemListener(MiniLoginQueue plugin, QueueManager queueManager) {
        this.plugin = plugin;
        this.queueManager = queueManager;
        loadConfig();
    }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.autoQueue = config.getBoolean("queue.auto-queue", false);
        this.slot = Math.max(0, Math.min(8, config.getInt("queue.queue-item.slot", 4)));
        String matName = config.getString("queue.queue-item.material", "BEACON");
        Material parsed = Material.getMaterial(matName);
        this.material = parsed != null ? parsed : Material.BEACON;
        this.itemName = ChatColor.translateAlternateColorCodes('&',
                config.getString("queue.queue-item.name", "&a加入游戏"));
    }

    /**
     * 手动模式下玩家加入时发放按钮物品
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (autoQueue) {
            return;
        }
        giveQueueItem(event.getPlayer());
    }

    /**
     * 玩家退出时清理保存的物品记录，避免内存泄漏
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        savedItems.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (autoQueue) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getAction() == Action.PHYSICAL) {
            return;
        }
        if (!isQueueItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        handleQueueItemClick(player);
    }

    /**
     * 左键点击空气不会触发 PlayerInteractEvent，通过挥臂动画拦截
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (autoQueue) {
            return;
        }
        Player player = event.getPlayer();
        // 已在队列中时不拦截挥臂动画，避免玩家无聊时反复触发提示
        if (queueManager.isInQueue(player.getUniqueId())) {
            return;
        }
        if (!isQueueItem(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        handleQueueItemClick(player);
    }

    /**
     * 防止用按钮物品攻击实体
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (autoQueue) {
            return;
        }
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getDamager();
        if (!isQueueItem(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        handleQueueItemClick(player);
    }

    /**
     * 防止按钮物品被丢弃
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (autoQueue) {
            return;
        }
        if (isQueueItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * 防止按钮物品在容器中被点击移动
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (autoQueue) {
            return;
        }
        if (isQueueItem(event.getCurrentItem()) || isQueueItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    /**
     * 防止按钮物品被拖拽
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (autoQueue) {
            return;
        }
        if (isQueueItem(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    private boolean isQueueItem(ItemStack item) {
        if (item == null || item.getType() != material) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && itemName.equals(meta.getDisplayName());
    }

    private void handleQueueItemClick(Player player) {
        queueManager.requestJoin(player);
    }

    public void giveQueueItem(Player player) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(itemName);
            item.setItemMeta(meta);
        }
        // 保存该槽位原有物品（如果不是按钮物品）
        ItemStack existing = player.getInventory().getItem(slot);
        if (existing != null && !isQueueItem(existing)) {
            savedItems.put(player.getUniqueId(), existing.clone());
        }
        player.getInventory().setItem(slot, item);
    }

    /**
     * 清除玩家的按钮物品并恢复槽位原有物品
     */
    public void removeQueueItem(Player player) {
        ItemStack item = player.getInventory().getItem(slot);
        if (isQueueItem(item)) {
            player.getInventory().setItem(slot, null);
        }
        ItemStack saved = savedItems.remove(player.getUniqueId());
        if (saved != null) {
            player.getInventory().setItem(slot, saved);
        }
    }

    /**
     * 清除玩家物品栏中所有按钮物品（放行时调用）
     */
    public void removeAllQueueItems(Player player) {
        int removedCount = 0;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isQueueItem(item)) {
                player.getInventory().setItem(i, null);
                removedCount++;
            }
        }
        savedItems.remove(player.getUniqueId());
        if (removedCount > 0 && plugin.isDebug()) {
            plugin.getLogger().info("已清除 " + removedCount + " 个按钮物品（玩家: " + player.getName() + "）");
        }
    }
}
