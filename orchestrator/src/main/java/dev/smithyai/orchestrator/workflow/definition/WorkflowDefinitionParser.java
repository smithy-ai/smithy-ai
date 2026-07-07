package dev.smithyai.orchestrator.workflow.definition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class WorkflowDefinitionParser {

    private final YAMLMapper mapper;
    private final WorkflowDefinitionValidator validator;

    public WorkflowDefinitionParser() {
        this(new WorkflowDefinitionValidator());
    }

    WorkflowDefinitionParser(WorkflowDefinitionValidator validator) {
        this.mapper = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
        this.validator = validator;
    }

    public WorkflowDefinition parse(String sourceName, String yaml) {
        try {
            WorkflowDefinition definition = mapper.readValue(yaml, WorkflowDefinition.class);
            validator.validate(sourceName, definition);
            return definition;
        } catch (WorkflowDefinitionException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkflowDefinitionException(
                sourceName + " is not valid workflow YAML",
                java.util.List.of(e.getMessage())
            );
        }
    }
}
