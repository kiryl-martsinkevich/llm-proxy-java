# Implementation Plan

## Phase 1: Project Setup

### 1.1 Gradle Project Structure
- [x] Create CLAUDE.md
- [ ] Create build.gradle.kts with:
  - Java 21 configuration
  - Vert.x dependencies (core, web, web-client)
  - Jackson for JSON processing
  - JsonPath for JSON manipulation
  - SLF4J + Logback for logging
  - JUnit 5 for testing
- [ ] Create settings.gradle.kts
- [ ] Create gradle.properties
- [ ] Set up standard Java source directories:
  - `src/main/java/com/llmproxy/`
  - `src/main/resources/`
  - `src/test/java/com/llmproxy/`
  - `src/test/resources/`

### 1.2 Package Structure
```
com.llmproxy/
├── Main.java                    # Application entry point
├── config/                      # Configuration models
│   ├── ProxyConfig.java
│   ├── RouteConfig.java
│   ├── ProviderConfig.java
│   ├── HeaderConfig.java
│   ├── TransformationConfig.java
│   └── ClientConfig.java
├── server/                      # HTTP server
│   ├── ProxyServer.java
│   └── handlers/
│       ├── OpenAIHandler.java
│       └── AnthropicHandler.java
├── client/                      # HTTP client
│   ├── ProxyClient.java
│   └── StreamHandler.java
├── transformer/                 # Request/response transformation
│   ├── HeaderTransformer.java
│   ├── ContentTransformer.java
│   └── JsonPathTransformer.java
└── util/                        # Utilities
    └── Logger.java
```

## Phase 2: Configuration System

### 2.1 Configuration Models
- [ ] Create `ProxyConfig` - root configuration class
- [ ] Create `RouteConfig` - model mapping and routing rules
- [ ] Create `ProviderConfig` - provider details (type, URL, API key, model)
- [ ] Create `HeaderConfig` - header manipulation rules
- [ ] Create `TransformationConfig` - regex and JSONPath operations
- [ ] Create `ClientConfig` - timeout, retry, SSL settings

### 2.2 Configuration Loading
- [ ] Implement JSON configuration loader using Jackson
- [ ] Support for environment variable substitution
- [ ] Configuration validation
- [ ] Default configuration values

### 2.3 Example Configurations
- [ ] Create `config.json` - main example with all features
- [ ] Create `config-ollama.json` - specific for Ollama testing
- [ ] Create `config-simple.json` - minimal configuration

## Phase 3: Core Infrastructure

### 3.1 HTTP Server
- [ ] Create `ProxyServer` class
- [ ] Initialize Vert.x instance
- [ ] Set up HTTP server with configurable port/host
- [ ] Add request logging middleware
- [ ] Add error handling middleware
- [ ] Health check endpoint `/health`

### 3.2 HTTP Client
- [ ] Create `ProxyClient` class
- [ ] Configure Vert.x WebClient with:
  - Connection pooling
  - Configurable SSL verification
  - Timeout settings
  - Keep-alive settings
- [ ] Implement retry logic with exponential backoff
- [ ] Handle HTTP status codes (429, 503, 5xx)

## Phase 4: Request/Response Transformation

### 4.1 Header Transformer
- [ ] Implement `HeaderTransformer` class
- [ ] `dropHeaders()` - remove specific headers
- [ ] `dropAllHeaders()` - clear all headers
- [ ] `addHeaders()` - add if not present
- [ ] `forceHeaders()` - override existing headers
- [ ] Special handling for Authorization header

### 4.2 Content Transformer
- [ ] Implement `ContentTransformer` class
- [ ] Regex search and replace on JSON content
- [ ] Model name replacement in request body
- [ ] Preserve JSON structure during transformation

### 4.3 JSONPath Transformer
- [ ] Implement `JsonPathTransformer` class
- [ ] `removeField()` - delete field by path
- [ ] `addField()` - inject field at path
- [ ] Handle arrays and nested objects
- [ ] Error handling for invalid paths

## Phase 5: API Endpoints

### 5.1 OpenAI Compatible Endpoint
- [ ] Create `OpenAIHandler` class
- [ ] Handle `POST /v1/chat/completions`
- [ ] Parse request body and extract model name
- [ ] Look up routing configuration
- [ ] Non-streaming response handling
- [ ] Streaming response (SSE) handling
- [ ] Error response formatting

### 5.2 Anthropic Compatible Endpoint
- [ ] Create `AnthropicHandler` class
- [ ] Handle `POST /v1/messages`
- [ ] Parse request body and extract model name
- [ ] Look up routing configuration
- [ ] Non-streaming response handling
- [ ] Streaming response (SSE) handling
- [ ] Error response formatting

## Phase 6: Provider Integration

### 6.1 OpenAI Provider
- [ ] Request transformation (OpenAI format)
- [ ] Response transformation
- [ ] Stream parsing (SSE format)
- [ ] Error handling

### 6.2 Anthropic Provider
- [ ] Request transformation (Anthropic format)
- [ ] Response transformation
- [ ] Stream parsing (SSE format)
- [ ] Error handling

### 6.3 Ollama Provider
- [ ] Request transformation (Ollama format)
- [ ] Handle `/api/chat` endpoint
- [ ] Response transformation
- [ ] Stream parsing (Ollama format)
- [ ] Error handling

## Phase 7: Logging System

### 7.1 Request Logging
- [ ] Log incoming request details
- [ ] Configurable levels: OFF, HEADERS_ONLY, FULL
- [ ] Log headers (if enabled)
- [ ] Log request body (if enabled)
- [ ] Sanitize sensitive data (API keys)

### 7.2 Response Logging
- [ ] Log outgoing response details
- [ ] Log response headers (if enabled)
- [ ] Log response body (if enabled)
- [ ] Log latency metrics
- [ ] Error logging with stack traces

### 7.3 Logback Configuration
- [ ] Create `logback.xml`
- [ ] Configure console appender
- [ ] Configure file appender (optional)
- [ ] Set log levels per package
- [ ] Add request ID for tracing

## Phase 8: Testing

### 8.1 Unit Tests
- [ ] Test configuration loading and validation
- [ ] Test header transformation logic
- [ ] Test content transformation (regex)
- [ ] Test JSONPath operations
- [ ] Test model mapping lookup

### 8.2 Integration Tests
- [ ] Test OpenAI endpoint (non-streaming)
- [ ] Test OpenAI endpoint (streaming)
- [ ] Test Anthropic endpoint (non-streaming)
- [ ] Test Anthropic endpoint (streaming)
- [ ] Test error handling and retries

### 8.3 Manual Testing
- [ ] Test with Ollama (gpt-4 → llama3.2:1b)
- [ ] Test streaming responses
- [ ] Test header manipulation
- [ ] Test content transformations
- [ ] Test SSL verification toggle
- [ ] Test timeout and retry behavior

## Phase 9: Documentation and Polish

### 9.1 Documentation
- [ ] Update README.md with:
  - Build instructions
  - Running instructions
  - Configuration guide
  - API examples
- [ ] Add inline code comments
- [ ] Document configuration options

### 9.2 Build and Packaging
- [ ] Gradle build task
- [ ] Create executable JAR with dependencies
- [ ] Add Gradle wrapper
- [ ] Create run scripts (run.sh, run.bat)

### 9.3 Configuration Examples
- [ ] Working Ollama example
- [ ] OpenAI example (template)
- [ ] Anthropic example (template)
- [ ] Multi-provider example

## Implementation Order (Commit Points)

1. ✅ **Commit 1**: CLAUDE.md and PLAN.md
2. **Commit 2**: Gradle setup + project structure
3. **Commit 3**: Configuration system (models + loading)
4. **Commit 4**: HTTP server setup + basic routing
5. **Commit 5**: HTTP client with retry logic
6. **Commit 6**: Header transformer
7. **Commit 7**: Content transformer (regex + JSONPath)
8. **Commit 8**: OpenAI endpoint (non-streaming)
9. **Commit 9**: OpenAI endpoint (streaming)
10. **Commit 10**: Anthropic endpoint (both modes)
11. **Commit 11**: Ollama provider integration
12. **Commit 12**: Request/response logging
13. **Commit 13**: Example configurations
14. **Commit 14**: Testing and bug fixes
15. **Commit 15**: Documentation and README

## Technical Decisions

### Why Vert.x?
- **Non-blocking I/O**: Efficient handling of concurrent requests
- **Built-in streaming**: Native SSE support
- **HTTP client**: Powerful WebClient with retry support
- **Performance**: Low overhead, high throughput
- **Reactive**: Natural fit for proxy/router pattern

### Why Java 21?
- **Virtual threads**: Better concurrency model
- **Pattern matching**: Cleaner code
- **Records**: Perfect for configuration DTOs
- **Modern language features**: Sealed classes, switch expressions

### Configuration Format
- **JSON**: Human-readable and widely supported
- **Validation**: Jackson with bean validation
- **Environment variables**: ${ENV_VAR} substitution for secrets

### Error Handling Strategy
- **Fail fast**: Invalid configuration prevents startup
- **Graceful degradation**: Log and return error response to client
- **Retry**: Automatic retry for transient errors
- **Circuit breaker**: Prevent cascade failures

### Streaming Strategy
- **Direct forwarding**: Minimal buffering for SSE streams
- **Backpressure**: Respect client consumption rate
- **Timeout**: Per-chunk timeout for hanging streams
- **Error handling**: Proper stream termination on errors
