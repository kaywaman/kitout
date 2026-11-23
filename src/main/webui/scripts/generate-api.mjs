// Generates src/api/schema.d.ts from openapi.json.
//
// openapi.json is written by the backend's OpenApiContractTest, so it does not exist before the
// first `./mvnw test`. In dev mode (`--optional`) a missing file only prints a hint: Vite does not
// type-check, so the UI still starts. For a production build it is an error, because `tsc` needs
// the generated types.
import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";

const optional = process.argv.includes("--optional");

if (!existsSync("openapi.json")) {
  const hint = "openapi.json is missing. Run './mvnw test' once in the project root to create it.";
  if (optional) {
    console.warn(`[kitout] ${hint} Starting without regenerating the API types.`);
    process.exit(0);
  }
  console.error(`[kitout] ${hint}`);
  process.exit(1);
}

// npm puts node_modules/.bin on the PATH for scripts
const result = spawnSync("openapi-typescript", ["openapi.json", "--output", "src/api/schema.d.ts"], {
  stdio: "inherit",
  shell: process.platform === "win32",
});
if (result.status !== 0 && !optional) {
  process.exit(result.status ?? 1);
}
