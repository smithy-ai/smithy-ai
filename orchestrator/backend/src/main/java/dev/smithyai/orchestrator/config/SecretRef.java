package dev.smithyai.orchestrator.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

/** A secret whose storage location is configuration, while its value is not. */
public record SecretRef(String env, String file, String literal) {
    public String resolve(Environment environment, String field) {
        int sources = present(env) + present(file) + present(literal);
        if (sources > 1) {
            throw new IllegalStateException(field + " must set exactly one of env, file, or literal");
        }
        if (present(env) == 1) return environment.getProperty(env, "");
        if (present(file) == 1) {
            try {
                return Files.readString(Path.of(file)).stripTrailing();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read secret file for " + field + ": " + file, e);
            }
        }
        return literal == null ? "" : literal;
    }

    public static SecretRef literal(String value) {
        return new SecretRef(null, null, value);
    }

    private static int present(String value) {
        return value != null && !value.isBlank() ? 1 : 0;
    }

    @Override
    public String toString() {
        if (present(env) == 1) return "SecretRef(env=" + env + ")";
        if (present(file) == 1) return "SecretRef(file=" + file + ")";
        return "SecretRef(redacted)";
    }
}
