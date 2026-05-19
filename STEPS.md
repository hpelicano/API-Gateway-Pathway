# PathwayProxyServer — Guía técnica de referencia

---

## 1. Arquitectura de ejecución en HPE NonStop

### Cómo levanta PATHMON un proceso Java

En NonStop, un proceso Java **no arranca como en Linux con `java -jar`**. Lo hace **PATHMON**, que es el supervisor del entorno Pathway. La secuencia real es:

```
TACL> PATHCOM $PROXYM
PATHCOM> OBEY $DATA.PROXYAPP.PATHCFG   ← lee tu PATHCFG
```

Al ejecutar `START SERVERCLASS PROXYSRV`, PATHMON hace internamente:

```bash
/usr/tandem/java11/bin/java \
  -cp /usr/tandem/java11/lib/tdmext.jar:/home/proxyuser/proxy.jar \
  -Xms64m -Xmx256m -XX:+UseG1GC \
  com.nonstop.proxy.PathwayProxyServer
```

Ese proceso Java **es** el SERVERCLASS. PATHMON arranca `MINSERVERS=2` instancias en frío esperando en `$RECEIVE`, y escala hasta `MAXSERVERS=10` bajo carga.

---

## 2. Flujo completo mensaje a mensaje (DDL-driven)

El servidor es **DDL-driven**: la estructura de cada mensaje está descripta en un archivo JSON en disco. El servidor no tiene conocimiento hardcodeado de campos de negocio.

```
XPNET Process (TAL/COBOL/C)
        │
        │  WRITEREAD $PROXYLM, buffer_texto_plano, buffer_reply
        │  (formato: clave=valor por línea)
        ▼
  LINKMON $PROXYLM          ← balancea entre instancias PROXYSRV disponibles
        │
        │  distribuye la conexión a un PROXYSRV libre
        ▼
  PathwayProxyServer.java
  └── run() → Receive.getInstance().open()
             │
             │  receive.read(rawBuffer, 32768, info)   ← BLOQUEANTE
             │  [PathwayProxyServer.java:96]
             │
             ▼
  ── PASO 1: Parseo del buffer de texto plano ─────────────────────────
  MessageSerializer.parseBuffer(rawBuffer, bytesRead, ddlLoader)
  [MessageSerializer.java:57]
  │
  ├── parseKeyValue()  → Map<String, String> de todos los pares clave=valor
  │
  ├── Lee campos de control obligatorios:
  │     metodo    → GET / POST / PUT / DELETE
  │     endpoint  → /api/v1/clientes
  │     ddl_json  → alta_cliente
  │
  ├── DdlLoader.load("alta_cliente")
  │   [DdlLoader.java]
  │   └── Lee /home/proxyuser/ddl/alta_cliente.json (o cache si ya fue cargada)
  │   └── Parsea secciones "request" y "response" con mini parser regex
  │   └── Devuelve DdlDefinition{requestFields, responseFields}
  │
  └── buildRequestJson(dataFields, ddl)
      [MessageSerializer.java]
      └── Itera sección "request" de la DDL
      └── Mapea campos del buffer al JSON usando json_path (soporta anidamiento)
      └── Produce: {"nombre":"Juan","documento":{"numero":"20345678901"}}
             │
             ▼
  ── PASO 2: Llamada al API Gateway externo ───────────────────────────
  ApiGatewayClient.call(request)
  [ApiGatewayClient.java:100]
  └── buildUrl()  → baseUrl + request.getEndpoint()
  └── buildHttpRequest() → HTTP POST/GET/PUT/DELETE con headers:
        Content-Type:     application/json
        X-API-Key:        <PROXY_API_KEY>
        X-Correlation-Id: <correlationId>
        X-Source-System:  NONSTOP-XPNET
        body:             JSON construido con la DDL
  └── httpClient.send() → HTTPS con TLS 1.2/1.3
             │
             ▼
  IpcResponse.success(correlationId, httpStatus, jsonResponseBody)
  [ApiGatewayClient.java]
             │
             ▼
  ── PASO 3: Construcción del buffer de respuesta fijo ────────────────
  MessageSerializer.buildReplyBuffer(apiJsonResponse, request)
  [MessageSerializer.java:83]
  └── Itera sección "response" de la DDL del request original
  └── Para cada campo: extractByJsonPath(json, field.getJsonPath())
      └── Soporta notación dot: "data.id_cliente" → json["data"]["id_cliente"]
  └── padField(value, length, isNumeric)
      └── numeric → alineado derecha, ceros a la izquierda  "0000000000099"
      └── string  → alineado izquierda, espacios a la derecha "Alta exitosa   "
  └── Concatena todos los campos → buffer de longitud fija total
             │
             ▼
  receive.reply(replyBuffer, len, 0)
  [PathwayProxyServer.java:121]
        │
        ▼
  XPNET Process recibe buffer_reply ← WRITEREAD retorna
  (buffer de longitud fija con campos paddeados como PIC COBOL)
```

---

## 3. Formato del buffer IPC (texto plano key=valor)

### Request (XPNET → Proxy)

```
metodo=POST
endpoint=/api/v1/clientes
ddl_json=alta_cliente
nombre=Juan
apellido=Pérez
dni=20345678901
email=juan@banco.com
telefono=+54911234567
```

- Una clave=valor por línea, separadas por `\n` o `\r\n`
- Las claves se normalizan a minúsculas internamente
- Los primeros 3 campos son de control del servidor (obligatorios)
- `correlation_id=` es opcional; si falta el servidor genera uno automáticamente

### Response (Proxy → XPNET) — longitud fija

```
[cod_ret 4][id_cliente 10][mensaje                                         80]
0000000000099Alta de cliente exitosa                                           
```

El layout exacto lo define la sección `response` de la DDL correspondiente.

---

## 4. Sistema DDL — gestión de definiciones de mensajes

### Estructura de la carpeta DDL en NonStop

```
/home/proxyuser/
├── proxy.jar
├── certs/
│   ├── truststore.jks
│   └── keystore.jks
└── ddl/                              ← PROXY_DDL_PATH apunta aquí
    ├── consulta_saldo.json
    ├── alta_cliente.json
    ├── transferencia.json
    └── ...  (una DDL por tipo de mensaje)
```

### Estructura de un archivo DDL JSON

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

| Atributo | Descripción |
|----------|-------------|
| `field` | Nombre de la clave en el buffer key=value del XPNET |
| `length` | Longitud fija en bytes — equivale a `PIC X(n)` o `PIC 9(n)` en COBOL |
| `type` | `"string"` → padding espacios / `"numeric"` → padding ceros |
| `json_path` | Dot-notation para mapear hacia/desde el JSON de la API. `"documento.numero"` produce `{"documento":{"numero":"..."}}` |

### Ciclo de vida de la cache DDL

```
Primer mensaje con ddl_json=alta_cliente
    ↓
DdlLoader.load("alta_cliente")
    ↓ (archivo no está en cache)
Lee /home/proxyuser/ddl/alta_cliente.json
    ↓
Parsea con mini parser regex (sin librerías externas)
    ↓
Guarda en ConcurrentHashMap — permanece en memoria
    ↓
Segundo mensaje con ddl_json=alta_cliente
    ↓
DdlLoader.load("alta_cliente") → retorna desde cache (sin I/O)
```

**Para recargar una DDL modificada** (no hay recarga en caliente):
```tacl
PATHCOM $PROXYM
STOP SERVERCLASS PROXYSRV
START SERVERCLASS PROXYSRV
```

### Agregar un nuevo tipo de mensaje

1. Crear `/home/proxyuser/ddl/nuevo_mensaje.json` con la estructura DDL
2. No se requiere ningún cambio de código ni recompilación
3. El proceso XPNET envía `ddl_json=nuevo_mensaje` y el servidor lo resuelve automáticamente

---

## 5. Cadena de inicialización SSL/TLS

Cuando `PathwayProxyServer` arranca, en su constructor llama `new ApiGatewayClient()`. Internamente ocurre en orden:

```
ApiGatewayClient constructor
  [ApiGatewayClient.java:52-70]
        │
        ├── SslContextFactory.createFromEnv()
        │     [SslContextFactory.java:128]
        │     Lee 4 variables de entorno:
        │       PROXY_KEYSTORE_PATH      → ¿existe? → mTLS
        │       PROXY_TRUSTSTORE_PATH    → ¿existe? → CA privada
        │       ninguna                  → truststore por defecto de la JVM
        │
        └── SslContextFactory.tlsParameters()
              [SslContextFactory.java:118]
              SSLParameters.setProtocols(["TLSv1.2", "TLSv1.3"])
              ← rechaza TLS 1.0 y 1.1 explícitamente

HttpClient.newBuilder()
  .sslContext(sslContext)     ← quién confía en quién
  .sslParameters(sslParams)   ← qué versiones de protocolo se permiten
  .build()
```

Hay **tres modos** que el código selecciona automáticamente:

| Caso | Variables definidas | Método usado |
|------|---------------------|-------------|
| API pública (Let's Encrypt, DigiCert) | ninguna | `createDefault()` — usa cacerts de la JVM |
| API corporativa con CA privada | `PROXY_TRUSTSTORE_PATH` | `createWithTrustStore()` |
| API que exige cert de cliente (mTLS) | `PROXY_KEYSTORE_PATH` + `PROXY_TRUSTSTORE_PATH` | `createWithMutualTls()` |

El mismo `HttpClient` construido aquí es compartido con `KeepAliveService` — ambos usan el mismo pool de conexiones TLS.

---

## 6. Dónde colocar los certificados en NonStop OSS

### Estructura de paths recomendada

```
/home/proxyuser/
├── proxy.jar
├── ddl/                              ← DDL JSON de mensajes
└── certs/
    ├── truststore.jks                ← CA corporativa
    ├── keystore.jks                  ← cert de cliente (mTLS — opcional)
    └── README.txt                    ← documentar passwords usadas
```

Verificar permisos OSS:

```bash
ls -la /home/proxyuser/certs/
chmod 640 /home/proxyuser/certs/*.jks
chown proxyuser:users /home/proxyuser/certs/*.jks
```

### Crear el truststore JKS desde un certificado PEM

```bash
# keytool incluido en /usr/tandem/java11/bin/
/usr/tandem/java11/bin/keytool \
  -importcert \
  -alias api-gateway-ca \
  -file /home/proxyuser/certs/ca-cert.pem \
  -keystore /home/proxyuser/certs/truststore.jks \
  -storepass changeit \
  -noprompt
```

Cadena de CAs intermedias (lo más común en empresas):

```bash
/usr/tandem/java11/bin/keytool -importcert -alias root-ca    -file root.pem   -keystore truststore.jks -storepass changeit -noprompt
/usr/tandem/java11/bin/keytool -importcert -alias inter-ca-1 -file inter1.pem -keystore truststore.jks -storepass changeit -noprompt
/usr/tandem/java11/bin/keytool -importcert -alias api-gw-ca  -file api-gw.pem -keystore truststore.jks -storepass changeit -noprompt
```

Para mTLS (cert de cliente desde `.p12`):

```bash
/usr/tandem/java11/bin/keytool \
  -importkeystore \
  -srckeystore cliente.p12 -srcstoretype PKCS12 -srcstorepass <pass_del_p12> \
  -destkeystore /home/proxyuser/certs/keystore.jks -deststorepass changeit \
  -noprompt
```

---

## 7. PATHCFG completo (variables actualizadas)

Las variables son leídas por `SslContextFactory.createFromEnv()` y `DdlLoader` en sus constructores:

```tacl
!-- JVM Home: NSJ 11 --
SET SERVERCLASS PROXYSRV ENV "JREHOME=/usr/tandem/java11"

!-- Classpath: tdmext.jar primero, luego proxy.jar --
SET SERVERCLASS PROXYSRV ARGLIST &
    "-cp /usr/tandem/java11/lib/tdmext.jar:/home/proxyuser/proxy.jar &
     com.nonstop.proxy.PathwayProxyServer"

!-- API Gateway --
SET SERVERCLASS PROXYSRV ENV "PROXY_API_BASE_URL=https://api.miempresa.com/v1"
SET SERVERCLASS PROXYSRV ENV "PROXY_API_KEY=mi-api-key-produccion"

!-- DDL (NOVEDAD) --
SET SERVERCLASS PROXYSRV ENV "PROXY_DDL_PATH=/home/proxyuser/ddl"

!-- Timeouts --
SET SERVERCLASS PROXYSRV ENV "PROXY_CONNECT_TIMEOUT_MS=5000"
SET SERVERCLASS PROXYSRV ENV "PROXY_REQUEST_TIMEOUT_MS=30000"

!-- SSL/TLS --
!-- Caso 1: CA privada solamente --
SET SERVERCLASS PROXYSRV ENV "PROXY_TRUSTSTORE_PATH=/home/proxyuser/certs/truststore.jks"
SET SERVERCLASS PROXYSRV ENV "PROXY_TRUSTSTORE_PASSWORD=changeit"
!-- Caso 2: mTLS (cert de cliente + CA) --
!SET SERVERCLASS PROXYSRV ENV "PROXY_KEYSTORE_PATH=/home/proxyuser/certs/keystore.jks"
!SET SERVERCLASS PROXYSRV ENV "PROXY_KEYSTORE_PASSWORD=changeit"

!-- Keep-alive --
SET SERVERCLASS PROXYSRV ENV "PROXY_HEALTH_PATH=/health"
SET SERVERCLASS PROXYSRV ENV "PROXY_KEEPALIVE_INTERVAL_S=30"
SET SERVERCLASS PROXYSRV ENV "PROXY_KEEPALIVE_MAX_FAILS=3"

!-- JVM flags --
SET SERVERCLASS PROXYSRV ENV &
    "JAVA_OPTS=-Xms64m -Xmx256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

El classpath `-cp tdmext.jar:proxy.jar` es **el mecanismo de dependencias**. No hay Maven. Los archivos `.jks` y `.json` de DDL son datos, no código — no van en `-cp`.

---

## 8. Cómo probar la configuración de ambiente

### Paso 1 — Verificar que keytool lee el truststore

```bash
/usr/tandem/java11/bin/keytool \
  -list -v \
  -keystore /home/proxyuser/certs/truststore.jks \
  -storepass changeit
# Debe mostrar el alias "api-gateway-ca" con fecha de expiración y fingerprint
```

### Paso 2 — Verificar que los archivos DDL existen y son válidos JSON

```bash
# Verificar que el directorio DDL es accesible para el usuario del proceso
ls -la /home/proxyuser/ddl/

# Verificar que cada DDL es JSON válido (python disponible en OSS toolkit)
python3 -m json.tool /home/proxyuser/ddl/alta_cliente.json > /dev/null \
  && echo "OK" || echo "JSON inválido"

# Listar todas las DDLs disponibles con su tamaño
ls -lh /home/proxyuser/ddl/*.json
```

### Paso 3 — Probar TLS desde OSS antes de deployar

```bash
openssl s_client \
  -connect api.miempresa.com:443 \
  -CAfile /home/proxyuser/certs/ca-cert.pem \
  -tls1_2
# "Verify return code: 0 (ok)" → cert válido
# "self signed certificate"    → falta la CA en el truststore
```

### Paso 4 — Probar el JAR directamente en OSS (fuera de Pathway)

Ver logs en consola sin la capa de PATHMON. Útil para verificar SSL y carga de DDLs:

```bash
export PROXY_API_BASE_URL=https://api.miempresa.com/v1
export PROXY_API_KEY=mi-api-key-produccion
export PROXY_DDL_PATH=/home/proxyuser/ddl
export PROXY_CONNECT_TIMEOUT_MS=5000
export PROXY_REQUEST_TIMEOUT_MS=30000
export PROXY_TRUSTSTORE_PATH=/home/proxyuser/certs/truststore.jks
export PROXY_TRUSTSTORE_PASSWORD=changeit
export PROXY_HEALTH_PATH=/health
export PROXY_KEEPALIVE_INTERVAL_S=30
export PROXY_DEBUG=true

/usr/tandem/java11/bin/java \
  -cp /usr/tandem/java11/lib/tdmext.jar:/home/proxyuser/proxy.jar \
  -Xms64m -Xmx256m \
  com.nonstop.proxy.PathwayProxyServer
```

Al arrancar deberías ver en stdout:
```
[INFO ] [DdlLoader]            DDL path: /home/proxyuser/ddl
[INFO ] [ApiGatewayClient]     inicializado. baseUrl=https://...
[INFO ] [KeepAliveService]     iniciado. healthUrl=https://.../health, interval=30s
[INFO ] [PathwayProxyServer]   iniciado (JDK 11, TLS 1.2/1.3, DDL-driven).
```

Si el SSL falla:
```
[ERROR] Error fatal en $RECEIVE: PKIX path building failed...
```
→ La CA del API Gateway no está en `truststore.jks`.

Si la DDL no se encuentra (al recibir el primer mensaje):
```
[ERROR] DDL no encontrada: /home/proxyuser/ddl/mi_ddl.json — verificar PROXY_DDL_PATH
```

### Paso 5 — Verificar desde Pathway con STATUS

```tacl
PATHCOM $PROXYM
STATUS SERVERCLASS PROXYSRV
INFO SERVERCLASS PROXYSRV, DETAIL
```

Si el proceso murió al arrancar (error de SSL o de DDL path), el STATUS mostrará 0 servidores `STARTED`.

### Paso 6 — Simulación en WSL2 antes de ir a NonStop

```bash
cd wsl2-sim
export PROXY_API_BASE_URL=https://api.miempresa.com/v1
export PROXY_API_KEY=mi-api-key-dev
export PROXY_DDL_PATH=../ddl                               # usa las DDLs del repo
export PROXY_TRUSTSTORE_PATH=/mnt/c/certs/truststore.jks
export PROXY_TRUSTSTORE_PASSWORD=changeit
./run.sh
```

Ejecuta exactamente el mismo `SslContextFactory.createFromEnv()` y `DdlLoader` que corren en NonStop, usando los mismos `.jks` y `.json`, sin hardware real.

---

## 9. Diagrama de dependencias en runtime

```
PATHMON arranca la JVM con este classpath:
─────────────────────────────────────────────────────────────────────
/usr/tandem/java11/lib/tdmext.jar          ← JToolkit (HPE)
  └── com.tandem.ext.guardian.Receive
  └── com.tandem.ext.guardian.ReceiveInfo
  └── com.tandem.ext.guardian.GuardianException

/home/proxyuser/proxy.jar                  ← JAR compilado
  └── com.nonstop.proxy.PathwayProxyServer      (entry point)
  └── com.nonstop.proxy.handler.ApiGatewayClient
  └── com.nonstop.proxy.handler.KeepAliveService
  └── com.nonstop.proxy.util.SslContextFactory
  └── com.nonstop.proxy.util.MessageSerializer
  └── com.nonstop.proxy.util.DdlLoader          ← NUEVO
  └── com.nonstop.proxy.util.Logger
  └── com.nonstop.proxy.model.IpcRequest
  └── com.nonstop.proxy.model.IpcResponse
  └── com.nonstop.proxy.model.DdlDefinition      ← NUEVO
  └── com.nonstop.proxy.model.DdlField            ← NUEVO

JDK 11 stdlib (incluido en la JVM, no va en -cp):
  └── java.net.http.HttpClient
  └── javax.net.ssl.SSLContext / SSLParameters
  └── java.security.KeyStore
  └── java.util.regex.Pattern / Matcher          ← usado por DdlLoader

Datos en runtime (no van en -cp, se leen por path):
  /home/proxyuser/certs/truststore.jks  ← via PROXY_TRUSTSTORE_PATH
  /home/proxyuser/ddl/*.json            ← via PROXY_DDL_PATH (NUEVO)
─────────────────────────────────────────────────────────────────────
```

`tdmext.jar` y `proxy.jar` son los **únicos dos elementos del `-cp`**. Certificados y DDLs son archivos de datos — se leen en runtime desde paths de variables de entorno.

---

## 10. Debugging en Pathway

### Opción 1 — PROXY_DEBUG=true (siempre disponible)

```tacl
SET SERVERCLASS PROXYSRV ENV "PROXY_DEBUG=true"
```

Habilita `logger.debug(...)` en todo el código. En modo debug se loggean:
- El buffer key=value completo recibido
- El JSON construido para la API (con todos los campos mapeados)
- El JSON de respuesta completo de la API
- El buffer de reply serializado

Ver logs en tiempo real:
```tacl
PATHCOM $PROXYM
INFO SERVERCLASS PROXYSRV, DETAIL
```

Redirigir stdout a archivo OSS:
```tacl
SET SERVERCLASS PROXYSRV PARAM HOMETERM $ZHOME
SET SERVERCLASS PROXYSRV PARAM OUT /home/proxyuser/logs/proxy-debug.log
```

### Opción 2 — Remote debugging via JDWP

Agregar en `JAVA_OPTS` del PATHCFG:

```tacl
SET SERVERCLASS PROXYSRV ENV "JAVA_OPTS=-Xms64m -Xmx256m &
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

`suspend=n` es crítico — con `suspend=y` PATHMON mata el proceso al no recibir respuesta durante el arranque.

Deshabilitar `TIMEOUT` antes de debuggear:
```tacl
PATHCOM $PROXYM
ALTER SERVERCLASS PROXYSRV, TIMEOUT 0
ALTER SERVERCLASS PROXYSRV, MINSERVERS 1, MAXSERVERS 1
STOP SERVERCLASS PROXYSRV
START SERVERCLASS PROXYSRV
```

Conectar desde IntelliJ IDEA: **Run → Edit Configurations → + → Remote JVM Debug**
```
Host:          <IP del NonStop>
Port:          5005
Debugger mode: Attach to remote JVM
```

Restaurar al terminar:
```tacl
ALTER SERVERCLASS PROXYSRV, TIMEOUT 120, MINSERVERS 2, MAXSERVERS 10
```

### Opción 3 — Correr fuera de Pathway (más cómodo para desarrollo)

```bash
export PROXY_API_BASE_URL=https://api.miempresa.com/v1
export PROXY_API_KEY=mi-clave
export PROXY_DDL_PATH=/home/proxyuser/ddl
export PROXY_TRUSTSTORE_PATH=/home/proxyuser/certs/truststore.jks
export PROXY_TRUSTSTORE_PASSWORD=changeit
export PROXY_DEBUG=true

/usr/tandem/java11/bin/java \
  -cp /usr/tandem/java11/lib/tdmext.jar:/home/proxyuser/proxy.jar \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
  com.nonstop.proxy.PathwayProxyServer
```

Aquí sí puede usarse `suspend=y` — no hay PATHMON que mate el proceso.

### Resumen de cuándo usar cada opción

| Situación | Técnica |
|-----------|---------|
| Ver qué pasa en producción sin afectar el servicio | `PROXY_DEBUG=true` + logs a archivo |
| Desarrollar, probar DDLs, verificar SSL | Opción 3 / WSL2 |
| Bug que solo ocurre dentro de Pathway bajo carga | JDWP `suspend=n`, `TIMEOUT 0`, `MAXSERVERS 1` |
| DDL no carga / campos mal mapeados | `PROXY_DEBUG=true` — loggea el JSON construido |
