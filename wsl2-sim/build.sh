#!/usr/bin/env bash
# ============================================================
#  build.sh — Compila PathwayProxyServer para simulación WSL2
#  Requiere: JDK 11+  (no requiere tdmext.jar de HPE)
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$SCRIPT_DIR/build/classes"

echo "================================================================"
echo "  PathwayProxyServer — Build WSL2 (JDK 11, DDL-driven)"
echo "================================================================"
echo "  Raíz proyecto : $ROOT_DIR"
echo "  Build output  : $BUILD_DIR"
echo ""

# ── Verificar JDK ─────────────────────────────────────────────
java -version 2>&1 | head -1
JAVA_MAJOR=$(java -version 2>&1 | grep -oP '(?<=version ")[0-9]+' | head -1)
if [ -z "$JAVA_MAJOR" ]; then
    JAVA_MAJOR=$(java -version 2>&1 | grep -oP '"[0-9]+' | grep -oP '[0-9]+' | head -1)
fi
if [ "${JAVA_MAJOR:-0}" -lt 11 ]; then
    echo "ERROR: Se requiere JDK 11 o superior (detectado: ${JAVA_MAJOR:-desconocido})"
    exit 1
fi
echo ""

mkdir -p "$BUILD_DIR"

# ── PASO 1: Stubs de JToolkit ──────────────────────────────────────────────
echo "[1/3] Compilando stubs de JToolkit (com.tandem.ext.guardian.*)..."
javac -d "$BUILD_DIR" \
    "$SCRIPT_DIR/stubs/GuardianException.java" \
    "$SCRIPT_DIR/stubs/Guardian.java" \
    "$SCRIPT_DIR/stubs/ReceiveInfo.java" \
    "$SCRIPT_DIR/stubs/Receive.java"
echo "      OK"

# ── PASO 2: Código de producción (modelos + utilidades + server) ───────────
echo "[2/3] Compilando código de producción (JDK 11)..."
javac -cp "$BUILD_DIR" \
      -source 11 -target 11 \
      -d "$BUILD_DIR" \
    "$ROOT_DIR/Logger.java" \
    "$ROOT_DIR/DdlField.java" \
    "$ROOT_DIR/DdlDefinition.java" \
    "$ROOT_DIR/DdlLoader.java" \
    "$ROOT_DIR/SslContextFactory.java" \
    "$ROOT_DIR/IpcRequest.java" \
    "$ROOT_DIR/IpcResponse.java" \
    "$ROOT_DIR/MessageSerializer.java" \
    "$ROOT_DIR/KeepAliveService.java" \
    "$ROOT_DIR/ApiGatewayClient.java" \
    "$ROOT_DIR/PathwayProxyServer.java"
echo "      OK"

# ── PASO 3: Harness de simulación ─────────────────────────────────────────
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
