package moe.nekomc.resourcepack.pack;

/** 注册表缝合处需要的状态三件套（世代、地址、摘要）。 */
public record HostStatus(int version, String url, String sha1) {

    public static HostStatus empty() {
        return new HostStatus(0, "", "");
    }
}
