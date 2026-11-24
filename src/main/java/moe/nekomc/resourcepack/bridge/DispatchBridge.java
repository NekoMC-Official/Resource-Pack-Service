package moe.nekomc.resourcepack.bridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import moe.nekomc.resourcepack.pack.PackView;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * 通过反射接上下发宿主的 {@code CompanionPackApi}（宿主自身类型，仅出现在本类）。
 *
 * <p>本插件不在编译期依赖 Companion：它的接口随版本可能加方法，用动态代理实现
 * {@code PackProvider} 才不会因为多出一个默认方法就 {@code AbstractMethodError}。</p>
 */
public final class DispatchBridge {

    private static final String API_CLASS = "moe.nekomc.companion.api.CompanionPackApi";
    private static final String PROVIDER_CLASS = "moe.nekomc.companion.api.PackProvider";

    private final Plugin plugin;
    private Object api;
    private Class<?> apiClass;
    private Class<?> providerClass;
    private Object provider;
    private PackView view;

    public DispatchBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean attached() {
        return api != null && provider != null;
    }

    public String unavailableReason() {
        if (Bukkit.getPluginManager().getPlugin("Companion") == null) {
            return "服务器上没有 Companion";
        }
        if (api == null) {
            return "Companion 未注册 " + API_CLASS;
        }
        return "";
    }

    /** 找到 Companion 的 API 并注册提供者；失败返回 false，调用方只记一条 WARN。 */
    public boolean attach(PackView packView) {
        this.view = packView;
        Plugin companion = Bukkit.getPluginManager().getPlugin("Companion");
        if (companion == null) {
            return false;
        }
        try {
            ClassLoader loader = companion.getClass().getClassLoader();
            this.apiClass = Class.forName(API_CLASS, true, loader);
            this.providerClass = Class.forName(PROVIDER_CLASS, true, loader);
            this.api = Bukkit.getServicesManager().load(apiClass);
            if (api == null) {
                return false;
            }
            this.provider = providerProxy(providerClass, view);
            apiClass.getMethod("register", providerClass).invoke(api, provider);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            plugin.getLogger().warning("接入 Companion 失败：" + error);
            this.api = null;
            this.provider = null;
            return false;
        }
    }

    /** 版本或地址变化后重新注册一次，让 Companion 的缓存失效。 */
    public void refresh() {
        if (!attached()) {
            return;
        }
        try {
            apiClass.getMethod("register", providerClass).invoke(api, provider);
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("刷新注册失败：" + error);
        }
    }

    /** @return 1 已排程，0 没有生效包或玩家不在线 */
    public int push(UUID playerId, boolean force) {
        if (!attached()) {
            return 0;
        }
        try {
            Object result = apiClass.getMethod("push", UUID.class, boolean.class).invoke(api, playerId, force);
            return result instanceof Integer value ? value : 0;
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("推送失败：" + error);
            return 0;
        }
    }

    public String activeProvider() {
        if (!attached()) {
            return "";
        }
        try {
            Object value = apiClass.getMethod("activeProvider").invoke(api);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException error) {
            return "";
        }
    }

    public void detach() {
        if (!attached()) {
            return;
        }
        try {
            apiClass.getMethod("unregister", String.class).invoke(api, view.id());
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("注销失败：" + error);
        } finally {
            this.provider = null;
            this.api = null;
        }
    }

    /** 把 {@link PackView} 包成 Companion 的 PackProvider 实例；抽成静态方法便于单测。 */
    public static Object providerProxy(Class<?> providerType, PackView view) {
        return Proxy.newProxyInstance(
                providerType.getClassLoader(),
                new Class<?>[]{providerType},
                new ProviderHandler(view));
    }

    private static final class ProviderHandler implements InvocationHandler {

        private final PackView view;

        ProviderHandler(PackView view) {
            this.view = view;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (view == null) {
                return defaultValue(method.getReturnType());
            }
            return switch (method.getName()) {
                case "id" -> view.id();
                case "version" -> view.version();
                case "url" -> view.url();
                case "sha1" -> view.sha1();
                case "priority" -> view.priority();
                case "required" -> view.required();
                case "prompt" -> view.prompt();
                case "toString" -> "ResourcePackService[" + view.id() + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> callDefault(proxy, method, args);
            };
        }
    }

    /** 未来 Companion 新增带默认实现的方法时，走它自己的默认实现而不是返回 null。 */
    private static Object callDefault(Object proxy, Method method, Object[] args) {
        if (method.isDefault()) {
            try {
                return InvocationHandler.invokeDefault(proxy, method, args);
            } catch (Throwable ignored) {
                return defaultValue(method.getReturnType());
            }
        }
        return defaultValue(method.getReturnType());
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
