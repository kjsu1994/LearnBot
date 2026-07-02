import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';

const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error',
});

try {
  const { DocumentWorkspace } = await vite.ssrLoadModule('/src/components/documents/DocumentWorkspace.jsx');
  const noop = () => {};
  const loading = () => false;
  const documentId = 'document-retry-context-1';
  const sourceId = 'source-retry-context-1';
  const jobId = 'job-retry-context-1';
  const props = {
    showSourceManagement: true,
    documents: [
      {
        id: documentId,
        sourceId,
        title: 'Retry context seed',
        sourceUri: 'file:///retry-context.md',
        sourceType: 'FILE',
        sourceStatus: 'PARTIAL',
        createdAt: '2026-07-02T00:00:00Z',
      },
    ],
    documentJobs: [
      {
        id: jobId,
        sourceId,
        status: 'PARTIAL',
        phase: 'POST_PROCESSING',
        progressPercent: 100,
        startedAt: '2026-07-02T00:00:00Z',
        completedAt: '2026-07-02T00:01:00Z',
        totalChunks: 4,
        embeddedChunks: 4,
        enrichmentStatus: 'FAILED',
        graphStatus: 'FAILED',
      },
    ],
    documentJobDiagnostics: {
      [jobId]: [
        {
          id: 'diagnostic-graph-failed',
          stage: 'DOCUMENT_GRAPH_REBUILD',
          status: 'FAILED',
          analyzer: 'DETERMINISTIC_GRAPH',
          durationMillis: 420,
          attemptedItems: 4,
          processedItems: 2,
          nodeCount: 3,
          edgeCount: 1,
          message: 'Graph rebuild failed after base chunks became searchable.',
        },
        {
          id: 'diagnostic-enrichment-failed',
          stage: 'DOCUMENT_LLM_ENRICHMENT',
          status: 'FAILED',
          mode: 'LLM_CONTEXT',
          durationMillis: 380,
          attemptedItems: 4,
          processedItems: 1,
          message: 'LLM enrichment failed and can be retried without re-extraction.',
        },
      ],
    },
    searchResults: [],
    answer: null,
    pendingDocumentTurn: null,
    documentConversationTurns: [],
    documentConversationId: '',
    question: '',
    setQuestion: noop,
    ask: noop,
    loading,
    answerMode: 'qa',
    setAnswerMode: noop,
    documentSpeedProfile: 'BALANCED',
    setDocumentSpeedProfile: noop,
    refreshDocumentConversations: noop,
    startNewDocumentConversation: noop,
    query: '',
    setQuery: noop,
    selectedDocumentId: documentId,
    documentDetail: null,
  };

  const markup = renderToStaticMarkup(React.createElement(DocumentWorkspace, props));

  assert.match(markup, /Retry context seed/);
  assert.match(markup, /DOCUMENT_GRAPH_REBUILD|Graph rebuild failed after base chunks became searchable\./);
  assert.match(markup, /DOCUMENT_LLM_ENRICHMENT|LLM enrichment failed and can be retried without re-extraction\./);
  assert.match(markup, /Graph rebuild failed after base chunks became searchable\./);
  assert.match(markup, /LLM enrichment failed and can be retried without re-extraction\./);
  assert.match(markup, /diagnostic-failed/);
  assert.match(markup, /FAILED/);
  assert.ok((markup.match(/ghost-button compact-action/g) ?? []).length >= 3);
} finally {
  await vite.close();
}

console.log('documentWorkspaceRetryContextSmoke tests passed');
