#!/usr/bin/env bash
# =============================================================================
# run.sh — Start RiskEngineMain with production-grade JVM tuning
#
# Heap:
#   -Xms512m / -Xmx1g   Start at 512 MB, cap at 1 GB (matches ecs-task-def.json
#                        1024 MB task memory; leaving ~100 MB for OS + Metaspace)
#
# GC — G1GC:
#   G1 is the default from JDK 9+; flags below tune it for low-latency
#   risk calculations where consistent pause times matter more than raw
#   throughput.
#
# String deduplication:
#   -XX:+UseStringDeduplication  G1 post-processing pass that detects char[]
#   arrays with identical content and collapses them into one, reducing heap
#   pressure from duplicate underlier/portfolio/country strings across 1 M Trade
#   objects (e.g. "AAPL" stored 500 000 times → stored once).
#   Requires G1GC (enabled below).
#
# Usage:
#   ./run.sh                  # run with defaults
#   HEAP_MAX=2g ./run.sh      # override max heap
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${SCRIPT_DIR}/target/risk-engine-*.jar"
HEAP_MIN="${HEAP_MIN:-512m}"
HEAP_MAX="${HEAP_MAX:-1g}"

# Resolve glob — pick the most recently built jar
JAR_FILE=$(ls -t ${JAR} 2>/dev/null | head -1)
if [[ -z "${JAR_FILE}" ]]; then
    echo "[ERROR] No JAR found at ${JAR}. Run: mvn clean package -DskipTests" >&2
    exit 1
fi

echo "=== Risk Engine Startup ==="
echo "  JAR       : ${JAR_FILE}"
echo "  Heap      : ${HEAP_MIN} – ${HEAP_MAX}"
echo "  GC        : G1GC + StringDeduplication"
echo "  LogFile   : ${SCRIPT_DIR}/logs/risk-engine.log"
echo ""

mkdir -p "${SCRIPT_DIR}/logs"

exec java \
    \
    # ── Heap ──────────────────────────────────────────────────────────────
    -Xms${HEAP_MIN} \
    -Xmx${HEAP_MAX} \
    \
    # ── GC: G1 ────────────────────────────────────────────────────────────
    -XX:+UseG1GC \
    \
    # Max GC pause target in ms (tuned for latency-sensitive risk loop)
    -XX:MaxGCPauseMillis=100 \
    \
    # Trigger G1 concurrent cycle earlier to avoid full GCs
    -XX:InitiatingHeapOccupancyPercent=35 \
    \
    # G1 region size — 4 MB suits mixed small Trade objects + large result sets
    -XX:G1HeapRegionSize=4m \
    \
    # Reserve extra memory to avoid promotion failure at high trade counts
    -XX:G1ReservePercent=15 \
    \
    # ── String deduplication (requires G1GC) ──────────────────────────────
    # Collapses duplicate char[] in heap; very effective for 1 M Trade objects
    # sharing the same underlier / portfolio / country string values.
    -XX:+UseStringDeduplication \
    -XX:StringDeduplicationAgeThreshold=3 \
    \
    # ── Metaspace ─────────────────────────────────────────────────────────
    -XX:MetaspaceSize=64m \
    -XX:MaxMetaspaceSize=128m \
    \
    # ── Container awareness ───────────────────────────────────────────────
    # Respect cgroup CPU/memory limits when running inside Docker / ECS
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    \
    # ── JIT ───────────────────────────────────────────────────────────────
    # Tiered compilation is on by default; reserve extra code cache for
    # hot Black-Scholes math being compiled to native code
    -XX:ReservedCodeCacheSize=64m \
    \
    # ── GC logging (rotated, 5 × 20 MB) ──────────────────────────────────
    -Xlog:gc*:file="${SCRIPT_DIR}/logs/gc.log":time,uptime,level,tags:filecount=5,filesize=20m \
    \
    # ── Crash / heap dumps on OOM ─────────────────────────────────────────
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath="${SCRIPT_DIR}/logs/heapdump.hprof" \
    -XX:+ExitOnOutOfMemoryError \
    \
    # ── Misc ──────────────────────────────────────────────────────────────
    # Faster SecureRandom — avoids blocking on /dev/random
    -Djava.security.egd=file:/dev/./urandom \
    \
    -jar "${JAR_FILE}" \
    "$@"
