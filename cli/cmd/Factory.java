import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(
    name = "factory",
    description = "Batch-tune many agents in parallel from a factory.yaml spec. [Chapter 6]",
    mixinStandardHelpOptions = true
)
public class Factory implements Runnable {

    @Parameters(index = "0", description = "Path to the factory.yaml spec")
    private Path factoryYaml;

    @Override
    public void run() {
        System.err.println("am factory: not yet implemented (Chapter 6)");
        System.exit(2);
    }
}
