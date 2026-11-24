package moe.nekomc.resourcepack.delivery;

/**
 * sha1 十六进制与字节数组的互转。
 *
 * <p>Bukkit 的 setResourcePack 需要原始 20 字节摘要，配置与状态里存的是 40 位十六进制，
 * 转换集中在这里，避免各处各写一遍还写歪。</p>
 */
public final class Sha1Hex {

    private Sha1Hex() {
    }

    /**
     * 40 位十六进制转 20 字节摘要。
     *
     * @return 为空、长度不是 40 或含非十六进制字符时返回 null（调用方按"没有摘要"处理）
     */
    public static byte[] toBytes(String hex) {
        if (hex == null || hex.length() != 40) {
            return null;
        }
        byte[] out = new byte[20];
        for (int i = 0; i < 20; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 把十六进制 sha1 包成 HTTP ETag 的带引号形式。 */
    public static String quote(String hex) {
        return "\"" + (hex == null ? "" : hex.trim()) + "\"";
    }
}
