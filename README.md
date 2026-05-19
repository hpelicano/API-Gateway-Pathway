# PathwayProxyServer — HPE NonStop / NSJ 11

## Descripción

Servidor Pathway escrito en **Java (NSJ 11)** para HPE NonStop que actúa como proxy entre procesos XPNET y un API Gateway externo vía HTTPS.

El servidor es **DDL-driven**: la estructura de cada mensaje (campos, longitudes, tipos y mapeo JSON) está descripta en archivos JSON en disco. El servidor no tiene conocimiento hardcodeado de ningún campo de negocio — simplemente carga la DDL indicada en cada mensaje y la usa para traducir entre el buffer IPC y el JSON de la API.

---

## Arquitectura del flujo

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         HPE NonStop (Guardian OS)                        │
│                                                                           │
│  ┌─────────────┐    IPC / WRITEREAD      ┌──────────────────────────┐   │
│  │             │ ──────────────────────► │   LINKMON ($PROXYLM)     │   │
│  │  Proceso    │                         │   TS/MP Load Balancer    │   │
│  │  XPNET      │  buffer texto plano     └────────────┬─────────────┘   │
│  │  (caller)   │  key=value por línea                 │ distribuye      │
│  │             │                              ┌────────┴──────────┐     │
│  │             │                              │  PATHMON ($PROXYM) │     │
│  │             │                              │  gestiona N        │     │
│  │             │                              │  instancias Java   │     │
│  │             │                              └────────┬──────────┘     │
│  │             │                                       │                 │
│  │             │    ◄── Guardian REPLY ──────          │                 │
│  │             │    buffer longitud fija │              ▼                 │
│  │             │    (campos paddeados)   │   ┌──────────────────────┐    │
│  └─────────────┘                         │   │  PathwayProxyServer   │    │
│                                          │   │  (NSJ 11, JDK 11)    │    │
│                                          │   │                      │    │
│                                          │   │ 1. $RECEIVE (texto)  │    │
│                                          │   │ 2. Parseo key=value  │    │
│                                          │   │ 3. Carga DDL JSON    │    │
│                                          │   │ 4. Arma JSON → API   │    │
│                                          │   │ 5. Mapea respuesta   │    │
│                                          └── │ 6. REPLY fijo COBOL  │    │
│                                              └──────────────────────┘    │
└──────────────────────────────────────────────│──────────────────────────┘
                                               │  HTTPS / TLS 1.2 + TLS 1.3
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
API Gateway Pathway/
│
├── PathwayProxyServer.java      ← Entry point. Loop $RECEIVE + orquestación DDL
├── ApiGatewayClient.java        ← Cliente HTTPS con TLS 1.2/1.3 y retry
├── KeepAliveService.java        ← Ping periódico al /health del API Gateway
├── SslContextFactory.java       ← SSLContext (truststore JKS / mTLS / default)
├── MessageSerializer.java       ← Buffer texto↔JSON usando la DDL cargada
├── DdlLoader.java               ← Carga y cachea DDL JSON desde PROXY_DDL_PATH
├── DdlDefinition.java           ← Modelo: nombre + campos request + campos response
├── DdlField.java                ← Modelo: name, length, type, json_path
├── IpcRequest.java              ← Mensaje parseado: method, endpoint, ddl, jsonPayload
├── IpcResponse.java             ← Contenedor respuesta HTTP: httpStatus, payload JSON
├── Logger.java                  ← Logger stdout compatible con NonStop EMS
│
├── PATHCFG                      ← Script PATHCOM para desplegar en NonStop
├── XPNET_CALLER_EXAMPLE.tal     ← Ejemplo TAL del proceso XPNET caller
│
├── ddl/                         ← Definiciones DDL (una por tipo de mensaje)
│   ├── consulta_saldo.json
│   ├── alta_cliente.json
│   └── transferencia.json
│
└── wsl2-sim/                    ← Simulación completa sin NonStop ni JToolkit
    ├── stubs/                   ← Stubs de com.tandem.ext.guardian.*
    ├── MockApiServer.java       ← Servidor HTTP local que simula el API Gateway
    ├── SimRunner.java           ← Harness de prueba con 5 casos de test
    ├── build.sh
    └── run.sh
```

---

## Formato del buffer IPC (texto plano key=value)

El proceso XPNET envía un buffer de **texto plano** via `WRITEREAD`, con un par `clave=valor` por línea.

### Request (XPNET → Proxy Server)

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

| Campo | Obligatorio | Descripción |
|-------|-------------|-------------|
| `metodo` | Sí | Método HTTP: `GET`, `POST`, `PUT`, `DELETE` |
| `endpoint` | Sí | Path del API Gateway (ej: `/api/v1/clientes`) |
| `ddl_json` | Sí | Nombre del archivo DDL a cargar (sin `.json`) |
| `correlation_id` | No | ID de correlación end-to-end (se genera si falta) |
| *resto* | — | Campos de negocio mapeados al JSON según la DDL |

### Response (Proxy Server → XPNET) — longitud fija definida por la DDL

La respuesta es un buffer de **longitud fija**, con los campos de la sección `response` de la DDL concatenados y paddeados:

```
[cod_ret  4][id_cliente  10][mensaje                                         80]
0000000000099Alta exitosa                                                       
```

| Tipo DDL | Alineación | Padding |
|----------|-----------|---------|
| `"string"` | Izquierda | Espacios a la derecha |
| `"numeric"` | Derecha | Ceros a la izquierda |

---

## DDL JSON — Definición de mensajes

Cada tipo de mensaje tiene un archivo `.json` en `PROXY_DDL_PATH`. La DDL describe qué campos existen, su longitud (como en un `PIC X(n)` COBOL) y cómo mapearlos al JSON de la API.

### Estructura del archivo DDL

```json
{
  "ddl_name":    "alta_cliente",
  "description": "Alta de nuevo cliente persona física",

  "request": [
    { "field": "nombre",   "length": 40, "type": "string",  "json_path": "nombre"            },
    { "field": "apellido", "length": 40, "type": "string",  "json_path": "apellido"          },
    { "field": "dni",      "length": 11, "type": "string",  "json_path": "documento.numero"  },
    { "field": "email",    "length": 80, "type": "string",  "json_path": "email"             },
    { "field": "telefono", "length": 20, "type": "string",  "json_path": "contacto.telefono" }
  ],

  "response": [
    { "field": "cod_ret",    "length":  4, "type": "numeric", "json_path": "codigo"          },
    { "field": "id_cliente", "length": 10, "type": "numeric", "json_path": "data.id_cliente" },
    { "field": "mensaje",    "length": 80, "type": "string",  "json_path": "mensaje"         }
  ]
}
```

### Campos de la DDL

| Atributo | Descripción |
|----------|-------------|
| `field` | Nombre del campo en el buffer key=value del XPNET |
| `length` | Longitud fija en bytes (equivale a `PIC X(n)` o `PIC 9(n)`) |
| `type` | `"string"` o `"numeric"` (controla el padding del buffer de respuesta) |
| `json_path` | Path dot-notation en el JSON de la API. Soporta anidamiento: `"documento.numero"` → `{"documento":{"numero":"..."}}` |

### Ejemplo de mapeo completo

**Buffer XPNET de entrada:**
```
metodo=POST
endpoint=/api/v1/clientes
ddl_json=alta_cliente
nombre=Juan
apellido=Pérez
dni=20345678901
```

**JSON construido para la API (usando sección `request`):**
```json
{
  "nombre": "Juan",
  "apellido": "Pérez",
  "documento": { "numero": "20345678901" }
}
```

**JSON de respuesta de la API:**
```json
{ "codigo": 0, "mensaje": "Alta exitosa", "data": { "id_cliente": 99 } }
```

**Buffer de respuesta al XPNET (usando sección `response`, longitud fija):**
```
0000000000099Alta exitosa                                                       
│──────────────────────────────────────────────────────────────────────────────│
 cod_ret(4) + id_cliente(10) + mensaje(80) = 94 bytes totales
```

---

## Códigos de error del proxy

| Código | Condición |
|--------|-----------|
| `E000` | Mensaje IPC vacío |
| `E001` | Falta `metodo`, `endpoint` o `ddl_json` en el buffer |
| `E002` | Archivo DDL no encontrado en `PROXY_DDL_PATH` |
| `E003` | Timeout o error de red llamando a la API |
| `E4xx` | La API respondió HTTP 4xx (error del caller, no se reintenta) |
| `E5xx` | La API respondió HTTP 5xx (error transitorio, se reintenta hasta 3 veces) |

---

## Variables de entorno (configurar en PATHCOM)

```
PROXY_API_BASE_URL         URL base del API Gateway externo        (obligatoria)
PROXY_API_KEY              API Key de autenticación                 (obligatoria)
PROXY_DDL_PATH             Directorio con archivos DDL JSON         (obligatoria)
                             NonStop: /home/proxyuser/ddl
                             WSL2 sim: ../ddl

PROXY_CONNECT_TIMEOUT_MS   Timeout de conexión TCP en ms           (default: 5000)
PROXY_REQUEST_TIMEOUT_MS   Timeout de request HTTP en ms           (default: 30000)
PROXY_DEBUG                true/false para logging verbose          (default: false)

PROXY_HEALTH_PATH          Path del endpoint de health check        (default: /health)
PROXY_KEEPALIVE_INTERVAL_S Intervalo de ping keep-alive en segundos (default: 30)
PROXY_KEEPALIVE_TIMEOUT_MS Timeout del ping keep-alive en ms        (default: 5000)
PROXY_KEEPALIVE_MAX_FAILS  Fallos consecutivos antes de log ERROR   (default: 3)

PROXY_TRUSTSTORE_PATH      Ruta al JKS con CA corporativa           (opcional)
PROXY_TRUSTSTORE_PASSWORD  Contraseña del truststore                (opcional)
PROXY_KEYSTORE_PATH        Ruta al JKS con cert de cliente (mTLS)   (opcional)
PROXY_KEYSTORE_PASSWORD    Contraseña del keystore de cliente        (opcional)

JREHOME                    Path de instalación de NSJ 11            (obligatoria)
JAVA_OPTS                  Flags adicionales de la JVM              (default: -Xms64m -Xmx256m -XX:+UseG1GC)
```

---

## Dependencias

| Dependencia | Versión | Fuente |
|-------------|---------|--------|
| NSJ 11 | OpenJDK 11.x portado NonStop | HPE Support Center |
| JToolkit | 5.x | HPE (`tdmext.jar`) — requiere licencia |
| java.net.http | JDK 11 built-in | Incluido en el JDK estándar |

> Sin dependencias externas de terceros. El parser JSON de la DDL está implementado con `java.util.regex` dentro de `DdlLoader`. No se usa Jackson, Gson ni ninguna otra librería.

---

## Compilación

```bash
# En NonStop OSS (o cross-compilado con NSJ 11 JDK)
javac -cp /usr/tandem/java11/lib/tdmext.jar \
      -source 11 -target 11 \
      -d build/classes \
      *.java

jar -cf proxy.jar -C build/classes .

# Copiar al volume del NonStop
FUP COPY proxy.jar, $DATA.PROXYAPP.PROXYSRV
```

---

## Simulación en WSL2

Permite desarrollar y probar sin hardware NonStop ni licencia JToolkit:

```bash
cd wsl2-sim
chmod +x build.sh run.sh

./build.sh     # compila stubs Guardian + producción + harness en 3 pasos
./run.sh       # ejecuta 5 tests de integración con MockApiServer local
```

La simulación incluye casos de prueba para:
- `GET` consulta de saldo (DDL `consulta_saldo`)
- `POST` alta de cliente con `json_path` anidado (DDL `alta_cliente`)
- `POST` transferencia con paths multi-nivel (DDL `transferencia`)
- Error `E002` por DDL inexistente
- Error `E001` por buffer mal formado

Para probar contra el API Gateway real (con SSL) desde WSL2:

```bash
export PROXY_API_BASE_URL=https://api.miempresa.com/v1
export PROXY_TRUSTSTORE_PATH=/mnt/c/certs/truststore.jks
export PROXY_TRUSTSTORE_PASSWORD=changeit
./run.sh
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

1. **DDL es obligatoria**: Cada mensaje debe indicar `ddl_json=<nombre>`. Si el archivo no existe en `PROXY_DDL_PATH`, el servidor responde con código de error `E002`. Agregar un nuevo tipo de mensaje solo requiere crear un `.json` en el directorio de DDLs — sin cambios de código.

2. **Cache de DDLs**: Las DDLs se cargan la primera vez que se recibe un mensaje que las referencia y quedan en memoria por el resto del ciclo de vida del proceso. Para recargar una DDL modificada: `STOP SERVERCLASS PROXYSRV` + `START SERVERCLASS PROXYSRV` en PATHCOM.

3. **JToolkit es obligatorio en producción**: Las clases `com.tandem.ext.guardian.*` solo están en `tdmext.jar`, distribuido por HPE NonStop Support. Para desarrollo, usar los stubs de `wsl2-sim/stubs/`.

4. **NSJ 11 es headless**: No soporta AWT/Swing. La JVM no tiene acceso a ninguna clase gráfica.

5. **Thread model**: Cada proceso Pathway Server corre un único thread en el loop `$RECEIVE`. El keep-alive corre en un daemon thread separado. PATHMON escala levantando más instancias (`MAXSERVERS`). No usar multithreading en el loop IPC.

6. **TLS 1.2 y 1.3 únicamente**: `SslContextFactory` rechaza TLS 1.0 y TLS 1.1 explícitamente. Los API Gateways corporativos modernos requieren mínimo TLS 1.2.

7. **XPNET y Pathsend**: El proceso XPNET puede usar `WRITEREAD` (TAL/COBOL) o `SERVERCLASS_SEND_` (C API) para enviar mensajes al Pathway server. Ambos enfoques son compatibles.
