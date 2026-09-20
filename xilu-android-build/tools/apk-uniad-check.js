// 列出 APK 内所有 dex 里"被定义的类"（class_defs），用于确认 uni-ad 的类到底在不在包里
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const os = require('os');

const apk = process.argv[2];
if (!apk) { console.error('usage: node dexclasses.js <apk>'); process.exit(2); }

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dexcls-'));
// 用 PowerShell 解包（Node 无内置 zip 读；借助 Expand-Archive 需要 .zip 后缀）
const zipCopy = path.join(tmp, 'a.zip');
fs.copyFileSync(apk, zipCopy);
execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${zipCopy}' -DestinationPath '${path.join(tmp, 'x')}' -Force"`, { stdio: 'ignore' });

const dexDir = path.join(tmp, 'x');
const dexFiles = fs.readdirSync(dexDir).filter(f => /^classes\d*\.dex$/.test(f));

function u32(b, o) { return b.readUInt32LE(o); }
function u16(b, o) { return b.readUInt16LE(o); }

function stringAt(buf, stringIdsOff, idx) {
  const off = u32(buf, stringIdsOff + idx * 4);
  let p = off;
  // uleb128 长度
  let len = 0, shift = 0, byte;
  do { byte = buf[p++]; len |= (byte & 0x7f) << shift; shift += 7; } while (byte & 0x80);
  return buf.toString('utf8', p, p + len);
}

const found = { 'io/dcloud/sdk/': [], 'io/dcloud/feature/gg/': [], 'io/dcloud/api/custom/': [] };
let total = 0;
for (const f of dexFiles) {
  const buf = fs.readFileSync(path.join(dexDir, f));
  const stringIdsSize = u32(buf, 0x38), stringIdsOff = u32(buf, 0x3C);
  const typeIdsSize = u32(buf, 0x40), typeIdsOff = u32(buf, 0x44);
  const classDefsSize = u32(buf, 0x60), classDefsOff = u32(buf, 0x64);
  total += classDefsSize;
  for (let i = 0; i < classDefsSize; i++) {
    const classIdx = u32(buf, classDefsOff + i * 32);
    const typeStrIdx = u32(buf, typeIdsOff + classIdx * 4);
    const desc = stringAt(buf, stringIdsOff, typeStrIdx);
    for (const p of Object.keys(found)) {
      if (desc.startsWith('L' + p)) { found[p].push(desc.slice(1, -1) + '  [' + f + ']'); }
    }
  }
}
console.log('APK: ' + apk);
console.log('dex 文件: ' + dexFiles.join(', ') + '   定义类总数: ' + total);
for (const p of Object.keys(found)) {
  console.log(`  ${p}  被定义的类: ${found[p].length}`);
}
const all = [].concat(...Object.values(found));
if (all.length) {
  console.log('样例:');
  all.slice(0, 8).forEach(s => console.log('    ' + s));
}
fs.rmSync(tmp, { recursive: true, force: true });
