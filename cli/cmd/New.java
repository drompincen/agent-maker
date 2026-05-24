import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Stream;

@Command(
    name = "new",
    description = "Scaffold a new agent, task, grader, or tuner bundle from a template",
    mixinStandardHelpOptions = true
)
public class New implements Runnable {

    private static final Set<String> KINDS = Set.of("agent", "task", "grader", "tuner");

    @Parameters(index = "0", description = "Kind: agent | task | grader | tuner")
    private String kind;

    @Parameters(index = "1", description = "Name (kebab-case)")
    private String name;

    @Option(names = "--at",
        description = "Target path. Default: ./<name> for agent/task; factory's <kind>s/<name> for grader/tuner.")
    private Path at;

    @Override
    public void run() {
        if (!KINDS.contains(kind)) {
            System.err.println("Error: kind must be one of " + KINDS);
            System.exit(2);
        }
        if (!name.matches("[a-z][a-z0-9-]*")) {
            System.err.println("Error: name must be kebab-case (lowercase letters, digits, hyphens), got: " + name);
            System.exit(2);
        }

        Path factoryRoot = findFactoryRoot();
        Path templateDir = factoryRoot.resolve("templates").resolve(kind);
        if (!Files.isDirectory(templateDir)) {
            System.err.println("Error: template not found at " + templateDir);
            System.exit(2);
        }

        Path target = resolveTarget(factoryRoot);
        if (Files.exists(target)) {
            System.err.println("Error: target already exists: " + target);
            System.exit(2);
        }

        try {
            copyTree(templateDir, target, name);
            System.out.println("Created " + kind + " '" + name + "' at " + target);
            System.out.println();
            System.out.println("Next steps:");
            printNext(target);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private Path resolveTarget(Path factoryRoot) {
        if (at != null) return at.toAbsolutePath().normalize();
        Path cwd = Paths.get(".").toAbsolutePath().normalize();
        return switch (kind) {
            case "agent", "task" -> cwd.resolve(name);
            case "grader" -> factoryRoot.resolve("graders").resolve(name);
            case "tuner" -> factoryRoot.resolve("tuners").resolve(name);
            default -> throw new IllegalStateException();
        };
    }

    private void printNext(Path target) {
        switch (kind) {
            case "agent" -> {
                System.out.println("  1. Edit " + target.resolve("system-prompt.md") + " — the agent's identity");
                System.out.println("  2. Edit " + target.resolve("agent.yaml") + " — id, model, allow_edit, permissions");
                System.out.println("  3. Add skills under " + target.resolve("skills") + "/");
                System.out.println("  4. Run interactively: am avatar " + target);
            }
            case "task" -> {
                System.out.println("  1. Drop input files into " + target.resolve("artifacts") + "/");
                System.out.println("  2. Drop gold outputs / rubrics into " + target.resolve("expected") + "/");
                System.out.println("  3. Edit " + target.resolve("task.yaml") + " — instructions, grader ref, target_score");
            }
            case "grader" -> {
                System.out.println("  1. Edit " + target.resolve("system-prompt.md") + " — scoring rules");
                System.out.println("  2. Edit " + target.resolve("agent.yaml") + " if needed");
            }
            case "tuner" -> {
                System.out.println("  1. Edit " + target.resolve("system-prompt.md") + " — tuner instructions");
                System.out.println("  2. Tuners are usually used as-is; the runtime injects per-run context.");
            }
        }
    }

    static Path findFactoryRoot() {
        String env = System.getenv("AGENT_MAKER_HOME");
        if (env != null) {
            Path p = Paths.get(env).toAbsolutePath().normalize();
            if (Files.exists(p.resolve("jbang-catalog.json"))) return p;
        }
        Path p = Paths.get(".").toAbsolutePath().normalize();
        while (p != null) {
            if (Files.exists(p.resolve("jbang-catalog.json"))
                && Files.isDirectory(p.resolve("templates"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new RuntimeException(
            "Cannot find factory root. Run from agent-maker dir, or set AGENT_MAKER_HOME.");
    }

    static void copyTree(Path src, Path dst, String name) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            walk.forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path out = dst.resolve(rel.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        String text = Files.readString(p);
                        text = text.replace("{{NAME}}", name);
                        Files.writeString(out, text);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
