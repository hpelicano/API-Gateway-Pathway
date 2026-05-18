# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**PathwayProxyServer** — A Java (NSJ 17) Pathway server for HPE NonStop that bridges XPNET processes to an external HTTP API Gateway via Guardian IPC (`$RECEIVE`/REPLY protocol).

## Build (NonStop — Producción)

No hay Maven/Gradle. Requiere NSJ **11** (OpenJDK 11, NonStop-ported) y JToolkit (`tdmext.jar`, librería propietaria HPE):

```bash
# En NonStop OSS o cross-compilado con NSJ 11 JDK
javac -cp /usr/tandem/java11/lib/tdmext.jar \
      -source 11 -target 11 \
      -d build/classes \
      *.java

jar -cf proxy.jar -C build/classes .

# Copiar al volume/subvolume del NonStop
FUP COPY proxy.jar, $DATA.PROXYAPP.PROXYSRV
```

Deployment desde TACL:
```tacl
PATHCOM $PROXYM
OBEY $DATA.PROXYAPP.PATHCFG
```

## Simulación local en WSL2

La carpeta `wsl2-sim/` permite compilar y ejecutar el servidor sin NonStop ni JToolkit, usando stubs de Guardian y un servidor HTTP local.

```bash
cd wsl2-sim
chmod +x build.sh run.sh
./build.sh          # compila stubs + producción + harness
./run.sh            # ejecuta la simulación completa
./run.sh --debug    # con logging verbose
```

El script levanta un `MockApiServer` en `localhost:18080`, envía 5 mensajes IPC de prueba (GET/POST/PUT/DELETE) y decodifica los buffers de REPLY.

No hay tests automatizados ni herramientas de lint configuradas.

## Architecture

The server implements a **bridge/proxy pattern**: it receives binary IPC messages from XPNET processes via NonStop Guardian `$RECEIVE`, translates them into HTTPS calls to an external API Gateway, and returns the HTTP response back via Guardian REPLY.

```
XPNET process → WRITEREAD → LINKMON ($PROXYLM) → PATHMON ($PROXYM) → PathwayProxyServer
PathwayProxyServer → HTTPS (TLS 1.2/1.3) → External API Gateway
PathwayProxyServer → Guardian REPLY → XPNET process
```

**Key source files:**

| File | Role |
|------|------|
| `PathwayProxyServer.java` | Entry point. Opens `$RECEIVE` via JToolkit, drives the single-threaded message loop, starts `KeepAliveService`. |
| `ApiGatewayClient.java` | Converts `IpcRequest` → HTTPS call (TLS 1.2/1.3) → `IpcResponse`. Retry with exponential backoff on 5xx/timeout. |
| `KeepAliveService.java` | Background daemon thread: pings `GET /health` on the external API at a configurable interval to detect outages proactively and keep TLS connections warm. |
| `SslContextFactory.java` | Creates `SSLContext` restricted to TLS 1.2 + TLS 1.3. Supports default JVM truststore, custom JKS truststore (corporate CA), or mutual TLS (mTLS). |
| `MessageSerializer.java` | Deserializes binary IPC buffer → `IpcRequest`; serializes `IpcResponse` → binary buffer. All multi-byte fields are **big-endian**. |
| `IpcRequest.java` / `IpcResponse.java` | Message models (JDK 11 compatible — no records/switch expressions). |
| `Logger.java` | stdout-based logging compatible with NonStop EMS. |
| `PATHCFG` | PATHCOM script defining the SERVERCLASS, LINKMON, and environment variables for deployment. |

**WSL2 simulation files (`wsl2-sim/`):**

| File | Role |
|------|------|
| `stubs/Receive.java` | Mock Guardian `$RECEIVE` using `BlockingQueue`. `SimRunner` deposits IPC buffers; `reply()` captures responses. |
| `stubs/ReceiveInfo.java`, `stubs/GuardianException.java`, `stubs/Guardian.java` | Minimal JToolkit stubs for compilation without `tdmext.jar`. |
| `MockApiServer.java` | `HttpServer`-based local API mock responding to any path with JSON. Has `/health` endpoint for keep-alive. |
| `SimRunner.java` | Test harness: builds binary IPC buffers (as a real XPNET process would), runs the server, and decodes the REPLY buffers. |
| `build.sh` / `run.sh` | Compile and run the full simulation on any JDK 11+ Linux environment. |

## IPC Binary Protocol

**Request buffer (XPNET → Proxy):**

| Offset | Bytes | Field | Notes |
|--------|-------|-------|-------|
| 0 | 2 | version | Protocol version (= 1) |
| 2 | 2 | msgType | 1=GET, 2=POST, 3=PUT, 4=DELETE |
| 4 | 16 | correlationId | End-to-end correlation ID (ASCII) |
| 20 | 32 | operation | API path (e.g., `/pay/auth`) |
| 52 | 2 | payloadLength | JSON payload length |
| 54 | var | payload | JSON body (UTF-8) |

**Response buffer (Proxy → XPNET):**

| Offset | Bytes | Field | Notes |
|--------|-------|-------|-------|
| 0 | 2 | version | Protocol version (= 1) |
| 2 | 2 | httpStatusCode | HTTP status from external API |
| 4 | 2 | errorCode | 0=OK, see table below |
| 6 | 16 | correlationId | Echo of request correlationId |
| 22 | 2 | payloadLength | JSON payload length |
| 24 | var | payload | JSON response body (UTF-8) |

**Proxy error codes (errorCode field):**

| Code | Meaning |
|------|---------|
| 0 | ERR_OK |
| 1 | ERR_DESERIALIZE — failed to parse IPC buffer |
| 2 | ERR_API_ERROR — external API returned HTTP error |
| 3 | ERR_API_TIMEOUT |
| 4 | ERR_API_UNAVAILABLE |
| 5 | ERR_EMPTY_MSG — empty or system IPC message |
| 99 | ERR_INTERNAL — unexpected internal error |

## Environment Variables (set in PATHCFG / PATHCOM)

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `PROXY_API_BASE_URL` | Yes | — | Base URL of the external API Gateway |
| `PROXY_API_KEY` | Yes | — | API Key for authentication |
| `PROXY_CONNECT_TIMEOUT_MS` | No | 5000 | TCP connection timeout (ms) |
| `PROXY_REQUEST_TIMEOUT_MS` | No | 30000 | HTTP request timeout (ms) |
| `PROXY_HEALTH_PATH` | No | `/health` | Keep-alive endpoint path |
| `PROXY_KEEPALIVE_INTERVAL_S` | No | 30 | Keep-alive ping interval (seconds) |
| `PROXY_KEEPALIVE_TIMEOUT_MS` | No | 5000 | Keep-alive ping timeout (ms) |
| `PROXY_KEEPALIVE_MAX_FAILS` | No | 3 | Consecutive failures before ERROR log |
| `PROXY_TRUSTSTORE_PATH` | No | — | JKS with corporate CA (TLS) |
| `PROXY_TRUSTSTORE_PASSWORD` | No | — | Truststore password |
| `PROXY_KEYSTORE_PATH` | No | — | JKS client cert for mTLS |
| `PROXY_KEYSTORE_PASSWORD` | No | — | Keystore password |
| `PROXY_DEBUG` | No | false | Verbose logging |
| `JREHOME` | Yes | — | NSJ 11 installation path |
| `JAVA_OPTS` | No | `-Xms64m -Xmx256m -XX:+UseG1GC` | JVM flags |

## Critical Constraints

- **JDK 11 target**: All source files use only Java 11 APIs. No switch expressions, records, text blocks, or pattern matching instanceof — these require Java 14+/16+/15+ respectively.
- **JToolkit is required in production**: `com.tandem.ext.guardian.*` classes are only available in `tdmext.jar` distributed by HPE. The `wsl2-sim/stubs/` directory provides compile-time replacements for development.
- **Single-threaded IPC loop**: Each Pathway server process runs a single-thread `$RECEIVE` loop. `KeepAliveService` runs on a separate daemon thread but must never touch `$RECEIVE`. Scaling is done by PATHMON launching more server instances.
- **Big-endian**: All multi-byte buffer fields use `ByteOrder.BIG_ENDIAN` — this must be preserved in any protocol changes.
- **NSJ 11 is headless**: No AWT/Swing. Do not import Java GUI libraries.
- **No external HTTP libraries**: `java.net.http.HttpClient` (JDK 11 built-in) is used intentionally. Do not add Apache HttpClient, OkHttp, or other HTTP dependencies.
- **TLS 1.2 + 1.3 only**: `SslContextFactory.tlsParameters()` restricts protocols. Do not remove this restriction — corporate API Gateways typically reject TLS 1.0/1.1.
