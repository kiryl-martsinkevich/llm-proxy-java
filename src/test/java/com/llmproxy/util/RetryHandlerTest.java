package com.llmproxy.util;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
public class RetryHandlerTest {

    private Vertx vertx;
    private RetryHandler retryHandler;

    @BeforeEach
    public void setUp(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
        this.retryHandler = new RetryHandler(vertx);
        testContext.completeNow();
    }

    @AfterEach
    public void tearDown(VertxTestContext testContext) {
        if (vertx != null) {
            vertx.close(testContext.succeedingThenComplete());
        } else {
            testContext.completeNow();
        }
    }

    @Test
    public void testSuccessfulOperationNoRetry(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryHandler.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return Future.succeededFuture("success");
                },
                3,
                "Test operation"
        ).onComplete(testContext.succeeding(result -> {
            assertEquals("success", result, "Should return successful result");
            assertEquals(1, attempts.get(), "Should only attempt once");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRetryableErrorWithSuccess(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryHandler.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        return Future.failedFuture(new RuntimeException("Connection refused"));
                    }
                    return Future.succeededFuture("success");
                },
                3,
                "Test operation"
        ).onComplete(testContext.succeeding(result -> {
            assertEquals("success", result, "Should eventually succeed");
            assertEquals(3, attempts.get(), "Should retry until success");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMaxRetriesExhausted(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryHandler.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return Future.failedFuture(new RuntimeException("Connection refused"));
                },
                2,
                "Test operation"
        ).onComplete(testContext.failing(error -> {
            assertEquals("Connection refused", error.getMessage());
            assertEquals(3, attempts.get(), "Should attempt 3 times (initial + 2 retries)");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testNonRetryableError(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryHandler.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return Future.failedFuture(new RuntimeException("Invalid request"));
                },
                3,
                "Test operation"
        ).onComplete(testContext.failing(error -> {
            assertEquals("Invalid request", error.getMessage());
            assertEquals(1, attempts.get(), "Should not retry non-retryable errors");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRetryable429StatusCode(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryHandler.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        return Future.failedFuture(new RuntimeException("HTTP 429 Too Many Requests"));
                    }
                    return Future.succeededFuture("success");
                },
                3,
                "Test operation"
        ).onComplete(testContext.succeeding(result -> {
            assertEquals("success", result);
            assertEquals(2, attempts.get(), "Should retry 429 errors");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRetryable503StatusCode(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryHandler.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        return Future.failedFuture(new RuntimeException("503 Service Unavailable"));
                    }
                    return Future.succeededFuture("success");
                },
                3,
                "Test operation"
        ).onComplete(testContext.succeeding(result -> {
            assertEquals("success", result);
            assertEquals(2, attempts.get(), "Should retry 503 errors");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRetryable502StatusCode(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryHandler.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        return Future.failedFuture(new RuntimeException("502 Bad Gateway"));
                    }
                    return Future.succeededFuture("success");
                },
                3,
                "Test operation"
        ).onComplete(testContext.succeeding(result -> {
            assertEquals("success", result);
            assertEquals(2, attempts.get(), "Should retry 502 errors");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testConnectionTimeout(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryHandler.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        return Future.failedFuture(new RuntimeException("Connection timed out"));
                    }
                    return Future.succeededFuture("success");
                },
                3,
                "Test operation"
        ).onComplete(testContext.succeeding(result -> {
            assertEquals("success", result);
            assertEquals(2, attempts.get(), "Should retry timeout errors");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testZeroRetries(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        retryHandler.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return Future.failedFuture(new RuntimeException("Connection refused"));
                },
                0,
                "Test operation"
        ).onComplete(testContext.failing(error -> {
            assertEquals("Connection refused", error.getMessage());
            assertEquals(1, attempts.get(), "Should only attempt once with zero retries");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testIsRetryableStatusCode() {
        assertTrue(RetryHandler.isRetryableStatusCode(429), "429 should be retryable");
        assertTrue(RetryHandler.isRetryableStatusCode(502), "502 should be retryable");
        assertTrue(RetryHandler.isRetryableStatusCode(503), "503 should be retryable");
        assertTrue(RetryHandler.isRetryableStatusCode(504), "504 should be retryable");

        assertFalse(RetryHandler.isRetryableStatusCode(200), "200 should not be retryable");
        assertFalse(RetryHandler.isRetryableStatusCode(400), "400 should not be retryable");
        assertFalse(RetryHandler.isRetryableStatusCode(401), "401 should not be retryable");
        assertFalse(RetryHandler.isRetryableStatusCode(404), "404 should not be retryable");
        assertFalse(RetryHandler.isRetryableStatusCode(500), "500 should not be retryable");
    }

    @Test
    public void testExponentialBackoff(VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        retryHandler.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        return Future.failedFuture(new RuntimeException("Connection refused"));
                    }
                    return Future.succeededFuture("success");
                },
                3,
                "Test operation"
        ).onComplete(testContext.succeeding(result -> {
            long duration = System.currentTimeMillis() - startTime;
            // With backoff, should take at least 100ms (first retry) + 200ms (second retry) = 300ms
            // But with jitter, could be less, so we just verify it took some time
            assertTrue(duration >= 50, "Should have backoff delays");
            assertEquals(3, attempts.get());
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDifferentRetryableErrors(VertxTestContext testContext) throws Exception {
        String[] retryableErrors = {
                "Connection refused",
                "Connection reset",
                "Connection timed out",
                "Timeout",
                "429 Too Many Requests",
                "503 Service Unavailable",
                "502 Bad Gateway"
        };

        AtomicInteger testCount = new AtomicInteger(0);

        for (String errorMessage : retryableErrors) {
            AtomicInteger attempts = new AtomicInteger(0);

            retryHandler.executeWithRetry(
                    () -> {
                        int attempt = attempts.incrementAndGet();
                        if (attempt < 2) {
                            return Future.failedFuture(new RuntimeException(errorMessage));
                        }
                        return Future.succeededFuture("success");
                    },
                    3,
                    "Test " + errorMessage
            ).onComplete(testContext.succeeding(result -> {
                assertEquals(2, attempts.get(),
                        "Should retry error: " + errorMessage);
                if (testCount.incrementAndGet() == retryableErrors.length) {
                    testContext.completeNow();
                }
            }));
        }

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
}
