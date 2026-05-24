import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(
    name = "avatar",
    description = "Launch claude-code as the agent. Builds a bare $CLAUDE_CONFIG_DIR from the bundle and execs `claude`.",
    mixinStandardHelpOptions = true
)
public class Avatar implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle")
    private Path agentPath;

    @Option(names = {"-p", "--print"},
        description = "Headless mode — passes -p PROMPT to claude")
    private String prompt;

    @Option(names = "--model",
        description = "Override the model from agent.yaml (opus | sonnet | haiku | <full id>)")
    private String modelOverride;

    @Option(names = "--keep-config",
        description = "Keep the ephemeral $CLAUDE_CONFIG_DIR after exit (default: deleted)")
    private boolean keepConfig;

    @Option(names = "--dry-run",
        description = "Build the overlay and print the command; do not exec claude")
    private boolean dryRun;

    @Override
    public void run() {
        try {
            AvatarLauncher.Options opt = new AvatarLauncher.Options();
            opt.agentPath = agentPath;
            opt.prompt = prompt;
            opt.modelOverride = modelOverride;
            opt.keepConfig = keepConfig;
            opt.dryRun = dryRun;
            int rc = AvatarLauncher.launch(opt);
            if (dryRun) {
                System.out.println("CLAUDE_CONFIG_DIR=" + opt.configDir);
                System.out.println(String.join(" ", opt.command));
            } else if (keepConfig) {
                System.err.println("[am avatar] kept config dir: " + opt.configDir);
            }
            System.exit(rc);
        } catch (Exception e) {
            System.err.println("am avatar: " + e.getMessage());
            System.exit(1);
        }
    }
}
