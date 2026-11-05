#!/usr/bin/env node
// mtb2penpot.mjs — Material Theme Builder 导出的 JSON → Penpot(W3C DTCG) 可导入的 token 文件
// 用法: node mtb2penpot.mjs <mtb导出.json> [输出目录]
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { basename, join, resolve } from "node:path";

const src = process.argv[2];
if (!src) {
  console.error("用法: node mtb2penpot.mjs <mtb导出.json> [输出目录]");
  process.exit(1);
}
const outDir = resolve(process.argv[3] || join(process.env.HOME, "src/ymcl/design/tokens"));
const raw = JSON.parse(readFileSync(src, "utf8"));
const stem = basename(src).replace(/\.json$/i, "").replace(/[^\w.-]+/g, "-");

const isHex = (v) => typeof v === "string" && /^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(v.trim());

// 把任意 {role: "#RRGGBB"} 平面/嵌套对象拍平成 color.<path> token
function flatten(obj, prefix = "color", out = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const path = `${prefix}.${k}`;
    if (isHex(v)) {
      out[path] = { $value: v.toUpperCase(), $type: "color", $description: "" };
    } else if (v && typeof v === "object" && !Array.isArray(v)) {
      flatten(v, path, out);
    } else if (typeof v === "string" && /^[A-Za-z]+$/.test(v)) {
      // Material 的 key color 名字（如 "primary"），非颜色值，跳过
    }
  }
  return out;
}

function nest(tokens) {
  const root = {};
  for (const [path, tok] of Object.entries(tokens)) {
    const segs = path.split(".");
    let node = root;
    for (const s of segs.slice(0, -1)) node = node[s] ||= {};
    node[segs.at(-1)] = tok;
  }
  return root;
}

mkdirSync(outDir, { recursive: true });
const written = [];

if (raw.schemes) {                       // MTB 原生格式
  const all = process.argv.includes("--all");
  const modes = Object.keys(raw.schemes).filter(
    (m) => all || m === "light" || m === "dark"
  );
  const skipped = Object.keys(raw.schemes).filter((m) => !modes.includes(m));
  for (const mode of modes) {
    const tokens = flatten(raw.schemes[mode]);
    const file = join(outDir, `ymcl-${mode}.json`);
    writeFileSync(file, JSON.stringify(nest(tokens), null, 2) + "\n");
    written.push([file, Object.keys(tokens).length]);
  }
  if (skipped.length) console.log(`(跳过高对比度变体: ${skipped.join(", ")}；要全部导就加 --all)`);
} else if (Object.values(raw).some((v) => v && typeof v === "object" && "$value" in v)) {
  // 已经是 DTCG，只做规范化（补 $type/$description）
  const tokens = {};
  const walk = (o, prefix = "") => {
    for (const [k, v] of Object.entries(o)) {
      if (k.startsWith("$")) continue;
      const path = prefix ? `${prefix}.${k}` : k;
      if (v && typeof v === "object" && "$value" in v) {
        tokens[path] = { $value: v.$value, $type: v.$type || "color", $description: v.$description || "" };
      } else if (v && typeof v === "object") walk(v, path);
    }
  };
  walk(raw);
  const file = join(outDir, `${stem}.json`);
  writeFileSync(file, JSON.stringify(nest(tokens), null, 2) + "\n");
  written.push([file, Object.keys(tokens).length]);
} else {
  // 兜底：干脆把整份 JSON 里所有 hex 值都搜出来
  const tokens = flatten(raw);
  const file = join(outDir, `${stem}.json`);
  writeFileSync(file, JSON.stringify(nest(tokens), null, 2) + "\n");
  written.push([file, Object.keys(tokens).length]);
}

for (const [f, n] of written) console.log(`${n} tokens -> ${f}`);
