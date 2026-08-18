package dev.smithyai.orchestrator.runtime.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a definition's declared output shape into a JSON schema.
 *
 * <p>The existing structured agent turns generate their schema from a Java DTO,
 * which a YAML workflow cannot name. A definition writes the shape it wants
 * instead, in the same YAML as the rest of the step:
 *
 * <pre>
 * output:
 *   summary: string
 *   tasks:
 *     - repo: string
 *       title: string
 *       wave: number
 * </pre>
 *
 * <p>A scalar is named by a type; a map is a nested object; a one-element list
 * is an array of whatever that element describes. Every declared field is
 * required, because a definition that asks for a field and reads it downstream
 * has no use for it being absent.
 */
public final class OutputSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> SCALARS = Map.of(
        "string",
        "string",
        "number",
        "number",
        "integer",
        "integer",
        "boolean",
        "boolean"
    );

    private OutputSchema() {}

    public static String toJsonSchema(Map<String, Object> declared) {
        try {
            return MAPPER.writeValueAsString(objectSchema(declared, ""));
        } catch (Exception e) {
            throw new WorkflowDefinitionException("Failed to build a schema from the declared output shape", e);
        }
    }

    private static Map<String, Object> objectSchema(Map<String, Object> declared, String path) {
        if (declared == null || declared.isEmpty()) {
            throw new WorkflowDefinitionException("Declared output shape at '" + path + "' is empty");
        }
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        declared.forEach((field, shape) -> {
            properties.put(field, fieldSchema(shape, path.isEmpty() ? field : path + "." + field));
            required.add(field);
        });

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldSchema(Object shape, String path) {
        return switch (shape) {
            case String scalar -> {
                String type = SCALARS.get(scalar.strip().toLowerCase());
                if (type == null) {
                    throw new WorkflowDefinitionException(
                        "Unknown output type '%s' at '%s'; expected one of %s, a nested map, or a one-element list".formatted(
                            scalar,
                            path,
                            SCALARS.keySet()
                        )
                    );
                }
                yield Map.of("type", type);
            }
            case Map<?, ?> nested -> objectSchema((Map<String, Object>) nested, path);
            case List<?> list -> {
                if (list.size() != 1) {
                    throw new WorkflowDefinitionException(
                        "Array shape at '%s' must have exactly one element describing its items, found %d".formatted(
                            path,
                            list.size()
                        )
                    );
                }
                yield Map.of("type", "array", "items", fieldSchema(list.getFirst(), path + "[]"));
            }
            case null -> throw new WorkflowDefinitionException("Output field '" + path + "' has no declared type");
            default -> throw new WorkflowDefinitionException(
                "Cannot read an output type from '%s' at '%s'".formatted(shape, path)
            );
        };
    }
}
