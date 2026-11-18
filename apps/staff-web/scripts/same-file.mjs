// Fails when the committed API types differ from types freshly generated from the contract.
import { readFileSync, rmSync } from "node:fs";
const [committed, fresh] = process.argv.slice(2);
const a = readFileSync(committed, "utf8").replace(/\r\n/g, "\n");
const b = readFileSync(fresh, "utf8").replace(/\r\n/g, "\n");
rmSync(fresh);
if (a !== b) {
  console.error(`${committed} is out of date with the OpenAPI contract: run "npm run api:types" and commit.`);
  process.exit(1);
}
console.log("API types match the OpenAPI contract.");
