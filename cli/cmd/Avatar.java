import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(
    name = "avatar",
    description = "Launch claude-code as the agent (interactive or headless via -p). [Chapter 3]",
    mixinStandardHelpOptions = true
)
public class Avatar implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle")
    private Path agentPath;

    @Option(names = {"-p", "--print"}, description = "Headless mode — pass prompt to claude -p")
    private String prompt;

    @Option(names = "--cleanup", description = "Nuke the ephemeral $CLAUDE_CONFIG_DIR on exit", defaultValue = "false")
    private boolean cleanup;

    @Override
    public void run() {
        System.err.println("am avatar: not yet implemented (Chapter 3)");
        System.err.println("  agent: " + agentPath);
        if (prompt != null) System.err.println("  prompt: " + prompt);
        System.exit(2);
    }
}
