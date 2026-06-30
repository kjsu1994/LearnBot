import assert from 'node:assert/strict';
import { buildDryRunPatchFilesSummaryView } from './dryRunPatchFilesSummary.js';

const view = buildDryRunPatchFilesSummaryView([
  {
    path: 'src/App.jsx',
    contextMatches: true,
  },
  {
    path: 'src/Broken.jsx',
    contextMatches: false,
  },
]);

assert.equal(view.show, true);
assert.equal(view.text, 'files: src/App.jsx:context-ok, src/Broken.jsx:context-blocked');

const missingContextView = buildDryRunPatchFilesSummaryView([
  {
    path: 'README.md',
  },
]);

assert.equal(missingContextView.show, true);
assert.equal(missingContextView.text, 'files: README.md:context-blocked');

const emptyView = buildDryRunPatchFilesSummaryView([]);

assert.equal(emptyView.show, false);
assert.equal(emptyView.text, '');

console.log('dryRunPatchFilesSummary view tests passed');
