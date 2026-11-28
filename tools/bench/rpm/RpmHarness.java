import com.magmaguy.resourcepackmanager.mixer.engine.MixEngine;
import com.magmaguy.resourcepackmanager.mixer.engine.MixInput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixOutput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives ResourcePackManager's real merge pipeline (MixEngine.run) outside Bukkit:
 * unzip each source to a staging folder, merge JSON collisions, re-zip with
 * ParallelZipWriter. Prints the same fields the rps-pack report exposes so the
 * two arms can be compared line by line.
 *
 * <p>usage: RpmHarness &lt;outputDir&gt; &lt;outputName&gt; &lt;pack&gt;...</p>
 */
public final class RpmHarness {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RpmHarness <outputDir> <outputName> <pack>...");
            System.exit(2);
        }
        File outputDir = new File(args[0]);
        String outputName = args[1];
        List<File> packs = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            packs.add(new File(args[i]));
        }
        MixInput input = new MixInput(packs, outputDir, outputDir, outputName, false);
        long started = System.nanoTime();
        MixOutput output = new MixEngine(MixerLogger.SILENT, () -> false).run(input);
        long millis = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("millis=" + millis);
        System.out.println("sha1=" + output.sha1Hex());
        System.out.println("bytes=" + output.mergedZip().length());
        System.out.println("zip=" + output.mergedZip().getAbsolutePath());
    }
}
