import { isDeepStrictEqual } from 'node:util';

// Quality preservation is distinct from meeting an absolute accuracy target.
export function compareNonRegression(baseline, current, baselineIdentity, currentIdentity, latencyRatio = 0.2) {
  const issues = [];
  const reject = (type, message) => issues.push({ type, message });
  if (!Number.isFinite(latencyRatio) || latencyRatio < 0) reject('invalid-latency-ratio', 'Latency tolerance must be finite and nonnegative.');
  const identityKeys = ['fixtureHash', 'model', 'modelDigest', 'inferenceSettings', 'repositories'];
  if (identityKeys.some(k => baselineIdentity?.[k] == null || currentIdentity?.[k] == null)
      || !isDeepStrictEqual(baselineIdentity, currentIdentity)) {
    reject('environment-mismatch', 'Frozen repository, fixture and inference identities must be present and identical.');
  }
  for (const [label, report] of [['baseline', baseline], ['current', current]]) {
    const results = report.results || [];
    if (report.schema !== 'learnbot.quality.rag-score.v1' || !results.length
        || new Set(results.map(r => r.id)).size !== results.length
        || results.some(r => !r.id || !['passed', 'failed'].includes(r.status))
        || report.summary?.scoredCases !== results.length || report.summary?.totalCases !== results.length
        || report.summary?.skippedCases !== 0) {
      reject('invalid-cohort', `${label}: complete, uniquely identified, non-skipped scored cases are required.`);
    }
  }
  const oldCases = new Map((baseline.results || []).map(r => [r.id, r]));
  const newCases = new Map((current.results || []).map(r => [r.id, r]));
  if (!isDeepStrictEqual([...oldCases.keys()].sort(), [...newCases.keys()].sort())) reject('cohort-mismatch', 'Case IDs must match exactly.');
  function metric(a, b, key, label, lowerIsBetter = false) {
    if (typeof a?.[key] !== 'number' || typeof b?.[key] !== 'number' || !Number.isFinite(a[key]) || !Number.isFinite(b[key])) {
      reject('missing-metric', `${label}.${key}: numeric metric required.`);
    } else if (lowerIsBetter ? b[key] > a[key] : b[key] < a[key]) {
      reject('metric-regression', `${label}.${key}: ${a[key]} -> ${b[key]}`);
    }
  }
  for (const key of ['passedCases', 'citationRecall', 'citationPrecision', 'evidenceCoverage', 'requiredClaimCoverage', 'expectedFileCoverage', 'expectedSymbolCoverage', 'implementationBodyCoverage', 'followUpQuality']) metric(baseline.summary, current.summary, key, 'summary');
  metric(baseline.summary, current.summary, 'hallucinationRiskFlags', 'summary', true);
  for (const [id, old] of oldCases) {
    const next = newCases.get(id);
    if (!next) continue;
    if (!isDeepStrictEqual([old.question, old.domain, old.dimensions], [next.question, next.domain, next.dimensions])) reject('case-definition-changed', `${id}: question or cohort changed.`);
    if (old.status === 'passed' && next.status !== 'passed') reject('case-status-regression', `${id}: passing case no longer passes.`);
    for (const [group, keys] of Object.entries({citation:['recall','precision'], evidence:['coverage'], claims:['requiredCoverage'], codeGrounding:['fileCoverage','symbolCoverage','implementationCoverage'], followUp:['quality']})) {
      if (group === 'codeGrounding' && old.domain !== 'code') continue;
      for (const key of keys) metric(old[group], next[group], key, `${id}.${group}`);
    }
    if (!old.gates || !next.gates || !isDeepStrictEqual(Object.keys(old.gates).sort(), Object.keys(next.gates).sort())) reject('gate-contract-changed', `${id}: gate definitions missing or changed.`);
    for (const [gate, passed] of Object.entries(old.gates || {})) if (passed === true && next.gates?.[gate] !== true) reject('gate-regression', `${id}: ${gate} regressed.`);
  }
  const p95 = report => {
    const values = (report.results || []).map(r => r.latency?.observedMs);
    if (!values.length || values.some(v => !Number.isFinite(v) || v <= 0)) return null;
    values.sort((a,b)=>a-b);
    return values[Math.ceil(values.length * 0.95) - 1];
  };
  const before=p95(baseline), after=p95(current);
  if (before == null || after == null) reject('missing-latency', 'All cases require positive observed latency.');
  else if (after > before * (1 + latencyRatio)) reject('p95-latency-regression', `P95 latency: ${before} -> ${after}ms`);
  return issues;
}
