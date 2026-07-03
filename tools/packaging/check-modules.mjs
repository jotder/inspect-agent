#!/usr/bin/env node
// T-404 packaging conformance checker (ADR-0014).
// Verifies that every built jar module carries a stable Automatic-Module-Name manifest entry
// matching com.eoiagent.<module>, and that no module ships a JPMS module-info.class (split
// packages are illegal under JPMS — ADR-0014). Zero dependencies; reads jars as zip via `jar`.
//
// Run AFTER a build: mvn -o -q install -DskipTests && node tools/packaging/check-modules.mjs
// Exits non-zero on any violation.

import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const ROOT = resolve(fileURLToPath(import.meta.url), "..", "..", "..");

// Expected automatic module name from the maven artifactId: eoiagent-app-api -> com.eoiagent.app.api
const expectedName = (mod) => "com.eoiagent." + mod.replace(/^eoiagent-/, "").replace(/-/g, ".");

function jarEntries(jar) {
  return execFileSync("jar", ["tf", jar], { encoding: "utf8" }).split(/\r?\n/).filter(Boolean);
}

// Extract the manifest to a temp dir and read it back (`jar` has no extract-to-stdout).
function readManifest(jar) {
  const out = execFileSync("jar", ["xf", jar, "META-INF/MANIFEST.MF"], { cwd: TMP });
  const mf = join(TMP, "META-INF", "MANIFEST.MF");
  return readFileSync(mf, "utf8");
}

const TMP = join(ROOT, "target", "packaging-check");
execFileSync("node", ["-e", `require('fs').rmSync(${JSON.stringify(TMP)},{recursive:true,force:true});require('fs').mkdirSync(${JSON.stringify(TMP)},{recursive:true})`]);

const modules = readdirSync(ROOT)
  .filter((e) => e.startsWith("eoiagent-") && e !== "eoiagent-bom")
  .filter((e) => statSync(join(ROOT, e)).isDirectory());

let failures = 0;
console.log("T-404 packaging conformance (ADR-0014)\n");

for (const mod of modules) {
  const target = join(ROOT, mod, "target");
  const jar = existsSync(target)
    ? readdirSync(target).find((f) => f === `${mod}-0.1.0-SNAPSHOT.jar`)
    : undefined;
  if (!jar) {
    console.log(`SKIP  ${mod.padEnd(24)} (no jar built — run mvn install first)`);
    continue;
  }
  const jarPath = join(target, jar);
  const want = expectedName(mod);
  const mf = readManifest(jarPath);
  const got = (mf.match(/Automatic-Module-Name:\s*(\S+)/) || [])[1];
  const hasModuleInfo = jarEntries(jarPath).some((e) => e.endsWith("module-info.class"));

  let ok = true;
  let note = `Automatic-Module-Name=${got}`;
  if (got !== want) { ok = false; note = `expected ${want}, got ${got ?? "(none)"}`; }
  else if (hasModuleInfo) { ok = false; note = "ships module-info.class (forbidden by ADR-0014)"; }

  console.log(`${ok ? "OK  " : "FAIL"}  ${mod.padEnd(24)} ${note}`);
  if (!ok) failures++;
}

console.log(`\n${modules.length} modules, ${failures} failing`);
process.exit(failures ? 1 : 0);
