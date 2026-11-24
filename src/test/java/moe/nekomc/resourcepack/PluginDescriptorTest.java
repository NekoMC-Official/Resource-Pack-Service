package moe.nekomc.resourcepack;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginDescriptorTest {

    private static String descriptor() throws Exception {
        try (InputStream in = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "plugin.yml 必须打进 jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void declaresGranularPermissions() throws Exception {
        String yml = descriptor();
        for (String node : new String[] {"rps.use", "rps.status", "rps.list", "rps.diff",
                "rps.check", "rps.build", "rps.reload", "rps.push", "rps.admin"}) {
            assertTrue(yml.contains(node + ":"), "缺少权限节点 " + node);
        }
        assertTrue(yml.contains("permission: rps.use"), "命令入口应受 rps.use 约束");
        assertTrue(yml.contains("children:"), "rps.admin 应是管理组");
    }
}
