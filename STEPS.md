1. Arquitectura de ejecución en HPE NonStop
Cómo levanta PATHMON un proceso Java
En NonStop, un proceso Java no arranca como en Linux con java -jar. Lo hace PATHMON, que es el supervisor del entorno Pathway. La secuencia real es:


TACL> PATHCOM $PROXYM
PATHCOM> OBEY $DATA.PROXYAPP.PATHCFG   ← lee tu PATHCFG
Al ejecutar START SERVERCLASS PROXYSRV, PATHMON hace internamente:


/usr/tandem/java11/bin/java \
  -cp /usr/tandem/java11/lib/tdmext.jar:/home/proxyuser/proxy.jar \
  -Xms64m -Xmx256m -XX:+UseG1GC \
  com.nonstop.proxy.PathwayProxyServer
Ese proceso Java es el SERVERCLASS. PATHMON arranca MINSERVERS=2 instancias en frío esperando en $RECEIVE, y escala hasta MAXSERVERS=10 bajo carga.

2. Flujo completo mensaje a mensaje

XPNET Process (TAL/COBOL/C)
        │
        │  WRITEREAD $PROXYLM, buffer_request, buffer_reply
        ▼
  LINKMON $PROXYLM          ← balancea entre instancias PROXYSRV disponibles
        │
        │  distribuye la conexión a un PROXYSRV libre
        ▼
  PathwayProxyServer.java
  └── run() línea 65: Receive.getInstance().open()
             │
             │  receive.read(rawBuffer, 4096, info)   ← BLOQUEANTE
             │  [línea 96 PathwayProxyServer.java]
             │
             ▼
  MessageSerializer.deserialize(rawBuffer, bytesRead)
  [líneas 58-93 MessageSerializer.java]
  └── Lee big-endian: version(2) + msgType(2) + correlationId(16)
                    + operation(32) + payloadLen(2) + payload(var)
  └── Construye IpcRequest con el payload JSON que vino del XPNET
             │
             ▼
  ApiGatewayClient.call(request)
  [línea 100 ApiGatewayClient.java]
  └── buildUrl()  → baseUrl + request.getOperation()
  └── buildHttpRequest() → HTTP GET/POST/PUT/DELETE con headers:
        Content-Type: application/json
        X-API-Key: <PROXY_API_KEY>
        X-Correlation-Id: <correlationId del buffer IPC>
        X-Source-System: NONSTOP-XPNET
  └── httpClient.send() → HTTPS con TLS 1.2/1.3
             │
             ▼
  IpcResponse.success(correlationId, httpStatus, body)
  [línea 132 ApiGatewayClient.java]
             │
             ▼
  MessageSerializer.serialize(apiResponse)
  [líneas 106-126 MessageSerializer.java]
  └── Escribe big-endian: version(2) + httpStatus(2) + errorCode(2)
                        + correlationId(16) + payloadLen(2) + payload(var)
             │
             ▼
  receive.reply(replyBuffer, len, 0)
  [línea 121 PathwayProxyServer.java]
        │
        ▼
  XPNET Process recibe buffer_reply ← WRITEREAD retorna
3. Cadena de inicialización SSL/TLS
Cuando PathwayProxyServer arranca, en su constructor llama new ApiGatewayClient(). Internamente esto ocurre en orden:


ApiGatewayClient constructor
  [línea 62-63 ApiGatewayClient.java]
        │
        ├── SslContextFactory.createFromEnv()
        │     [línea 128 SslContextFactory.java]
        │     Lee 4 variables de entorno:
        │       PROXY_KEYSTORE_PATH      → ¿existe? → mTLS
        │       PROXY_TRUSTSTORE_PATH    → ¿existe? → CA privada
        │       ninguna                  → truststore por defecto de la JVM
        │
        └── SslContextFactory.tlsParameters()
              [línea 118 SslContextFactory.java]
              SSLParameters.setProtocols(["TLSv1.2", "TLSv1.3"])
              ← rechaza TLS 1.0 y 1.1 explícitamente

HttpClient.newBuilder()
  .sslContext(sslContext)     ← quién confía en quién
  .sslParameters(sslParams)   ← qué versiones de protocolo se permiten
  .build()
Hay tres modos que el código selecciona automáticamente:

Caso	Variables definidas	Método usado
API pública (Let's Encrypt, DigiCert)	ninguna	createDefault() — usa cacerts de la JVM
API corporativa con CA privada	PROXY_TRUSTSTORE_PATH	createWithTrustStore()
API que exige cert de cliente (mTLS)	PROXY_KEYSTORE_PATH + PROXY_TRUSTSTORE_PATH	createWithMutualTls()
4. Dónde colocar los certificados en NonStop OSS
Estructura de paths recomendada

/home/proxyuser/
├── proxy.jar                         ← JAR compilado
└── certs/
    ├── truststore.jks                ← CA corporativa (servidor le presenta cert, aquí validamos)
    ├── keystore.jks                  ← cert de cliente (para mTLS — opcional)
    └── README.txt                    ← documentar passwords usadas
El path en NonStop OSS es POSIX estándar. Verificar que el usuario del proceso Pathway tiene permisos de lectura:


# En OSS shell del NonStop:
ls -la /home/proxyuser/certs/
chmod 640 /home/proxyuser/certs/*.jks
chown proxyuser:users /home/proxyuser/certs/*.jks
Cómo crear el truststore JKS a partir del certificado de la CA
Si el API Gateway te entregó el certificado de su CA en formato PEM (ca-cert.pem):


# En OSS shell (NSJ keytool incluido en /usr/tandem/java11/bin/)
/usr/tandem/java11/bin/keytool \
  -importcert \
  -alias api-gateway-ca \
  -file /home/proxyuser/certs/ca-cert.pem \
  -keystore /home/proxyuser/certs/truststore.jks \
  -storepass changeit \
  -noprompt
Si hay una cadena de CAs intermedias (lo más común en empresas):


# Primero la raíz, luego cada intermediaria
/usr/tandem/java11/bin/keytool -importcert -alias root-ca    -file root.pem    -keystore truststore.jks -storepass changeit -noprompt
/usr/tandem/java11/bin/keytool -importcert -alias inter-ca-1 -file inter1.pem  -keystore truststore.jks -storepass changeit -noprompt
/usr/tandem/java11/bin/keytool -importcert -alias api-gw-ca  -file api-gw.pem  -keystore truststore.jks -storepass changeit -noprompt
Para mTLS (cert de cliente):


# El API Gateway te entrega un .p12 o .pfx con tu certificado de cliente
/usr/tandem/java11/bin/keytool \
  -importkeystore \
  -srckeystore cliente.p12 -srcstoretype PKCS12 -srcstorepass <pass_del_p12> \
  -destkeystore /home/proxyuser/certs/keystore.jks -deststorepass changeit \
  -noprompt
5. Vincular los certificados al proceso: PATHCFG actualizado
Actualiza PATHCFG para incluir las rutas de los certificados. Las variables son leídas por SslContextFactory.createFromEnv() en la línea 128 de SslContextFactory.java:


!-- JVM Home: NSJ 11 --
SET SERVERCLASS PROXYSRV ENV "JREHOME=/usr/tandem/java11"

!-- Classpath: tdmext.jar primero, luego proxy.jar --
SET SERVERCLASS PROXYSRV ARGLIST &
    "-cp /usr/tandem/java11/lib/tdmext.jar:/home/proxyuser/proxy.jar &
     com.nonstop.proxy.PathwayProxyServer"

!-- API Gateway --
SET SERVERCLASS PROXYSRV ENV "PROXY_API_BASE_URL=https://api.miempresa.com/v1"
SET SERVERCLASS PROXYSRV ENV "PROXY_API_KEY=mi-api-key-produccion"

!-- Timeouts --
SET SERVERCLASS PROXYSRV ENV "PROXY_CONNECT_TIMEOUT_MS=5000"
SET SERVERCLASS PROXYSRV ENV "PROXY_REQUEST_TIMEOUT_MS=30000"

!-- SSL/TLS: CA corporativa (descomentar según el caso) --
!-- Caso 1: CA privada solamente --
SET SERVERCLASS PROXYSRV ENV "PROXY_TRUSTSTORE_PATH=/home/proxyuser/certs/truststore.jks"
SET SERVERCLASS PROXYSRV ENV "PROXY_TRUSTSTORE_PASSWORD=changeit"

!-- Caso 2: mTLS (certificado de cliente + CA) --
!SET SERVERCLASS PROXYSRV ENV "PROXY_KEYSTORE_PATH=/home/proxyuser/certs/keystore.jks"
!SET SERVERCLASS PROXYSRV ENV "PROXY_KEYSTORE_PASSWORD=changeit"

!-- Keep-alive --
SET SERVERCLASS PROXYSRV ENV "PROXY_HEALTH_PATH=/health"
SET SERVERCLASS PROXYSRV ENV "PROXY_KEEPALIVE_INTERVAL_S=30"

!-- JVM flags --
SET SERVERCLASS PROXYSRV ENV &
    "JAVA_OPTS=-Xms64m -Xmx256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
El classpath -cp tdmext.jar:proxy.jar es el mecanismo de dependencias. No hay Maven. Todo lo que el JAR necesita en runtime debe estar listado ahí separado por :.

6. Cómo probar la configuración de ambiente
Paso 1 — Verificar que keytool lee el truststore correctamente

# En OSS shell del NonStop:
/usr/tandem/java11/bin/keytool \
  -list -v \
  -keystore /home/proxyuser/certs/truststore.jks \
  -storepass changeit

# Debe mostrar el alias "api-gateway-ca" con su fecha de expiración y fingerprint
Paso 2 — Probar TLS desde OSS antes de deployar el JAR

# openssl disponible en NSJ OSS toolkit
openssl s_client \
  -connect api.miempresa.com:443 \
  -CAfile /home/proxyuser/certs/ca-cert.pem \
  -tls1_2

# Si responde con "Verify return code: 0 (ok)" → el cert es válido
# Si responde con "self signed certificate" → falta la CA en el truststore
Paso 3 — Probar el JAR directamente en OSS (fuera de Pathway)
Esto permite ver los logs en consola sin la capa de PATHMON:


# Exportar las mismas variables que PATHCFG pondría
export PROXY_API_BASE_URL=https://api.miempresa.com/v1
export PROXY_API_KEY=mi-api-key-produccion
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
Si el SSL falla verás en consola (Logger va a stdout):


[ERROR] Error fatal al operar $RECEIVE: ...
o durante el keep-alive:


[WARN ] Keep-alive falló (1/3). Causa: PKIX path building failed: ...
Ese error PKIX path building failed significa que la CA del API Gateway no está en tu truststore.jks — hay que agregar el certificado correcto.

Paso 4 — Verificar desde Pathway con STATUS

PATHCOM $PROXYM
STATUS SERVERCLASS PROXYSRV
Si el proceso murió al arrancar (error de SSL en el constructor de ApiGatewayClient), el STATUS mostrará que no hay servidores STARTED. Los logs de stdout de cada instancia quedan en el spool de PATHMON — se ven con:


INFO SERVERCLASS PROXYSRV, DETAIL
Paso 5 — Simulación en WSL2 antes de ir a NonStop

# En WSL2, probar con CA real contra la API real
cd wsl2-sim
export PROXY_API_BASE_URL=https://api.miempresa.com/v1
export PROXY_API_KEY=mi-api-key-dev
export PROXY_TRUSTSTORE_PATH=/mnt/c/certs/truststore.jks
export PROXY_TRUSTSTORE_PASSWORD=changeit
./run.sh
Esto ejecuta exactamente el mismo SslContextFactory.createFromEnv() que corre en NonStop, usando los mismos .jks, sin necesitar el hardware real.

7. Diagrama de dependencias en runtime

PATHMON arranca la JVM con este classpath:
─────────────────────────────────────────────────────────────
/usr/tandem/java11/lib/tdmext.jar          ← JToolkit (HPE)
  └── com.tandem.ext.guardian.Receive       [PathwayProxyServer.java:5]
  └── com.tandem.ext.guardian.ReceiveInfo   [PathwayProxyServer.java:6]
  └── com.tandem.ext.guardian.GuardianException [PathwayProxyServer.java:4]

/home/proxyuser/proxy.jar                  ← tu JAR compilado
  └── com.nonstop.proxy.PathwayProxyServer  (entry point)
  └── com.nonstop.proxy.handler.ApiGatewayClient
  └── com.nonstop.proxy.handler.KeepAliveService
  └── com.nonstop.proxy.util.SslContextFactory
  └── com.nonstop.proxy.util.MessageSerializer
  └── com.nonstop.proxy.util.Logger
  └── com.nonstop.proxy.model.IpcRequest
  └── com.nonstop.proxy.model.IpcResponse

JDK 11 stdlib (ya incluido en la JVM, no necesita estar en -cp):
  └── java.net.http.HttpClient              [ApiGatewayClient.java:14]
  └── javax.net.ssl.SSLContext              [SslContextFactory.java:4]
  └── javax.net.ssl.SSLParameters           [SslContextFactory.java:5]
  └── java.security.KeyStore                [SslContextFactory.java:9]

/home/proxyuser/certs/truststore.jks       ← leído en runtime por SslContextFactory
  └── NO va en -cp; se referencia via PROXY_TRUSTSTORE_PATH
─────────────────────────────────────────────────────────────
El punto clave: tdmext.jar y proxy.jar son los únicos dos elementos del -cp. Los certificados .jks son archivos de datos, no código — se leen en runtime desde el path de la variable de entorno, no del classpath.

===========================================================================================

Opción 1 — PROXY_DEBUG=true (la más simple, siempre disponible)
Ya está implementado en Logger.java. Activarlo en PATHCFG no requiere reiniciar con flags especiales:


SET SERVERCLASS PROXYSRV ENV "PROXY_DEBUG=true"
Esto habilita el nivel DEBUG en todos los logger.debug(...) del código. El output va a stdout del proceso, que PATHMON captura. Para verlo en tiempo real desde TACL:


PATHCOM $PROXYM
INFO SERVERCLASS PROXYSRV, DETAIL
O redirigir stdout del proceso a un archivo OSS para no perder líneas:


SET SERVERCLASS PROXYSRV PARAM HOMETERM $ZHOME
SET SERVERCLASS PROXYSRV PARAM OUT /home/proxyuser/logs/proxy-debug.log
Opción 2 — Remote debugging via JDWP (debugger desde tu PC)
JDWP es el protocolo estándar de Java para conectar IntelliJ/Eclipse a una JVM remota. La JVM abre un puerto TCP y el IDE se conecta a él.

Paso 1 — Agregar el agente JDWP en PATHCFG

SET SERVERCLASS PROXYSRV ENV "JAVA_OPTS=-Xms64m -Xmx256m &
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
El parámetro crítico es suspend=n (no suspend=y). Explicación:

Valor	Comportamiento	Problema en Pathway
suspend=y	La JVM espera que el debugger se conecte antes de ejecutar main()	PATHMON detecta que el server no responde y lo mata con TIMEOUT
suspend=n	La JVM arranca normalmente; el debugger se puede conectar en cualquier momento	Correcto para Pathway
Paso 2 — Deshabilitar TIMEOUT de PATHMON mientras debuggeas
Con TIMEOUT=120 PATHMON mata servidores ociosos, interrumpiendo tu sesión de debug si el proceso está en un breakpoint más de 2 minutos:


PATHCOM $PROXYM
ALTER SERVERCLASS PROXYSRV, TIMEOUT 0   ! 0 = sin timeout
START SERVERCLASS PROXYSRV
Al terminar el debug, restablecer:


ALTER SERVERCLASS PROXYSRV, TIMEOUT 120
Paso 3 — Conectar desde IntelliJ IDEA
Run → Edit Configurations → + → Remote JVM Debug


Host:             <IP del NonStop en tu red>
Port:             5005
Debugger mode:    Attach to remote JVM
Con el proyecto abierto y los fuentes del JAR disponibles localmente, los breakpoints en PathwayProxyServer.java, ApiGatewayClient.java etc. funcionan normalmente.

Paso 4 — Problema con MINSERVERS > 1
Con MINSERVERS=2 hay dos instancias escuchando en $RECEIVE. El debugger se conecta al proceso que abrió el puerto 5005, pero PATHMON puede enviar tu mensaje a la otra instancia.

Solución para debugging:


ALTER SERVERCLASS PROXYSRV, MINSERVERS 1, MAXSERVERS 1
STOP SERVERCLASS PROXYSRV
START SERVERCLASS PROXYSRV
Así garantizas que hay una sola instancia y el debugger la intercepta siempre.

Opción 3 — Correr fuera de Pathway (la más cómoda para desarrollo)
Ejecutar el JAR directamente desde el shell OSS, sin PATHMON. La diferencia: en lugar de JToolkit real, usas los stubs de wsl2-sim/ o simplemente pruebas la parte HTTP.


# En OSS shell del NonStop, con las mismas variables que PATHCFG definiría:
export PROXY_API_BASE_URL=https://api.miempresa.com/v1
export PROXY_API_KEY=mi-clave
export PROXY_TRUSTSTORE_PATH=/home/proxyuser/certs/truststore.jks
export PROXY_TRUSTSTORE_PASSWORD=changeit
export PROXY_DEBUG=true

/usr/tandem/java11/bin/java \
  -cp /usr/tandem/java11/lib/tdmext.jar:/home/proxyuser/proxy.jar \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
  com.nonstop.proxy.PathwayProxyServer
Aquí sí puedes usar suspend=y porque no hay PATHMON que te mate el proceso. El servidor espera que el IDE se conecte antes de ejecutar main().

Resumen de cuándo usar cada opción
Situación	Técnica
Producción / ver qué pasa en vivo	PROXY_DEBUG=true + logs a archivo
Desarrollar lógica de negocio, probar SSL, timeouts	Opción 3 (fuera de Pathway, WSL2 o OSS directo)
Bug que solo ocurre dentro de Pathway (bajo carga, con LINKMON)	JDWP con suspend=n, TIMEOUT 0, MAXSERVERS 1

