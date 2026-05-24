import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(
    name = "publish",
    description = "Package an agent bundle as a zip for shipment. [Chapter 6]",
    mixinStandardHelpOptions = true
)
public class Publish implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle")
    private Path agentPath;

    @Option(names = "--to", description = "Destination zip path (default: <factory>/dist/<name>-<version>.zip)")
    private Path to;

    @Override
    public void run() {
        System.err.println("am publish: not yet implemented (Chapter 6)");
        System.exit(2);
    }
}
