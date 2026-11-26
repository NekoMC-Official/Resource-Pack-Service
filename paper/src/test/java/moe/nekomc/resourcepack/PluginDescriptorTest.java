package moe.nekomc.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginDescriptorTest {

    private static YamlConfiguration descriptor() throws Exception {
        try (InputStream in = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "plugin.yml 必须打进 jar");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void doesNotDependOnAnyHostPlugin() throws Exception {
        YamlConfiguration yml = descriptor();
        assertFalse(yml.contains("depend"), "plugin.yml 不应有 depend");
        assertFalse(yml.contains("softdepend"), "plugin.yml 不应有 softdepend");
    }

    @Test
    void usesAPluginNamePaperAccepts() throws Exception {
        String name = descriptor().getString("name");
        assertNotNull(name);
        assertFalse(name.contains(" "), "Paper 26.x 不允许插件名包含空格");
    }

    @Test
    void declaresMainClassAndFoliaSupport() throws Exception {
        YamlConfiguration yml = descriptor();
        assertEquals("moe.nekomc.resourcepack.ResourcePackServicePlugin", yml.getString("main"));
        assertEquals("26.3", yml.getString("api-version"));
        assertTrue(yml.getBoolean("folia-supported"), "必须声明 Folia 支持");
    }

    @Test
    void declaresGranularPermissions() throws Exception {
        YamlConfiguration yml = descriptor();
        for (String node : new String[]{"rps.use", "rps.status", "rps.list", "rps.diff",
                "rps.check", "rps.build", "rps.reload", "rps.push", "rps.community", "rps.admin"}) {
            assertNotNull(yml.get("permissions." + node), "缺少权限节点 " + node);
        }
        assertEquals("rps.use", yml.getString("commands.rps.permission"));
        assertTrue(yml.getConfigurationSection("permissions.rps.admin").contains("children"), "rps.admin 应是管理组");
    }
}
