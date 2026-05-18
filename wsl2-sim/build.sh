#!/usr/bin/env bash
# ============================================================
#  build.sh — Compila PathwayProxyServer para simulación WSL2
#  Requiere: JDK 11+  (no requiere tdmext.jar de HPE)
#
#  Estructura de compilación:
#    stubs/         → Stubs de com.tandem.ext.guardian.*
#    ../src-prod/   → Código de producción (usa la raíz del repo)
#    MockApiServer  → Servidor HTTP local simulado
#    SimRunner      → Harness de prueba
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"          # raíz del proyecto
BUILD_DIR="$SCRIPT_DIR/build/classes"

echo "================================================================"
echo "  PathwayProxyServer — Build WSL2 (JDK 11)"
echo "================================================================"
echo "  Raíz proyecto : $ROOT_DIR"
echo "  Build output  : $BUILD_DIR"
echo ""

# ── Verificar JDK ─────────────────────────────────────────────
java -version 2>&1 | head -1
JAVA_MAJOR=$(java -version 2>&1 | grep -oP '(?<=version ")[0-9]+' | head -1)
if [ -z "$JAVA_MAJOR" ]; then
    JAVA_MAJOR=$(java -version 2>&1 | grep -oP '"([0-9]+)\.' | grep -oP '[0-9]+' | head -1)
fi
if [ "$JAVA_MAJOR" -lt 11 ]; then
    echo "ERROR: Se requiere JDK 11 o superior (detectado: $JAVA_MAJOR)"
    exit 1
fi
echo ""

mkdir -p "$BUILD_DIR"

# ── PASO 1: Compilar stubs de JToolkit (com.tandem.ext.guardian.*) ─────
echo "[1/3] Compilando stubs de JToolkit..."
javac -d "$BUILD_DIR" \
    "$SCRIPT_DIR/stubs/GuardianException.java" \
    "$SCRIPT_DIR/stubs/Guardian.java" \
    "$SCRIPT_DIR/stubs/ReceiveInfo.java" \
    "$SCRIPT_DIR/stubs/Receive.java"
echo "      OK"

# ── PASO 2: Compilar código de producción contra los stubs ─────────────
echo "[2/3] Compilando código de producción (JDK 11)..."
javac -cp "$BUILD_DIR" \
      -source 11 -target 11 \
      -d "$BUILD_DIR" \
    "$ROOT_DIR/Logger.java" \
    "$ROOT_DIR/MessageSerializer.java" \
    "$ROOT_DIR/SslContextFactory.java" \
    "$ROOT_DIR/IpcRequest.java" \
    "$ROOT_DIR/IpcResponse.java" \
    "$ROOT_DIR/KeepAliveService.java" \
    "$ROOT_DIR/ApiGatewayClient.java" \
    "$ROOT_DIR/PathwayProxyServer.java"
echo "      OK"

# ── PASO 3: Compilar harness de simulación ─────────────────────────────
echo "[3/3] Compilando harness de simulación..."
javac -cp "$BUILD_DIR" \
      -source 11 -target 11 \
      -d "$BUILD_DIR" \
    "$SCRIPT_DIR/MockApiServer.java" \
    "$SCRIPT_DIR/SimRunner.java"
echo "      OK"

echo ""
echo "================================================================"
echo "  Build completado → $BUILD_DIR"
echo "  Ejecutar con: ./run.sh"
echo "================================================================"
