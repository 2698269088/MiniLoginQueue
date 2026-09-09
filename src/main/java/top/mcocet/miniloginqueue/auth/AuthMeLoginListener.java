package top.mcocet.miniloginqueue.auth;

import fr.xephi.authme.events.LoginEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import top.mcocet.miniloginqueue.MiniLoginQueue;

/**
 * AuthMe 登录成功事件监听器（仅在 AuthMe 可用时动态注册）
 * <p>
 * 自动排队模式下玩家入服时未登录，会在等待区被拦下并登记为"待认证"，
 * 本监听器在玩家通过 AuthMe 登录后立即触发其入队流程，无需手动再次操作。
 * 延迟 1 tick 执行，确保 AuthMe 已完成登录状态标记。
 */
public class AuthMeLoginListener implements Listener {

    private final MiniLoginQueue plugin;

    public AuthMeLoginListener(MiniLoginQueue plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuthMeLogin(LoginEvent event) {
        final Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.getQueueManager().onAuthLogin(player);
            }
        }, 1L);
    }
}
