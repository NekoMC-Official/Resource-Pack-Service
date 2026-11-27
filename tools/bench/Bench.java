
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class Bench {
    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        for (int s = 0; s < args.length; s++) {
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(args[s])))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    zis.transferTo(bos);
                    entries.put(e.getName(), bos.toByteArray());
                }
            }
        }
        Path out = Paths.get("merged-java.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            zos.setLevel(6);
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(en.getKey());
                ze.setTime(0L);
                zos.putNextEntry(ze);
                zos.write(en.getValue());
                zos.closeEntry();
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("java " + ms + " ms, entries=" + entries.size() + ", bytes=" + Files.size(out));
    }
}
