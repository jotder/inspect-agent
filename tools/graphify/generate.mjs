#!/usr/bin/env node
// Local "graphify" equivalent for the EOI Agent docs repo.
// Builds two graphs and writes graphify-out/graph.json + graphify-out/GRAPH_REPORT.md
//   1. docs        — every .md file as a node; markdown cross-references as edges.
//   2. architecture — the 11 CORE components + reuse layer + modules, from
//                     tools/graphify/architecture.json (hand-maintained model).
// Zero dependencies; run with: node tools/graphify/generate.mjs

import { readFileSync, writeFileSync, readdirSync, statSync, mkdirSync } from "node:fs";
import { join, relative, dirname, resolve, posix } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(fileURLToPath(import.meta.url), "..", "..", "..");
const OUT_DIR = join(ROOT, "graphify-out");
const toPosix = (p) => p.split("\\").join("/");

// ---------- collect markdown files ----------
function walk(dir, acc = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === "node_modules" || entry === ".git" || entry === "graphify-out" || entry === ".claude") continue;
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) walk(full, acc);
    else if (entry.toLowerCase().endsWith(".md")) acc.push(full);
  }
  return acc;
}

function groupOf(relPath) {
  if (relPath.startsWith("docs/adr/")) return "adr";
  if (relPath.startsWith("docs/architecture/")) return "architecture";
  if (relPath.startsWith("docs/specs/")) return "spec";
  if (relPath.startsWith("docs/roadmap/")) return "roadmap";
  if (relPath.startsWith("docs/")) return "docs";
  return "root";
}

function titleOf(content, fallback) {
  const m = content.match(/^#\s+(.+)$/m);
  return m ? m[1].trim() : fallback;
}

// markdown link: [text](target)  — capture target up to ) , strip #anchor
const LINK_RE = /\[[^\]]*\]\(([^)]+)\)/g;

const mdFiles = walk(ROOT).map((f) => toPosix(relative(ROOT, f)));
const fileSet = new Set(mdFiles);

const docNodes = [];
const docEdges = [];
const danglingLinks = [];
const fileContent = {};

for (const rel of mdFiles) {
  const content = readFileSync(join(ROOT, rel), "utf8");
  fileContent[rel] = content;
  docNodes.push({
    id: rel,
    type: "doc",
    group: groupOf(rel),
    title: titleOf(content, rel),
    bytes: Buffer.byteLength(content, "utf8"),
  });
}

const linkCount = {}; // rel -> outbound md link count
for (const rel of mdFiles) {
  const dir = dirname(rel);
  let m;
  LINK_RE.lastIndex = 0;
  while ((m = LINK_RE.exec(fileContent[rel])) !== null) {
    let target = m[1].trim();
    if (/^(https?:|mailto:|#)/i.test(target)) continue; // external / pure-anchor
    const [pathPart] = target.split("#");
    if (!pathPart || !pathPart.endsWith(".md")) continue;
    const resolved = toPosix(posix.normalize(posix.join(dir, pathPart)));
    linkCount[rel] = (linkCount[rel] || 0) + 1;
    if (fileSet.has(resolved)) {
      docEdges.push({ source: rel, target: resolved, type: "links-to" });
    } else {
      danglingLinks.push({ source: rel, target, resolved });
    }
  }
}

// inbound counts + orphans (no inbound, excluding entry points)
const inbound = {};
for (const e of docEdges) inbound[e.target] = (inbound[e.target] || 0) + 1;
const ENTRY = new Set(["README.md", "AGENTS.md"]);
const orphans = docNodes
  .filter((n) => !inbound[n.id] && !ENTRY.has(n.id))
  .map((n) => n.id);

// ---------- architecture graph ----------
const arch = JSON.parse(readFileSync(join(ROOT, "tools/graphify/architecture.json"), "utf8"));
const archNodes = [];
const archEdges = [];

for (const c of [...arch.components, ...arch.reuseLayer]) {
  archNodes.push({
    id: c.id,
    type: "component",
    name: c.name,
    layer: c.layer,
    ports: c.ports || [],
    adapters: c.adapters || [],
    providers: c.providers || undefined,
    package: c.package || undefined,
    module: c.module || undefined,
    spec: c.spec || undefined,
  });
  for (const s of [c.spec, c.spec2].filter(Boolean)) {
    if (fileSet.has(s)) archEdges.push({ source: c.id, target: s, type: "documented-in" });
    else danglingLinks.push({ source: `architecture:${c.id}`, target: s, resolved: s });
  }
}
for (const m of arch.modules) {
  archNodes.push({ id: m.id, type: "module", name: m.name, layer: m.layer });
}
for (const d of arch.moduleDeps) {
  archEdges.push({ source: d.from, target: d.to, type: d.kind });
}

// ---------- assemble graph.json ----------
const graph = {
  tool: "local-graphify (docs equivalent)",
  version: "1.0.0",
  generatedAt: new Date().toISOString(),
  root: toPosix(relative(ROOT, ROOT)) || ".",
  graphs: {
    docs: { nodes: docNodes, edges: docEdges },
    architecture: { nodes: archNodes, edges: archEdges },
  },
  stats: {
    docs: {
      nodes: docNodes.length,
      edges: docEdges.length,
      danglingLinks: danglingLinks.length,
      orphanDocs: orphans.length,
    },
    architecture: {
      components: arch.components.length,
      reuseLayerNodes: arch.reuseLayer.length,
      modules: arch.modules.length,
      edges: archEdges.length,
    },
  },
  health: { danglingLinks, orphanDocs: orphans },
};

mkdirSync(OUT_DIR, { recursive: true });
writeFileSync(join(OUT_DIR, "graph.json"), JSON.stringify(graph, null, 2) + "\n");

// ---------- GRAPH_REPORT.md ----------
const byGroup = {};
for (const n of docNodes) (byGroup[n.group] ||= []).push(n);
const groupOrder = ["root", "architecture", "adr", "spec", "roadmap", "docs"];

// most-referenced docs
const topRef = [...docNodes]
  .map((n) => ({ id: n.id, in: inbound[n.id] || 0 }))
  .sort((a, b) => b.in - a.in)
  .slice(0, 8);

const L = [];
L.push("# Graph Report — EOI Agent");
L.push("");
L.push(`Generated: ${graph.generatedAt}`);
L.push(`Tool: ${graph.tool} v${graph.version}`);
L.push("");
L.push("## Summary");
L.push("");
L.push("| Graph | Nodes | Edges | Issues |");
L.push("|-------|------:|------:|--------|");
L.push(`| Docs link graph | ${graph.stats.docs.nodes} | ${graph.stats.docs.edges} | ${graph.stats.docs.danglingLinks} dangling, ${graph.stats.docs.orphanDocs} orphan |`);
L.push(`| Architecture graph | ${archNodes.length} | ${archEdges.length} | — |`);
L.push("");

L.push("## Health");
L.push("");
if (danglingLinks.length === 0) L.push("- ✅ **No dangling `.md` links.**");
else {
  L.push(`- ⚠️ **${danglingLinks.length} dangling link(s):**`);
  for (const d of danglingLinks) L.push(`  - \`${d.source}\` → \`${d.target}\``);
}
if (orphans.length === 0) L.push("- ✅ **No orphan docs** (every doc is reachable; README/AGENTS are entry points).");
else {
  L.push(`- ⚠️ **${orphans.length} orphan doc(s)** (no inbound links, not an entry point):`);
  for (const o of orphans) L.push(`  - \`${o}\``);
}
L.push("");

L.push("## Most-referenced docs (inbound links)");
L.push("");
L.push("| Doc | Inbound |");
L.push("|-----|--------:|");
for (const t of topRef) L.push(`| \`${t.id}\` | ${t.in} |`);
L.push("");

L.push("## Docs by group");
L.push("");
for (const g of groupOrder) {
  if (!byGroup[g]) continue;
  L.push(`### ${g} (${byGroup[g].length})`);
  L.push("");
  for (const n of byGroup[g].sort((a, b) => a.id.localeCompare(b.id))) {
    L.push(`- \`${n.id}\` — ${n.title}  _(in:${inbound[n.id] || 0}, out:${linkCount[n.id] || 0})_`);
  }
  L.push("");
}

L.push("## Architecture graph");
L.push("");
L.push("### Components (CORE)");
L.push("");
L.push("| ID | Component | Ports | Adapters | Spec |");
L.push("|----|-----------|-------|---------:|------|");
for (const c of arch.components) {
  L.push(`| ${c.id} | ${c.name} | ${c.ports.join(", ")} | ${c.adapters.length} | \`${c.spec}\` |`);
}
L.push("");
L.push("### Reuse layer");
L.push("");
for (const c of arch.reuseLayer) {
  L.push(`- **${c.name}** (\`${c.module || c.package || ""}\`, layer: ${c.layer})${c.providers ? ` — providers: ${c.providers.join(", ")}` : ""}`);
}
L.push("");
L.push("### Module dependency direction");
L.push("");
L.push("```");
for (const d of arch.moduleDeps) {
  const name = (id) => arch.modules.find((m) => m.id === id)?.name || id;
  L.push(`${name(d.from)}  ──${d.kind}──►  ${name(d.to)}`);
}
L.push("```");
L.push("");
L.push("_Invariant: core imports no adapter and no pack; the pack depends on `eoiagent-app-api` + BOM only._");
L.push("");

writeFileSync(join(OUT_DIR, "GRAPH_REPORT.md"), L.join("\n"));

console.log(`graphify-out/graph.json        (${graph.stats.docs.nodes} docs, ${archNodes.length} arch nodes)`);
console.log(`graphify-out/GRAPH_REPORT.md`);
console.log(`docs: ${graph.stats.docs.edges} edges, ${danglingLinks.length} dangling, ${orphans.length} orphan`);
