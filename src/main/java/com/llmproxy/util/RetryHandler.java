package com.llmproxy.util;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Utility class for handling retries with exponential backoff.
 * Retries are attempted for transient errors like network failures,
 * 429 (Too Many Requests), and 503 (Service Unavailable).
 */
public class RetryHandler {
    private static final Logger logger = LoggerFactory.getLogger(RetryHandler.class);

    private static final long INITIAL_DELAY_MS = 100;
    private static final int BACKOFF_MULTIPLIER = 2;
    private static final long MAX_DELAY_MS = 10000; // 10 seconds

    private final Vertx vertx;

    public RetryHandler(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * Execute a Future-returning operation with retry logic.
     *
     * @param operation The operation to execute
     * @param maxRetries Maximum number of retry attempts (0 means no retries)
     * @param context Description of the operation for logging
     * @param <T> The type of the Future result
     * @return A Future that completes with the operation result or fails after all retries
     */
    public <T> Future<T> executeWithRetry(
            Supplier<Future<T>> operation,
            int maxRetries,
            String context) {

        return executeWithRetryInternal(operation, maxRetries, 0, context);
    }

    private <T> Future<T> executeWithRetryInternal(
            Supplier<Future<T>> operation,
            int maxRetries,
            int attemptNumber,
            String context) {

        Promise<T> promise = Promise.promise();

        operation.get()
                .onSuccess(promise::complete)
                .onFailure(error -> {
                    if (attemptNumber >= maxRetries) {
                        logger.error("{} - All retry attempts exhausted (attempt {}/{})",
                                context, attemptNumber + 1, maxRetries + 1);
                        promise.fail(error);
                        return;
                    }

                    if (isRetryable(error)) {
                        long delay = calculateBackoff(attemptNumber);
                        logger.warn("{} - Attempt {}/{} failed with retryable error: {}. Retrying in {}ms",
                                context, attemptNumber + 1, maxRetries + 1,
                                error.getMessage(), delay);

                        vertx.setTimer(delay, timerId -> {
                            executeWithRetryInternal(operation, maxRetries, attemptNumber + 1, context)
                                    .onSuccess(promise::complete)
                                    .onFailure(promise::fail);
                        });
                    } else {
                        logger.error("{} - Non-retryable error on attempt {}: {}",
                                context, attemptNumber + 1, error.getMessage());
                        promise.fail(error);
                    }
                });

        return promise.future();
    }

    /**
     * Calculate exponential backoff delay with jitter.
     *
     * @param attemptNumber The current attempt number (0-indexed)
     * @return Delay in milliseconds
     */
    private long calculateBackoff(int attemptNumber) {
        long delay = INITIAL_DELAY_MS * (long) Math.pow(BACKOFF_MULTIPLIER, attemptNumber);

        // Cap at max delay
        delay = Math.min(delay, MAX_DELAY_MS);

        // Add jitter (±25%) to prevent thundering herd
        double jitterFactor = 0.75 + (Math.random() * 0.5); // 0.75 to 1.25
        delay = (long) (delay * jitterFactor);

        return delay;
    }

    /**
     * Determine if an error is retryable.
     * Retryable errors include:
     * - Network errors (connection refused, timeout)
     * - HTTP 429 (Too Many Requests)
     * - HTTP 503 (Service Unavailable)
     * - HTTP 502 (Bad Gateway)
     *
     * @param error The error to check
     * @return true if the error is retryable
     */
    private boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }

        String message = error.getMessage();
        if (message == null) {
            return false;
        }

        // Check for HTTP status codes in error message
        if (message.contains("429") || message.contains("Too Many Requests")) {
            return true;
        }
        if (message.contains("503") || message.contains("Service Unavailable")) {
            return true;
        }
        if (message.contains("502") || message.contains("Bad Gateway")) {
            return true;
        }

        // Check for network errors
        if (message.contains("Connection refused") ||
            message.contains("Connection reset") ||
            message.contains("Connection timed out") ||
            message.contains("Timeout") ||
            message.contains("ConnectException") ||
            message.contains("SocketException")) {
            return true;
        }

        // Check for timeout exceptions
        if (error instanceof java.util.concurrent.TimeoutException ||
            error.getClass().getSimpleName().contains("Timeout")) {
            return true;
        }

        return false;
    }

    /**
     * Check if an HTTP status code is retryable.
     *
     * @param statusCode The HTTP status code
     * @return true if the status code indicates a retryable error
     */
    public static boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 429 ||  // Too Many Requests
               statusCode == 502 ||  // Bad Gateway
               statusCode == 503 ||  // Service Unavailable
               statusCode == 504;    // Gateway Timeout
    }
}
