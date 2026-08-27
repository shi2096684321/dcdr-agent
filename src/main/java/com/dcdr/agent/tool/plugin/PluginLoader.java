package com.dcdr.agent.tool.plugin;

import com.dcdr.agent.audit.AuditService;
import com.dcdr.agent.config.V3Properties;
import com.dcdr.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3.0-rc F-37 插件加载器。
 * <p>
 * 启动时扫描 {@code agent.v3.plugin.dir} (默认 ./plugins) 目录下的 *.jar:
 * <ol>
 *   <li><b>危险 API 审计</b> (文档 §11/§14.9): 扫描 class 字节码中的
 *       Runtime/ProcessBuilder/反射 特征, 命中且 blockDangerous=true 时拒绝加载并记审计日志</li>
 *   <li>URLClassLoader + ServiceLoader 实例化 {@link ToolPlugin}</li>
 *   <li>initialize(context) → getTools() → 经 {@link PluginToolAdapter} 注册进 ToolRegistry</li>
 * </ol>
 * 单个插件加载失败不影响其他插件与主程序; 支持 POST /api/plugins/reload 热重载
 * (先 destroy 旧实例 + 反注册旧工具)。
 */
@Slf4j
@Service
public class PluginLoader implements ApplicationRunner {

    private final V3Properties v3Properties;
    private final ToolRegistry toolRegistry;
    private final AuditService auditService;

    /** 已加载插件: pluginName -> 运行态 */
    private final Map<String, LoadedPlugin> loaded = new ConcurrentHashMap<>();

    /** 危险 API 黑名单 (class 常量池字符串特征, 轻量字节码审计) */
    private static final List<String> DANGEROUS_PATTERNS = List.of(
            "java/lang/Runtime", "java/lang/ProcessBuilder", "java/lang/invoke/StringConcatFactory",
            "java/lang/reflect/InvocationHandler", "setSecurityManager", "System.exit"
    );

    public PluginLoader(V3Properties v3Properties, ToolRegistry toolRegistry, AuditService auditService) {
        this.v3Properties = v3Properties;
        this.toolRegistry = toolRegistry;
        this.auditService = auditService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!v3Properties.getPlugin().isEnabled()) {
            log.info("插件系统未启用 (agent.v3.plugin.enabled=false), 跳过加载");
            return;
        }
        loadAll();
    }

    /** 扫描并加载全部插件 (reload 时复用) */
    public synchronized Map<String, LoadedPlugin> loadAll() {
        Path dir = Paths.get(v3Properties.getPlugin().getDir());
        if (!Files.isDirectory(dir)) {
            log.info("插件目录不存在, 跳过: {}", dir.toAbsolutePath());
            return Map.of();
        }
        List<Path> jars;
        try {
            jars = Files.list(dir).filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            log.error("扫描插件目录失败: {}", dir, e);
            return Map.of();
        }
        for (Path jar : jars) {
            try {
                loadJar(jar);
            } catch (Exception e) {
                log.error("插件加载失败: {}", jar.getFileName(), e);
                auditService.record("plugin_load_failed", "plugin", jar.getFileName().toString(),
                        "插件加载失败: " + e.getMessage(), "system");
            }
        }
        log.info("插件加载完成: {} 个插件, {} 个工具", loaded.size(), toolCount());
        auditService.record("plugin_load", "plugin", "all",
                "加载 " + loaded.size() + " 个插件, " + toolCount() + " 个工具", "system");
        return Map.copyOf(loaded);
    }

    private void loadJar(Path jar) throws Exception {
        List<String> violations = auditDangerousApis(jar);
        if (!violations.isEmpty() && v3Properties.getPlugin().isBlockDangerous()) {
            log.warn("插件 {} 危险 API 审计未通过, 拒绝加载: {}", jar.getFileName(), violations);
            auditService.record("plugin_blocked", "plugin", jar.getFileName().toString(),
                    "危险 API: " + String.join(", ", violations), "system");
            return;
        } else if (!violations.isEmpty()) {
            log.warn("插件 {} 检出危险 API 但 blockDangerous=false, 继续加载: {}", jar.getFileName(), violations);
        }

        try (URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                getClass().getClassLoader())) {
            ServiceLoader<ToolPlugin> serviceLoader = ServiceLoader.load(ToolPlugin.class, classLoader);
            for (ToolPlugin plugin : serviceLoader) {
                String name = plugin.getName();
                // 同名插件: 先卸载旧实例 (热重载场景)
                unload(name);
                try {
                    plugin.initialize(new PluginContext(
                            v3Properties.getPlugin().getDir(), Map.of()));
                    List<String> registered = new ArrayList<>();
                    for (ToolPlugin.PluginTool t : plugin.getTools()) {
                        toolRegistry.register(new PluginToolAdapter(t, name));
                        registered.add(t.name());
                    }
                    loaded.put(name, new LoadedPlugin(name, plugin.getVersion(),
                            plugin.getAuthor(), jar.getFileName().toString(), registered, plugin));
                    log.info("加载插件: {} v{} (工具: {})", name, plugin.getVersion(), registered);
                } catch (Exception e) {
                    try { plugin.destroy(); } catch (Exception ignore) { }
                    throw e;
                }
            }
        }
    }

    /** 卸载插件 (反注册工具 + destroy) */
    public synchronized void unload(String pluginName) {
        LoadedPlugin p = loaded.remove(pluginName);
        if (p == null) return;
        for (String toolName : p.tools()) {
            toolRegistry.unregister(toolName);
        }
        try { p.plugin().destroy(); } catch (Exception e) { log.warn("插件 {} destroy 失败", pluginName, e); }
    }

    /** 危险 API 审计: 解析 jar 内 .class 文件的常量池字符串特征 */
    private List<String> auditDangerousApis(Path jar) throws IOException {
        Set<String> violations = new LinkedHashSet<>();
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                byte[] bytes;
                try (InputStream in = jarFile.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                // 粗粒度扫描: 常量池字符串以 UTF-8 明文存在于字节码中, includes 即命中
                String probe = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                for (String pattern : DANGEROUS_PATTERNS) {
                    if (probe.contains(pattern)) violations.add(pattern);
                }
                if (violations.size() >= 3) break; // 采样上限, 避免大 jar 扫描过久
            }
        }
        return List.copyOf(violations);
    }

    public int toolCount() {
        return loaded.values().stream().mapToInt(p -> p.tools().size()).sum();
    }

    /** 插件运行态视图 (GET /api/plugins) */
    public List<Map<String, Object>> listPlugins() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LoadedPlugin p : loaded.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("version", p.version());
            m.put("author", p.author());
            m.put("jar", p.jar());
            m.put("tools", p.tools());
            list.add(m);
        }
        return list;
    }

    /**
     * 插件运行态记录。
     */
    public record LoadedPlugin(String name, String version, String author, String jar,
                               List<String> tools, ToolPlugin plugin) { }
}
