package moe.nekomc.resourcepack.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import moe.nekomc.resourcepack.pack.PackView;
import org.junit.jupiter.api.Test;

class ProviderProxyTest {

    private static PackView view() {
        return new PackView() {
            @Override
            public String id() {
                return "resourcepack-service/active";
            }

            @Override
            public int version() {
                return 7;
            }

            @Override
            public String url() {
                return "http://host/packs/merged.zip?v=7";
            }

            @Override
            public String sha1() {
                return "0123456789abcdef0123456789abcdef01234567";
            }

            @Override
            public int priority() {
                return 4;
            }

            @Override
            public boolean required() {
                return true;
            }

            @Override
            public String prompt() {
                return "下载中";
            }
        };
    }

    @Test
    void mapsEveryProviderMethodToTheLiveView() {
        StubProvider provider = (StubProvider) DispatchBridge.providerProxy(StubProvider.class, view());

        assertEquals("resourcepack-service/active", provider.id());
        assertEquals(7, provider.version());
        assertEquals("http://host/packs/merged.zip?v=7", provider.url());
        assertEquals("0123456789abcdef0123456789abcdef01234567", provider.sha1());
        assertEquals(4, provider.priority());
        assertTrue(provider.required());
        assertEquals("下载中", provider.prompt());
    }

    @Test
    void unknownMethodFallsBackInsteadOfThrowing() {
        StubProvider provider = (StubProvider) DispatchBridge.providerProxy(StubProvider.class, view());

        assertEquals("fallback", provider.unknownFutureMethod());
        assertTrue(provider.toString().contains("resourcepack-service/active"));
    }

    @Test
    void nullViewIsSafe() {
        StubProvider provider = (StubProvider) DispatchBridge.providerProxy(StubProvider.class, null);

        assertEquals("", provider.id() == null ? "" : provider.id());
        assertEquals(0, provider.version());
        assertFalse(provider.required());
    }
}
