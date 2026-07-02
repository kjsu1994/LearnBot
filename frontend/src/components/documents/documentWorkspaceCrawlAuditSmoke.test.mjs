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
  const documentId = 'document-crawler-1';
  const props = {
    showSourceManagement: false,
    documents: [],
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
    documentDetail: {
      summary: {
        id: documentId,
        title: 'Crawler audit seed',
        sourceUri: 'https://example.com/docs',
        sourceType: 'WEB',
        sourceStatus: 'READY',
      },
      chunkCount: 2,
      storedObject: null,
      chunks: [],
      crawlAudits: [
        {
          id: 'audit-policy-block',
          url: 'https://evil.example.net/docs',
          host: 'evil.example.net',
          allowedDomain: false,
          robotsAllowed: null,
          statusCode: null,
          success: false,
          reasonCode: 'DOMAIN_NOT_ALLOWED',
          depth: 1,
          referrerUrl: 'https://example.com/docs',
          normalizedUrl: 'https://evil.example.net/docs',
          contentType: null,
          metadata: {},
          message: 'Skipped URL because the domain is not allowed.',
          category: 'POLICY_BLOCK',
          severity: 'WARNING',
          indexingBlocked: true,
          userAction: 'Add the domain to the crawler allowlist or choose an allowed URL.',
        },
      ],
    },
  };

  const markup = renderToStaticMarkup(React.createElement(DocumentWorkspace, props));

  assert.match(markup, /POLICY_BLOCK/);
  assert.match(markup, /Add the domain to the crawler allowlist or choose an allowed URL\./);
  assert.match(markup, /Skipped URL because the domain is not allowed\./);
  assert.match(markup, /evil\.example\.net/);
} finally {
  await vite.close();
}

console.log('documentWorkspaceCrawlAuditSmoke tests passed');
