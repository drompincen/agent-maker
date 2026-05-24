import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Command(
    name = "avatar",
    description = "Launch claude-code as the agent. Builds a bare $CLAUDE_CONFIG_DIR from the bundle and execs `claude`.",
    mixinStandardHelpOptions = true
)
public class Avatar implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle")
    private Path agentPath;

    @Option(names = {"-p", "--print"},
        description = "Headless mode — passes -p PROMPT to claude and exits when claude exits")
    private String prompt;

    @Option(names = "--model",
        description = "Override the model from agent.yaml (opus | sonnet | haiku | <full id>)")
    private String modelOverride;

    @Option(names = "--keep-config",
        description = "Keep the ephemeral $CLAUDE_CONFIG_DIR after exit (default: deleted)")
    private boolean keepConfig;

    @Option(names = "--dry-run",
        description = "Build the overlay and print the command, but do not exec claude")
    private boolean dryRun;

    @Override
    public void run() {
        try {
            int exit = launch();
            System.exit(exit);
        } catch (Exception e) {
            System.err.println("am avatar: " + e.getMessage());
            System.exit(1);
        }
    }

    private int launch() throws IOException, InterruptedException {
        agentPath = agentPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(agentPath)) {
            throw new IOException("agent bundle not found: " + agentPath);
        }
        Map<String, Object> agent = Bundle.readAgentYaml(agentPath);
        if (agent == null) {
            throw new IOException("missing agent.yaml in " + agentPath);
        }
        String id = String.valueOf(agent.getOrDefault("id", agentPath.getFileName().toString()));
        String model = Bundle.resolveModelId(modelOverride != null ? modelOverride : (String) agent.get("model"));

        // 1. Ephemeral $CLAUDE_CONFIG_DIR
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Path configDir = Files.createTempDirectory("am-avatar-" + id + "-" + runId + "-");

        // 2. Symlink-via-copy the bundle's skills/, hooks/, mcp/ (bare — no drom-flow).
        copyIfPresent(agentPath.resolve("skills"), configDir.resolve("skills"));
        copyIfPresent(agentPath.resolve("hooks"), configDir.resolve("hooks"));

        // 3. Seed memory (claude-code reads CLAUDE.md from $CLAUDE_CONFIG_DIR).
        Path memSrc = agentPath.resolve("memory").resolve("MEMORY.md");
        if (Files.exists(memSrc)) {
            Files.copy(memSrc, configDir.resolve("CLAUDE.md"), StandardCopyOption.REPLACE_EXISTING);
        }

        // 4. Synthesize settings.json from agent.yaml.
        Map<String, Object> settings = synthesizeSettings(agent);
        ObjectMapper json = new ObjectMapper();
        json.enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(configDir.resolve("settings.json"), json.writeValueAsString(settings));

        // 5. Build claude command — bare: no drom-flow files entered the overlay.
        Path systemPrompt = agentPath.resolve("system-prompt.md");
        List<String> cmd = new ArrayList<>();
        cmd.add("claude");
        if (Files.exists(systemPrompt)) {
            cmd.add("--append-system-prompt");
            cmd.add("@" + systemPrompt);
        }
        cmd.add("--model");
        cmd.add(model);
        if (prompt != null) {
            cmd.add("-p");
            cmd.add(prompt);
        }

        if (dryRun) {
            System.out.println("CLAUDE_CONFIG_DIR=" + configDir);
            System.out.println(String.join(" ", cmd));
            if (!keepConfig) deleteRecursive(configDir);
            return 0;
        }

        // 6. Env hygiene — replace any inherited CLAUDE_CONFIG_DIR; clear similar leaks.
        ProcessBuilder pb = new ProcessBuilder(cmd);
        var env = pb.environment();
        env.put("CLAUDE_CONFIG_DIR", configDir.toString());
        env.remove("CLAUDE_HOME");
        pb.inheritIO();

        int exit;
        try {
            Process p = pb.start();
            exit = p.waitFor();
        } catch (IOException e) {
            System.err.println("am avatar: failed to exec claude — is it installed and on PATH?");
            System.err.println("  config dir was: " + configDir);
            return 127;
        }

        if (keepConfig) {
            System.err.println("[am avatar] kept config dir: " + configDir);
        } else {
            deleteRecursive(configDir);
        }
        return exit;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> synthesizeSettings(Map<String, Object> agent) {
        Map<String, Object> s = new LinkedHashMap<>();
        Object toolsObj = agent.get("tool_allowlist");
        if (toolsObj instanceof Map<?, ?> tools) {
            Map<String, Object> perms = new LinkedHashMap<>();
            if (tools.get("allow") != null) perms.put("allow", tools.get("allow"));
            if (tools.get("deny") != null) perms.put("deny", tools.get("deny"));
            s.put("permissions", perms);
        }
        Object hooks = agent.get("hooks");
        if (hooks != null) s.put("hooks", hooks);
        Object mcps = agent.get("mcp_servers");
        if (mcps instanceof List<?> list) {
            Map<String, Object> mcpMap = new LinkedHashMap<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> srv) {
                    String name = String.valueOf(srv.get("name"));
                    Map<String, Object> entry = new LinkedHashMap<>();
                    for (var e : srv.entrySet()) {
                        if (!"name".equals(e.getKey())) entry.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    mcpMap.put(name, entry);
                }
            }
            s.put("mcpServers", mcpMap);
        }
        return s;
    }

    private static void copyIfPresent(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) return;
        try (Stream<Path> walk = Files.walk(src)) {
            walk.forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path out = dst.resolve(rel.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.delete(x); } catch (IOException e) { /* best effort */ }
            });
        }
    }
}
