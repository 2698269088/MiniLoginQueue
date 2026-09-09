package top.mcocet.miniloginqueue.util;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {

    private final JavaPlugin plugin;
    private FileConfiguration langConfig;
    private final Map<String, String> cache = new HashMap<>();

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadLanguage();
    }

    public void loadLanguage() {
        cache.clear();
        String language = plugin.getConfig().getString("language", "zh_CN");
        // 语言资源不存在时回退到默认语言，避免启动崩溃
        if (plugin.getResource("lang/" + language + ".yml") == null) {
            plugin.getLogger().warning("未知的语言: " + language + "，回退到 zh_CN");
            language = "zh_CN";
        }
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        File langFile = new File(langFolder, language + ".yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang/" + language + ".yml", false);
        }

        FileConfiguration defaultConfig = loadDefaultLangConfig(language);

        if (langFile.exists()) {
            langConfig = YamlConfiguration.loadConfiguration(langFile);
            // 自动合并内置资源中新增但外部文件缺失的语言键，避免升级插件后提示 Missing message
            if (defaultConfig != null) {
                mergeMissingKeys(langConfig, defaultConfig);
                try {
                    langConfig.save(langFile);
                } catch (IOException e) {
                    plugin.getLogger().warning("[MiniLoginQueue] Failed to save merged language file: " + e.getMessage());
                }
            }
        } else {
            plugin.getLogger().warning("[MiniLoginQueue] Language file lang/" + language + ".yml not found, using built-in default language.");
            langConfig = defaultConfig != null ? defaultConfig : new YamlConfiguration();
        }
    }

    /**
     * 加载内置默认语言配置
     */
    private FileConfiguration loadDefaultLangConfig(String language) {
        InputStream stream = plugin.getResource("lang/" + language + ".yml");
        if (stream == null) {
            stream = plugin.getResource("lang/zh_CN.yml");
        }
        if (stream != null) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return null;
    }

    /**
     * 将 source 中存在但 target 中缺失的键合并到 target
     */
    private void mergeMissingKeys(FileConfiguration target, FileConfiguration source) {
        for (String key : source.getKeys(true)) {
            if (!target.contains(key)) {
                target.set(key, source.get(key));
            }
        }
    }

    public String getMessage(String key) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        String message = langConfig.getString("messages." + key, "&cMissing message: " + key);
        message = ChatColor.translateAlternateColorCodes('&', message);
        cache.put(key, message);
        return message;
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        String message = getMessage(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    public String getMessage(String key, String... placeholders) {
        String message = getMessage(key);
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("占位符参数必须为键值对形式");
        }
        for (int i = 0; i < placeholders.length; i += 2) {
            message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return message;
    }

    public String getLogMessage(String key) {
        if (cache.containsKey("log." + key)) {
            return cache.get("log." + key);
        }

        String message = langConfig.getString("log-messages." + key, "Missing log message: " + key);
        cache.put("log." + key, message);
        return message;
    }

    public String getLogMessage(String key, String... placeholders) {
        String message = getLogMessage(key);
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("占位符参数必须为键值对形式");
        }
        for (int i = 0; i < placeholders.length; i += 2) {
            message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return message;
    }

    public void reload() {
        loadLanguage();
    }
}
