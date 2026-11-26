package moe.nekomc.resourcepack.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Paths;
import moe.nekomc.resourcepack.platform.BukkitConfigSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** provider.announce-required 的三态解析：缺省跟随 provider.required，显式值覆盖。 */
class RpsConfigAnnounceRequiredTest {

    private static RpsConfig load(Boolean required, Boolean announceRequired) {
        YamlConfiguration config = new YamlConfiguration();
        if (required != null) {
            config.set("provider.required", required);
        }
        if (announceRequired != null) {
            config.set("provider.announce-required", announceRequired);
        }
        return RpsConfig.load(new BukkitConfigSource(config), Paths.get("."), null);
    }

    @Test
    void missingKeyFollowsRequired() {
        assertEquals(true, load(true, null).announceRequired());
        assertEquals(true, load(true, null).required());
        assertEquals(false, load(false, null).announceRequired());
        assertEquals(false, load(null, null).announceRequired());
    }

    @Test
    void explicitFalseOverridesRequired() {
        RpsConfig config = load(true, false);
        assertEquals(true, config.required());
        assertEquals(false, config.announceRequired());
    }

    @Test
    void explicitTrueDoesNotChangeRequired() {
        RpsConfig config = load(false, true);
        assertEquals(false, config.required());
        assertEquals(true, config.announceRequired());
    }
}
