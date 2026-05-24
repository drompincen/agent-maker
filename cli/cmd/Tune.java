import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(
    name = "tune",
    description = "Closed-loop tuning with AUT/Grader/Tuner triad. Loops until target_score, max-iters, or plateau.",
    mixinStandardHelpOptions = true
)
public class Tune implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle (AUT) — will be edited by the tuner")
    private Path agentPath;

    @Option(names = "--task", required = true, description = "Path to the task bundle")
    private Path taskPath;

    @Option(names = "--grader", description = "Override the task's default grader")
    private Path graderOverride;

    @Option(names = "--tuner", description = "Path to the tuner bundle (default: factory's tuners/default)")
    private Path tunerPath;

    @Option(names = "--max-iters", defaultValue = "10", description = "Max iterations (default: 10)")
    private int maxIters;

    @Option(names = "--target-score", defaultValue = "0.9", description = "Stop when score >= this (default: 0.9)")
    private double targetScore;

    @Option(names = "--allow-edit", defaultValue = "prompt,skills,scripts",
        description = "Comma-separated scopes the tuner may edit (default: prompt,skills,scripts)")
    private String allowEdit;

    @Option(names = "--plateau-epsilon", defaultValue = "0.01",
        description = "Stop if score variance over last 3 iters < this (default: 0.01)")
    private double plateauEpsilon;

    @Option(names = "--dry-run", description = "Stage everything; do not exec claude; exit after iter 1")
    private boolean dryRun;

    @Option(names = "--keep-config", description = "Keep ephemeral $CLAUDE_CONFIG_DIRs for debugging")
    private boolean keepConfig;

    @Override
    public void run() {
        try {
            System.exit(execute());
        } catch (Exception e) {
            System.err.println("am tune: " + e.getMessage());
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
        if (!Files.isDirectory(graderPath)) throw new IOException("grader bundle not found: " + graderPath);

        Path effectiveTuner = (tunerPath != null)
            ? tunerPath.toAbsolutePath().normalize()
            : New.findFactoryRoot().resolve("tuners").resolve("default");
        if (!Files.isDirectory(effectiveTuner)) {
            throw new IOException("tuner bundle not found: " + effectiveTuner
                + " — create with `am new tuner default`");
        }

        Set<String> scopes = Arrays.stream(allowEdit.split(","))
            .map(String::trim).collect(Collectors.toSet());

        Path tuneDir = New.findFactoryRoot()
            .resolve("runs").resolve(taskId)
            .resolve(TaskRunner.agentKey(agentPath))
            .resolve(TaskRunner.nowStamp());
        Files.createDirectories(tuneDir);

        System.out.println("[am tune] AUT          → " + agentPath);
        System.out.println("[am tune] task         → " + taskPath);
        System.out.println("[am tune] grader       → " + graderPath);
        System.out.println("[am tune] tuner        → " + effectiveTuner);
        System.out.println("[am tune] allow_edit   → " + scopes);
        System.out.println("[am tune] max_iters    → " + maxIters);
        System.out.println("[am tune] target_score → " + targetScore);
        System.out.println("[am tune] run dir      → " + tuneDir);

        List<Double> history = new ArrayList<>();
        String exitReason = "max-iters";

        for (int iter = 1; iter <= maxIters; iter++) {
            Path iterDir = tuneDir.resolve(String.format("iter-%02d", iter));
            System.out.println();
            System.out.println("=== iter " + iter + " ===");

            TaskRunner.Result r = TaskRunner.runOnce(agentPath, taskPath, graderPath, iterDir, keepConfig, dryRun);

            if (dryRun) {
                exitReason = "dry-run";
                break;
            }

            System.out.println("[iter " + iter + "] score=" + r.score + " pass=" + r.pass + " issues=" + r.issueCount);
            history.add(r.score);

            if (r.score >= targetScore) {
                exitReason = "converged (score >= target)";
                break;
            }
            if (r.pass) {
                exitReason = "converged (grader said pass)";
                break;
            }
            if (history.size() >= 3 && plateau(history.subList(history.size() - 3, history.size()))) {
                exitReason = "plateau (variance < " + plateauEpsilon + ")";
                break;
            }
            if (iter == maxIters) break;

            // Run tuner
            Path tunerWork = iterDir.resolve("tuner-work");
            Files.createDirectories(tunerWork);
            AvatarLauncher.copyIfPresent(agentPath, tunerWork.resolve("aut-source"));
            Files.copy(r.transcriptPath, tunerWork.resolve("transcript.jsonl"));
            Files.copy(r.gradePath, tunerWork.resolve("grade.json"));
            Files.writeString(tunerWork.resolve("instructions.md"), r.instructions);
            Files.writeString(tunerWork.resolve("allow-edit.txt"), allowEdit);

            AvatarLauncher.Options tOpt = new AvatarLauncher.Options();
            tOpt.agentPath = effectiveTuner;
            tOpt.prompt =
                "Read transcript.jsonl, grade.json, instructions.md, and the AUT's source in aut-source/. " +
                "Make MINIMAL edits directly to files in aut-source/ that address the highest-severity issues. " +
                "Allowed scopes (only edit files matching these): " + allowEdit + ". " +
                "Edit files in place with your Edit/Write tools — do NOT emit a diff. " +
                "Edit only what you need; prefer the smallest change.";
            tOpt.workingDir = tunerWork;
            tOpt.outputFormat = "stream-json";
            tOpt.stdoutFile = iterDir.resolve("tuner-transcript.jsonl");
            tOpt.stderrFile = iterDir.resolve("tuner.stderr.log");
            tOpt.keepConfig = keepConfig;
            AvatarLauncher.launch(tOpt);

            // Apply tuner's edits back to the agent's home path, scoped.
            ApplyResult applied = applyChanges(tunerWork.resolve("aut-source"), agentPath, scopes);
            Files.writeString(iterDir.resolve("tuner-apply.txt"), applied.summary());
            System.out.println("[iter " + iter + "] tuner applied: " + applied.summary());

            if (applied.changedCount == 0) {
                exitReason = "tuner made no in-scope changes";
                break;
            }
        }

        writeSummary(tuneDir, history, exitReason);
        System.out.println();
        System.out.println("[am tune] exit: " + exitReason);
        System.out.println("[am tune] summary: " + tuneDir.resolve("SUMMARY.md"));
        return exitReason.startsWith("converged") || "dry-run".equals(exitReason) ? 0 : 1;
    }

    private boolean plateau(List<Double> last3) {
        double mean = last3.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = last3.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0);
        return var < plateauEpsilon;
    }

    /** Scope-checked copy from tuner's edited bundle back to the agent's home path. */
    private ApplyResult applyChanges(Path tunedSrc, Path agentHome, Set<String> scopes) throws IOException {
        ApplyResult result = new ApplyResult();
        try (Stream<Path> walk = Files.walk(tunedSrc)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                Path rel = tunedSrc.relativize(p);
                String relStr = rel.toString().replace('\\', '/');
                if (!inScope(relStr, scopes)) {
                    result.skipped.add(relStr);
                    return;
                }
                try {
                    Path target = agentHome.resolve(rel.toString());
                    boolean differs = !Files.exists(target)
                        || !java.util.Arrays.equals(Files.readAllBytes(target), Files.readAllBytes(p));
                    if (differs) {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                        result.changed.add(relStr);
                        result.changedCount++;
                    }
                } catch (IOException e) {
                    result.skipped.add(relStr + " (io: " + e.getMessage() + ")");
                }
            });
        }
        return result;
    }

    private static boolean inScope(String relPath, Set<String> scopes) {
        if (scopes.contains("all")) return true;
        if (scopes.contains("prompt") && relPath.equals("system-prompt.md")) return true;
        if (scopes.contains("prompt") && relPath.equals("agent.yaml")) return false; // never edit agent.yaml by default
        if (scopes.contains("skills") && relPath.startsWith("skills/")) return true;
        if (scopes.contains("scripts") && relPath.startsWith("scripts/")) return true;
        if (scopes.contains("hooks") && relPath.startsWith("hooks/")) return true;
        if (scopes.contains("mcp") && relPath.startsWith("mcp/")) return true;
        return false;
    }

    private static class ApplyResult {
        int changedCount;
        List<String> changed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String summary() {
            return "changed=" + changedCount + " skipped=" + skipped.size()
                + " files=" + changed;
        }
    }

    private void writeSummary(Path tuneDir, List<Double> history, String exitReason) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Tuning run summary\n\n");
        sb.append("- Exit: ").append(exitReason).append("\n");
        sb.append("- Iterations: ").append(history.size()).append("\n");
        sb.append("- Target: ").append(String.format(Locale.ROOT, "%.3f", targetScore)).append("\n");
        sb.append("- Best score: ").append(history.stream().mapToDouble(Double::doubleValue).max().orElse(0)).append("\n\n");
        sb.append("| Iter | Score |\n|---|---|\n");
        for (int i = 0; i < history.size(); i++) {
            sb.append("| ").append(i + 1).append(" | ")
              .append(String.format(Locale.ROOT, "%.3f", history.get(i))).append(" |\n");
        }
        Files.writeString(tuneDir.resolve("SUMMARY.md"), sb.toString());
    }
}
