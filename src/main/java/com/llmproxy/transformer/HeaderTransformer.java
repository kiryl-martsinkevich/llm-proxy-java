package com.llmproxy.transformer;

import com.llmproxy.config.HeaderConfig;
import io.vertx.core.MultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeaderTransformer {
    private static final Logger logger = LoggerFactory.getLogger(HeaderTransformer.class);

    public MultiMap transform(MultiMap incomingHeaders, HeaderConfig config) {
        MultiMap result = MultiMap.caseInsensitiveMultiMap();

        // If dropAll is true, start with empty headers
        if (!config.isDropAll()) {
            // Copy all headers except those in drop list
            incomingHeaders.forEach(entry -> {
                String headerName = entry.getKey();
                if (!config.getDrop().contains(headerName)) {
                    result.add(headerName, entry.getValue());
                }
            });
        }

        // Add headers (if not already present)
        config.getAdd().forEach((key, value) -> {
            if (!result.contains(key)) {
                result.add(key, value);
                logger.debug("Added header: {}", key);
            }
        });

        // Force headers (override existing)
        config.getForce().forEach((key, value) -> {
            result.set(key, value);
            logger.debug("Forced header: {}", key);
        });

        return result;
    }
}
