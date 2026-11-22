"use strict";
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

async function main() {
  const dir = process.argv[2];
  const hash = data => crypto.createHash("sha256").update(data).digest("hex");
  const files = {
    "runtime.cjs": "5965e880c2992db755822902e9751efb81d12fd319e791aa1dafd82fd72ef025",
    "highs.wasm": "7e6432b2b26f4fab9f6d9bac55da43307c7a4b1b071cb204cb4d23e1901bc4d0"
  };
  for (const [name, expected] of Object.entries(files)) {
    if (hash(fs.readFileSync(path.join(dir, name))) !== expected) throw Error("Solver asset hash mismatch: " + name);
  }
  const raw = fs.readFileSync(0);
  if (raw.length > 1048576) throw Error("Factory graph exceeds the planner budget.");
  const runtime = require(path.join(dir, "runtime.cjs"));
  await runtime.initialize(path.join(dir, "highs.wasm"));
  runtime.resetLpDiagnostics();
  const report = runtime.calculateNative(JSON.parse(raw));
  const nodes = Object.fromEntries(Object.entries(report.nodes).map(([id, n]) => [id, {
    machines: n.theoreticalMachinesRequired, euT: n.euT, utilization: n.utilization,
    status: n.status, powerStalled: n.powerStalled, effectiveDurationTicks: n.effectiveDurationTicks,
    warnings: n.warnings
  }]));
  const storages = Object.fromEntries(Object.entries(report.storages).map(([id, s]) => [id, {
    consumedPerSecond: s.consumedPerSecond, producedPerSecond: s.producedPerSecond
  }]));
  const edgeRates = Object.fromEntries(Object.entries(report.edges).map(([id, edge]) =>
    [id, edge.transferredPerSecond]));
  const plan = report.plan || {};
  const data = {nodes, storages, edgeRates, totalEuT: report.totalEuT, stale: !!report.stale,
    failure: plan.failure, unanchored: plan.unanchored, forced: plan.forced, shortfalls: plan.shortfalls,
    unmetDrawers: plan.unmetDrawers, notes: plan.notes, spills: plan.spills,
    alternativesComplete: plan.alternativesComplete, diagnostics: runtime.getLpDiagnostics(),
    backend:runtime.activeLpEngine(), inputSha256: hash(raw), assetSha256: files};
  process.stdout.write(JSON.stringify(data, (_, value) => {
    if (typeof value === "number" && !Number.isFinite(value)) throw Error("Nonfinite result.");
    return value;
  }));
}
main().catch(e => {process.stderr.write(String(e.message).slice(0, 1500));process.exitCode = 1;});
