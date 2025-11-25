package com.llmproxy.transformer;

import com.llmproxy.config.HeaderConfig;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class HeaderTransformerTest {

    private HeaderTransformer transformer;

    @BeforeEach
    public void setUp() {
        transformer = new HeaderTransformer();
    }

    @Test
    public void testNoTransformation() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Content-Type", "application/json");
        incoming.add("User-Agent", "Test/1.0");

        HeaderConfig config = new HeaderConfig();

        MultiMap result = transformer.transform(incoming, config);

        assertEquals(2, result.size(), "Should preserve all headers");
        assertEquals("application/json", result.get("Content-Type"));
        assertEquals("Test/1.0", result.get("User-Agent"));
    }

    @Test
    public void testDropSpecificHeaders() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Content-Type", "application/json");
        incoming.add("User-Agent", "Test/1.0");
        incoming.add("Authorization", "Bearer token123");

        HeaderConfig config = new HeaderConfig();
        config.setDrop(Arrays.asList("Authorization", "User-Agent"));

        MultiMap result = transformer.transform(incoming, config);

        assertEquals(1, result.size(), "Should drop specified headers");
        assertEquals("application/json", result.get("Content-Type"));
        assertNull(result.get("Authorization"), "Authorization should be dropped");
        assertNull(result.get("User-Agent"), "User-Agent should be dropped");
    }

    @Test
    public void testDropAllHeaders() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Content-Type", "application/json");
        incoming.add("User-Agent", "Test/1.0");
        incoming.add("Authorization", "Bearer token123");

        HeaderConfig config = new HeaderConfig();
        config.setDropAll(true);

        MultiMap result = transformer.transform(incoming, config);

        assertEquals(0, result.size(), "Should drop all headers");
    }

    @Test
    public void testAddHeaders() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Content-Type", "application/json");

        HeaderConfig config = new HeaderConfig();
        config.getAdd().put("User-Agent", "LLM-Proxy/1.0");
        config.getAdd().put("X-Custom-Header", "custom-value");

        MultiMap result = transformer.transform(incoming, config);

        assertEquals(3, result.size(), "Should have original + added headers");
        assertEquals("application/json", result.get("Content-Type"));
        assertEquals("LLM-Proxy/1.0", result.get("User-Agent"));
        assertEquals("custom-value", result.get("X-Custom-Header"));
    }

    @Test
    public void testAddDoesNotOverrideExisting() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("User-Agent", "Original/1.0");

        HeaderConfig config = new HeaderConfig();
        config.getAdd().put("User-Agent", "New/2.0");

        MultiMap result = transformer.transform(incoming, config);

        assertEquals("Original/1.0", result.get("User-Agent"),
                "Add should not override existing headers");
    }

    @Test
    public void testForceOverridesExisting() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("User-Agent", "Original/1.0");
        incoming.add("Content-Type", "text/plain");

        HeaderConfig config = new HeaderConfig();
        config.getForce().put("User-Agent", "Forced/2.0");
        config.getForce().put("Content-Type", "application/json");

        MultiMap result = transformer.transform(incoming, config);

        assertEquals(2, result.size(), "Should have same number of headers");
        assertEquals("Forced/2.0", result.get("User-Agent"),
                "Force should override existing User-Agent");
        assertEquals("application/json", result.get("Content-Type"),
                "Force should override existing Content-Type");
    }

    @Test
    public void testForceAddsNewHeaders() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Content-Type", "application/json");

        HeaderConfig config = new HeaderConfig();
        config.getForce().put("X-Custom-Header", "forced-value");

        MultiMap result = transformer.transform(incoming, config);

        assertEquals(2, result.size(), "Should have original + forced header");
        assertEquals("forced-value", result.get("X-Custom-Header"),
                "Force should add new headers");
    }

    @Test
    public void testCombinedTransformations() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Content-Type", "text/plain");
        incoming.add("User-Agent", "Original/1.0");
        incoming.add("Authorization", "Bearer old-token");
        incoming.add("X-Old-Header", "old-value");

        HeaderConfig config = new HeaderConfig();
        // Drop specific header
        config.setDrop(Arrays.asList("X-Old-Header"));
        // Add new header if not present
        config.getAdd().put("X-Request-ID", "req-123");
        // Force override existing headers
        config.getForce().put("Content-Type", "application/json");
        config.getForce().put("Authorization", "Bearer new-token");

        MultiMap result = transformer.transform(incoming, config);

        assertEquals(4, result.size(), "Should have correct number of headers");
        assertNull(result.get("X-Old-Header"), "X-Old-Header should be dropped");
        assertEquals("application/json", result.get("Content-Type"),
                "Content-Type should be forced to new value");
        assertEquals("Bearer new-token", result.get("Authorization"),
                "Authorization should be forced to new value");
        assertEquals("Original/1.0", result.get("User-Agent"),
                "User-Agent should remain unchanged");
        assertEquals("req-123", result.get("X-Request-ID"),
                "X-Request-ID should be added");
    }

    @Test
    public void testDropAllThenAddAndForce() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Content-Type", "text/plain");
        incoming.add("User-Agent", "Original/1.0");
        incoming.add("Authorization", "Bearer token");

        HeaderConfig config = new HeaderConfig();
        config.setDropAll(true);
        config.getAdd().put("Content-Type", "application/json");
        config.getAdd().put("User-Agent", "LLM-Proxy/1.0");
        config.getForce().put("X-Custom-Header", "custom");

        MultiMap result = transformer.transform(incoming, config);

        assertEquals(3, result.size(), "Should have only new headers");
        assertEquals("application/json", result.get("Content-Type"),
                "Content-Type should be added after drop all");
        assertEquals("LLM-Proxy/1.0", result.get("User-Agent"),
                "User-Agent should be added after drop all");
        assertEquals("custom", result.get("X-Custom-Header"),
                "X-Custom-Header should be forced");
        assertNull(result.get("Authorization"),
                "Authorization should not be present after drop all");
    }

    @Test
    public void testCaseInsensitiveHeaders() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("content-type", "text/plain");

        HeaderConfig config = new HeaderConfig();
        config.getForce().put("Content-Type", "application/json");

        MultiMap result = transformer.transform(incoming, config);

        assertEquals("application/json", result.get("Content-Type"),
                "Should handle case-insensitive headers");
        assertEquals("application/json", result.get("content-type"),
                "Should work with lowercase");
        assertEquals("application/json", result.get("CONTENT-TYPE"),
                "Should work with uppercase");
    }

    @Test
    public void testEmptyConfig() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Content-Type", "application/json");

        HeaderConfig config = new HeaderConfig();
        // All collections are empty by default

        MultiMap result = transformer.transform(incoming, config);

        assertEquals(1, result.size(), "Should preserve original headers");
        assertEquals("application/json", result.get("Content-Type"));
    }

    @Test
    public void testOrderOfOperations() {
        // Test that operations happen in correct order: drop -> add -> force
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Header1", "value1");
        incoming.add("Header2", "value2");

        HeaderConfig config = new HeaderConfig();
        config.setDrop(Arrays.asList("Header1"));
        config.getAdd().put("Header2", "should-not-override");
        config.getAdd().put("Header3", "added-value");
        config.getForce().put("Header2", "forced-value");

        MultiMap result = transformer.transform(incoming, config);

        assertNull(result.get("Header1"), "Header1 should be dropped first");
        assertEquals("forced-value", result.get("Header2"),
                "Header2 should be forced (not added)");
        assertEquals("added-value", result.get("Header3"),
                "Header3 should be added");
    }
}
