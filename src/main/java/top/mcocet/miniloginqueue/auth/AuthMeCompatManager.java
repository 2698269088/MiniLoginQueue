package top.mcocet.miniloginqueue.auth;

import fr.xephi.authme.api.v3.AuthMeApi;
import org.bukkit.entity.Player;
import top.mcocet.miniloginqueue.MiniLoginQueue;
import top.mcocet.miniloginqueue.util.LanguageManager;

/**
 * AuthMe 登录验证集成管理器
 * <p>
 * 当服务器安装了 AuthMe 且配置开启时，要求玩家先通过 AuthMe 登录
 * 才能加入排队队列；未安装 AuthMe 或配置关闭时不做任何限制。
 * <p>
 * AuthMe 为可选软依赖：仅在检测到插件与 API 后才引用 AuthMe 类，
 * 未安装 AuthMe 的服务器可正常运行本插件。
 */
public class AuthMeCompatManager {

    private final MiniLoginQueue plugin;
    private final LanguageManager languageManager;

    // 配置：是否要求 AuthMe 登录验证（默认开启，未装 AuthMe 自动跳过）
    private boolean enabled;
    // AuthMe 插件与 API 是否可用
    private boolean authMeAvailable;
    private AuthMeApi authMeApi;

    public AuthMeCompatManager(MiniLoginQueue plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        loadConfig();
    }

    public void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("auth.enabled", true);
        if (!enabled) {
            return;
        }
        initAuthMe();
    }

    private void initAuthMe() {
        // 检查是否安装了 AuthMe 插件
        if (plugin.getServer().getPluginManager().getPlugin("AuthMe") == null) {
            plugin.getLogger().info(languageManager.getLogMessage("authme-not-found"));
            return;
        }

        // 获取 AuthMe API 实例
        this.authMeApi = AuthMeApi.getInstance();
        if (authMeApi == null) {
            plugin.getLogger().warning(languageManager.getLogMessage("authme-api-unavailable"));
            return;
        }

        this.authMeAvailable = true;
        plugin.getLogger().info(languageManager.getLogMessage("authme-compat-enabled"));
    }

    /**
     * 当前是否要求玩家通过 AuthMe 登录（配置开启且 AuthMe 可用）
     */
    public boolean isRequiringAuth() {
        return enabled && authMeAvailable;
    }

    /**
     * 玩家是否已通过 AuthMe 认证
     * 未要求认证时恒返回 true（不做限制）
     */
    public boolean isAuthenticated(Player player) {
        if (!isRequiringAuth() || player == null) {
            return true;
        }
        return authMeApi.isAuthenticated(player);
    }
}
