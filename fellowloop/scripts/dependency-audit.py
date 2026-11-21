#!/usr/bin/env python3
"""Checks the shipped dependencies for known vulnerabilities.

- Java: every runtime dependency of the server, daemon, CLI and agent drivers is looked up in the OSV database
  (https://osv.dev, which includes the GitHub Advisory Database).
- Web: `npm audit` on the bundled front end.

Run from the repository root before a release (needs network access to api.osv.dev and the npm registry):
    python3 scripts/dependency-audit.py
Exit code 0 when nothing is known, 1 when something was found, 2 when a check could not run.
"""
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULES = ["fellowloop-server", "fellowloop-daemon", "fellowloop-cli", "fellowloop-agent"]


def maven_deps():
    deps = set()
    with tempfile.TemporaryDirectory() as tmp:
        for m in MODULES:
            out = os.path.join(tmp, m + ".txt")
            subprocess.run(["mvn", "-q", "-pl", m, "dependency:list", "-DincludeScope=runtime",
                            "-DexcludeGroupIds=dev.fellowloop", "-DoutputFile=" + out], cwd=ROOT, check=True)
            for line in open(out, encoding="utf-8"):
                p = re.sub(r"\x1b\[[0-9;]*m", "", line).strip().split(" ")[0].split(":")
                if len(p) >= 5:
                    deps.add((p[0] + ":" + p[1], p[-2]))
    return sorted(deps)


def osv(deps):
    query = {"queries": [{"package": {"name": n, "ecosystem": "Maven"}, "version": v} for n, v in deps]}
    req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(query).encode(),
                                 headers={"Content-Type": "application/json"})
    results = json.load(urllib.request.urlopen(req, timeout=60))["results"]
    return [(deps[i], [v["id"] for v in r.get("vulns", [])]) for i, r in enumerate(results) if r.get("vulns")]


def npm_audit():
    r = subprocess.run(["npm", "audit", "--json"], cwd=os.path.join(ROOT, "web"), capture_output=True, text=True)
    data = json.loads(r.stdout or "{}")
    found = []
    for name, v in (data.get("vulnerabilities") or {}).items():
        titles = [x.get("title") for x in v.get("via", []) if isinstance(x, dict)]
        found.append((name, v.get("severity"), titles))
    return data.get("metadata", {}).get("dependencies", {}).get("total"), found


def main():
    status = 0
    try:
        deps = maven_deps()
        hits = osv(deps)
        print(f"Java: {len(deps)} runtime dependencies checked against OSV, {len(hits)} with known vulnerabilities")
        for (name, version), ids in hits:
            print(f"  {name} {version}: {', '.join(ids)}")
        status = max(status, 1 if hits else 0)
    except Exception as e:  # noqa: BLE001 - report and continue with the other check
        print(f"Java: check did not run ({e})")
        status = 2
    try:
        total, found = npm_audit()
        print(f"Web: {total} npm packages audited, {len(found)} with known vulnerabilities")
        for name, severity, titles in found:
            print(f"  {name} ({severity}): {'; '.join(titles)}")
        status = max(status, 1 if found else 0)
    except Exception as e:  # noqa: BLE001
        print(f"Web: check did not run ({e})")
        status = 2
    return status


if __name__ == "__main__":
    sys.exit(main())
