import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Command(
    name = "run",
    description = "Headless AUT run on a task + grader scoring (one iteration).",
    mixinStandardHelpOptions = true
)
public class Run implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle (AUT)")
    private Path agentPath;

    @Option(names = "--task", required = true, description = "Path to the task bundle")
    private Path taskPath;

    @Option(names = "--grader", description = "Override the task's default grader")
    private Path graderOverride;

    @Option(names = "--dry-run", description = "Stage everything; print AUT and grader commands; do not exec claude")
    private boolean dryRun;

    @Option(names = "--keep-config", description = "Keep ephemeral $CLAUDE_CONFIG_DIRs for debugging")
    private boolean keepConfig;

    @Override
    public void run() {
        try {
            System.exit(execute());
        } catch (Exception e) {
            System.err.println("am run: " + e.getMessage());
            System.exit(1);
        }
    }

    private int execute() throws Exception {
        agentPath = agentPath.toAbsolutePath().normalize();
        taskPath = taskPath.toAbsolutePath().normalize();

        Map<String, Object> task = TaskRunner.readTaskYaml(taskPath);
        if (task == null) throw new IOException("missing task.yaml in " + taskPath);
        String taskId = String.valueOf(task.getOrDefault("id", taskPath.getFileName()));

        Path graderPath = (graderOverride != null)
            ? graderOverride.toAbsolutePath().normalize()
            : taskPath.resolve(String.valueOf(task.get("grader"))).normalize();
        if (!Files.isDirectory(graderPath)) {
            throw new IOException("grader bundle not found: " + graderPath);
        }

        Path iterDir = New.findFactoryRoot()
            .resolve("runs")
            .resolve(taskId)
            .resolve(TaskRunner.agentKey(agentPath))
            .resolve(TaskRunner.nowStamp())
            .resolve("iter-1");

        System.out.println("[am run] AUT    → " + agentPath);
        System.out.println("[am run] task   → " + taskPath);
        System.out.println("[am run] grader → " + graderPath);
        System.out.println("[am run] iter   → " + iterDir);

        TaskRunner.Result r = TaskRunner.runOnce(agentPath, taskPath, graderPath, iterDir, keepConfig, dryRun);
        if (r.dryRun) return 0;

        System.out.println("[am run] score=" + r.score + " pass=" + r.pass + " issues=" + r.issueCount);
        System.out.println("[am run] " + r.rationale);
        return r.pass ? 0 : 1;
    }
}
