#!/usr/bin/env node
// OKF v0.1 conformance checker for the docs/ knowledge bundle.
// Conformance rules (https://github.com/GoogleCloudPlatform/knowledge-catalog, okf/SPEC.md):
//   1. Every non-reserved .md file has parseable YAML frontmatter.
//   2. Every frontmatter block has a non-empty `type` field.
//   3. Reserved files index.md / log.md follow their shapes:
//      - index.md: no frontmatter, except an optional `okf_version` at the BUNDLE ROOT only.
//      - log.md:   no frontmatter.
// Zero dependencies; run with: node tools/okf/check.mjs
// Exits non-zero if any file violates conformance.

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve, basename, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(fileURLToPath(import.meta.url), "..", "..", "..");
const BUNDLE = join(ROOT, "docs");
const toPosix = (p) => p.split("\\").join("/");

function walk(dir, acc = []) {
  for (const e of readdirSync(dir)) {
    const full = join(dir, e);
    if (statSync(full).isDirectory()) walk(full, acc);
    else if (e.toLowerCase().endsWith(".md")) acc.push(full);
  }
  return acc;
}

// Returns { present, raw, keys } for a leading ---...--- frontmatter block.
function frontmatter(content) {
  if (!content.startsWith("---\n") && !content.startsWith("---\r\n")) return { present: false };
  const end = content.indexOf("\n---", 3);
  if (end < 0) return { present: true, parseable: false };
  const body = content.slice(content.indexOf("\n") + 1, end);
  const keys = {};
  for (const line of body.split(/\r?\n/)) {
    const m = line.match(/^([A-Za-z_][\w-]*):\s*(.*)$/);
    if (m) keys[m[1]] = m[2].trim();
  }
  return { present: true, parseable: true, keys };
}

const files = walk(BUNDLE).map((f) => toPosix(relative(BUNDLE, f))).sort();
const rows = [];
let failures = 0;

for (const rel of files) {
  const name = basename(rel);
  const atRoot = dirname(rel) === ".";
  const content = readFileSync(join(BUNDLE, rel), "utf8");
  const fm = frontmatter(content);
  let ok = true, note = "";

  if (name === "index.md") {
    if (!fm.present) note = "index (no frontmatter)";
    else if (!fm.parseable) { ok = false; note = "unterminated frontmatter"; }
    else {
      const extra = Object.keys(fm.keys).filter((k) => k !== "okf_version");
      if (extra.length) { ok = false; note = `index frontmatter may only hold okf_version (found: ${extra.join(", ")})`; }
      else if ("okf_version" in fm.keys && !atRoot) { ok = false; note = "okf_version allowed only in bundle-root index.md"; }
      else note = `index (okf_version=${fm.keys.okf_version || "-"})`;
    }
  } else if (name === "log.md") {
    if (fm.present) { ok = false; note = "log.md must not have frontmatter"; }
    else note = "log";
  } else {
    if (!fm.present) { ok = false; note = "missing frontmatter"; }
    else if (!fm.parseable) { ok = false; note = "unterminated frontmatter"; }
    else if (!fm.keys.type) { ok = false; note = "missing/empty `type`"; }
    else note = `type=${fm.keys.type}`;
  }

  if (!ok) failures++;
  rows.push({ ok, rel, note });
}

const pad = Math.max(...rows.map((r) => r.rel.length), 4);
console.log(`OKF v0.1 conformance — bundle: ${toPosix(relative(ROOT, BUNDLE))}/`);
console.log("");
for (const r of rows) {
  console.log(`${r.ok ? "OK  " : "FAIL"}  ${r.rel.padEnd(pad)}  ${r.note}`);
}
console.log("");
console.log(`${rows.length} files, ${rows.length - failures} ok, ${failures} failing`);
process.exit(failures ? 1 : 0);
