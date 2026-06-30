import assert from 'node:assert/strict';
import { buildRollbackReadinessSummaryView } from './rollbackReadinessSummary.js';

const rollback = {
  status: 'INVALID',
  message: 'Rollback manifest readiness requires restore preconditions from a non-mutating Local Agent dry-run.',
  blocking: true,
  fileCount: 6,
  requiresUserApproval: true,
  observationLinkage: {
    status: 'RELEASE_ATTEMPT_LINKED',
    releaseAttemptLinked: true,
    sourceOnlyFallback: false,
    releaseAttemptId: '12345678-1234-1234-1234-123456789abc',
    sourceRequestId: 'abcdef12-1234-1234-1234-123456789abc',
  },
  fileChecks: [
    {
      path: 'src/App.jsx',
      snapshotRelativePath: '.learnbot/snapshots/snap-1/src/App.jsx',
      targetPathSafe: true,
      snapshotPathSafe: true,
    },
    {
      path: 'src/unsafe.jsx',
      snapshotRelativePath: '../outside.jsx',
      targetPathSafe: true,
      snapshotPathSafe: false,
    },
    { path: 'README.md', snapshotRelativePath: '.learnbot/snapshots/snap-1/README.md' },
    { path: 'package.json', snapshotRelativePath: '.learnbot/snapshots/snap-1/package.json' },
    { path: 'vite.config.js', snapshotRelativePath: '.learnbot/snapshots/snap-1/vite.config.js' },
    { path: 'hidden.js', snapshotRelativePath: '.learnbot/snapshots/snap-1/hidden.js' },
  ],
};

const view = buildRollbackReadinessSummaryView({ rollback });

assert.equal(view.show, true);
assert.equal(view.headerText, 'Rollback manifest readiness: INVALID');
assert.equal(view.message, rollback.message);
assert.equal(
  view.linkageText,
  'observation linkage: RELEASE_ATTEMPT_LINKED / release-attempt linked: true / source-only fallback: false / attempt 12345678 / source abcdef12'
);
assert.equal(view.blockingText, 'blocking release: true / files 6 / user approval: true');
assert.deepEqual(view.fileCheckLines, [
  'src/App.jsx -> .learnbot/snapshots/snap-1/src/App.jsx / target safe: true / snapshot safe: true',
  'src/unsafe.jsx -> ../outside.jsx / target safe: true / snapshot safe: false',
  'README.md -> .learnbot/snapshots/snap-1/README.md',
  'package.json -> .learnbot/snapshots/snap-1/package.json',
  'vite.config.js -> .learnbot/snapshots/snap-1/vite.config.js',
]);
assert.equal(view.overflowText, '1 more rollback file checks hidden');

const fallbackView = buildRollbackReadinessSummaryView({
  rollback: {
    blocking: false,
    fileChecks: [
      {
        targetPathSafe: false,
        snapshotPathSafe: false,
      },
    ],
  },
});
assert.equal(fallbackView.show, true);
assert.equal(fallbackView.headerText, 'Rollback manifest readiness: UNKNOWN');
assert.equal(fallbackView.message, '');
assert.equal(fallbackView.linkageText, '');
assert.equal(fallbackView.blockingText, 'blocking release: false');
assert.deepEqual(fallbackView.fileCheckLines, [
  '(target) -> (snapshot) / target safe: false / snapshot safe: false',
]);
assert.equal(fallbackView.overflowText, '');

const hiddenView = buildRollbackReadinessSummaryView();
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.fileCheckLines, []);

console.log('rollbackReadinessSummary view tests passed');
