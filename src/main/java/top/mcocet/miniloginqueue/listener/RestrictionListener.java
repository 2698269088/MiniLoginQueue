package top.mcocet.miniloginqueue.listener;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import top.mcocet.miniloginqueue.MiniLoginQueue;
import top.mcocet.miniloginqueue.gui.ServerSelectorMenu;
import top.mcocet.miniloginqueue.queue.QueueManager;

import java.util.Set;
import java.util.UUID;

/**
 * 等待区玩家限制
 * 未放行的玩家（正在等待或尚未加入队列）无法移动/破坏/放置/交互方块、
 * 攻击实体、丢弃物品或操作容器；服务器选择菜单的点击放行。
 * 拥有 miniloginqueue.admin.bypass 权限的玩家不受限制。
 */
public class RestrictionListener implements Listener {

    private final MiniLoginQueue plugin;
    private final Set<UUID> allowedPlayers;

    private boolean restrictMovement;
    private boolean adminBypass;
    private boolean totalEnabled;
    private boolean allowBlockInteract;
    private boolean allowBlockPlace;
    private boolean allowBlockBreak;

    public RestrictionListener(MiniLoginQueue plugin, QueueManager queueManager) {
        this.plugin = plugin;
        this.allowedPlayers = queueManager.getAllowedPlayers();
        loadConfig();
    }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.restrictMovement = config.getBoolean("queue.restrict-movement", false);
        this.adminBypass = config.getBoolean("queue.admin-bypass", true);
        this.totalEnabled = config.getBoolean("restrictions.enabled", true);
        this.allowBlockInteract = config.getBoolean("restrictions.allow-block-interact", false);
        this.allowBlockPlace = config.getBoolean("restrictions.allow-block-place", false);
        this.allowBlockBreak = config.getBoolean("restrictions.allow-block-break", false);
    }

    /**
     * 判断玩家是否处于受限状态（未放行且无 bypass 权限）
     * restrictions.enabled 为 false 时全部限制失效
     * （public 供 QueueAreaListener 等其它限制模块复用同一判定）
     */
    public boolean isRestricted(Player player) {
        if (!totalEnabled) {
            return false;
        }
        if (allowedPlayers.contains(player.getUniqueId())) {
            return false;
        }
        if (adminBypass && player.hasPermission("miniloginqueue.admin.bypass")) {
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!restrictMovement) {
            return;
        }
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }
        // 只拦截位置变化（忽略视角转动）
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (allowBlockInteract) {
            return;
        }
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }
        if (event.getClickedBlock() != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (allowBlockPlace) {
            return;
        }
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (allowBlockBreak) {
            return;
        }
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * 未放行玩家不能攻击任何实体（包括 PvP）
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (!(damager instanceof Player)) {
            return;
        }
        Player player = (Player) damager;
        if (!isRestricted(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        // 放行服务器选择菜单中的点击
        if (event.getInventory().getHolder() instanceof ServerSelectorMenu.ServerSelectorHolder) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!isRestricted(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof ServerSelectorMenu.ServerSelectorHolder) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!isRestricted(player)) {
            return;
        }
        event.setCancelled(true);
    }
}
