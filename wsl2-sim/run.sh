#!/usr/bin/env bash
# ============================================================
#  run.sh — Ejecuta la simulación del PathwayProxyServer en WSL2
#
#  Uso:
#    ./run.sh              # ejecutar con defaults
#    ./run.sh --debug      # activar PROXY_DEBUG=true
#    ./run.sh --port 9090  # cambiar puerto del MockApiServer
#
#  Si el build no existe, lo genera automáticamente.
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build/classes"

# ── Parseo de argumentos ────────────────────────────────────
API_PORT=18080
DEBUG="false"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug)  DEBUG="true";        shift ;;
        --port)   API_PORT="$2";       shift 2 ;;
        *) echo "Opción desconocida: $1"; exit 1 ;;
    esac
done

# ── Build automático si falta ────────────────────────────────
if [ ! -d "$BUILD_DIR" ]; then
    echo "[run.sh] Build no encontrado. Compilando..."
    "$SCRIPT_DIR/build.sh"
fi

# ── Variables de entorno del Proxy ──────────────────────────
#    En producción NonStop se configuran en PATHCOM.
#    Aquí las exportamos antes de invocar java.
export PROXY_API_BASE_URL="http://localhost:${API_PORT}"
export PROXY_API_KEY="sim-api-key-12345"
export PROXY_CONNECT_TIMEOUT_MS="3000"
export PROXY_REQUEST_TIMEOUT_MS="10000"
export PROXY_HEALTH_PATH="/health"
export PROXY_KEEPALIVE_INTERVAL_S="15"
export PROXY_KEEPALIVE_TIMEOUT_MS="3000"
export PROXY_KEEPALIVE_MAX_FAILS="3"
export PROXY_DEBUG="${DEBUG}"

# Variables SSL opcionales (descomentar para pruebas con TLS real):
# export PROXY_TRUSTSTORE_PATH="/path/to/truststore.jks"
# export PROXY_TRUSTSTORE_PASSWORD="changeit"
# export PROXY_KEYSTORE_PATH="/path/to/keystore.jks"
# export PROXY_KEYSTORE_PASSWORD="changeit"

echo "================================================================"
echo "  PathwayProxyServer — Simulación WSL2"
echo "  MockApiServer: http://localhost:${API_PORT}"
echo "  PROXY_DEBUG:   ${DEBUG}"
echo "================================================================"
echo ""

java \
    -cp "$BUILD_DIR" \
    -Djava.util.logging.SimpleFormatter.format='' \
    SimRunner
