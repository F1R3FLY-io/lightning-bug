#!/bin/bash
# Benchmark environment setup script for Lightning Bug
#
# This script prepares the system for consistent benchmark measurements by:
# 1. Setting CPU governor to performance mode
# 2. Optionally disabling turbo boost for consistency
# 3. Reporting CPU frequencies
# 4. Providing commands to run benchmarks with CPU affinity
#
# Usage:
#   ./scripts/benchmark-setup.sh [--prepare|--restore|--status|--run <command>]
#
# Options:
#   --prepare   Configure system for benchmarking
#   --restore   Restore default system settings
#   --status    Show current system status
#   --run       Run command with CPU affinity on core 0

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BENCHMARK_CPU=0
TURBO_BOOST_PATH="/sys/devices/system/cpu/intel_pstate/no_turbo"
TURBO_BOOST_AMD_PATH="/sys/devices/system/cpu/cpufreq/boost"

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_warn "Some operations require root privileges. Run with sudo for full functionality."
        return 1
    fi
    return 0
}

get_cpu_governor() {
    if [[ -f /sys/devices/system/cpu/cpu${BENCHMARK_CPU}/cpufreq/scaling_governor ]]; then
        cat /sys/devices/system/cpu/cpu${BENCHMARK_CPU}/cpufreq/scaling_governor
    else
        echo "unknown"
    fi
}

get_cpu_frequency() {
    if [[ -f /sys/devices/system/cpu/cpu${BENCHMARK_CPU}/cpufreq/scaling_cur_freq ]]; then
        local freq=$(cat /sys/devices/system/cpu/cpu${BENCHMARK_CPU}/cpufreq/scaling_cur_freq)
        echo "$((freq / 1000)) MHz"
    else
        echo "unknown"
    fi
}

get_cpu_max_frequency() {
    if [[ -f /sys/devices/system/cpu/cpu${BENCHMARK_CPU}/cpufreq/scaling_max_freq ]]; then
        local freq=$(cat /sys/devices/system/cpu/cpu${BENCHMARK_CPU}/cpufreq/scaling_max_freq)
        echo "$((freq / 1000)) MHz"
    else
        echo "unknown"
    fi
}

get_turbo_status() {
    if [[ -f $TURBO_BOOST_PATH ]]; then
        local status=$(cat $TURBO_BOOST_PATH)
        if [[ $status -eq 1 ]]; then
            echo "disabled"
        else
            echo "enabled"
        fi
    elif [[ -f $TURBO_BOOST_AMD_PATH ]]; then
        local status=$(cat $TURBO_BOOST_AMD_PATH)
        if [[ $status -eq 0 ]]; then
            echo "disabled"
        else
            echo "enabled"
        fi
    else
        echo "unknown"
    fi
}

show_status() {
    echo ""
    echo "========================================"
    echo "  Benchmark Environment Status"
    echo "========================================"
    echo ""
    echo "CPU Information:"
    echo "  Model:          $(grep 'model name' /proc/cpuinfo | head -1 | cut -d: -f2 | xargs)"
    echo "  Cores:          $(nproc)"
    echo "  Benchmark CPU:  $BENCHMARK_CPU"
    echo ""
    echo "Frequency Settings:"
    echo "  Governor:       $(get_cpu_governor)"
    echo "  Current Freq:   $(get_cpu_frequency)"
    echo "  Max Freq:       $(get_cpu_max_frequency)"
    echo "  Turbo Boost:    $(get_turbo_status)"
    echo ""
    echo "Memory:"
    echo "  Total:          $(free -h | grep Mem | awk '{print $2}')"
    echo "  Available:      $(free -h | grep Mem | awk '{print $7}')"
    echo ""
    echo "System Load:"
    echo "  Load Avg:       $(cat /proc/loadavg | awk '{print $1, $2, $3}')"
    echo ""
}

set_governor() {
    local governor=$1
    log_info "Setting CPU governor to $governor..."

    if check_root; then
        for cpu in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
            echo "$governor" > "$cpu" 2>/dev/null || true
        done
        log_success "Governor set to $governor"
    else
        log_warn "Cannot set governor without root. Try: sudo cpupower frequency-set -g $governor"
    fi
}

set_turbo_boost() {
    local enable=$1
    log_info "Setting turbo boost to $enable..."

    if check_root; then
        if [[ -f $TURBO_BOOST_PATH ]]; then
            if [[ $enable == "enabled" ]]; then
                echo 0 > $TURBO_BOOST_PATH
            else
                echo 1 > $TURBO_BOOST_PATH
            fi
            log_success "Turbo boost set to $enable"
        elif [[ -f $TURBO_BOOST_AMD_PATH ]]; then
            if [[ $enable == "enabled" ]]; then
                echo 1 > $TURBO_BOOST_AMD_PATH
            else
                echo 0 > $TURBO_BOOST_AMD_PATH
            fi
            log_success "Turbo boost set to $enable"
        else
            log_warn "Turbo boost control not available on this system"
        fi
    else
        log_warn "Cannot set turbo boost without root"
    fi
}

prepare_benchmark() {
    echo ""
    log_info "Preparing system for benchmarking..."
    echo ""

    # Set performance governor
    set_governor "performance"

    # Disable turbo boost for consistency
    set_turbo_boost "disabled"

    # Sync and clear caches if root
    if check_root; then
        log_info "Syncing and clearing caches..."
        sync
        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true
        log_success "Caches cleared"
    fi

    echo ""
    log_success "System prepared for benchmarking"
    echo ""
    show_status
}

restore_system() {
    echo ""
    log_info "Restoring default system settings..."
    echo ""

    # Restore to powersave or ondemand governor
    set_governor "powersave"

    # Re-enable turbo boost
    set_turbo_boost "enabled"

    echo ""
    log_success "System settings restored"
    echo ""
    show_status
}

run_with_affinity() {
    local command="$@"

    if [[ -z "$command" ]]; then
        log_error "No command provided"
        echo "Usage: $0 --run <command>"
        exit 1
    fi

    log_info "Running with CPU affinity on core $BENCHMARK_CPU: $command"
    echo ""

    # Use taskset to pin to specific CPU
    if command -v taskset &> /dev/null; then
        taskset -c $BENCHMARK_CPU $command
    else
        log_warn "taskset not available, running without CPU affinity"
        $command
    fi
}

show_help() {
    echo "Benchmark Environment Setup Script"
    echo ""
    echo "Usage: $0 [OPTION]"
    echo ""
    echo "Options:"
    echo "  --prepare     Configure system for benchmarking (performance mode, disable turbo)"
    echo "  --restore     Restore default system settings (powersave mode, enable turbo)"
    echo "  --status      Show current system status"
    echo "  --run CMD     Run command with CPU affinity on core 0"
    echo "  --help        Show this help message"
    echo ""
    echo "Examples:"
    echo "  sudo $0 --prepare"
    echo "  $0 --status"
    echo "  $0 --run npm run benchmark"
    echo "  sudo $0 --restore"
    echo ""
}

# Main
case "${1:-}" in
    --prepare)
        prepare_benchmark
        ;;
    --restore)
        restore_system
        ;;
    --status)
        show_status
        ;;
    --run)
        shift
        run_with_affinity "$@"
        ;;
    --help|-h)
        show_help
        ;;
    *)
        show_status
        echo ""
        echo "Run '$0 --help' for usage information."
        ;;
esac
