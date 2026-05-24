import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Command(
    name = "run",
    description = "Headless AUT run on a task + grader scoring (one iteration). Persists transcripts, output, and grade.json under runs/.",
    mixinStandardHelpOptions = true
)
public class Run implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle (AUT)")
    private Path agentPath;

    @Option(names = "--task", required = true, description = "Path to the task bundle")
    private Path taskPath;

    @Option(names = "--grader", description = "Override the task's default grader")
    private Path graderOverride;

    @Option(names = "--dry-run", description = "Stage everything; print the AUT and grader commands; do not exec claude")
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

        Map<String, Object> task = readTaskYaml(taskPath);
        if (task == null) throw new IOException("missing task.yaml in " + taskPath);

        String taskId = String.valueOf(task.getOrDefault("id", taskPath.getFileName()));
        String instructions = String.valueOf(task.getOrDefault("instructions", ""));
        Path graderPath = (graderOverride != null)
            ? graderOverride.toAbsolutePath().normalize()
            : taskPath.resolve(String.valueOf(task.get("grader"))).normalize();
        if (!Files.isDirectory(graderPath)) {
            throw new IOException("grader bundle not found: " + graderPath);
        }

        // Run record dir under <factory>/runs/<task>/<agent-key>/<run-id>/iter-1/
        String agentKey = agentKey(agentPath);
        String runId = nowStamp();
        Path runDir = New.findFactoryRoot()
            .resolve("runs").resolve(taskId).resolve(agentKey).resolve(runId).resolve("iter-1");
        Files.createDirectories(runDir);
        ObjectMapper json = new ObjectMapper();
        Files.writeString(runDir.resolve("input.json"),
            json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "task", taskPath.toString(),
                "agent", agentPath.toString(),
                "grader", graderPath.toString(),
                "instructions", instructions)));

        // Stage AUT working dir: artifacts/ (read), output/ (write)
        Path autWork = runDir.resolve("aut-work");
        Files.createDirectories(autWork.resolve("output"));
        Path artifactsDir = taskPath.resolve(String.valueOf(task.getOrDefault("artifacts_dir", "artifacts/")));
        if (Files.isDirectory(artifactsDir)) {
            AvatarLauncher.copyIfPresent(artifactsDir, autWork.resolve("artifacts"));
        }

        System.out.println("[am run] AUT  → " + agentPath);
        System.out.println("[am run] task → " + taskPath);
        System.out.println("[am run] run  → " + runDir);

        AvatarLauncher.Options autOpt = new AvatarLauncher.Options();
        autOpt.agentPath = agentPath;
        autOpt.prompt = instructions;
        autOpt.workingDir = autWork;
        autOpt.outputFormat = "stream-json";
        autOpt.stdoutFile = runDir.resolve("transcript.jsonl");
        autOpt.stderrFile = runDir.resolve("aut.stderr.log");
        autOpt.keepConfig = keepConfig;
        autOpt.dryRun = dryRun;
        int autRc = AvatarLauncher.launch(autOpt);
        if (dryRun) System.out.println("[dry-run] AUT cmd: " + String.join(" ", autOpt.command));

        // Snapshot AUT output
        Path autOut = autWork.resolve("output");
        Path outSnap = runDir.resolve("output");
        if (Files.exists(autOut)) AvatarLauncher.copyIfPresent(autOut, outSnap);

        // Stage grader working dir: instructions.md (read), expected/ (read), aut-output/ (read), output/ (write)
        Path graderWork = runDir.resolve("grader-work");
        Files.createDirectories(graderWork.resolve("output"));
        Files.writeString(graderWork.resolve("instructions.md"), instructions);
        Path expectedDir = taskPath.resolve(String.valueOf(task.getOrDefault("expected_dir", "expected/")));
        if (Files.isDirectory(expectedDir)) {
            AvatarLauncher.copyIfPresent(expectedDir, graderWork.resolve("expected"));
        }
        if (Files.exists(autOut)) AvatarLauncher.copyIfPresent(autOut, graderWork.resolve("aut-output"));

        AvatarLauncher.Options grOpt = new AvatarLauncher.Options();
        grOpt.agentPath = graderPath;
        grOpt.prompt =
            "Read `instructions.md`, the `expected/` directory, and the AUT's `aut-output/` directory. " +
            "Score the AUT's output and write your judgment as a single JSON object to `output/grade.json` " +
            "per the contract in your system prompt. You do NOT have access to the AUT's transcript.";
        grOpt.workingDir = graderWork;
        grOpt.outputFormat = "stream-json";
        grOpt.stdoutFile = runDir.resolve("grader-transcript.jsonl");
        grOpt.stderrFile = runDir.resolve("grader.stderr.log");
        grOpt.keepConfig = keepConfig;
        grOpt.dryRun = dryRun;
        int grRc = AvatarLauncher.launch(grOpt);
        if (dryRun) {
            System.out.println("[dry-run] grader cmd: " + String.join(" ", grOpt.command));
            return 0;
        }

        // Read + validate grade.json
        Path gradeFile = graderWork.resolve("output").resolve("grade.json");
        if (!Files.exists(gradeFile)) {
            System.err.println("[am run] grader did not produce grade.json at " + gradeFile);
            System.err.println("[am run] grader exit: " + grRc + ", AUT exit: " + autRc);
            return 2;
        }
        Files.copy(gradeFile, runDir.resolve("grade.json"));
        @SuppressWarnings("unchecked")
        Map<String, Object> grade = json.readValue(Files.readString(gradeFile), Map.class);
        validateGrade(grade);

        System.out.println("[am run] score=" + grade.get("score")
            + " pass=" + grade.get("pass")
            + " issues=" + ((List<?>) grade.get("issues")).size());
        System.out.println("[am run] run dir: " + runDir);
        return 0;
    }

    private static Map<String, Object> readTaskYaml(Path bundle) throws IOException {
        Path f = bundle.resolve("task.yaml");
        if (!Files.exists(f)) return null;
        try (var r = Files.newBufferedReader(f)) {
            return new Yaml().load(r);
        }
    }

    /** Hash absolute agent path into the run key so two same-named agents in different paths don't collide. */
    private static String agentKey(Path agentPath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(agentPath.toString().getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
            return agentPath.getFileName().toString() + "-" + sb;
        } catch (Exception e) {
            return agentPath.getFileName().toString();
        }
    }

    private static String nowStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private static void validateGrade(Map<String, Object> g) {
        if (!(g.get("score") instanceof Number)) throw new RuntimeException("grade.json: 'score' must be a number");
        double s = ((Number) g.get("score")).doubleValue();
        if (s < 0.0 || s > 1.0) throw new RuntimeException("grade.json: 'score' out of [0.0, 1.0]: " + s);
        if (!(g.get("pass") instanceof Boolean)) throw new RuntimeException("grade.json: 'pass' must be boolean");
        if (!(g.get("issues") instanceof List<?>)) throw new RuntimeException("grade.json: 'issues' must be array");
        if (!g.containsKey("rationale")) throw new RuntimeException("grade.json: 'rationale' required");
    }
}
