export const tokenKey = 'runbot.session.token';
export const defaultSpaceId = '00000000-0000-0000-0000-000000000001';
export const evidencePreviewLimit = 3;

export const routePaths = {
  home: '/app',
  login: '/login',
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
  { value: 'overview', label: '통합 질문', description: '기능 구조, 여러 파일의 관계, 구현 의도처럼 범위가 넓은 질문에 사용합니다.' },
  { value: 'reasoning', label: '구현 의도', description: '왜 이렇게 구현됐는지, 설계상 이유와 코드 근거를 분리해 검토할 때 사용합니다.' },
  { value: 'locate', label: '위치 찾기', description: '특정 기능, 화면, API, 설정이 어느 파일과 라인에 구현되어 있는지 찾을 때 사용합니다.' },
  { value: 'method', label: '메서드 설명', description: '특정 함수나 클래스가 무슨 일을 하고 입력/출력/예외가 어떻게 흐르는지 설명할 때 사용합니다.' },
  { value: 'flow', label: '호출 흐름', description: '한 기능이 어떤 메서드들을 거쳐 실행되는지 호출 순서와 의존 관계를 추적할 때 사용합니다.' },
  { value: 'ui_event', label: 'UI 이벤트', description: '버튼 클릭, 폼 제출, 화면 상태 변경이 프론트에서 백엔드까지 어떻게 이어지는지 볼 때 사용합니다.' },
  { value: 'impact', label: '영향 범위', description: '코드를 수정했을 때 영향을 받을 파일, 메서드, 화면, 테스트 범위를 파악할 때 사용합니다.' },
];
