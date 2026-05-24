import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Command(
    name = "publish",
    description = "Package an agent bundle as a zip for shipment. Default dest: <factory>/dist/<id>-<version>.zip",
    mixinStandardHelpOptions = true
)
public class Publish implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle")
    private Path agentPath;

    @Option(names = "--to", description = "Destination zip path (default: <factory>/dist/<id>-<version>.zip)")
    private Path to;

    @Override
    public void run() {
        try {
            System.exit(execute());
        } catch (Exception e) {
            System.err.println("am publish: " + e.getMessage());
            System.exit(1);
        }
    }

    private int execute() throws Exception {
        agentPath = agentPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(agentPath)) {
            throw new IOException("agent bundle not found: " + agentPath);
        }
        Map<String, Object> agent = Bundle.readAgentYaml(agentPath);
        if (agent == null) throw new IOException("missing agent.yaml: " + agentPath);
        String id = String.valueOf(agent.getOrDefault("id", agentPath.getFileName().toString()));
        String version = String.valueOf(agent.getOrDefault("version", "0.0.0"));

        Path dest = (to != null)
            ? to.toAbsolutePath().normalize()
            : New.findFactoryRoot().resolve("dist").resolve(id + "-" + version + ".zip");
        Files.createDirectories(dest.getParent());

        System.out.println("[am publish] bundle: " + agentPath);
        System.out.println("[am publish] dest:   " + dest);

        int count = 0;
        long bytes = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(dest));
             Stream<Path> walk = Files.walk(agentPath)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String entryName = agentPath.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entryName));
                long n = Files.copy(p, zos);
                zos.closeEntry();
                count++;
                bytes += n;
            }
        }
        System.out.println("[am publish] " + count + " files, " + bytes + " bytes → " + dest);
        return 0;
    }
}
