import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Shared helpers for reading an agent bundle. */
public final class Bundle {
    private Bundle() {}

    /** Parse <bundle>/agent.yaml. Returns null if missing. */
    public static Map<String, Object> readAgentYaml(Path bundle) throws IOException {
        Path f = bundle.resolve("agent.yaml");
        if (!Files.exists(f)) return null;
        try (var r = Files.newBufferedReader(f)) {
            return new Yaml().load(r);
        }
    }

    /** Map a friendly model alias to a concrete model id. */
    public static String resolveModelId(String alias) {
        if (alias == null) return "claude-opus-4-7";
        return switch (alias) {
            case "opus" -> "claude-opus-4-7";
            case "sonnet" -> "claude-sonnet-4-6";
            case "haiku" -> "claude-haiku-4-5";
            default -> alias;
        };
    }
}
