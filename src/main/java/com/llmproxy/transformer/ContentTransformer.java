package com.llmproxy.transformer;

import com.llmproxy.config.RegexReplacement;
import com.llmproxy.config.TransformationRules;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

public class ContentTransformer {
    private static final Logger logger = LoggerFactory.getLogger(ContentTransformer.class);

    public String transform(String content, TransformationRules rules) {
        String result = content;

        // Apply regex replacements
        for (RegexReplacement replacement : rules.getRegexReplacements()) {
            try {
                Pattern pattern = Pattern.compile(replacement.getPattern());
                String oldResult = result;
                result = pattern.matcher(result).replaceAll(replacement.getReplacement());
                if (!oldResult.equals(result)) {
                    logger.debug("Applied regex replacement: {} -> {}",
                            replacement.getPattern(),
                            replacement.getReplacement());
                }
            } catch (Exception e) {
                logger.error("Failed to apply regex replacement: {}", replacement.getPattern(), e);
            }
        }

        return result;
    }

    public JsonObject replaceModel(JsonObject body, String targetModel) {
        JsonObject result = body.copy();
        result.put("model", targetModel);
        logger.debug("Replaced model name with: {}", targetModel);
        return result;
    }
}
