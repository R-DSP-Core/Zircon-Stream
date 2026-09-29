#!/usr/bin/env python3
"""Check the committed instruction trace of the 32x32x32 outer kernel."""

import argparse
import csv
from collections import Counter


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", help="profiling/stream-outer-matrix-riscv32/base.log")
    args = parser.parse_args()
    counts = Counter()
    computing = False
    finished = False
    with open(args.trace, newline="", encoding="utf-8") as trace:
        rows = csv.DictReader(trace)
        if not rows.fieldnames or "asm" not in rows.fieldnames:
            raise SystemExit("FAIL: not a base.log instruction CSV")
        for row in rows:
            op = row["asm"].split()[0]
            if op == "vclr" and not computing:
                if counts["fence"] != 2:
                    raise SystemExit("FAIL: expected pack/config fences before the first tile")
                computing = True
            if computing and op.startswith("cfg_"):
                raise SystemExit("FAIL: configuration executed after computation started")
            if computing and op == "fence":
                if counts["vregout8"] != 128:
                    raise SystemExit("FAIL: per-tile fence before all exports completed")
                finished = True
            if finished and op in ("vclr", "vomac8", "vregout8"):
                raise SystemExit("FAIL: vector work after the final completion fence")
            counts[op] += 1

    expected = {
        "cfg_i": 3, "cfg_i_limit": 3, "cfg_i_repeat": 3, "cfg_i_offset": 3,
        "cfg_reuse": 2, "cfg_load": 2, "cfg_store": 1, "fence": 3,
        "vclr": 16, "vomac8": 4096, "vregout8": 128,
    }
    for op, count in expected.items():
        if counts[op] != count:
            raise SystemExit(f"FAIL: {op}: expected {count}, got {counts[op]}")
    print("PASS: one configuration for all 16 tiles; 2 loads, 1 store, 3 fences")
    print("PASS: 4096 VOMAC8, 128 VREGOUT8, 16 VCLR; no per-tile CFG/fence")


if __name__ == "__main__":
    main()
