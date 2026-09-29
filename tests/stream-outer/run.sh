#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
base="$root/RV-Software/base-port"
out=$(mktemp -d "${TMPDIR:-/tmp}/zircon-outer-regression.XXXXXX")
printf 'Reports: %s\n' "$out"

if [[ ${SKIP_BUILD:-0} != 1 ]]; then
    make -C "$root/ZirconSim" > "$out/build.log" 2>&1
fi

cases=(
    stream-test/stream-simd-vector
    stream-test/stream-simd-edges
    stream-matrix/stream-simd-matrix
    stream-matrix/stream-outer-edges
    stream-matrix/stream-outer-matrix
    stream-test/stream-add
    stream-test/stream-complex-mag
    stream-matrix/stream-matrix-k128
    stream-matrix/stream-transpose
    FIR32/fir32
    fft32
    functest/mul-longlong
    functest/div
)
if (( $# )); then cases=("$@"); fi

failed=0
for entry in "${cases[@]}"; do
    directory=${entry%/*}
    name=${entry##*/}
    kernel="$root/RV-Software/$directory"
    source="src/$name.c"
    include="-I$kernel/include -I$base/base/include -I$base/tool/include"
    if [[ $entry == fft32 ]]; then
        kernel="$out/fft32-build"
        mkdir -p "$kernel"
        source="$root/tests/stream-outer/fft32.c"
        include="-I$root/RV-Software/cfft-4k-sh-stream/include -I$base/base/include -I$base/tool/include"
    fi
    if ! make -s -C "$kernel" -f "$base/Makefile" BASE_PORT="$base" \
        SIM_PATH="$root" NAMES="$name" SRCS="$source" INC_PATH="$include" image > "$out/$name.build.log" 2>&1; then
        printf 'BUILD FAILED %s\n' "$entry"
        failed=1
        continue
    fi
    # Keep generated profiles and any failure artifacts out of the git worktree.
    if (cd "$out" && timeout 300 "$root/ZirconSim/build/VCPU" \
        "$kernel/build/$name-riscv32.bin" --no-wave > "$name.log" 2>&1); then
        printf 'PASS %s\n' "$entry"
    else
        printf 'FAIL %s (see %s/%s.log)\n' "$entry" "$out" "$name"
        failed=1
    fi
done
exit "$failed"
