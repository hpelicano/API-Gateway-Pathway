# PathwayProxyServer — HPE NonStop / NSJ 17

## Descripción

Servidor Pathway escrito en **Java (NSJ 17)** para HPE NonStop que actúa como proxy entre procesos XPNET y un API Gateway externo. Implementa el patrón Guardian IPC / $RECEIVE para interoperar de forma nativa con el ecosistema NonStop.

---

## Arquitectura del flujo

```
┌──────────────────────────────────────────────────────────────────────┐
│                        HPE NonStop (Guardian OS)                      │
│                                                                        │
│  ┌─────────────┐    IPC / WRITEREAD     ┌──────────────────────────┐ │
│  │             │ ──────────────────────► │   LINKMON ($PROXYLM)     │ │
│  │  Proceso    │                         │   TS/MP Load Balancer    │ │
│  │  XPNET      │                         └────────────┬─────────────┘ │
│  │  (caller)   │                                      │ distribuye    │
│  │             │                              ┌───────┴──────────┐    │
│  │             │                              │  PATHMON ($PROXYM)│    │
│  │             │                              │  gestiona N       │    │
│  │             │                              │  instancias Java  │    │
│  │             │                              └───────┬──────────┘    │
│  │             │                                      │               │
│  │             │    ◄── Guardian REPLY ──────         │               │
│  │             │                         │            ▼               │
│  │             │                    ┌────┴─────────────────────────┐  │
│  └─────────────┘                    │   PathwayProxyServer (NSJ 17) │  │
│                                     │                               │  │
│                                     │  1. Lee $RECEIVE (JToolkit)   │  │
│                                     │  2. Deserializa buffer IPC    │  │
│                                     │  3. Llama API externa (HTTP)  │  │
│                                     │  4. Serializa respuesta       │  │
│                                     │  5. Guardian REPLY → XPNET   │  │
│                                     └───────────────────────────────┘  │
└──────────────────────────────────────│──────────────────────────────────┘
                                       │  HTTPS/REST (java.net.http)
                                       ▼
                          ┌─────────────────────────┐
                          │    API Gateway Externo   │
                          │  (Kong / AWS API GW /    │
                          │   Nginx / custom)        │
                          └─────────────────────────┘
```

---

## Estructura del proyecto

```
nonstop-proxy/
├── src/main/java/com/nonstop/proxy/
│   ├── PathwayProxyServer.java      ← Punto de entrada. Loop principal $RECEIVE
│   ├── model/
│   │   ├── IpcRequest.java          ← Modelo del mensaje IPC entrante (XPNET → Java)
│   │   └── IpcResponse.java         ← Modelo de la respuesta (Java → XPNET)
│   ├── handler/
│   │   └── ApiGatewayClient.java    ← Cliente HTTP hacia el API Gateway externo
│   └── util/
│       ├── MessageSerializer.java   ← Serialización binaria big-endian del buffer IPC
│       └── Logger.java              ← Logger compatible con EMS de NonStop
├── config/
│   └── PATHCFG                      ← Script PATHCOM para desplegar el SERVERCLASS
└── docs/
    └── XPNET_CALLER_EXAMPLE.tal     ← Ejemplo TAL del proceso XPNET caller
```

---

## Layout del buffer IPC

### Request (XPNET → Proxy Server)

| Offset | Bytes | Campo          | Descripción                              |
|--------|-------|----------------|------------------------------------------|
| 0      | 2     | version        | Versión del protocolo (= 1)              |
| 2      | 2     | msgType        | 1=GET, 2=POST, 3=PUT, 4=DELETE           |
| 4      | 16    | correlationId  | ID de correlación end-to-end (ASCII)     |
| 20     | 32    | operation      | Path del API Gateway (ej: `/pay/auth`)   |
| 52     | 2     | payloadLength  | Longitud del payload JSON                |
| 54     | var   | payload        | Body JSON (UTF-8)                        |

### Response (Proxy Server → XPNET)

| Offset | Bytes | Campo          | Descripción                              |
|--------|-------|----------------|------------------------------------------|
| 0      | 2     | version        | Versión del protocolo (= 1)              |
| 2      | 2     | httpStatusCode | Código HTTP de la API externa            |
| 4      | 2     | errorCode      | 0=OK, >0=error interno del proxy         |
| 6      | 16    | correlationId  | ID de correlación (echo del request)     |
| 22     | 2     | payloadLength  | Longitud del payload JSON                |
| 24     | var   | payload        | Body JSON de respuesta (UTF-8)           |

**Todos los campos multi-byte están en big-endian (network byte order).**

---

## Códigos de error del proxy (errorCode)

| Código | Constante          | Descripción                            |
|--------|--------------------|----------------------------------------|
| 0      | ERR_OK             | Éxito                                  |
| 1      | ERR_DESERIALIZE    | Error al parsear el buffer IPC         |
| 2      | ERR_API_ERROR      | La API externa retornó error HTTP      |
| 3      | ERR_API_TIMEOUT    | Timeout al conectar/leer de la API     |
| 4      | ERR_API_UNAVAILABLE| API Gateway no disponible              |
| 5      | ERR_EMPTY_MSG      | Mensaje IPC vacío o de sistema         |
| 99     | ERR_INTERNAL       | Error interno inesperado               |

---

## Variables de entorno (configurar en PATHCOM)

```
PROXY_API_BASE_URL        URL base del API Gateway externo (obligatoria)
PROXY_API_KEY             API Key de autenticación (obligatoria)
PROXY_CONNECT_TIMEOUT_MS  Timeout de conexión TCP en ms (default: 5000)
PROXY_REQUEST_TIMEOUT_MS  Timeout de request HTTP en ms (default: 30000)
PROXY_DEBUG               true/false para logging verbose (default: false)
JREHOME                   Path de instalación de NSJ 17
JAVA_OPTS                 Flags adicionales de la JVM
```

---

## Dependencias

| Dependencia       | Versión | Fuente                          |
|-------------------|---------|---------------------------------|
| NSJ 17            | OpenJDK 17.0.8 portado NonStop | HPE Support Center (L24.08 RVU) |
| JToolkit          | 5.x     | HPE (tdmext.jar) — requiere licencia |
| java.net.http     | JDK 11+ | Incluido en el JDK estándar     |

> **Nota:** `java.net.http.HttpClient` está incluido en el JDK desde Java 11, por lo que **no se requieren dependencias externas** como Apache HttpClient o OkHttp.

---

## Compilación

```bash
# En un entorno OSS del NonStop (o cross-compilado con NSJ 17 JDK)
javac -cp /usr/tandem/java17/lib/tdmext.jar \
      -source 17 -target 17 \
      -d build/classes \
      src/main/java/com/nonstop/proxy/**/*.java \
      src/main/java/com/nonstop/proxy/*.java

jar -cf proxy.jar -C build/classes .

# Copiar al volume/subvolume del NonStop
FUP COPY proxy.jar, $DATA.PROXYAPP.PROXYSRV
```

---

## Despliegue en NonStop

```tacl
! Desde TACL
PATHCOM $PROXYM
OBEY $DATA.PROXYAPP.PATHCFG
```

---

## Consideraciones importantes

1. **JToolkit es obligatorio**: Las clases `com.tandem.ext.guardian.*` que envuelven `$RECEIVE` y el REPLY de Guardian solo están disponibles en `tdmext.jar`, distribuido por HPE. Se obtiene a través del soporte de HPE NonStop.

2. **NSJ 17 es headless**: No soporta AWT/Swing. El servidor no debe usar ninguna clase gráfica de Java.

3. **Thread model**: Cada proceso Pathway Server es de un solo thread en el loop de `$RECEIVE`. PATHMON escala levantando más instancias del proceso (MAXSERVERS). No se recomienda multithreading dentro del proceso para el loop IPC.

4. **Endianness**: NonStop Guardian usa big-endian para los campos de 2+ bytes en los buffers IPC. El `MessageSerializer` usa `ByteOrder.BIG_ENDIAN` explícitamente.

5. **XPNET y Pathsend**: El proceso XPNET puede usar `WRITEREAD` (TAL/COBOL) o `SERVERCLASS_SEND_` (C API) para enviar mensajes al Pathway server. Ambos enfoques son compatibles con este servidor Java.
