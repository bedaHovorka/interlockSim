#!/bin/bash
# Performance benchmark: native fast-sim vs JVM interlockSim
# Usage: benchmark.sh [iterations] [endTime]
#   iterations - number of runs per configuration (default: 10)
#   endTime    - simulation end time in seconds (default: 60)
#
# Prerequisites:
#   ./gradlew :desktop-ui:shadowJar :fast-sim:linkReleaseExecutableLinuxX64

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

ITERATIONS="${1:-10}"
END_TIME="${2:-60}"

JVM_JAR="$PROJECT_ROOT/desktop-ui/build/libs/interlockSim.jar"
NATIVE_BIN="$PROJECT_ROOT/fast-sim/build/bin/linuxX64/releaseExecutable/fast-sim.kexe"

# --- Preflight checks ---

errors=0
if [ ! -f "$JVM_JAR" ]; then
	echo >&2 "ERROR: JVM JAR not found: $JVM_JAR"
	echo >&2 "  Run: ./gradlew :desktop-ui:shadowJar"
	errors=1
fi
if [ ! -x "$NATIVE_BIN" ]; then
	echo >&2 "ERROR: Native binary not found or not executable: $NATIVE_BIN"
	echo >&2 "  Run: ./gradlew :fast-sim:linkReleaseExecutableLinuxX64"
	errors=1
fi
if ! command -v java >/dev/null 2>&1; then
	echo >&2 "ERROR: java not found in PATH"
	errors=1
fi
if ! command -v bc >/dev/null 2>&1; then
	echo >&2 "ERROR: bc not found in PATH"
	errors=1
fi

# We need GNU time for peak RSS measurement
GNU_TIME=""
if command -v /usr/bin/time >/dev/null 2>&1; then
	GNU_TIME="/usr/bin/time"
elif command -v gtime >/dev/null 2>&1; then
	GNU_TIME="gtime"
fi

if [ "$errors" -ne 0 ]; then
	exit 1
fi

# --- Helper functions ---

# Returns nanoseconds since epoch
now_ns() {
	date +%s%N
}

# Compute stats from a file of numbers (one per line)
# Usage: compute_stats <file>
# Outputs: mean median stddev min max
compute_stats() {
	local file="$1"
	sort -n "$file" | awk '
	{
		vals[NR] = $1
		sum += $1
		sumsq += $1 * $1
		n = NR
	}
	END {
		mean = sum / n
		if (n % 2 == 1)
			median = vals[int(n/2) + 1]
		else
			median = (vals[n/2] + vals[n/2 + 1]) / 2
		variance = (sumsq / n) - (mean * mean)
		if (variance < 0) variance = 0
		stddev = sqrt(variance)
		printf "%.3f %.3f %.3f %.3f %.3f\n", mean, median, stddev, vals[1], vals[n]
	}'
}

# Run a single iteration and collect metrics
# Usage: run_once <label> <command...>
# Writes to: $WORK_DIR/{label}_wall.txt, {label}_rss.txt, {label}_events.txt, {label}_first_event.txt
run_once() {
	local label="$1"
	shift
	local tmpout
	tmpout=$(mktemp)
	local tmptiming
	tmptiming=$(mktemp)

	local start_ns
	start_ns=$(now_ns)

	local exit_code=0
	if [ -n "$GNU_TIME" ]; then
		"$GNU_TIME" -v "$@" >"$tmpout" 2>"$tmptiming" || exit_code=$?
	else
		"$@" >"$tmpout" 2>/dev/null || exit_code=$?
	fi

	local end_ns
	end_ns=$(now_ns)

	if [ "$exit_code" -ne 0 ]; then
		echo >&2 "  WARNING: command failed (exit $exit_code); skipping iteration"
		rm -f "$tmpout" "$tmptiming"
		return 1
	fi

	# Wall-clock time in seconds
	local wall_ns=$(( end_ns - start_ns ))
	echo "scale=6; $wall_ns / 1000000000" | bc >> "$WORK_DIR/${label}_wall.txt"

	# Peak RSS in KB (from GNU time output)
	if [ -n "$GNU_TIME" ]; then
		local rss_kb
		rss_kb=$(LC_ALL=C awk '/Maximum resident set size/ {print $NF}' "$tmptiming")
		if [ -n "$rss_kb" ]; then
			echo "$rss_kb" >> "$WORK_DIR/${label}_rss.txt"
		fi
	fi

	# Count event lines (lines starting with "t=")
	local event_count
	event_count=$(LC_ALL=C awk '/^t=/ {n++} END {print n+0}' "$tmpout")
	echo "$event_count" >> "$WORK_DIR/${label}_events.txt"

	# Time to first event: find first "t=" line
	local first_event_line
	first_event_line=$(LC_ALL=C awk '/^t=/ {print; exit}' "$tmpout")
	if [ -n "$first_event_line" ]; then
		# We measure wall time to first event by re-running with a pipe approach
		# Instead, we approximate: for short sims, startup dominates
		# Store the wall time; we'll also do a separate first-event measurement
		echo "scale=6; $wall_ns / 1000000000" | bc >> "$WORK_DIR/${label}_first_event_approx.txt"
	fi

	rm -f "$tmpout" "$tmptiming"
}

# Measure time-to-first-event more precisely by timing until first output line with t=
# Usage: measure_first_event <label> <command...>
measure_first_event() {
	local label="$1"
	shift

	local start_ns
	start_ns=$(now_ns)

	# Run command piped through grep that exits after first match
	"$@" 2>/dev/null | grep -m1 '^t=' >/dev/null || true

	local end_ns
	end_ns=$(now_ns)

	local wall_ns=$(( end_ns - start_ns ))
	echo "scale=6; $wall_ns / 1000000000" | bc >> "$WORK_DIR/${label}_first_event.txt"
}

# Format seconds with appropriate unit
fmt_time() {
	local val="$1"
	# Already in seconds with decimals
	echo "${val}s"
}

# --- Main ---

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

echo >&2 "=== Performance Benchmark: Native vs JVM ==="
echo >&2 "Iterations: $ITERATIONS, endTime: $END_TIME"
echo >&2 "JVM JAR: $JVM_JAR"
echo >&2 "Native: $NATIVE_BIN"
echo >&2 "GNU time: ${GNU_TIME:-not available (RSS measurement disabled)}"
echo >&2 ""

# --- JVM cold runs ---
echo >&2 "Running JVM cold ($ITERATIONS iterations)..."
for i in $(seq 1 "$ITERATIONS"); do
	echo -n >&2 "  [$i/$ITERATIONS] "
	if run_once "jvm" java -jar "$JVM_JAR" example shuntingLoop "$END_TIME"; then
		echo >&2 "done"
	else
		echo >&2 "FAILED (skipped)"
	fi
done

# --- Native runs ---
echo >&2 "Running Native ($ITERATIONS iterations)..."
for i in $(seq 1 "$ITERATIONS"); do
	echo -n >&2 "  [$i/$ITERATIONS] "
	if run_once "native" "$NATIVE_BIN" example shuntingLoop "$END_TIME"; then
		echo >&2 "done"
	else
		echo >&2 "FAILED (skipped)"
	fi
done

# --- Time-to-first-event measurement (separate, lighter runs) ---
FIRST_EVENT_ITERS=$(( ITERATIONS < 5 ? ITERATIONS : 5 ))
echo >&2 "Measuring time-to-first-event ($FIRST_EVENT_ITERS iterations each)..."

for i in $(seq 1 "$FIRST_EVENT_ITERS"); do
	echo -n >&2 "  JVM [$i/$FIRST_EVENT_ITERS] "
	measure_first_event "jvm" java -jar "$JVM_JAR" example shuntingLoop "$END_TIME"
	echo >&2 "done"
done

for i in $(seq 1 "$FIRST_EVENT_ITERS"); do
	echo -n >&2 "  Native [$i/$FIRST_EVENT_ITERS] "
	measure_first_event "native" "$NATIVE_BIN" example shuntingLoop "$END_TIME"
	echo >&2 "done"
done

echo >&2 ""
echo >&2 "=== Computing statistics ==="

# --- Compute stats ---

read -r jvm_wall_mean jvm_wall_median jvm_wall_stddev jvm_wall_min jvm_wall_max \
	<<< "$(compute_stats "$WORK_DIR/jvm_wall.txt")"
read -r nat_wall_mean nat_wall_median nat_wall_stddev nat_wall_min nat_wall_max \
	<<< "$(compute_stats "$WORK_DIR/native_wall.txt")"

jvm_events_median="N/A"
nat_events_median="N/A"
if [ -f "$WORK_DIR/jvm_events.txt" ]; then
	read -r _ jvm_events_median _ _ _ <<< "$(compute_stats "$WORK_DIR/jvm_events.txt")"
fi
if [ -f "$WORK_DIR/native_events.txt" ]; then
	read -r _ nat_events_median _ _ _ <<< "$(compute_stats "$WORK_DIR/native_events.txt")"
fi

# Events per second (using median wall time and median event count)
jvm_eps=$(echo "scale=1; $jvm_events_median / $jvm_wall_median" | bc 2>/dev/null || echo "N/A")
nat_eps=$(echo "scale=1; $nat_events_median / $nat_wall_median" | bc 2>/dev/null || echo "N/A")

# RSS stats
jvm_rss_median="N/A"
nat_rss_median="N/A"
jvm_rss_mb="N/A"
nat_rss_mb="N/A"
rss_ratio="N/A"
if [ -f "$WORK_DIR/jvm_rss.txt" ] && [ -f "$WORK_DIR/native_rss.txt" ]; then
	read -r _ jvm_rss_median _ _ _ <<< "$(compute_stats "$WORK_DIR/jvm_rss.txt")"
	read -r _ nat_rss_median _ _ _ <<< "$(compute_stats "$WORK_DIR/native_rss.txt")"
	jvm_rss_mb=$(echo "scale=1; $jvm_rss_median / 1024" | bc)
	nat_rss_mb=$(echo "scale=1; $nat_rss_median / 1024" | bc)
	rss_ratio=$(echo "scale=1; $jvm_rss_median / $nat_rss_median" | bc)
fi

# First event stats
jvm_fe_median="N/A"
nat_fe_median="N/A"
fe_ratio="N/A"
if [ -f "$WORK_DIR/jvm_first_event.txt" ] && [ -f "$WORK_DIR/native_first_event.txt" ]; then
	read -r _ jvm_fe_median _ _ _ <<< "$(compute_stats "$WORK_DIR/jvm_first_event.txt")"
	read -r _ nat_fe_median _ _ _ <<< "$(compute_stats "$WORK_DIR/native_first_event.txt")"
	fe_ratio=$(echo "scale=1; $jvm_fe_median / $nat_fe_median" | bc 2>/dev/null || echo "N/A")
fi

# Wall time ratio
wall_ratio=$(echo "scale=1; $jvm_wall_median / $nat_wall_median" | bc)

# Events/sec ratio (native/jvm — higher is better for native)
eps_ratio=$(echo "scale=1; $nat_eps / $jvm_eps" | bc 2>/dev/null || echo "N/A")

# --- Output markdown ---

echo "# Fast-Sim Performance Benchmark: Native vs JVM"
echo ""
echo "## Configuration"
echo ""
echo "| Parameter | Value |"
echo "|-----------|-------|"
echo "| Iterations | $ITERATIONS |"
echo "| Simulation | \`example shuntingLoop $END_TIME\` |"
echo "| JVM | $(java -version 2>&1 | head -1) |"
echo "| Native binary | \`fast-sim.kexe\` (Kotlin/Native linuxX64 release) |"
echo "| Kernel | $(uname -r) |"
echo "| CPU | $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | sed 's/^ //') |"
echo "| Date | $(date -Iseconds) |"
echo ""
echo "## Results"
echo ""
echo "| Metric | JVM (cold) | Native | Ratio (JVM/Native) |"
echo "|--------|-----------|--------|---------------------|"
echo "| Wall-clock median | ${jvm_wall_median}s | ${nat_wall_median}s | ${wall_ratio}x |"
echo "| Wall-clock mean | ${jvm_wall_mean}s | ${nat_wall_mean}s | |"
echo "| Wall-clock stddev | ${jvm_wall_stddev}s | ${nat_wall_stddev}s | |"
echo "| Wall-clock min | ${jvm_wall_min}s | ${nat_wall_min}s | |"
echo "| Wall-clock max | ${jvm_wall_max}s | ${nat_wall_max}s | |"
echo "| Peak RSS (median) | ${jvm_rss_mb} MB | ${nat_rss_mb} MB | ${rss_ratio}x |"
echo "| Time to first event (median) | ${jvm_fe_median}s | ${nat_fe_median}s | ${fe_ratio}x |"
echo "| Event count (median) | $jvm_events_median | $nat_events_median | |"
echo "| Events/sec (median wall) | $jvm_eps | $nat_eps | ${eps_ratio}x |"
echo ""
echo "## Methodology"
echo ""
echo "- **Wall-clock time**: Measured with nanosecond-precision \`date +%s%N\`"
echo "- **Peak RSS**: Measured via \`/usr/bin/time -v\` (GNU time) Maximum resident set size"
echo "- **Time to first event**: Wall time from process start until first \`t=\` output line"
echo "- **JVM cold**: Fresh \`java -jar\` invocation each iteration (no JVM warm-up between runs)"
echo "- **Native**: Fresh process invocation each iteration"
echo "- **Events/sec**: Total event count divided by median wall-clock time"
echo ""
echo "## Analysis"
echo ""
echo "### Startup Time"
echo ""
echo "The native binary avoids JVM class loading and JIT compilation overhead,"
echo "resulting in significantly faster startup (time-to-first-event ratio: ${fe_ratio}x)."
echo ""
echo "### Memory Usage"
echo ""
echo "Kotlin/Native produces a standalone binary with no JVM heap overhead."
echo "Peak RSS ratio: ${rss_ratio}x less memory for native."
echo ""
echo "### Simulation Throughput"
echo ""
echo "Both implementations run the same \`:core\` simulation logic (shared KMP code)."
echo "The wall-clock ratio of ${wall_ratio}x reflects the combined effect of startup"
echo "time and runtime performance differences."
