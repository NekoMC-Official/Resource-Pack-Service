package moe.nekomc.resourcepack.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-1 / SHA-256 小工具，统一小写十六进制输出。 */
public final class Hashing {

    private Hashing() {
    }

    public static String sha1Hex(Path file) throws IOException {
        MessageDigest digest = messageDigest("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    public static String sha1Hex(byte[] data) {
        return hex(messageDigest("SHA-1").digest(data));
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static MessageDigest messageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " 不可用", e);
        }
    }
}
