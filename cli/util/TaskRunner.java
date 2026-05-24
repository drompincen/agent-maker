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

/**
 * Single-iteration AUT-run + grader-score pipeline. Used by both `am run` and `am tune`.
 * Writes everything under the provided iter dir; never reaches outside it.
 */
public final class TaskRunner {
    private TaskRunner() {}

    public static class Result {
        public double score;
        public boolean pass;
        public int issueCount;
        public String rationale;
        public Path iterDir;
        public Path transcriptPath;
        public Path gradePath;
        public Path outputSnapshot;
        public String instructions;
        public boolean dryRun;
    }

    public static Result runOnce(
        Path agentPath,
        Path taskPath,
        Path graderPath,
        Path iterDir,
        boolean keepConfig,
        boolean dryRun
    ) throws Exception {
        Map<String, Object> task = readTaskYaml(taskPath);
        if (task == null) throw new IOException("missing task.yaml: " + taskPath);
        String instructions = String.valueOf(task.getOrDefault("instructions", ""));

        Files.createDirectories(iterDir);
        ObjectMapper json = new ObjectMapper();
        Files.writeString(iterDir.resolve("input.json"),
            json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "task", taskPath.toString(),
                "agent", agentPath.toString(),
                "grader", graderPath.toString(),
                "instructions", instructions)));

        // Stage AUT work
        Path autWork = iterDir.resolve("aut-work");
        Files.createDirectories(autWork.resolve("output"));
        Path artifactsDir = taskPath.resolve(String.valueOf(task.getOrDefault("artifacts_dir", "artifacts/")));
        if (Files.isDirectory(artifactsDir)) {
            AvatarLauncher.copyIfPresent(artifactsDir, autWork.resolve("artifacts"));
        }

        AvatarLauncher.Options autOpt = new AvatarLauncher.Options();
        autOpt.agentPath = agentPath;
        autOpt.prompt = instructions;
        autOpt.workingDir = autWork;
        autOpt.outputFormat = "stream-json";
        autOpt.stdoutFile = iterDir.resolve("transcript.jsonl");
        autOpt.stderrFile = iterDir.resolve("aut.stderr.log");
        autOpt.keepConfig = keepConfig;
        autOpt.dryRun = dryRun;
        int autRc = AvatarLauncher.launch(autOpt);

        // Snapshot AUT output
        Path autOut = autWork.resolve("output");
        Path outSnap = iterDir.resolve("output");
        if (Files.exists(autOut)) AvatarLauncher.copyIfPresent(autOut, outSnap);

        // Stage grader work (independence: NO transcript)
        Path grWork = iterDir.resolve("grader-work");
        Files.createDirectories(grWork.resolve("output"));
        Files.writeString(grWork.resolve("instructions.md"), instructions);
        Path expectedDir = taskPath.resolve(String.valueOf(task.getOrDefault("expected_dir", "expected/")));
        if (Files.isDirectory(expectedDir)) {
            AvatarLauncher.copyIfPresent(expectedDir, grWork.resolve("expected"));
        }
        if (Files.exists(autOut)) AvatarLauncher.copyIfPresent(autOut, grWork.resolve("aut-output"));

        AvatarLauncher.Options grOpt = new AvatarLauncher.Options();
        grOpt.agentPath = graderPath;
        grOpt.prompt =
            "Read `instructions.md`, the `expected/` directory, and the AUT's `aut-output/` directory. " +
            "Score the AUT's output and write your judgment as a single JSON object to `output/grade.json` " +
            "per the contract in your system prompt. You do NOT have access to the AUT's transcript.";
        grOpt.workingDir = grWork;
        grOpt.outputFormat = "stream-json";
        grOpt.stdoutFile = iterDir.resolve("grader-transcript.jsonl");
        grOpt.stderrFile = iterDir.resolve("grader.stderr.log");
        grOpt.keepConfig = keepConfig;
        grOpt.dryRun = dryRun;
        AvatarLauncher.launch(grOpt);

        Result r = new Result();
        r.iterDir = iterDir;
        r.transcriptPath = iterDir.resolve("transcript.jsonl");
        r.outputSnapshot = outSnap;
        r.instructions = instructions;
        r.dryRun = dryRun;

        if (dryRun) {
            System.out.println("[dry-run] AUT cmd:    " + String.join(" ", autOpt.command));
            System.out.println("[dry-run] grader cmd: " + String.join(" ", grOpt.command));
            return r;
        }

        Path gradeFile = grWork.resolve("output").resolve("grade.json");
        if (!Files.exists(gradeFile)) {
            throw new IOException("grader did not produce grade.json at " + gradeFile);
        }
        Files.copy(gradeFile, iterDir.resolve("grade.json"));
        r.gradePath = iterDir.resolve("grade.json");

        @SuppressWarnings("unchecked")
        Map<String, Object> grade = json.readValue(Files.readString(gradeFile), Map.class);
        validateGrade(grade);
        r.score = ((Number) grade.get("score")).doubleValue();
        r.pass = (Boolean) grade.get("pass");
        r.issueCount = ((List<?>) grade.get("issues")).size();
        r.rationale = String.valueOf(grade.get("rationale"));
        return r;
    }

    public static Map<String, Object> readTaskYaml(Path bundle) throws IOException {
        Path f = bundle.resolve("task.yaml");
        if (!Files.exists(f)) return null;
        try (var r = Files.newBufferedReader(f)) {
            return new Yaml().load(r);
        }
    }

    /** Hash absolute agent path so two same-named agents in different paths don't collide. */
    public static String agentKey(Path agentPath) {
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

    public static String nowStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    public static void validateGrade(Map<String, Object> g) {
        if (!(g.get("score") instanceof Number)) throw new RuntimeException("grade.json: 'score' must be a number");
        double s = ((Number) g.get("score")).doubleValue();
        if (s < 0.0 || s > 1.0) throw new RuntimeException("grade.json: 'score' out of [0.0, 1.0]: " + s);
        if (!(g.get("pass") instanceof Boolean)) throw new RuntimeException("grade.json: 'pass' must be boolean");
        if (!(g.get("issues") instanceof List<?>)) throw new RuntimeException("grade.json: 'issues' must be array");
        if (!g.containsKey("rationale")) throw new RuntimeException("grade.json: 'rationale' required");
    }
}
