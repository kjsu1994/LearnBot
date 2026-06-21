export const tokenKey = 'runbot.session.token';
export const defaultSpaceId = '00000000-0000-0000-0000-000000000001';
export const evidencePreviewLimit = 3;

export const routePaths = {
  home: '/app',
  code: '/code',
  docs: '/docs',
  saved: '/saved',
  admin: '/admin',
};

export const sourceLabels = {
  FILE: '파일',
  WEB: '웹',
};

export const statusLabels = {
  INDEXING: '인덱싱 중',
  SEARCHABLE: '검색 가능',
  READY: '준비 완료',
  PARTIAL: '부분 완료',
  INDEXED: '인덱싱 완료',
  PENDING: '대기',
  FAILED: '실패',
  PROCESSING: '처리 중',
  RUNNING: '실행 중',
  CANCELLING: '취소 중',
  CANCELLED: '취소됨',
  SUCCEEDED: '완료',
  ACTIVE: '활성',
  DELETED: '삭제됨',
};

export const answerModes = [
  { value: 'qa', label: '질문 답변' },
  { value: 'summary', label: '요약' },
  { value: 'table', label: '표 추출' },
  { value: 'quote', label: '원문 인용' },
];

export const documentSpeedProfiles = [
  { value: 'balanced', label: 'BALANCED' },
  { value: 'fast', label: 'FAST' },
  { value: 'deep', label: 'DEEP' },
];

export const codeModes = [
  { value: 'overview', label: '통합 질문' },
  { value: 'locate', label: '위치 찾기' },
  { value: 'method', label: '메서드 설명' },
  { value: 'flow', label: '호출 흐름' },
  { value: 'ui_event', label: 'UI 이벤트' },
  { value: 'impact', label: '영향 범위' },
];
