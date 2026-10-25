import { mkdirSync } from "node:fs";
import { spawnSync } from "node:child_process";

mkdirSync("target/classes", { recursive: true });
const result = spawnSync("javac", ["--release", "25", "-d", "target/classes", "Server.java"], {
  stdio: "inherit",
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
