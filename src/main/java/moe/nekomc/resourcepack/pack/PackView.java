package moe.nekomc.resourcepack.pack;

/** 交给下发宿主的提供者视图，取值永远是当前快照。 */
public interface PackView {

    String id();

    int version();

    String url();

    String sha1();

    int priority();

    boolean required();

    String prompt();
}
