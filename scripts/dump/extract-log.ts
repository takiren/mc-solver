// GroovyScript のログから mc-solver 用ダンプを取り出して JSONL にする。
//
// 使い方:
//   node scripts/dump/extract-log.ts <groovy_server.log のパス> <出力先 .jsonl> [補完用 .jsonl ...]
//
// 補完用 .jsonl (手書きのレコード) を渡すと、出力の末尾にそのまま追加する。
// ログに複数回分のダンプがある場合は、最後に完走したものを使う。

import { createReadStream, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { createInterface } from "node:readline";

type Rec = { t: string; [key: string]: unknown };

const [logPath, outPath, ...overridePaths] = process.argv.slice(2);
if (!logPath || !outPath) {
	console.error("usage: node scripts/dump/extract-log.ts <log> <out.jsonl> [overrides.jsonl ...]");
	process.exit(1);
}

const RECORD = /MCSOLVER\|([A-Za-z0-9+/=]+)/;
let current: string[] | null = null;
let complete: { lines: string[]; end: Rec } | null = null;
const errors: string[] = [];

const rl = createInterface({ input: createReadStream(logPath, "utf8"), crlfDelay: Infinity });
for await (const line of rl) {
	if (line.includes("MCSOLVER_ERR")) {
		errors.push(line.slice(line.indexOf("MCSOLVER_ERR")));
		continue;
	}
	const m = RECORD.exec(line);
	if (!m) continue;
	const json = Buffer.from(m[1], "base64").toString("utf8");
	const rec = JSON.parse(json) as Rec;
	if (rec.t === "begin") {
		current = [];
		errors.length = 0;
		continue;
	}
	if (!current) continue;
	if (rec.t === "end") {
		complete = { lines: current, end: rec };
		current = null;
		continue;
	}
	current.push(json);
}

if (!complete) {
	console.error("完走したダンプが見つかりません (begin から end までが揃っていません)。");
	if (errors.length) console.error(errors.join("\n"));
	process.exit(1);
}

const counts: Record<string, number> = {};
for (const json of complete.lines) {
	const t = (JSON.parse(json) as Rec).t;
	counts[t] = (counts[t] ?? 0) + 1;
}

const overrides: string[] = [];
for (const path of overridePaths) {
	for (const line of readFileSync(path, "utf8").split("\n")) {
		if (!line.trim()) continue;
		JSON.parse(line); // 壊れた行はここで止める
		overrides.push(line);
	}
}

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, [...complete.lines, ...overrides].join("\n") + "\n");

console.log(`書き出し: ${outPath} (ダンプ ${complete.lines.length} 行 + 補完 ${overrides.length} 行)`);
console.table(counts);

const expected = (complete.end.counts ?? {}) as Record<string, number>;
for (const [t, n] of Object.entries(expected)) {
	if (t === "begin") continue;
	if ((counts[t] ?? 0) !== n) {
		console.error(`件数が合いません: ${t} はスクリプト側 ${n} 件、ログ側 ${counts[t] ?? 0} 件`);
		process.exitCode = 1;
	}
}
if (complete.end.errors) {
	console.error(`スクリプト側で ${String(complete.end.errors)} 件のエラーがありました (種類ごとに先頭 5 件):`);
	console.error(JSON.stringify(complete.end.errorCounts ?? {}));
	console.error(errors.join("\n"));
}
