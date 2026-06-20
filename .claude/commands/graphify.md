---
description: Regenerate graphify-out/graph.json and graphify-out/GRAPH_REPORT.md for the EOI Agent project. Run whenever docs or architecture.json change.
---

Run `node tools/graphify/generate.mjs` from the project root (`C:\sandbox\agent-brainstorm`).

After it finishes:
1. Report the summary line from its stdout (node counts, edge counts, any dangling/orphan warnings).
2. If there are dangling links or orphan docs, list them and suggest fixes.
3. If everything is clean, confirm with the counts.

The argument `$ARGUMENTS` is ignored for now (the script always scans the project root).
