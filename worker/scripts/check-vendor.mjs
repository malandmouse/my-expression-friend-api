import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { ROOT, readJson, sha256 } from '../src/base.mjs';

// 재사용이라고 표시한 부분이 고정한 원본의 실제 코드인지 확인한다.
export async function checkVendor() {
  const manifest = await readJson(path.join(ROOT, 'vendor/sillytavern/UPSTREAM.json'));
  for (const file of manifest.files) {
    const bytes = await fs.readFile(path.join(ROOT, file.local ?? file.snapshot));
    assert.equal(sha256(bytes), file.sha256, file.upstream);
  }
  const original = (await fs.readFile(path.join(ROOT, manifest.files[1].snapshot), 'utf8')).split('\n');
  const derived = await fs.readFile(path.join(ROOT, manifest.files[1].derived), 'utf8');
  for (const [start, end] of [[2641,2670],[2676,2685],[2689,2689]]) {
    assert.ok(derived.includes(original.slice(start-1, end).join('\n')), `실제 원본 ${start}~${end}행`);
  }
  const expected = { 준: 'c61749788b8064a5e460241e6e0821ab70d3a75b76563bd12e58e8db9e6b1559', 나래: 'e125f247c74a6fb5aa3e5cc5c8f64fd3f0b0299760a867a6c3cddf53125e338f' };
  for (const [name, hash] of Object.entries(expected)) {
    const bytes = await fs.readFile(path.join(ROOT, 'cards', `${name}.json`));
    assert.equal(sha256(bytes), hash); assert.equal(JSON.parse(bytes).data.character_version, '0.3.0');
  }
  return { commit: manifest.commit, sourceHashes: 'PASS', reusedBlocks: 'PASS', characterHashes: 'PASS' };
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(JSON.stringify(await checkVendor(), null, 2));
}
