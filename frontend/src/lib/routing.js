import { routePaths } from '../config/constants.js';

function normalizeRoute(pathname = '/') {
  const cleanPath = String(pathname || '/').replace(/\/+$/, '') || '/';
  if (Object.values(routePaths).includes(cleanPath)) {
    return cleanPath;
  }
  return routePaths.home;
}

function routeToView(pathname) {
  if (pathname === routePaths.docs) return 'docs';
  if (pathname === routePaths.admin) return 'admin';
  return 'code';
}

const sourceLabels = {
  FILE: '파일',
  WEB: '웹',
};

const statusLabels = {
  INDEXING: '인덱싱 중',
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

const answerModes = [
  { value: 'qa', label: '질문 답변' },
  { value: 'summary', label: '요약' },
  { value: 'table', label: '표 추출' },
  { value: 'quote', label: '원문 인용' },
];

const codeModes = [
  { value: 'overview', label: '통합 질문' },
  { value: 'locate', label: '위치 찾기' },
  { value: 'method', label: '메서드 설명' },
  { value: 'flow', label: '호출 흐름' },
  { value: 'ui_event', label: 'UI 이벤트' },
  { value: 'impact', label: '영향 범위' },
];

export { normalizeRoute, routeToView };
