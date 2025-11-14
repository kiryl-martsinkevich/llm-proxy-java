# LLM API Router Service

## Overview

This project is a high-performance LLM API router service built with Vert.x framework in Java 21. It acts as a proxy/router between clients and various LLM API providers (OpenAI, Anthropic, Ollama), providing a unified interface with advanced configuration capabilities.

## Architecture

### Core Components

1. **HTTP Server (Vert.x Web)**
   - Exposes OpenAI-compliant endpoints (`/v1/chat/completions`)
   - Exposes Anthropic-compliant endpoints (`/v1/messages`)
   - Handles both streaming (SSE) and non-streaming responses
   - Request validation and routing

2. **Configuration Manager**
   - Loads routing rules from configuration files (JSON/YAML)
   - Model mapping (incoming model → target model + provider)
   - Per-model API keys and endpoint URLs
   - Header manipulation rules
   - Transformation rules (regex, JSONPath)
   - Timeout and retry policies

3. **HTTP Client Manager (Vert.x WebClient)**
   - Manages connections to downstream LLM APIs
   - Configurable SSL verification
   - Timeout handling
   - Automatic retries with exponential backoff
   - Connection pooling

4. **Request/Response Transformer**
   - Header manipulation (drop, add, force)
   - Request body transformation (regex search/replace)
   - Response body transformation
   - JSONPath-based field addition/removal

5. **Logging System**
   - Configurable request/response logging
   - Full header logging capability
   - Body logging for debugging
   - Structured logging with SLF4J + Logback

### Data Flow

```
Client Request
    ↓
[HTTP Server - Request Validation]
    ↓
[Configuration Lookup - Model Mapping]
    ↓
[Request Transformer]
    ├─ Header manipulation
    ├─ Model name replacement
    ├─ Regex transformations
    └─ JSONPath modifications
    ↓
[HTTP Client - Forward to Provider]
    ├─ OpenAI API
    ├─ Anthropic API
    └─ Ollama API
    ↓
[Response Transformer]
    ├─ Stream handling (if SSE)
    └─ JSONPath modifications
    ↓
[HTTP Server - Response to Client]
```

## Key Features

### 1. Multi-Provider Support
- **OpenAI**: Full API compatibility including streaming
- **Anthropic**: Claude API support with streaming
- **Ollama**: Local model support

### 2. Model Mapping
- Transparent model translation (e.g., `gpt-4` → `llama3.2:1b`)
- Per-model configuration including:
  - Target provider and endpoint
  - API keys
  - Custom headers
  - Transformation rules

### 3. Header Management
- **Drop**: Remove specific headers from incoming requests
- **Add**: Add new headers if they don't exist
- **Force**: Override existing headers
- **Drop All + Whitelist**: Start fresh with only specified headers
- Support for standard headers: Content-Type, User-Agent, Authorization, etc.

### 4. Content Transformation
- **Regex Search/Replace**: Transform message content using patterns
- **JSONPath Operations**:
  - Drop fields (e.g., remove `$.messages[*].metadata`)
  - Add fields (e.g., inject `$.system` with predefined value)
- Separate rules for requests and responses

### 5. Request/Response Logging
- **Levels**: OFF, HEADERS_ONLY, FULL
- **Configurable per route or globally**
- Logs include:
  - Timestamps
  - Request/response headers
  - Request/response bodies
  - Latency metrics
  - Error details

### 6. Resilience Features
- **Timeouts**: Configurable per provider
- **Retries**: Automatic retry with exponential backoff
- **Retry conditions**: HTTP 429, 503, network errors
- **Circuit breaker**: Prevent cascading failures
- **SSL verification**: Can be disabled for development/testing

### 7. Performance
- **Async/non-blocking**: Built on Vert.x event loop
- **Streaming support**: Efficient SSE forwarding
- **Connection pooling**: Reuse connections to providers
- **Minimal overhead**: Direct proxying with optional transformations

## Configuration Example

```json
{
  "server": {
    "port": 8080,
    "host": "0.0.0.0"
  },
  "logging": {
    "level": "FULL",
    "logHeaders": true,
    "logBodies": true
  },
  "routes": [
    {
      "incomingModel": "gpt-4",
      "provider": {
        "type": "ollama",
        "baseUrl": "http://localhost:11434",
        "targetModel": "llama3.2:1b",
        "apiKey": null
      },
      "headers": {
        "dropAll": true,
        "add": {
          "Content-Type": "application/json",
          "User-Agent": "LLM-Proxy/1.0"
        }
      },
      "transformations": {
        "request": {
          "regexReplacements": [
            {
              "pattern": "confidential",
              "replacement": "[REDACTED]"
            }
          ],
          "jsonPathOps": [
            {
              "op": "remove",
              "path": "$.metadata"
            }
          ]
        }
      },
      "client": {
        "timeout": 60000,
        "retries": 3,
        "verifySsl": true
      }
    }
  ]
}
```

## Technology Stack

- **Java 21**: Modern Java with virtual threads and pattern matching
- **Gradle**: Build tool with Kotlin DSL
- **Vert.x 4.x**: Reactive, non-blocking framework
- **Jackson**: JSON processing
- **Jayway JsonPath**: JSONPath operations
- **SLF4J + Logback**: Logging

## Development Approach

1. **Configuration-driven**: All behavior controlled via config files
2. **Test-friendly**: Easy to test with mock providers
3. **Production-ready**: Logging, metrics, error handling
4. **Extensible**: Easy to add new providers or transformations

## Testing Strategy

- Unit tests for transformers and configuration
- Integration tests with embedded Vert.x server
- Manual testing with:
  - Local Ollama instance (llama3.2:1b)
  - OpenAI API (if key available)
  - Mock HTTP servers

## Future Enhancements

- Load balancing across multiple provider instances
- Rate limiting per client/model
- Caching layer for common requests
- Metrics and monitoring (Prometheus)
- Admin API for runtime configuration updates
- Support for more providers (Google AI, Cohere, etc.)
