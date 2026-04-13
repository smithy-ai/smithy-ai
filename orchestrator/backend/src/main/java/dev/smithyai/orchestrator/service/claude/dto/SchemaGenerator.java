package dev.smithyai.orchestrator.service.claude.dto;

import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

public final class SchemaGenerator {

    private static final com.github.victools.jsonschema.generator.SchemaGenerator GENERATOR;

    static {
        var jacksonModule = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
        var configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON).with(
            jacksonModule
        );

        // Add enum constraint for LearnResult.action
        configBuilder
            .forFields()
            .withEnumResolver(field -> {
                if (field.getDeclaringType().getErasedType() == LearnResult.class && "action".equals(field.getName())) {
                    return java.util.List.of("UPDATE", "NONE");
                }
                return null;
            });

        GENERATOR = new com.github.victools.jsonschema.generator.SchemaGenerator(configBuilder.build());
    }

    private SchemaGenerator() {}

    public static String generate(Class<?> type) {
        return GENERATOR.generateSchema(type).toString();
    }
}
