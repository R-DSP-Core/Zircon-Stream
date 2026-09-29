#!/usr/bin/env python3
"""Audit retired SIMD op counts and one-time configuration in the new kernels."""

import argparse
import csv
from collections import Counter


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kernel", choices=("vector", "edges", "matrix"))
    parser.add_argument("trace", help="profiling/stream-simd-<kernel>-riscv32/base.log")
    args = parser.parse_args()
    expected = {
        "vector": {"vadd8": 8, "vsub8": 4, "vmul8": 12, "vdot8": 0},
        "edges": {"vadd8": 4, "vsub8": 4, "vmul8": 2, "vdot8": 8,
                  "vomac8": 1, "vregout8": 4, "vclr": 2},
        "matrix": {"vdot8": 16384, "cfg_i": 2, "cfg_i_limit": 2,
                   "cfg_i_repeat": 2, "cfg_i_offset": 2, "cfg_reuse": 2,
                   "cfg_load": 2, "cfg_store": 0},
    }[args.kernel]
    counts = Counter()
    computing = False
    dot_destinations = set()
    with open(args.trace, newline="", encoding="utf-8") as trace:
        rows = csv.DictReader(trace)
        if not rows.fieldnames or "asm" not in rows.fieldnames:
            raise SystemExit("FAIL: not a base.log instruction CSV")
        for row in rows:
            asm = row["asm"].split()
            op = asm[0]
            if op in ("vadd8", "vsub8", "vmul8", "vdot8"):
                computing = True
            if computing and op.startswith("cfg_"):
                raise SystemExit("FAIL: configuration after SIMD computation started")
            if op == "vdot8":
                dot_destinations.add(asm[1].rstrip(","))
            counts[op] += 1
    for op, count in expected.items():
        if counts[op] != count:
            raise SystemExit(f"FAIL: {op}: expected {count}, got {counts[op]}")
    if args.kernel == "edges" and not {"x0", "x29"}.issubset(dot_destinations):
        raise SystemExit(f"FAIL: missing real-GPR boundary cases: {dot_destinations}")
    print(f"PASS: {args.kernel} SIMD counts and one-time configuration: {expected}")


if __name__ == "__main__":
    main()
