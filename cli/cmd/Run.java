import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(
    name = "run",
    description = "Headless AUT run on a task + grader scoring (one iteration). [Chapter 4]",
    mixinStandardHelpOptions = true
)
public class Run implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle (AUT)")
    private Path agentPath;

    @Option(names = "--task", required = true, description = "Path to the task bundle")
    private Path taskPath;

    @Option(names = "--grader", description = "Override the task's default grader")
    private Path graderPath;

    @Override
    public void run() {
        System.err.println("am run: not yet implemented (Chapter 4)");
        System.exit(2);
    }
}
