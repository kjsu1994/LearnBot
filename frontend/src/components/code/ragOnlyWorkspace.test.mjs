import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';

const vite=await createServer({server:{middlewareMode:true},appType:'custom',logLevel:'error'});
try {
  const {CodeWorkspace}=await vite.ssrLoadModule('/src/components/code/CodeWorkspace.jsx');
  const evidence={citationNumber:1,repositoryId:'repo',fileId:'file',filePath:'src/Entry.java',symbolName:'start',lineStart:3,lineEnd:8,preview:'void start() { read(); }'};
  const turn={id:'turn',question:'Explain the entry point',answer:'The entry point reads input. [1]',evidence:[evidence],metadata:{changeAssist:{cards:[{diff:'obsolete patch'}]}},changeAssist:{cards:[{diff:'obsolete patch'}]}};
  const html=renderToStaticMarkup(React.createElement(CodeWorkspace,{showSourceManagement:false,codeConversationTurns:[turn],repositories:[{id:'repo',name:'Example'}]}));
  assert.match(html,/Explain the entry point/);
  assert.match(html,/reads input/);
  assert.match(html,/근거 1개/);
  const {CodeEvidenceList}=await vite.ssrLoadModule('/src/components/code/CodeEvidencePanels.jsx');
  assert.match(renderToStaticMarkup(React.createElement(CodeEvidenceList,{evidence:[evidence]})),/Entry\.java/);
  assert.match(html,/코드 검색/);
  assert.match(html,/정의와 참조/);
  assert.doesNotMatch(html,/obsolete patch|Local Agent|approve CLI|code-agent-panel/);
  const sources=renderToStaticMarkup(React.createElement(CodeWorkspace,{showSourceManagement:true}));
  for(const label of ['Git 저장소 등록','ZIP','로컬']) assert.ok(sources.includes(label),label);
  console.log('RAG workspace and legacy conversation metadata contracts passed');
} finally { await vite.close(); }
