# LLM API Router Service

A high-performance LLM API router/proxy service built with Vert.x and Java 21. Acts as a unified interface between clients and various LLM providers (OpenAI, Anthropic, Ollama) with advanced routing, transformation, and configuration capabilities.

## Features

- **Multi-Provider Support**: OpenAI, Anthropic, and Ollama
- **Model Mapping**: Transparently route requests from one model to another
- **Header Manipulation**: Drop, add, or force headers
- **Content Transformation**: Regex and JSONPath-based transformations
- **Streaming Support**: Full SSE (Server-Sent Events) support for streaming responses
- **Configuration-Driven**: All behavior controlled via JSON configuration
- **High Performance**: Built on Vert.x non-blocking framework
- **Production Ready**: Comprehensive logging, error handling, and retry logic

## Requirements

- Java 21 or higher
- Gradle 7.2+ (included via wrapper)

## Quick Start

### 1. Build the Project

```bash
./gradlew clean build
```

### 2. Create Configuration

Create a `config.json` file:

```json
{
  "server": {
    "host": "0.0.0.0",
    "port": 8080
  },
  "routes": [
    {
      "incomingModel": "gpt-4",
      "provider": {
        "type": "OLLAMA",
        "baseUrl": "http://localhost:11434",
        "targetModel": "llama3.2:1b"
      }
    }
  ]
}
```

### 3. Run the Service

```bash
./gradlew run
```

Or with a custom configuration:

```bash
./gradlew run --args="path/to/config.json"
```

### 4. Test the Service

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4",
    "messages": [
      {"role": "user", "content": "Hello!"}
    ]
  }'
```

## Configuration

### Server Configuration

```json
{
  "server": {
    "host": "0.0.0.0",
    "port": 8080
  }
}
```

### Logging Configuration

```json
{
  "logging": {
    "level": "FULL",
    "logHeaders": true,
    "logBodies": true
  }
}
```

Levels: `OFF`, `HEADERS_ONLY`, `FULL`

### Route Configuration

Each route maps an incoming model to a target provider:

```json
{
  "routes": [
    {
      "incomingModel": "gpt-4",
      "provider": {
        "type": "OLLAMA",
        "baseUrl": "http://localhost:11434",
        "targetModel": "llama3.2:1b",
        "apiKey": null
      },
      "headers": {
        "dropAll": true,
        "drop": ["X-Custom-Header"],
        "add": {
          "Content-Type": "application/json"
        },
        "force": {
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
              "op": "REMOVE",
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

### Environment Variables

Use environment variables in configuration:

```json
{
  "provider": {
    "apiKey": "${OPENAI_API_KEY}"
  }
}
```

## API Endpoints

### OpenAI Compatible

```
POST /v1/chat/completions
```

### Anthropic Compatible

```
POST /v1/messages
```

### Health Check

```
GET /health
```

## Header Manipulation

- **dropAll**: Remove all incoming headers and start fresh
- **drop**: Remove specific headers by name
- **add**: Add headers if they don't already exist
- **force**: Override existing headers

## Content Transformation

### Regex Replacements

Transform content using regular expressions:

```json
{
  "regexReplacements": [
    {
      "pattern": "sensitive_data",
      "replacement": "[REDACTED]"
    }
  ]
}
```

### JSONPath Operations

Manipulate JSON structure:

```json
{
  "jsonPathOps": [
    {
      "op": "REMOVE",
      "path": "$.messages[*].metadata"
    },
    {
      "op": "ADD",
      "path": "$.system",
      "value": "You are a helpful assistant"
    }
  ]
}
```

## Provider Types

- **OPENAI**: OpenAI API (`https://api.openai.com`)
- **ANTHROPIC**: Anthropic Claude API (`https://api.anthropic.com`)
- **OLLAMA**: Local Ollama instance (`http://localhost:11434`)

## Examples

### Route GPT-4 to Local Ollama

```json
{
  "incomingModel": "gpt-4",
  "provider": {
    "type": "OLLAMA",
    "baseUrl": "http://localhost:11434",
    "targetModel": "llama3.2:1b"
  }
}
```

### Use OpenAI with API Key

```json
{
  "incomingModel": "gpt-4",
  "provider": {
    "type": "OPENAI",
    "baseUrl": "https://api.openai.com",
    "targetModel": "gpt-4",
    "apiKey": "${OPENAI_API_KEY}"
  }
}
```

## Logging

Logs are written to:
- Console (stdout)
- File: `logs/llm-proxy.log` (rolling daily, 30 day retention)

Configure logging in `src/main/resources/logback.xml`

## Development

### Project Structure

```
src/
├── main/
│   ├── java/com/llmproxy/
│   │   ├── Main.java
│   │   ├── config/         # Configuration models
│   │   ├── server/         # HTTP server & handlers
│   │   ├── client/         # HTTP client
│   │   └── transformer/    # Request/response transformers
│   └── resources/
│       └── logback.xml     # Logging configuration
└── test/
    └── java/com/llmproxy/  # Tests
```

### Building

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

### Running

```bash
./gradlew run
```

## Troubleshooting

### Port Already in Use

Change the port in `config.json`:

```json
{
  "server": {
    "port": 8081
  }
}
```

### Connection Refused to Provider

- Ensure the provider is running (e.g., Ollama)
- Check the `baseUrl` in configuration
- Verify network connectivity

### SSL Certificate Errors

Disable SSL verification (development only):

```json
{
  "client": {
    "verifySsl": false
  }
}
```

## License

MIT License - see LICENSE file for details

## Contributing

Contributions are welcome! Please submit issues and pull requests.

## Architecture

See [CLAUDE.md](CLAUDE.md) for detailed architecture documentation.
