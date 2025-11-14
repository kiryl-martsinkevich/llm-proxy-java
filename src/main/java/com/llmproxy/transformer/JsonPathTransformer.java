package com.llmproxy.transformer;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.llmproxy.config.JsonPathOperation;
import com.llmproxy.config.TransformationRules;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonPathTransformer {
    private static final Logger logger = LoggerFactory.getLogger(JsonPathTransformer.class);
    private static final Configuration JSON_PATH_CONFIG = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS);

    public JsonObject transform(JsonObject body, TransformationRules rules) {
        String jsonString = body.encode();
        DocumentContext document = JsonPath.using(JSON_PATH_CONFIG).parse(jsonString);

        for (JsonPathOperation operation : rules.getJsonPathOps()) {
            try {
                switch (operation.getOp()) {
                    case REMOVE -> {
                        document.delete(operation.getPath());
                        logger.debug("Removed field at path: {}", operation.getPath());
                    }
                    case ADD -> {
                        document.set(operation.getPath(), operation.getValue());
                        logger.debug("Added field at path: {}", operation.getPath());
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to apply JSONPath operation {} at path: {}",
                        operation.getOp(),
                        operation.getPath(),
                        e);
            }
        }

        return new JsonObject(document.jsonString());
    }
}
