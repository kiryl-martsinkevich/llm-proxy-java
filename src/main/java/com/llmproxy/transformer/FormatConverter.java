package com.llmproxy.transformer;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts between different LLM API formats (Anthropic, OpenAI, Ollama).
 */
public class FormatConverter {
    private static final Logger logger = LoggerFactory.getLogger(FormatConverter.class);

    /**
     * Converts an Anthropic API request to OpenAI format.
     *
     * Anthropic format:
     * {
     *   "model": "claude-3-opus",
     *   "max_tokens": 1024,
     *   "system": "You are a helpful assistant",
     *   "messages": [
     *     {"role": "user", "content": "Hello"}
     *   ]
     * }
     *
     * OpenAI format:
     * {
     *   "model": "gpt-4",
     *   "max_tokens": 1024,
     *   "messages": [
     *     {"role": "system", "content": "You are a helpful assistant"},
     *     {"role": "user", "content": "Hello"}
     *   ]
     * }
     */
    public JsonObject anthropicToOpenAIRequest(JsonObject anthropicRequest) {
        JsonObject openaiRequest = new JsonObject();

        // Copy model (will be replaced later by route config)
        if (anthropicRequest.containsKey("model")) {
            openaiRequest.put("model", anthropicRequest.getString("model"));
        }

        // Convert max_tokens -> max_completion_tokens (required for newer OpenAI models)
        if (anthropicRequest.containsKey("max_tokens")) {
            openaiRequest.put("max_completion_tokens", anthropicRequest.getInteger("max_tokens"));
        }

        // Convert temperature
        if (anthropicRequest.containsKey("temperature")) {
            openaiRequest.put("temperature", anthropicRequest.getNumber("temperature"));
        }

        // Convert top_p
        if (anthropicRequest.containsKey("top_p")) {
            openaiRequest.put("top_p", anthropicRequest.getNumber("top_p"));
        }

        // Convert stream
        if (anthropicRequest.containsKey("stream")) {
            openaiRequest.put("stream", anthropicRequest.getBoolean("stream"));
        }

        // Convert stop sequences
        if (anthropicRequest.containsKey("stop_sequences")) {
            openaiRequest.put("stop", anthropicRequest.getJsonArray("stop_sequences"));
        }

        // Build messages array
        JsonArray messages = new JsonArray();

        // Convert system prompt to system message
        if (anthropicRequest.containsKey("system")) {
            Object systemValue = anthropicRequest.getValue("system");
            String systemContent;

            if (systemValue instanceof String) {
                systemContent = (String) systemValue;
            } else if (systemValue instanceof JsonArray) {
                // Anthropic supports array of content blocks for system
                StringBuilder sb = new StringBuilder();
                JsonArray systemArray = (JsonArray) systemValue;
                for (int i = 0; i < systemArray.size(); i++) {
                    JsonObject block = systemArray.getJsonObject(i);
                    if ("text".equals(block.getString("type"))) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(block.getString("text"));
                    }
                }
                systemContent = sb.toString();
            } else {
                systemContent = systemValue.toString();
            }

            messages.add(new JsonObject()
                    .put("role", "system")
                    .put("content", systemContent));
        }

        // Convert messages
        if (anthropicRequest.containsKey("messages")) {
            JsonArray anthropicMessages = anthropicRequest.getJsonArray("messages");
            for (int i = 0; i < anthropicMessages.size(); i++) {
                JsonObject msg = anthropicMessages.getJsonObject(i);
                JsonObject openaiMsg = convertAnthropicMessageToOpenAI(msg);
                messages.add(openaiMsg);
            }
        }

        openaiRequest.put("messages", messages);

        logger.debug("Converted Anthropic request to OpenAI format");
        return openaiRequest;
    }

    /**
     * Converts a single Anthropic message to OpenAI format.
     * Handles both simple string content and complex content blocks.
     */
    private JsonObject convertAnthropicMessageToOpenAI(JsonObject anthropicMsg) {
        JsonObject openaiMsg = new JsonObject();
        openaiMsg.put("role", anthropicMsg.getString("role"));

        Object content = anthropicMsg.getValue("content");

        if (content instanceof String) {
            // Simple string content
            openaiMsg.put("content", content);
        } else if (content instanceof JsonArray) {
            // Complex content blocks - convert to OpenAI format
            JsonArray contentBlocks = (JsonArray) content;

            // Check if it's just text blocks - can simplify to string
            boolean allText = true;
            StringBuilder textContent = new StringBuilder();
            JsonArray openaiContent = new JsonArray();

            for (int i = 0; i < contentBlocks.size(); i++) {
                JsonObject block = contentBlocks.getJsonObject(i);
                String type = block.getString("type");

                if ("text".equals(type)) {
                    textContent.append(block.getString("text"));
                    openaiContent.add(new JsonObject()
                            .put("type", "text")
                            .put("text", block.getString("text")));
                } else if ("image".equals(type)) {
                    allText = false;
                    // Convert Anthropic image format to OpenAI
                    JsonObject source = block.getJsonObject("source");
                    if (source != null && "base64".equals(source.getString("type"))) {
                        String mediaType = source.getString("media_type");
                        String data = source.getString("data");
                        openaiContent.add(new JsonObject()
                                .put("type", "image_url")
                                .put("image_url", new JsonObject()
                                        .put("url", "data:" + mediaType + ";base64," + data)));
                    }
                } else {
                    allText = false;
                    // Unknown block type, try to pass through
                    openaiContent.add(block);
                }
            }

            if (allText && contentBlocks.size() == 1) {
                // Single text block - use simple string
                openaiMsg.put("content", textContent.toString());
            } else {
                // Multiple blocks or non-text - use array format
                openaiMsg.put("content", openaiContent);
            }
        }

        return openaiMsg;
    }

    /**
     * Converts an OpenAI API response to Anthropic format.
     *
     * OpenAI format:
     * {
     *   "id": "chatcmpl-123",
     *   "object": "chat.completion",
     *   "model": "gpt-4",
     *   "choices": [{
     *     "index": 0,
     *     "message": {"role": "assistant", "content": "Hello!"},
     *     "finish_reason": "stop"
     *   }],
     *   "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}
     * }
     *
     * Anthropic format:
     * {
     *   "id": "msg_123",
     *   "type": "message",
     *   "role": "assistant",
     *   "model": "claude-3-opus",
     *   "content": [{"type": "text", "text": "Hello!"}],
     *   "stop_reason": "end_turn",
     *   "usage": {"input_tokens": 10, "output_tokens": 20}
     * }
     */
    public JsonObject openAIToAnthropicResponse(JsonObject openaiResponse, String originalModel) {
        JsonObject anthropicResponse = new JsonObject();

        // Convert ID
        String id = openaiResponse.getString("id", "msg_" + System.currentTimeMillis());
        anthropicResponse.put("id", id.startsWith("msg_") ? id : "msg_" + id);

        // Set type
        anthropicResponse.put("type", "message");

        // Set role
        anthropicResponse.put("role", "assistant");

        // Set model (use original requested model)
        anthropicResponse.put("model", originalModel);

        // Convert content from choices
        JsonArray choices = openaiResponse.getJsonArray("choices");
        if (choices != null && !choices.isEmpty()) {
            JsonObject firstChoice = choices.getJsonObject(0);
            JsonObject message = firstChoice.getJsonObject("message");

            if (message != null) {
                Object content = message.getValue("content");
                JsonArray anthropicContent = new JsonArray();

                if (content instanceof String) {
                    anthropicContent.add(new JsonObject()
                            .put("type", "text")
                            .put("text", content));
                } else if (content instanceof JsonArray) {
                    // OpenAI array content - convert each block
                    JsonArray contentArray = (JsonArray) content;
                    for (int i = 0; i < contentArray.size(); i++) {
                        JsonObject block = contentArray.getJsonObject(i);
                        if ("text".equals(block.getString("type"))) {
                            anthropicContent.add(new JsonObject()
                                    .put("type", "text")
                                    .put("text", block.getString("text")));
                        }
                    }
                }

                anthropicResponse.put("content", anthropicContent);
            }

            // Convert finish_reason to stop_reason
            String finishReason = firstChoice.getString("finish_reason");
            anthropicResponse.put("stop_reason", convertFinishReason(finishReason));
        } else {
            anthropicResponse.put("content", new JsonArray());
            anthropicResponse.put("stop_reason", "end_turn");
        }

        // Convert usage
        JsonObject openaiUsage = openaiResponse.getJsonObject("usage");
        if (openaiUsage != null) {
            JsonObject anthropicUsage = new JsonObject()
                    .put("input_tokens", openaiUsage.getInteger("prompt_tokens", 0))
                    .put("output_tokens", openaiUsage.getInteger("completion_tokens", 0));
            anthropicResponse.put("usage", anthropicUsage);
        }

        logger.debug("Converted OpenAI response to Anthropic format");
        return anthropicResponse;
    }

    /**
     * Converts an OpenAI streaming chunk to Anthropic streaming format.
     *
     * OpenAI streaming format:
     * data: {"id":"chatcmpl-123","choices":[{"delta":{"content":"Hello"},"index":0}]}
     *
     * Anthropic streaming format:
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
     */
    public String openAIStreamChunkToAnthropic(String openaiChunk, String originalModel, StreamingState state) {
        // Handle [DONE] marker
        if (openaiChunk.trim().equals("[DONE]")) {
            return buildAnthropicStreamEnd(state);
        }

        try {
            JsonObject chunk = new JsonObject(openaiChunk);
            StringBuilder result = new StringBuilder();

            // First chunk - send message_start
            if (!state.messageStartSent) {
                result.append(buildAnthropicMessageStart(chunk, originalModel, state));
                state.messageStartSent = true;
            }

            JsonArray choices = chunk.getJsonArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JsonObject choice = choices.getJsonObject(0);
                JsonObject delta = choice.getJsonObject("delta");

                if (delta != null) {
                    // Check for content
                    String content = delta.getString("content");
                    if (content != null && !content.isEmpty()) {
                        // Send content_block_start if not sent yet
                        if (!state.contentBlockStartSent) {
                            result.append("event: content_block_start\n");
                            result.append("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n");
                            state.contentBlockStartSent = true;
                        }

                        // Send content delta
                        result.append("event: content_block_delta\n");
                        result.append("data: ");
                        result.append(new JsonObject()
                                .put("type", "content_block_delta")
                                .put("index", 0)
                                .put("delta", new JsonObject()
                                        .put("type", "text_delta")
                                        .put("text", content))
                                .encode());
                        result.append("\n\n");

                        state.outputTokens++;
                    }

                    // Check for finish reason
                    String finishReason = choice.getString("finish_reason");
                    if (finishReason != null) {
                        state.stopReason = convertFinishReason(finishReason);
                    }
                }
            }

            // Check for usage in chunk (some providers include it)
            JsonObject usage = chunk.getJsonObject("usage");
            if (usage != null) {
                if (usage.containsKey("prompt_tokens")) {
                    state.inputTokens = usage.getInteger("prompt_tokens");
                }
                if (usage.containsKey("completion_tokens")) {
                    state.outputTokens = usage.getInteger("completion_tokens");
                }
            }

            return result.toString();
        } catch (Exception e) {
            logger.warn("Failed to parse OpenAI stream chunk: {}", openaiChunk, e);
            return "";
        }
    }

    private String buildAnthropicMessageStart(JsonObject firstChunk, String originalModel, StreamingState state) {
        String id = firstChunk.getString("id", "msg_" + System.currentTimeMillis());
        state.messageId = id.startsWith("msg_") ? id : "msg_" + id;

        StringBuilder result = new StringBuilder();
        result.append("event: message_start\n");
        result.append("data: ");
        result.append(new JsonObject()
                .put("type", "message_start")
                .put("message", new JsonObject()
                        .put("id", state.messageId)
                        .put("type", "message")
                        .put("role", "assistant")
                        .put("model", originalModel)
                        .put("content", new JsonArray())
                        .put("stop_reason", (String) null)
                        .put("stop_sequence", (String) null)
                        .put("usage", new JsonObject()
                                .put("input_tokens", state.inputTokens)
                                .put("output_tokens", 0)))
                .encode());
        result.append("\n\n");
        return result.toString();
    }

    private String buildAnthropicStreamEnd(StreamingState state) {
        StringBuilder result = new StringBuilder();

        // Send content_block_stop if we started a content block
        if (state.contentBlockStartSent) {
            result.append("event: content_block_stop\n");
            result.append("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n");
        }

        // Send message_delta with stop reason
        result.append("event: message_delta\n");
        result.append("data: ");
        result.append(new JsonObject()
                .put("type", "message_delta")
                .put("delta", new JsonObject()
                        .put("stop_reason", state.stopReason != null ? state.stopReason : "end_turn")
                        .put("stop_sequence", (String) null))
                .put("usage", new JsonObject()
                        .put("output_tokens", state.outputTokens))
                .encode());
        result.append("\n\n");

        // Send message_stop
        result.append("event: message_stop\n");
        result.append("data: {\"type\":\"message_stop\"}\n\n");

        return result.toString();
    }

    private String convertFinishReason(String openaiReason) {
        if (openaiReason == null) return "end_turn";

        return switch (openaiReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "content_filter" -> "end_turn"; // No direct equivalent
            case "tool_calls", "function_call" -> "tool_use";
            default -> "end_turn";
        };
    }

    /**
     * State tracker for streaming conversion.
     */
    public static class StreamingState {
        public String messageId;
        public boolean messageStartSent = false;
        public boolean contentBlockStartSent = false;
        public int inputTokens = 0;
        public int outputTokens = 0;
        public String stopReason = null;
    }
}
