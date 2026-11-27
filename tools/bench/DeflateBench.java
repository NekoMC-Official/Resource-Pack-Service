
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class DeflateBench {
    static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(6, true);
        d.setInput(raw); d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
        byte[] buf = new byte[64 * 1024];
        while (!d.finished()) { int n = d.deflate(buf); bos.write(buf, 0, n); }
        d.end();
        return bos.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        List<byte[]> blobs = new ArrayList<>();
        for (String a : args) {
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(a)))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    zis.transferTo(bos);
                    blobs.add(bos.toByteArray());
                }
            }
        }
        long raw = blobs.stream().mapToLong(b -> b.length).sum();
        long t0 = System.nanoTime();
        long sum = 0;
        for (byte[] b : blobs) sum += deflate(b).length;
        long seq = (System.nanoTime() - t0) / 1_000_000L;
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        long t1 = System.nanoTime();
        List<Future<Integer>> futures = new ArrayList<>();
        for (byte[] b : blobs) futures.add(pool.submit(() -> deflate(b).length));
        long sum2 = 0;
        for (Future<Integer> f : futures) sum2 += f.get();
        long par = (System.nanoTime() - t1) / 1_000_000L;
        pool.shutdown();
        System.out.println("raw=" + raw + " seqDeflate=" + seq + "ms par4=" + par + "ms speedup=" + String.format("%.2f", seq * 1.0 / Math.max(1, par)) + " sum=" + sum + "/" + sum2);
    }
}
