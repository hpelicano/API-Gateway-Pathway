# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**PathwayProxyServer** — A Java (NSJ 11) Pathway server for HPE NonStop that bridges XPNET processes to an external HTTP API Gateway via Guardian IPC (`$RECEIVE`/REPLY). The server is **DDL-driven**: the structure of each message is described by a JSON file on disk; the server has no hardcoded knowledge of field names or lengths.

## Build (NonStop — Producción)

No hay Maven/Gradle. Requiere NSJ **11** (OpenJDK 11, NonStop-ported) y JToolkit (`tdmext.jar`, librería propietaria HPE):

```bash
# En NonStop OSS — compilar todos los fuentes juntos
javac -cp /usr/tandem/java11/lib/tdmext.jar \
      -source 11 -target 11 \
      -d build/classes \
      *.java

jar -cf proxy.jar -C build/classes .

FUP COPY proxy.jar, $DATA.PROXYAPP.PROXYSRV
```

Deployment desde TACL:
```tacl
PATHCOM $PROXYM
OBEY $DATA.PROXYAPP.PATHCFG
```

## Simulación local en WSL2

```bash
cd wsl2-sim
chmod +x build.sh run.sh
./build.sh          # compila stubs JToolkit + producción + harness
./run.sh            # 5 tests: consulta_saldo, alta_cliente, transferencia, error DDL, buffer inválido
./run.sh --debug    # con PROXY_DEBUG=true
```

Los DDL JSON de ejemplo están en `ddl/`. El `MockApiServer` los usa para construir respuestas realistas.

No hay tests automatizados ni herramientas de lint configuradas.

## Architecture

El servidor implementa un **bridge DDL-driven** entre procesos XPNET y un API Gateway externo:

```
XPNET (COBOL/TAL/C)
  └─ WRITEREAD buffer texto plano (key=value)
        ↓  Guardian $RECEIVE (JToolkit)
  PathwayProxyServer
  ├─ [1] MessageSerializer.parseBuffer()
  │       Lee metodo / endpoint / ddl_json del buffer
  │       Carga DdlDefinition desde ddl/<ddl_json>.json
  │       Mapea campos del buffer → JSON (sección "request" de la DDL)
  ├─ [2] ApiGatewayClient.call()
  │       HTTPS con TLS 1.2/1.3 → API Gateway externo
  └─ [3] MessageSerializer.buildReplyBuffer()
          Extrae campos del JSON de respuesta (json_path dot-notation)
          Arma buffer de longitud fija (padding COBOL) — sección "response" de la DDL
        ↓  Guardian REPLY
  XPNET recibe buffer de longitud fija
```

**Archivos fuente:**

| Archivo | Rol |
|---------|-----|
| `PathwayProxyServer.java` | Entry point. Loop `$RECEIVE`, orquesta los 3 pasos del flujo, maneja errores y reintentos. |
| `MessageSerializer.java` | Parsea buffer texto→`IpcRequest` (con DDL) y convierte JSON de API→buffer fijo de respuesta. No tiene conocimiento de campos específicos. |
| `DdlLoader.java` | Carga y cachea `DdlDefinition` desde archivos JSON en `PROXY_DDL_PATH`. Incluye mini parser JSON sin dependencias externas. |
| `DdlDefinition.java` | Modelo: nombre de DDL + lista de `DdlField` para request y response. |
| `DdlField.java` | Un campo DDL: `name`, `length`, `type` (string/numeric), `json_path` (dot-notation). |
| `ApiGatewayClient.java` | Cliente HTTPS. Traduce `IpcRequest` → llamada HTTP con el JSON construido → `IpcResponse`. Retry con backoff en 5xx/timeout. |
| `KeepAliveService.java` | Daemon thread: pings `GET /health` periódicamente para detectar caídas proactivamente y mantener el pool TLS activo. |
| `SslContextFactory.java` | Crea `SSLContext` restringido a TLS 1.2+1.3. Soporta truststore JKS, mTLS, o truststore por defecto de la JVM. |
| `IpcRequest.java` | Modelo del mensaje parseado: method, endpoint, ddlName, correlationId, dataFields (Map), jsonPayload, DdlDefinition. |
| `IpcResponse.java` | Contenedor de la respuesta HTTP de la API: httpStatus, payload (JSON raw). |
| `Logger.java` | Logger stdout compatible con NonStop EMS. |
| `PATHCFG` | Script PATHCOM para desplegar el SERVERCLASS en NonStop. |

**WSL2 simulation (`wsl2-sim/`):**

| Archivo | Rol |
|---------|-----|
| `stubs/Receive.java` | Mock de Guardian `$RECEIVE` usando `BlockingQueue`. `SimRunner` deposita buffers; `reply()` los captura. |
| `stubs/Guardian*.java` | Stubs mínimos de compilación para `tdmext.jar`. |
| `MockApiServer.java` | Servidor HTTP local con `/health` y respuestas JSON simuladas por endpoint. |
| `SimRunner.java` | 5 tests: construye buffers IPC de texto como XPNET real, verifica los buffers de REPLY. |
| `build.sh` / `run.sh` | Build y ejecución en JDK 11+ Linux. |

## IPC Buffer Format (texto plano key=value)

**Request buffer (XPNET → Proxy):**

```
metodo=POST
endpoint=/api/v1/clientes
ddl_json=alta_cliente
nombre=Juan
apellido=Pérez
dni=20345678901
email=juan@banco.com
```

Las primeras 3 líneas son campos de control del servidor (obligatorios).
El resto son campos de negocio mapeados al JSON usando la DDL.
`correlation_id=` es opcional; si no está presente, el servidor genera uno.

**Response buffer (Proxy → XPNET) — longitud fija definida por DDL:**

```
[cod_ret 4 chars][id_cliente 10 chars][mensaje 80 chars]
0000000000099Alta de cliente exitosa                                               
```

Cada campo tiene la longitud exacta definida en la sección `response` de la DDL:
- `type: "string"` → alineado izquierda, padded con espacios
- `type: "numeric"` → alineado derecha, padded con ceros

## DDL JSON Format

Los archivos DDL están en `PROXY_DDL_PATH` (default: `/home/proxyuser/ddl` en NonStop, `./ddl` en WSL2).

```json
{
  "ddl_name":    "alta_cliente",
  "description": "Alta de nuevo cliente persona física",
  "request": [
    { "field": "nombre",   "length": 40, "type": "string",  "json_path": "nombre"           },
    { "field": "dni",      "length": 11, "type": "string",  "json_path": "documento.numero" }
  ],
  "response": [
    { "field": "cod_ret",    "length":  4, "type": "numeric", "json_path": "codigo"          },
    { "field": "id_cliente", "length": 10, "type": "numeric", "json_path": "data.id_cliente" },
    { "field": "mensaje",    "length": 80, "type": "string",  "json_path": "mensaje"         }
  ]
}
```

- `json_path` soporta notación dot para anidamiento: `"documento.numero"` → `{"documento":{"numero":"..."}}`
- La DDL se carga una vez y queda en cache. Modificar un `.json` requiere reiniciar el SERVERCLASS.
- Archivos de ejemplo en `ddl/`: `consulta_saldo.json`, `alta_cliente.json`, `transferencia.json`.

## Proxy Error Codes (buffer de error)

| Código | Condición |
|--------|-----------|
| E000 | Mensaje IPC vacío |
| E001 | Falta campo obligatorio (metodo / endpoint / ddl_json) |
| E002 | DDL no encontrada en `PROXY_DDL_PATH` |
| E003 | Timeout o error de red llamando a la API |
| E4xx | HTTP 4xx de la API (no retryable) |
| E5xx | HTTP 5xx de la API (retryable hasta 3 intentos) |

## Environment Variables (set in PATHCFG / PATHCOM)

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `PROXY_API_BASE_URL` | Yes | — | Base URL del API Gateway externo |
| `PROXY_API_KEY` | Yes | — | API Key para autenticación |
| `PROXY_DDL_PATH` | Yes | `/home/proxyuser/ddl` | Directorio con los archivos DDL JSON |
| `PROXY_CONNECT_TIMEOUT_MS` | No | 5000 | Timeout TCP (ms) |
| `PROXY_REQUEST_TIMEOUT_MS` | No | 30000 | Timeout HTTP request (ms) |
| `PROXY_HEALTH_PATH` | No | `/health` | Endpoint de keep-alive |
| `PROXY_KEEPALIVE_INTERVAL_S` | No | 30 | Intervalo de ping keep-alive (segundos) |
| `PROXY_KEEPALIVE_TIMEOUT_MS` | No | 5000 | Timeout del ping keep-alive (ms) |
| `PROXY_KEEPALIVE_MAX_FAILS` | No | 3 | Fallos consecutivos antes de log ERROR |
| `PROXY_TRUSTSTORE_PATH` | No | — | JKS con CA corporativa (TLS) |
| `PROXY_TRUSTSTORE_PASSWORD` | No | — | Password del truststore |
| `PROXY_KEYSTORE_PATH` | No | — | JKS cert de cliente para mTLS |
| `PROXY_KEYSTORE_PASSWORD` | No | — | Password del keystore |
| `PROXY_DEBUG` | No | false | Logging verbose |
| `JREHOME` | Yes | — | Path de instalación de NSJ 11 |
| `JAVA_OPTS` | No | `-Xms64m -Xmx256m -XX:+UseG1GC` | JVM flags |

## Critical Constraints

- **JDK 11 target**: Solo APIs de Java 11. Sin switch expressions, records, text blocks ni pattern matching instanceof.
- **Sin dependencias externas**: `java.net.http.HttpClient` (JDK 11) para HTTP. El parser JSON de la DDL está implementado con `java.util.regex` dentro de `DdlLoader`. No agregar Jackson, Gson, ni otras librerías.
- **JToolkit requerido en producción**: `com.tandem.ext.guardian.*` solo está en `tdmext.jar` de HPE. `wsl2-sim/stubs/` provee reemplazos para desarrollo.
- **Loop IPC single-threaded**: Cada proceso Pathway corre un único thread en `$RECEIVE`. `KeepAliveService` corre en un daemon thread separado pero nunca debe tocar `$RECEIVE`. El escalado es via PATHMON (MAXSERVERS).
- **Cache de DDLs es perpetua por proceso**: Las DDLs se cargan una vez al primer mensaje que las usa. Cambios en `.json` requieren `STOP/START SERVERCLASS PROXYSRV` en PATHCOM.
- **NSJ 11 es headless**: Sin AWT/Swing. Sin librerías gráficas Java.
- **TLS 1.2 + 1.3 únicamente**: `SslContextFactory.tlsParameters()` rechaza TLS 1.0/1.1 explícitamente.
