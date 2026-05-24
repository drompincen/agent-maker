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

/**
 * Shared launcher used by both `am avatar` and `am run` / `am tune`.
 * Builds a bare ephemeral $CLAUDE_CONFIG_DIR from the agent bundle and execs `claude`.
 */
public final class AvatarLauncher {
    private AvatarLauncher() {}

    public static class Options {
        public Path agentPath;
        public String prompt;          // null → interactive
        public Path workingDir;        // CWD for spawned claude; null → inherit
        public Path stdoutFile;        // capture stdout to file; null → inherit IO
        public Path stderrFile;        // capture stderr to file; null → inherit IO
        public String modelOverride;   // null → use agent.yaml's model
        public String outputFormat;    // e.g. "stream-json"; null → default
        public boolean keepConfig;     // keep ephemeral config dir after exit
        public boolean dryRun;         // build everything but don't exec
        // Outputs:
        public Path configDir;
        public List<String> command;
    }

    public static int launch(Options opt) throws IOException, InterruptedException {
        opt.agentPath = opt.agentPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(opt.agentPath)) {
            throw new IOException("agent bundle not found: " + opt.agentPath);
        }
        Map<String, Object> agent = Bundle.readAgentYaml(opt.agentPath);
        if (agent == null) throw new IOException("missing agent.yaml in " + opt.agentPath);

        String id = String.valueOf(agent.getOrDefault("id", opt.agentPath.getFileName().toString()));
        String model = Bundle.resolveModelId(
            opt.modelOverride != null ? opt.modelOverride : (String) agent.get("model"));

        String runId = UUID.randomUUID().toString().substring(0, 8);
        Path configDir = Files.createTempDirectory("am-avatar-" + id + "-" + runId + "-");
        opt.configDir = configDir;

        // Bare overlay — only the agent's bundle contents.
        copyIfPresent(opt.agentPath.resolve("skills"), configDir.resolve("skills"));
        copyIfPresent(opt.agentPath.resolve("hooks"), configDir.resolve("hooks"));

        Path memSrc = opt.agentPath.resolve("memory").resolve("MEMORY.md");
        if (Files.exists(memSrc)) {
            Files.copy(memSrc, configDir.resolve("CLAUDE.md"), StandardCopyOption.REPLACE_EXISTING);
        }

        Map<String, Object> settings = synthesizeSettings(agent);
        ObjectMapper json = new ObjectMapper();
        json.enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(configDir.resolve("settings.json"), json.writeValueAsString(settings));

        Path systemPrompt = opt.agentPath.resolve("system-prompt.md");
        List<String> cmd = new ArrayList<>();
        cmd.add("claude");
        if (Files.exists(systemPrompt)) {
            cmd.add("--append-system-prompt");
            cmd.add("@" + systemPrompt);
        }
        cmd.add("--model");
        cmd.add(model);
        if (opt.outputFormat != null) {
            cmd.add("--output-format");
            cmd.add(opt.outputFormat);
            if ("stream-json".equals(opt.outputFormat)) cmd.add("--verbose");
        }
        if (opt.prompt != null) {
            cmd.add("-p");
            cmd.add(opt.prompt);
        }
        opt.command = cmd;

        if (opt.dryRun) {
            if (!opt.keepConfig) deleteRecursive(configDir);
            return 0;
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        var env = pb.environment();
        env.put("CLAUDE_CONFIG_DIR", configDir.toString());
        env.remove("CLAUDE_HOME");
        if (opt.workingDir != null) {
            Files.createDirectories(opt.workingDir);
            pb.directory(opt.workingDir.toFile());
        }
        if (opt.stdoutFile != null) {
            Files.createDirectories(opt.stdoutFile.getParent());
            pb.redirectOutput(opt.stdoutFile.toFile());
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }
        if (opt.stderrFile != null) {
            Files.createDirectories(opt.stderrFile.getParent());
            pb.redirectError(opt.stderrFile.toFile());
        } else {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

        int exit;
        try {
            Process p = pb.start();
            exit = p.waitFor();
        } catch (IOException e) {
            if (!opt.keepConfig) deleteRecursive(configDir);
            throw new IOException("failed to exec claude — is it installed and on PATH? cause: " + e.getMessage(), e);
        }

        if (!opt.keepConfig) deleteRecursive(configDir);
        return exit;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> synthesizeSettings(Map<String, Object> agent) {
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

    public static void copyIfPresent(Path src, Path dst) throws IOException {
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

    public static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.delete(x); } catch (IOException e) { /* best effort */ }
            });
        }
    }
}
