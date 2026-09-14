# RAG 전용 전환 실행 기록

## 기준선

- 소스: 3c4f1b9; 변경 전 ZIP을 .tmp/quality/rag-only/baseline-3c4f1b9.zip에 보존.
- ZIP 평가 저장소 d1640b46-beb7-4ef8-94bf-620a254936f1 색인 SUCCEEDED: 808/808 files, failed 0. 기존 사용자 저장소는 재색인하지 않음.
- Gemini proxy 주·보조 gemini-3.1-flash-lite로 전후 측정. 클라우드 모델의 불변 revision은 공급자가 노출하지 않으므로 모델 alias·proxy hash·설정·색인 동일성만 확인 가능.
- 제거 전 backend: 1008 tests, failures 4, errors 3, skipped 9. 원본 보고서는 .tmp/quality/rag-only/backend-before-reports.

## 진행

- 공유 RAG controller/hook/UI에서 에이전트 의존성을 분리함. frontend build 통과.
- 전용 코드/테스트/설치 스크립트 462개 삭제. 소스 하위 빌드 잔여물 약 678MB 정리. DB migration과 배포 패키지는 보존.
- 일반 LAN 초기화 도구와 up.ps1 -Lan 지원 추가.
- 비회귀 비교 모드 추가: 고정 identity, 문항 누락/skip, claims/files/symbols/bodies, P95 검사. 비교기 테스트 통과.
- clean backend: 621 tests, failure 1/error 3/skip 0. 제거 전부터 존재한 OllamaClientTest 4건과 동일; 새 실패 없음.
- frontend build, RAG 화면/기존 metadata/라우팅 테스트 통과. 통합 harness는 기존 OllamaClientTest 실패 때문에 전체 gate 실패(통과로 처리하지 않음).
- 로컬 before 측정은 요청 12건 중 HTTP 200 8건, 120초 취소(499) 4건을 확인하고 중단. 완주 점수/유효 품질 기준선으로 채택하지 않음. 기록: .tmp/quality/rag-only/before-live.log, before-identity.json.
- 최종 의존성 정리 후 backend 재실행도 621개 중 동일 기존 실패 4건. GPU 설정 확인. 웹 로그인/코드/문서 화면 확인, 콘솔 오류 없음. LAN 설정 계약 테스트 통과.
- backend/nginx 이미지 빌드 완료. 빈 임시 DB Flyway 37개 적용, 웹 인증/갱신/로그아웃/RAG 조회/폐기 API 404 통과. Roslyn SAFE_PROJECT + 관계 6종 통과. nginx SPA 200/폐기 다운로드 404 통과. 임시 컨테이너 제거 완료.
- Gemini 키 및 backend→proxy 연결 확인, 주·보조 모델 전환 완료. 임베딩 설정 유지. 제거 전 기준선 측정을 마친 뒤 새 이미지로 교체함.
- Compose/application.yml/기동 설정에서 Agent 전용 키 제거 확인. 옛 로컬 Agent LAN env/policy는 일반 .env.lan-http 및 deploy/lan-http.generated.inc로 이전 후 삭제. 기본/GPU/LAN Compose 구문 및 LAN 계약 테스트 통과. 새 실행 컨테이너에서도 Agent 키와 다운로드 마운트 제거 확인.

## 남은 검증

- 회귀 원인 조사: .tmp/quality/rag-only/regression-diagnosis.md. 확인된 별도 문제는 fixture 검색 오염, C# 계획/근거 누락, 내부 ServiceUnavailable, HTTP 200 답변 대체. 최종 생성 예외는 기존 capture가 diagnostics를 버려 소급 확정 불가. capture에 diagnostics 보존을 추가하고 mock live/비회귀 비교 테스트 통과. production RAG·채점 기준은 변경하지 않음. 품질 복구는 아직 미검증.

1. Gemini 제거 전 balanced-20 완료: 14/20, skipped 0, claims .888 / files .900 / symbols .833. 보고서: .tmp/quality/rag-only/gemini-before-report.json.
2. 2026-09-14 22:15 KST 새 backend(025ee7425241)/nginx(f38eea1e3af6) 배포 완료. 실제 Agent 환경변수 0개·다운로드 마운트 제거 확인. GPU Ollama는 재시작하지 않음. 기존 DB 37 migrations 검증, 추가 migration 없음. 웹 인증/갱신/로그아웃/RAG 조회/폐기 API 404 검증 통과.
3. 제거 후 balanced-20 22:51 KST 완료: 11/20, skipped 0. 전후 identity 동일, 120초 timeout/문항 간 60초 유지. 비회귀 비교 실패: 통과→실패 4건, 실패→통과 1건. claims .888→.817 / files .900→.867 / symbols .833→.800 / bodies .808→.742. 결과: .tmp/quality/rag-only/gemini-comparison.json. 원인은 아직 확정되지 않았으며 Agent 제거 영향 또는 모델 변동으로 단정하지 않음. 추가 코드 수정/재실행 전에 실패 capture와 실행 경로 근거 분석 필요. 새 버전은 배포되어 있으나 Live 비회귀 목표는 미달성.
