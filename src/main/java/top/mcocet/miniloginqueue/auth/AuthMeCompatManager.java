package top.mcocet.miniloginqueue.auth;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import top.mcocet.miniloginqueue.MiniLoginQueue;
import top.mcocet.miniloginqueue.util.LanguageManager;

import java.lang.reflect.Method;

/**
 * AuthMe 登录验证集成管理器（反射实现，无编译期依赖）
 * <p>
 * AuthMe 6.x 的 jar 以 Java 17 编译（class 版本 61），而本项目以 Java 8 构建，
 * javac 8 无法读取其 class，因此对 AuthMe API 的全部调用均通过反射完成：
 * 编译期不引用任何 AuthMe 类，运行期才由服务器 JVM 加载 AuthMe 真实类。
 * <p>
 * 当服务器安装了 AuthMe 且配置开启时，要求玩家先通过 AuthMe 登录
 * 才能加入排队队列；未安装 AuthMe、反射失败或配置关闭时不做任何限制
 * （查询失败按"已登录"放行，避免异常卡住正常排队流程）。
 */
public class AuthMeCompatManager {

    /** AuthMe 稳定兼容 API（v3，AuthMe 5.6+ 与 6.x 均提供） */
    private static final String AUTHME_API_CLASS = "fr.xephi.authme.api.v3.AuthMeApi";

    private final MiniLoginQueue plugin;
    private final LanguageManager languageManager;

    // 配置：是否要求 AuthMe 登录验证（默认开启，未装 AuthMe 自动跳过）
    private boolean enabled;
    // AuthMe 插件与 API 是否可用
    private boolean authMeAvailable;
    // 反射句柄（初始化时查找一次并缓存，避免每次查询重复反射）
    private Object authMeApiInstance;
    private Method isAuthenticatedMethod;

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

        // 反射获取 AuthMe API 实例与查询方法
        try {
            Class<?> apiClass = Class.forName(AUTHME_API_CLASS);
            Method getInstanceMethod = apiClass.getMethod("getInstance");
            this.authMeApiInstance = getInstanceMethod.invoke(null);
            this.isAuthenticatedMethod = findIsAuthenticatedMethod(apiClass);
        } catch (ReflectiveOperationException e) {
            // AuthMe 大版本变更导致 API 形态变化时仅提示并跳过验证
            plugin.getLogger().warning(languageManager.getLogMessage("authme-api-unavailable"));
            if (plugin.isDebug()) {
                e.printStackTrace();
            }
            return;
        }
        if (authMeApiInstance == null || isAuthenticatedMethod == null) {
            plugin.getLogger().warning(languageManager.getLogMessage("authme-api-unavailable"));
            return;
        }

        this.authMeAvailable = true;
        plugin.getLogger().info(languageManager.getLogMessage("authme-compat-enabled"));
    }

    /**
     * 查找 isAuthenticated 方法：优先 Player 参数，找不到时回退 OfflinePlayer 参数
     * （Player 是其子接口，invoke 时直接传入 Player 即可）
     */
    private Method findIsAuthenticatedMethod(Class<?> apiClass) {
        try {
            return apiClass.getMethod("isAuthenticated", Player.class);
        } catch (NoSuchMethodException ignored) {
            try {
                return apiClass.getMethod("isAuthenticated", OfflinePlayer.class);
            } catch (NoSuchMethodException ignoredAgain) {
                return null;
            }
        }
    }

    /**
     * 当前是否要求玩家通过 AuthMe 登录（配置开启且 AuthMe 可用）
     */
    public boolean isRequiringAuth() {
        return enabled && authMeAvailable;
    }

    /**
     * 玩家是否已通过 AuthMe 认证
     * 未要求认证时恒返回 true（不做限制）；
     * 反射调用异常时按已登录放行并记录日志，避免玩家被异常卡住
     */
    public boolean isAuthenticated(Player player) {
        if (!isRequiringAuth() || player == null) {
            return true;
        }
        try {
            return (Boolean) isAuthenticatedMethod.invoke(authMeApiInstance, player);
        } catch (ReflectiveOperationException e) {
            if (plugin.isDebug()) {
                e.printStackTrace();
            }
            return true;
        }
    }
}
