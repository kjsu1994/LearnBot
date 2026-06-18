import { answerModes, codeModes, sourceLabels, statusLabels } from '../config/constants.js';

function formatSelectedFiles(files = []) {
  if (!files.length) return '파일 선택';
  if (files.length === 1) return files[0].name;
  return `${files.length}개 파일 선택됨`;
}

function formatBrandText(value) {
  return String(value || '').replace(/LearnBot/g, '런봇');
}

function submitFormOnShortcut(event, canSubmit) {
  if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey) || !canSubmit) {
    return;
  }
  event.preventDefault();
  event.currentTarget.form?.requestSubmit();
}

function getStatusLabel(status) {
  return statusLabels[status] || status || '대기';
}

function getSourceLabel(type) {
  return sourceLabels[type] || type || '문서';
}

function getAnswerModeLabel(mode) {
  return answerModes.find((item) => item.value === mode)?.label || '질문 답변';
}

function getAnswerModeGuide(mode) {
  const guides = {
    qa: {
      title: '질문 답변 예시',
      description: '인덱싱된 문서 전체에서 관련 근거를 찾고, 질문에 맞는 자연어 답변을 받을 때 사용합니다.',
      placeholder: '예: 기간제 단시간 파견 근로자 차별예방을 위해 뭐가 개선되는거야?',
      tips: [
        '규정명, 파일명, 주제어처럼 알고 있는 단서를 같이 적으면 관련 문서를 더 잘 찾습니다.',
        '“왜?”, “어떻게?”, “무엇이 달라져?”처럼 설명형 질문에 적합합니다.',
        '답변 아래의 신뢰도, 진단, 근거 문서를 함께 확인하세요.',
      ],
    },
    summary: {
      title: '요약 질문 예시',
      description: '문서나 주제를 핵심 요점 중심으로 짧게 정리받을 때 사용합니다.',
      placeholder: '예: 기간제·단시간·파견 근로자 차별 예방 가이드라인을 핵심만 요약해줘.',
      tips: [
        '특정 문서를 요약하려면 문서명이나 파일명을 질문에 포함하세요.',
        '“핵심만”, “실무자가 볼 내용”, “결정해야 할 사항”처럼 요약 관점을 적으면 좋습니다.',
        '여러 문서가 있으면 현재 공간의 문서 전체에서 관련 근거를 찾아 요약합니다.',
      ],
    },
    table: {
      title: '표 추출 질문 예시',
      description: '표, 엑셀, 항목 목록, 비교 내용, 인원수나 건수 같은 집계 정보를 구조화할 때 사용합니다.',
      placeholder: '예: 육아기단축근로 대상자는 총 몇 명이고 부서별로 몇 명인지 표로 정리해줘.',
      tips: [
        '“총 몇 명”, “부서별”, “항목별”, “표로 정리”처럼 원하는 구조를 직접 적으세요.',
        '엑셀이나 CSV 집계 질문은 가능한 경우 서버가 전체 청크를 기준으로 계산합니다.',
        'PDF 표는 추출 품질에 따라 행과 열이 깨질 수 있으니 근거 내용을 같이 확인하세요.',
      ],
    },
    quote: {
      title: '원문 인용 질문 예시',
      description: '규정, 지침, 계약 문구처럼 실제 문서 표현을 짧게 확인하고 싶을 때 사용합니다.',
      placeholder: '예: 차별적 처우 금지와 관련된 원문 근거 문구를 인용해서 보여줘.',
      tips: [
        '법령, 규정, 가이드라인 문구를 확인할 때 가장 적합합니다.',
        '“그대로 인용”, “원문 문구”, “근거 조항”처럼 요청하면 인용 중심으로 답합니다.',
        '긴 문서 전체 복사보다 관련 짧은 발췌와 출처 확인에 맞춰져 있습니다.',
      ],
    },
  };
  return guides[mode] || guides.qa;
}

function getCodeModeLabel(mode) {
  if (mode === 'commit') return '커밋 분석';
  return codeModes.find((item) => item.value === mode)?.label || '통합 질문';
}

function getCodeModeGuide(mode) {
  const guides = {
    overview: {
      title: '통합 질문 예시',
      description: '검색, 위치 찾기, 정의/참조, 주변 코드 근거를 함께 사용해 기능의 위치와 동작을 자연어로 설명받을 때 사용합니다.',
      placeholder: '예: 최근 커밋에대해 분석해줘',
      tips: [
        '기능명, 화면명, 버튼명, 에러 문구처럼 사용자가 아는 단서를 자연어로 적어도 됩니다.',
        '어디 있는지와 어떻게 동작하는지를 한 번에 물어보면 관련 파일과 처리 흐름을 함께 정리합니다.',
        '답변의 신뢰도와 근거 부족 여부를 함께 확인하세요.',
      ],
    },
    locate: {
      title: '위치 찾기 질문 예시',
      description: '기능이나 화면이 어느 파일, 클래스, 메서드에 구현돼 있는지 찾을 때 사용합니다.',
      placeholder: '예: GitHub 저장소 인덱싱 실패 사유를 저장하는 로직은 어느 파일과 메서드에 있어?',
      tips: [
        '기능명, 화면명, 버튼명, 에러 메시지 중 아는 단어를 함께 적으세요.',
        '“어디에 있어?”, “어느 파일에서 처리해?”처럼 위치를 직접 물어보면 좋습니다.',
        '파일명을 일부 알고 있으면 같이 적으면 더 정확합니다.',
      ],
    },
    method: {
      title: '메서드 설명 질문 예시',
      description: '특정 메서드가 입력을 받아 어떤 검증, 저장, 호출, 반환을 하는지 설명받을 때 사용합니다.',
      placeholder: '예: CodeIndexingService.startIndex 메서드는 어떤 순서로 인덱싱 작업을 시작해?',
      tips: [
        '클래스명과 메서드명을 같이 적으세요.',
        '입력값, 예외, 부수효과, DB 업데이트 중 궁금한 관점을 덧붙이세요.',
        '정확한 메서드명을 모르면 관련 기능명과 “메서드 설명”이라고 적어도 됩니다.',
      ],
    },
    flow: {
      title: '호출 흐름 질문 예시',
      description: '컨트롤러에서 서비스, 저장소, 외부 API까지 이어지는 실행 순서를 보고 싶을 때 사용합니다.',
      placeholder: '예: 저장소 등록 버튼을 누른 뒤 Git clone, 청크 생성, 임베딩 저장까지 호출 흐름을 설명해줘.',
      tips: [
        '시작 이벤트와 끝 상태를 같이 적으세요.',
        '“A부터 B까지”처럼 범위를 지정하면 흐름이 덜 흩어집니다.',
        'API 경로를 알고 있으면 함께 적으세요.',
      ],
    },
    ui_event: {
      title: 'UI 이벤트 질문 예시',
      description: '화면의 버튼, 입력, 탭 변경이 어떤 핸들러와 API 호출로 이어지는지 추적할 때 사용합니다.',
      placeholder: '예: 코드 RAG 화면에서 실패 사유 버튼을 누르면 어떤 함수와 API가 호출돼?',
      tips: [
        '버튼명, 탭명, 화면명을 그대로 적으세요.',
        '프론트 이벤트와 백엔드 API 연결을 같이 물어보면 좋습니다.',
        'WPF/WinForms/XAML 코드도 컨트롤명이나 이벤트명을 같이 적으면 찾기 쉽습니다.',
      ],
    },
    impact: {
      title: '영향 범위 질문 예시',
      description: '설정, DTO, API, DB 컬럼 변경이 어느 코드와 화면에 영향을 주는지 분석할 때 사용합니다.',
      placeholder: '예: 임베딩 모델명을 바꾸면 영향을 받는 설정, DB 차원, 재인덱싱 코드는 어디야?',
      tips: [
        '바꾸려는 항목과 예상 변경 방향을 같이 적으세요.',
        '확정 근거와 추정 영역을 나눠달라고 요청하면 검토에 유리합니다.',
        '마이그레이션, API 계약, 프론트 요청 필드를 함께 확인시키면 좋습니다.',
      ],
    },
  };
  return guides[mode] || guides.locate;
}

function confidenceClass(value) {
  if (value === '높음') return 'high';
  if (value === '보통') return 'medium';
  return 'low';
}

function formatDate(value) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function jobPercent(job) {
  if (!job?.totalFiles) return job?.status === 'SUCCEEDED' ? 100 : 8;
  return Math.max(5, Math.min(100, Math.round((job.processedFiles / job.totalFiles) * 100)));
}

function jobChangeText(job) {
  const parts = [
    ['추가', job?.addedFiles],
    ['수정', job?.modifiedFiles],
    ['유지', job?.unchangedFiles],
    ['삭제', job?.deletedFiles],
  ]
    .filter(([, value]) => Number(value || 0) > 0)
    .map(([label, value]) => `${label} ${value}`);
  return parts.length ? parts.join(' · ') : '';
}

function getProgressMessage(busy) {
  if (!busy) return '';
  if (busy === 'login') return '로그인 중입니다.';
  if (busy === 'web') return '웹 페이지를 수집하고 임베딩하는 중입니다.';
  if (busy === 'file') return '파일 텍스트를 추출하고 임베딩하는 중입니다.';
  if (busy === 'ask' || busy === 'code-ask') return '근거 검색 후 답변을 생성하는 중입니다.';
  if (busy === 'search' || busy === 'code-search') return '검색 결과를 가져오는 중입니다.';
  if (busy === 'repo-register') return '저장소를 등록하는 중입니다.';
  if (busy === 'repo-zip-upload') return 'ZIP 코드 스냅샷을 업로드하고 인덱싱을 시작하는 중입니다.';
  if (busy.startsWith('repo-zip-replace-')) return '새 ZIP 코드 스냅샷으로 재인덱싱하는 중입니다.';
  if (busy.startsWith('repo-index-')) return '저장소를 동기화하고 코드 청크를 인덱싱하는 중입니다.';
  if (busy.startsWith('repo-cancel-')) return '인덱싱 취소를 요청하는 중입니다.';
  if (busy.startsWith('repo-delete-')) return '저장소를 삭제하는 중입니다.';
  if (busy.startsWith('repo-clear-jobs-')) return '실패/취소 인덱싱 이력을 정리하는 중입니다.';
  if (busy.startsWith('job-failures-')) return '인덱싱 실패 사유를 불러오는 중입니다.';
  if (busy.startsWith('code-file-')) return '코드 파일을 불러오는 중입니다.';
  if (busy.startsWith('reindex-')) return '문서를 재색인하는 중입니다.';
  if (busy.startsWith('delete-')) return '문서를 삭제하는 중입니다.';
  if (busy.startsWith('user-delete-')) return '사용자 계정을 삭제하는 중입니다.';
  if (busy.startsWith('user-update-')) return '사용자 계정을 저장하는 중입니다.';
  if (busy.startsWith('user-password-')) return '사용자 비밀번호를 재설정하는 중입니다.';
  if (busy.startsWith('user-spaces-')) return '사용자 공간 권한을 저장하는 중입니다.';
  if (busy.startsWith('space-update-')) return '공간 정보를 저장하는 중입니다.';
  if (busy.startsWith('space-delete-')) return '공간을 삭제하는 중입니다.';
  if (busy.startsWith('space-export-')) return '공간 RAG 데이터를 ZIP으로 내보내는 중입니다.';
  if (busy.startsWith('space-import-')) return '공간 RAG 데이터를 가져오는 중입니다.';
  if (busy.startsWith('space-download-')) return 'Export ZIP을 다운로드하는 중입니다.';
  if (busy === 'space-create') return '공간을 생성하는 중입니다.';
  if (busy === 'user-invite') return '사용자를 초대하는 중입니다.';
  return '요청을 처리하는 중입니다.';
}

function splitReaderParagraphs(text = '') {
  const normalized = String(text || '').replace(/\r\n/g, '\n').trim();
  if (!normalized) return [];
  const paragraphs = normalized.split(/\n{2,}/).map((item) => item.replace(/\s+/g, ' ').trim()).filter(Boolean);
  if (paragraphs.length > 1) return paragraphs;
  return normalized.split('\n').map((item) => item.trim()).filter(Boolean);
}

function getPreviewTypeLabel(type) {
  const labels = {
    pdf: 'PDF',
    docx: 'DOCX',
    pptx: 'PPTX',
    excel: 'Excel',
    csv: 'CSV',
    markdown: 'Markdown',
    web: 'URL 본문',
    text: 'Text',
  };
  return labels[type] || 'Document';
}

function formatFileSize(value) {
  const bytes = Number(value || 0);
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatTransferCounts(counts = {}) {
  const value = counts || {};
  const parts = [
    ['문서', value.documents],
    ['문서 청크', value.documentChunks],
    ['원문', value.sourceObjects],
    ['코드 저장소', value.codeRepositories],
    ['코드 파일', value.codeFiles],
    ['코드 청크', value.codeChunks],
  ]
    .filter(([, value]) => Number(value || 0) > 0)
    .map(([label, value]) => `${label} ${value}`);
  return parts.length ? parts.join(' · ') : '이관 데이터 없음';
}

export {
  formatSelectedFiles,
  formatBrandText,
  submitFormOnShortcut,
  getStatusLabel,
  getSourceLabel,
  getAnswerModeLabel,
  getAnswerModeGuide,
  getCodeModeLabel,
  getCodeModeGuide,
  confidenceClass,
  formatDate,
  jobPercent,
  jobChangeText,
  getProgressMessage,
  splitReaderParagraphs,
  getPreviewTypeLabel,
  formatFileSize,
  formatTransferCounts,
};
