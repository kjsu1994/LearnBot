import assert from 'node:assert/strict';
import { buildCodeChangeAssistantResult } from './codeChangeAssistantResult.js';

const plan = {
  targetFiles: [
    { path: 'src/auth/AuthFilter.java', reason: 'JWT 만료 응답을 처리하는 필터입니다.' },
    { path: 'src/auth/AuthErrorHandler.java', reason: '인증 실패 응답을 만드는 위치입니다.' },
  ],
  evidence: [
    { filePath: 'src/auth/AuthFilter.java', citationNumber: 1, repositoryId: 1, fileId: 10 },
    { filePath: 'src/auth/AuthErrorHandler.java', citationNumber: 2, repositoryId: 1, fileId: 11 },
  ],
};

const fullResult = buildCodeChangeAssistantResult({
  plan,
  patch: {
    valid: true,
    files: [
      { path: 'src/auth/AuthFilter.java', diff: '--- a/src/auth/AuthFilter.java\n+++ b/src/auth/AuthFilter.java' },
      { path: 'src/auth/AuthErrorHandler.java', diff: '--- a/src/auth/AuthErrorHandler.java\n+++ b/src/auth/AuthErrorHandler.java' },
    ],
  },
});

assert.equal(fullResult.overallStatus, 'DIFF_READY');
assert.deepEqual(fullResult.counts, { diffReady: 2, candidatesOnly: 0, needsMoreContext: 0 });
assert.equal(fullResult.cards[0].statusLabel, '수정 예시 있음');

const partialResult = buildCodeChangeAssistantResult({
  plan,
  patch: {
    valid: true,
    files: [
      { path: 'src/auth/AuthFilter.java', diff: '--- a/src/auth/AuthFilter.java\n+++ b/src/auth/AuthFilter.java' },
    ],
  },
});

assert.equal(partialResult.overallStatus, 'PARTIAL');
assert.deepEqual(partialResult.counts, { diffReady: 1, candidatesOnly: 1, needsMoreContext: 0 });
assert.equal(partialResult.cards.find((card) => card.path === 'src/auth/AuthErrorHandler.java').statusLabel, '후보만 확인됨');

const needsContextResult = buildCodeChangeAssistantResult({
  plan: { targetFiles: [], evidence: [], needsMoreContext: true },
});

assert.equal(needsContextResult.overallStatus, 'NEEDS_MORE_CONTEXT');
assert.deepEqual(needsContextResult.counts, { diffReady: 0, candidatesOnly: 0, needsMoreContext: 1 });

const invalidPatchResult = buildCodeChangeAssistantResult({
  plan,
  patch: { valid: false, files: [], warnings: ['diff format invalid'] },
});

assert.equal(invalidPatchResult.overallStatus, 'FAILED');
assert.equal(invalidPatchResult.warnings[0], 'diff format invalid');

console.log('codeChangeAssistantResult view tests passed');
