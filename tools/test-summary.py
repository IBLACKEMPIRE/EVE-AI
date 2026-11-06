#!/usr/bin/env python3
"""Sum JUnit XML results per module and emit one GitHub notice (or error) annotation."""
import glob, sys, xml.etree.ElementTree as ET
lines, failed_total = [], 0
for module in ("ai-brain", "android-app"):
    t = f = e = s = 0
    for path in glob.glob(f"{module}/build/test-results/**/*.xml", recursive=True):
        r = ET.parse(path).getroot()
        t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
    failed_total += f + e
    lines.append(f"{module}: {t} tests, {t - f - e - s} passed, {f + e} failed, {s} skipped")
msg = " | ".join(lines)
print(msg)
print(("::error title=Unit test results::" if failed_total else "::notice title=Unit test results::") + msg)
sys.exit(1 if failed_total else 0)
