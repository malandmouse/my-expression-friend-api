import { AgentError, ROOT, readJson } from './base.mjs';
import path from 'node:path';

export const openapi = await readJson(path.join(ROOT, 'contracts/agent-api.proposed.openapi.json'));
export const schemas = openapi.components.schemas;
// 외부 의존성 없는, 이 고정 계약에서 사용하는 JSON Schema 키워드의 검사기.
// 전체 JSON Schema 구현/감정 의미 검수기가 아니다. 미지원 키워드를 조용히 무시하지 않는다.
const supported = new Set(['$schema', '$id', '$ref', 'title', 'description', 'type', 'enum', 'const', 'anyOf', 'properties', 'required', 'additionalProperties', 'items', 'minItems', 'maxItems', 'uniqueItems', 'minLength', 'maxLength', 'minimum', 'maximum', 'pattern', 'format', 'default', 'examples']);
export function validate(schema, value, root = openapi, at = '$', errors = []) {
  for (const k of Object.keys(schema)) if (!supported.has(k)) throw new Error(`Unsupported schema keyword: ${k}`);
  if (schema.$ref) {
    const parts = schema.$ref.split('/').slice(1);
    if (!schema.$ref.startsWith('#/')) throw new Error('External schema reference disabled');
    let target = root; for (const p of parts) target = target[p];
    if (!target) throw new Error('Unknown schema reference');
    return validate(target, value, root, at, errors);
  }
  if (schema.anyOf) {
    if (!schema.anyOf.some(s => validate(s, value, root, at, []).length === 0)) errors.push({ path: at, reason: '허용된 형식이 아닙니다.' });
    return errors;
  }
  const fail = reason => errors.push({ path: at, reason });
  if ('const' in schema && value !== schema.const) fail(`고정 값 ${schema.const}이 필요합니다.`);
  if (schema.enum && !schema.enum.includes(value)) fail('허용된 선택지가 아닙니다.');
  const matches = type => type === 'null' ? value === null : type === 'array' ? Array.isArray(value) : type === 'object' ? value !== null && typeof value === 'object' && !Array.isArray(value) : type === 'integer' ? Number.isSafeInteger(value) : type === 'number' ? typeof value === 'number' && Number.isFinite(value) : typeof value === type;
  if (schema.type && !(Array.isArray(schema.type) ? schema.type : [schema.type]).some(matches)) { fail('자료형이 맞지 않습니다.'); return errors; }
  if (value === null) return errors;
  if (typeof value === 'string') {
    const length = [...value].length;
    if (length < (schema.minLength ?? 0) || length > (schema.maxLength ?? Infinity)) fail('문자 수 범위를 벗어났습니다.');
    if (schema.pattern && !new RegExp(schema.pattern, 'u').test(value)) fail('문자열 형식이 맞지 않습니다.');
    if (schema.format === 'uuid' && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) fail('UUID 형식이 필요합니다.');
    if (schema.format === 'date' && (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString().slice(0, 10) !== value)) fail('실제 날짜 YYYY-MM-DD가 필요합니다.');
    if (schema.format === 'date-time' && (!/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value) || !Number.isFinite(Date.parse(value)))) fail('시간대가 포함된 시각이 필요합니다.');
  }
  if (typeof value === 'number' && (value < (schema.minimum ?? -Infinity) || value > (schema.maximum ?? Infinity))) fail('숫자 범위를 벗어났습니다.');
  if (Array.isArray(value)) {
    if (value.length < (schema.minItems ?? 0) || value.length > (schema.maxItems ?? Infinity)) fail('항목 수 범위를 벗어났습니다.');
    if (schema.uniqueItems && new Set(value.map(v => JSON.stringify(v))).size !== value.length) fail('중복 항목이 있습니다.');
    if (schema.items) value.forEach((v, i) => validate(schema.items, v, root, `${at}[${i}]`, errors));
  } else if (typeof value === 'object') {
    for (const k of schema.required ?? []) if (!Object.hasOwn(value, k)) errors.push({ path: `${at}.${k}`, reason: '필수 항목입니다.' });
    for (const [k, v] of Object.entries(value)) {
      if (schema.properties?.[k]) validate(schema.properties[k], v, root, `${at}.${k}`, errors);
      else if (schema.additionalProperties === false) errors.push({ path: `${at}.${k}`, reason: '정의되지 않은 항목입니다.' });
    }
  }
  return errors;
}
export function check(name, value, { stage = 'INPUT', attempted = false } = {}) {
  const errors = validate(schemas[name], value);
  if (errors.length) throw new AgentError(stage === 'INPUT' ? 'INPUT_INVALID' : 'OUTPUT_INVALID', 'JSON 형식 확인에 실패했습니다.', 422, stage, attempted, attempted ? 'KNOWN_COMPLETED' : 'NOT_STARTED', errors.slice(0, 20));
  return value;
}
