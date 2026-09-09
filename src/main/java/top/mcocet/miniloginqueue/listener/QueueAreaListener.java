package top.mcocet.miniloginqueue.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import top.mcocet.miniloginqueue.MiniLoginQueue;

/**
 * 排队区域管理（活动范围限制 + 排队区域保护）
 *
 * 以 queue.spawn.world 中两个对角坐标（queue.range.pos1 / pos2）定义三维长方体区域：
 * - restrict-range: 排队玩家只能在区域内活动，越界会被拉回区域边缘（管理员不受限）
 * - spawn-protection: 区域内禁止以任何方式破坏/放置方块（玩家破坏/放置、爆炸、
 *   火焰燃烧/蔓延、方块融化、实体搬运、液体流动等），排队玩家在区域内免疫伤害，
 *   且区域内禁止 PVP
 * 区域坐标未配置完整时，两项功能自动禁用并输出警告。
 */
public class QueueAreaListener implements Listener {

    private final MiniLoginQueue plugin;
    private final RestrictionListener restrictionListener;

    private boolean restrictRange;
    private boolean spawnProtection;
    private World areaWorld;
    private double minX;
    private double maxX;
    private double minY;
    private double maxY;
    private double minZ;
    private double maxZ;

    public QueueAreaListener(MiniLoginQueue plugin, RestrictionListener restrictionListener) {
        this.plugin = plugin;
        this.restrictionListener = restrictionListener;
        loadConfig();
    }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.restrictRange = config.getBoolean("queue.restrict-range", false);
        this.spawnProtection = config.getBoolean("queue.spawn-protection", true);
        this.areaWorld = null;

        if (!loadArea(config)) {
            plugin.getLogger().warning("queue.range 区域坐标配置不完整（需 pos1/pos2 各含 x/y/z）或 spawn 世界不存在，" +
                    "活动范围限制与区域保护已禁用，请检查 config.yml");
            this.restrictRange = false;
            this.spawnProtection = false;
        }
    }

    /**
     * 读取区域配置：世界 + 两个对角坐标，任一缺失则返回 false
     */
    private boolean loadArea(FileConfiguration config) {
        World world = plugin.getServer().getWorld(config.getString("queue.spawn.world", "world"));
        if (world == null) {
            return false;
        }
        double[] pos1 = readCorner(config, "queue.range.pos1");
        double[] pos2 = readCorner(config, "queue.range.pos2");
        if (pos1 == null || pos2 == null) {
            return false;
        }
        this.areaWorld = world;
        this.minX = Math.min(pos1[0], pos2[0]);
        this.maxX = Math.max(pos1[0], pos2[0]);
        this.minY = Math.min(pos1[1], pos2[1]);
        this.maxY = Math.max(pos1[1], pos2[1]);
        this.minZ = Math.min(pos1[2], pos2[2]);
        this.maxZ = Math.max(pos1[2], pos2[2]);
        return true;
    }

    /**
     * 读取一个角点坐标，任一轴缺失返回 null
     */
    private double[] readCorner(FileConfiguration config, String path) {
        double x = config.getDouble(path + ".x", Double.NaN);
        double y = config.getDouble(path + ".y", Double.NaN);
        double z = config.getDouble(path + ".z", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return null;
        }
        return new double[]{x, y, z};
    }

    /**
     * 区域是否已配置并可用
     */
    private boolean isAreaReady() {
        return areaWorld != null;
    }

    /**
     * 判断位置是否位于区域内（跨世界视为区域外）
     */
    private boolean isInside(Location location) {
        if (!isAreaReady() || location == null || location.getWorld() == null) {
            return false;
        }
        if (!areaWorld.equals(location.getWorld())) {
            return false;
        }
        return location.getX() >= minX && location.getX() <= maxX
                && location.getY() >= minY && location.getY() <= maxY
                && location.getZ() >= minZ && location.getZ() <= maxZ;
    }

    /**
     * 将越界位置拉回区域边缘（各轴钳制到区域内）
     */
    private Location clampToArea(Location location) {
        double x = Math.max(minX, Math.min(maxX, location.getX()));
        double y = Math.max(minY, Math.min(maxY, location.getY()));
        double z = Math.max(minZ, Math.min(maxZ, location.getZ()));
        return new Location(areaWorld, x, y, z, location.getYaw(), location.getPitch());
    }

    // ==================== 活动范围限制 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!restrictRange || !isAreaReady()) {
            return;
        }
        Player player = event.getPlayer();
        if (!restrictionListener.isRestricted(player)) {
            return;
        }
        Location to = event.getTo();
        if (to == null || !areaWorld.equals(to.getWorld())) {
            return;
        }
        // 只拦截位置变化（忽略视角转动）；已在区域内则放行
        if (event.getFrom().getBlockX() == to.getBlockX()
                && event.getFrom().getBlockY() == to.getBlockY()
                && event.getFrom().getBlockZ() == to.getBlockZ()) {
            return;
        }
        if (isInside(to)) {
            return;
        }
        // 越界：把目标位置拉回区域边缘（形成无形墙）
        event.setTo(clampToArea(to));
    }

    // ==================== 方块保护（区域内禁止任何方式破坏/放置） ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (spawnProtection && isInside(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (spawnProtection && isInside(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * 区域内爆炸不破坏任何方块（但不阻止爆炸本身）
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (spawnProtection && isInside(event.getLocation())) {
            event.blockList().clear();
        }
    }

    /**
     * 区域内方块禁止被火焰烧毁
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (spawnProtection && isInside(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * 区域内火焰禁止蔓延（火势扩散、草/菌丝蔓延等）
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (spawnProtection && isInside(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * 区域内方块禁止融化/消退（冰/雪融化等）
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        if (spawnProtection && isInside(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * 区域内液体禁止流动（防止岩浆/水流入或冲毁方块，源方块或目标方块在区域内均拦截）
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!spawnProtection) {
            return;
        }
        if (isInside(event.getBlock().getLocation()) || isInside(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * 区域内禁止实体改变方块（末影人搬运、雪傀儡铺雪、踩踏耕地等）
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (spawnProtection && isInside(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    // ==================== 玩家伤害保护（区域内排队玩家） ====================

    /**
     * 排队玩家在区域内免疫一切伤害（环境/实体/虚空等）
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!spawnProtection) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }
        Player player = (Player) entity;
        if (!restrictionListener.isRestricted(player)) {
            return;
        }
        if (isInside(entity.getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * 区域内禁止 PVP：任一方为排队玩家且任一位置在区域内即拦截
     * （双方均为管理员时不受限，可正常切磋）
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!spawnProtection) {
            return;
        }
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();
        if (!(damager instanceof Player) || !(victim instanceof Player)) {
            return;
        }
        Player attacker = (Player) damager;
        Player target = (Player) victim;
        if (!restrictionListener.isRestricted(attacker) && !restrictionListener.isRestricted(target)) {
            return;
        }
        if (isInside(damager.getLocation()) || isInside(victim.getLocation())) {
            event.setCancelled(true);
        }
    }
}
