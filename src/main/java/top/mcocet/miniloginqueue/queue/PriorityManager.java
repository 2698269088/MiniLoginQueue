package top.mcocet.miniloginqueue.queue;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 优先队列管理器
 * 支持多种优先级匹配规则：permission, name, uuid, regex, group
 * 同优先级按入队时间先后排序（FIFO）
 */
public class PriorityManager {

    private final JavaPlugin plugin;
    private List<PriorityRule> priorityRules;
    private int defaultPriority;
    private boolean priorityEnabled;

    public PriorityManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * 从配置重新加载优先级规则
     */
    public void reload() {
        FileConfiguration config = plugin.getConfig();
        this.priorityEnabled = config.getBoolean("queue.priority-enabled", true);
        this.defaultPriority = config.getInt("queue.default-priority", 0);
        this.priorityRules = new ArrayList<>();

        List<String> rawRules = config.getStringList("queue.priority");
        int ruleIndex = 0;
        for (String rule : rawRules) {
            ruleIndex++;
            if (rule == null || rule.isEmpty()) {
                continue;
            }

            String[] parts = rule.split(":", 3);
            if (parts.length < 2) {
                plugin.getLogger().warning("优先级规则格式错误（第 " + ruleIndex + " 条，应为 类型:值[:权重]）: " + rule);
                continue;
            }

            String type = parts[0].toLowerCase();
            String value = parts[1];
            // 越靠前权重越高（默认权重 100, 99, 98 ...）
            int weight = 100 - ruleIndex;

            // 支持自定义权重: permission:vip:50
            if (parts.length >= 3) {
                try {
                    weight = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("优先级权重格式错误: " + parts[2]);
                }
            }

            PriorityRule parsedRule = parseRule(type, value, weight);
            if (parsedRule != null) {
                priorityRules.add(parsedRule);
            }
        }

        plugin.getLogger().info("已加载 " + priorityRules.size() + " 条优先级规则，默认优先级 " + defaultPriority);
    }

    private PriorityRule parseRule(String type, String value, int weight) {
        switch (type) {
            case "permission":
                return new PermissionRule(value, weight);
            case "name":
                return new NameRule(value, weight);
            case "uuid":
                return new UuidRule(value, weight);
            case "regex":
                return new RegexRule(value, weight);
            case "group":
                return new GroupRule(value, weight);
            default:
                plugin.getLogger().warning("未知的优先级规则类型: " + type);
                return null;
        }
    }

    /**
     * 计算玩家的优先级权重（取匹配到的最高权重）
     */
    public int calculatePriority(Player player) {
        if (!priorityEnabled) {
            return defaultPriority;
        }

        int highestPriority = defaultPriority;
        for (PriorityRule rule : priorityRules) {
            if (rule.matches(player)) {
                highestPriority = Math.max(highestPriority, rule.getWeight());
            }
        }
        return highestPriority;
    }

    // ==================== 规则接口与实现 ====================

    private interface PriorityRule {
        boolean matches(Player player);

        int getWeight();
    }

    private static class PermissionRule implements PriorityRule {
        private final String permission;
        private final int weight;

        PermissionRule(String permission, int weight) {
            this.permission = permission;
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            return player.hasPermission(permission);
        }

        @Override
        public int getWeight() {
            return weight;
        }
    }

    private static class NameRule implements PriorityRule {
        private final String name;
        private final int weight;

        NameRule(String name, int weight) {
            this.name = name;
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            return player.getName().equalsIgnoreCase(name);
        }

        @Override
        public int getWeight() {
            return weight;
        }
    }

    private static class UuidRule implements PriorityRule {
        private final UUID uuid;
        private final int weight;

        UuidRule(String uuidStr, int weight) {
            this.uuid = UUID.fromString(uuidStr);
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            return player.getUniqueId().equals(uuid);
        }

        @Override
        public int getWeight() {
            return weight;
        }
    }

    private static class RegexRule implements PriorityRule {
        private final Pattern pattern;
        private final int weight;

        RegexRule(String regex, int weight) {
            Pattern p = null;
            try {
                p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                // 格式错误时规则不匹配任何玩家，由调用方负责日志提示
            }
            this.pattern = p;
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            return pattern != null && pattern.matcher(player.getName()).matches();
        }

        @Override
        public int getWeight() {
            return weight;
        }
    }

    private static class GroupRule implements PriorityRule {
        private final String group;
        private final int weight;

        GroupRule(String group, int weight) {
            this.group = group;
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            // 通过 group.组名 权限检测权限组，兼容多数权限插件
            return player.hasPermission("group." + group);
        }

        @Override
        public int getWeight() {
            return weight;
        }
    }
}
