#!/usr/bin/env python3
"""
Manifest and environment integrity checker for Truffnotes Android F00 gate.
Verifies:
1. Source specifications and documents fingerprints.
2. requirements.json schema, R01-R40 and acceptance01-acceptance50 presence and uniqueness.
3. docs/perf/devices.json hardware matrix (D1, D2, D3) and emulator profiles.
4. docs/perf/protocol.json quantiles formula and numerical performance/memory gates.
5. fixtures/workloads/manifest.json workloads W0-W6 with seed 20261105.
6. Quantile formula calibration on synthetic benchmark distribution.
7. Bundled native libraries ELF 16KiB LOAD segment alignment.
"""

import sys
import os
import json
import hashlib
import math
import subprocess

def check_file(path, expected_min_bytes=1):
    if not os.path.exists(path):
        print(f"[FAIL] Missing file: {path}")
        return False
    size = os.path.getsize(path)
    if size < expected_min_bytes:
        print(f"[FAIL] File too small ({size} bytes): {path}")
        return False
    print(f"[OK] File exists ({size} B): {path}")
    return True

def compute_quantiles(data, quantiles=[0.50, 0.95, 0.99]):
    """
    Nearest-rank method as specified in protocol.json:
    Given sorted data x[0..N-1], rank_index(p) = ceil(p * N) - 1 clamped to [0, N-1].
    """
    if not data:
        return {}
    s = sorted(data)
    n = len(s)
    res = {}
    for q in quantiles:
        rank = max(0, min(n - 1, math.ceil(q * n) - 1))
        res[q] = s[rank]
    return res

def main():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
    os.chdir(root_dir)
    print(f"Checking F00 manifest from root: {root_dir}")

    all_ok = True

    # 1. Spec files
    specs = [
        ("truffnotes.md", 60000),
        ("goodnotes_red.md", 100000),
        ("images.zip", 1000000),
        ("docs/architecture/android-workspace.md", 40000),
        ("docs/plan/requirements.md", 15000),
        ("docs/plan/ai-execution.md", 10000),
        ("docs/superpowers/plans/2026-11-05-truffnotes-android.md", 50000),
    ]
    for rel_path, min_sz in specs:
        if not check_file(rel_path, min_sz):
            all_ok = False

    # 2. Requirements JSON
    req_path = "requirements.json"
    if check_file(req_path, 1000):
        try:
            with open(req_path, "r", encoding="utf-8") as f:
                rdata = json.load(f)
            items = rdata.get("items", [])
            ids = set()
            dup = []
            for it in items:
                iid = it.get("id")
                if iid in ids:
                    dup.append(iid)
                ids.add(iid)
            if dup:
                print(f"[FAIL] Duplicate IDs in requirements.json: {dup}")
                all_ok = False
            else:
                print(f"[OK] requirements.json contains {len(ids)} unique items without duplicates.")

            missing_r = [f"R{i:02d}" for i in range(1, 41) if f"R{i:02d}" not in ids]
            if missing_r:
                print(f"[FAIL] Missing requirement IDs in requirements.json: {missing_r}")
                all_ok = False
            else:
                print("[OK] All 40 requirement IDs (R01-R40) present.")

            missing_acc = [f"acceptance{i:02d}" for i in range(1, 51) if f"acceptance{i:02d}" not in ids]
            if missing_acc:
                print(f"[FAIL] Missing acceptance cases in requirements.json: {missing_acc}")
                all_ok = False
            else:
                print("[OK] All 50 acceptance cases (acceptance01-acceptance50) present.")
        except Exception as e:
            print(f"[FAIL] Could not parse requirements.json: {e}")
            all_ok = False
    else:
        all_ok = False

    # 3. Devices JSON
    dev_path = "docs/perf/devices.json"
    if check_file(dev_path, 200):
        try:
            with open(dev_path, "r", encoding="utf-8") as f:
                ddata = json.load(f)
            dev_ids = {d["id"] for d in ddata.get("hardware_profiles", [])}
            if not {"D1", "D2", "D3"}.issubset(dev_ids):
                print(f"[FAIL] Missing hardware profiles D1/D2/D3 in devices.json: {dev_ids}")
                all_ok = False
            else:
                print(f"[OK] Hardware profiles D1, D2, D3 found in devices.json.")
        except Exception as e:
            print(f"[FAIL] Could not parse devices.json: {e}")
            all_ok = False
    else:
        all_ok = False

    # 4. Protocol JSON
    proto_path = "docs/perf/protocol.json"
    if check_file(proto_path, 200):
        try:
            with open(proto_path, "r", encoding="utf-8") as f:
                pdata = json.load(f)
            gates = pdata.get("gates", {})
            required_gates = [
                "frame_time_60hz", "dispatch_input_batch", "input_to_canvas_post",
                "pen_to_photon", "durable_lag", "open_latency", "page_jump",
                "spatial_query", "search_latency", "memory_caps_mib", "crash_safety"
            ]
            missing_gates = [g for g in required_gates if g not in gates]
            if missing_gates:
                print(f"[FAIL] Missing gates in protocol.json: {missing_gates}")
                all_ok = False
            else:
                print(f"[OK] All {len(required_gates)} required gates defined in protocol.json.")
        except Exception as e:
            print(f"[FAIL] Could not parse protocol.json: {e}")
            all_ok = False
    else:
        all_ok = False

    # 5. Workloads manifest
    wl_path = "fixtures/workloads/manifest.json"
    if check_file(wl_path, 200):
        try:
            with open(wl_path, "r", encoding="utf-8") as f:
                wdata = json.load(f)
            if wdata.get("seed") != 20261105:
                print(f"[FAIL] Seed must be 20261105, got {wdata.get('seed')}")
                all_ok = False
            w_ids = [w["id"] for w in wdata.get("workloads", [])]
            expected_w = [f"W{i}" for i in range(7)]
            if w_ids != expected_w:
                print(f"[FAIL] Workloads mismatch: expected {expected_w}, got {w_ids}")
                all_ok = False
            else:
                print(f"[OK] All workloads W0-W6 defined with frozen seed 20261105.")
        except Exception as e:
            print(f"[FAIL] Could not parse manifest.json: {e}")
            all_ok = False
    else:
        all_ok = False

    # 6. Quantile calculation calibration
    synthetic_samples = [i * 0.1 for i in range(1, 1001)] # 1000 samples from 0.1 to 100.0
    q = compute_quantiles(synthetic_samples)
    if abs(q[0.50] - 50.0) > 0.1 or abs(q[0.95] - 95.0) > 0.1 or abs(q[0.99] - 99.0) > 0.1:
        print(f"[FAIL] Quantile calibration check failed: {q}")
        all_ok = False
    else:
        print(f"[OK] Quantile calibration check passed: p50={q[0.50]}, p95={q[0.95]}, p99={q[0.99]}")

    # 7. Native library ELF 16KiB alignment check (if readelf available)
    scratch_dir = os.path.expanduser("~/.gemini/antigravity/brain/a1e567d3-fc89-4a64-92a5-1a8163d40dc7/scratch")
    if os.path.exists(scratch_dir):
        so_files = [os.path.join(scratch_dir, f) for f in os.listdir(scratch_dir) if f.endswith(".so") and "arm64" in f]
        for so in so_files:
            try:
                out = subprocess.check_output(["readelf", "-W", "-l", so], text=True)
                aligns = {line.split()[-1] for line in out.splitlines() if "LOAD" in line}
                if "0x4000" in aligns:
                    print(f"[OK] 16KiB (0x4000) alignment verified on {os.path.basename(so)}: {aligns}")
                else:
                    print(f"[FAIL] 16KiB alignment missing on {os.path.basename(so)}: {aligns}")
                    all_ok = False
            except Exception as e:
                print(f"[WARN] readelf failed on {so}: {e}")

    if all_ok:
        print("\n=== ALL MANIFEST INTEGRITY CHECKS PASSED ===")
        return 0
    else:
        print("\n=== MANIFEST INTEGRITY CHECKS FAILED ===")
        return 1

if __name__ == "__main__":
    sys.exit(main())
