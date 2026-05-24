import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Command(
    name = "factory",
    description = "Batch-tune many agents in parallel from a factory.yaml spec.",
    mixinStandardHelpOptions = true
)
public class Factory implements Runnable {

    @Parameters(index = "0", description = "Path to the factory.yaml spec")
    private Path factoryYaml;

    @Option(names = "--parallel", defaultValue = "4", description = "Concurrent tune jobs (default 4)")
    private int parallel;

    @Option(names = "--dry-run", description = "Print each child `am tune` command; do not exec")
    private boolean dryRun;

    @Override
    public void run() {
        try {
            System.exit(execute());
        } catch (Exception e) {
            System.err.println("am factory: " + e.getMessage());
            System.exit(1);
        }
    }

    @SuppressWarnings("unchecked")
    private int execute() throws Exception {
        if (!Files.exists(factoryYaml)) {
            throw new IOException("factory spec not found: " + factoryYaml);
        }
        Map<String, Object> spec;
        try (var r = Files.newBufferedReader(factoryYaml)) {
            spec = new Yaml().load(r);
        }
        List<Map<String, Object>> agents = (List<Map<String, Object>>) spec.get("agents");
        if (agents == null || agents.isEmpty()) {
            System.err.println("[am factory] no `agents:` entries in spec");
            return 2;
        }

        Path factoryRoot = New.findFactoryRoot();
        String stem = factoryYaml.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path report = factoryRoot.resolve("factories")
            .resolve(stem + "-" + TaskRunner.nowStamp() + "-report.md");
        Files.createDirectories(report.getParent());

        System.out.println("[am factory] spec:     " + factoryYaml);
        System.out.println("[am factory] entries:  " + agents.size());
        System.out.println("[am factory] parallel: " + parallel);
        System.out.println("[am factory] report:   " + report);

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallel, agents.size()));
        List<Future<TuneJob>> futures = new ArrayList<>();
        for (int i = 0; i < agents.size(); i++) {
            final int idx = i;
            final Map<String, Object> entry = agents.get(i);
            futures.add(pool.submit(() -> runOne(idx, entry, factoryRoot)));
        }
        pool.shutdown();

        List<TuneJob> results = new ArrayList<>();
        for (Future<TuneJob> f : futures) results.add(f.get());

        writeReport(report, results);
        boolean allOk = results.stream().allMatch(r -> r.exitCode == 0);
        System.out.println("[am factory] " + (allOk ? "ALL OK" : "SOME FAILED")
            + " — " + results.stream().filter(r -> r.exitCode == 0).count() + "/" + results.size() + " ok");
        return allOk ? 0 : 1;
    }

    private TuneJob runOne(int idx, Map<String, Object> entry, Path factoryRoot) throws Exception {
        TuneJob j = new TuneJob();
        j.idx = idx;
        j.agent = String.valueOf(entry.get("agent"));
        j.task = String.valueOf(entry.get("task"));

        List<String> cmd = new ArrayList<>();
        cmd.add("jbang");
        cmd.add(factoryRoot.resolve("cli").resolve("Am.java").toString());
        cmd.add("tune");
        cmd.add(j.agent);
        cmd.add("--task"); cmd.add(j.task);
        if (entry.get("grader") != null) { cmd.add("--grader"); cmd.add(String.valueOf(entry.get("grader"))); }
        if (entry.get("tuner") != null)  { cmd.add("--tuner");  cmd.add(String.valueOf(entry.get("tuner"))); }
        if (entry.get("max_iters") != null) { cmd.add("--max-iters"); cmd.add(String.valueOf(entry.get("max_iters"))); }
        if (entry.get("target_score") != null) { cmd.add("--target-score"); cmd.add(String.valueOf(entry.get("target_score"))); }
        if (entry.get("allow_edit") != null) { cmd.add("--allow-edit"); cmd.add(String.valueOf(entry.get("allow_edit"))); }
        if (dryRun) cmd.add("--dry-run");
        j.command = cmd;

        if (dryRun) {
            System.out.println("[factory:" + idx + "] dry → " + String.join(" ", cmd));
            j.exitCode = 0;
            return j;
        }

        Path logFile = factoryRoot.resolve("runs").resolve(".factory")
            .resolve(TaskRunner.nowStamp() + "-job-" + idx + ".log");
        Files.createDirectories(logFile.getParent());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        System.out.println("[factory:" + idx + "] start → " + j.agent + " on " + j.task);
        Process p = pb.start();
        j.exitCode = p.waitFor();
        j.logFile = logFile;
        System.out.println("[factory:" + idx + "] done  → exit " + j.exitCode + " (log: " + logFile + ")");
        return j;
    }

    private void writeReport(Path file, List<TuneJob> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Factory run report\n\n");
        sb.append("Spec: `").append(factoryYaml).append("`\n");
        sb.append("Generated: ").append(TaskRunner.nowStamp()).append("\n\n");
        sb.append("| # | Agent | Task | Exit | Log |\n|---|---|---|---|---|\n");
        for (TuneJob r : results) {
            sb.append("| ").append(r.idx).append(" | `")
              .append(r.agent).append("` | `").append(r.task).append("` | ")
              .append(r.exitCode == 0 ? "OK" : "FAIL(" + r.exitCode + ")")
              .append(" | ").append(r.logFile != null ? "`" + r.logFile + "`" : "-").append(" |\n");
        }
        Files.writeString(file, sb.toString());
    }

    private static class TuneJob {
        int idx;
        String agent;
        String task;
        int exitCode;
        Path logFile;
        List<String> command;
    }
}
