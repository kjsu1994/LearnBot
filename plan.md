# Code RAG 실행 계획

## 현재 상태

- 공식 최신 점수는 R45 balanced-20 13/20이며 목표는 16/20이다.
- R45에서 R42 통과 사례 중 Local Agent claim flow와 C# `DoWork`가 회귀했고, symbol-reference 증거도 약화됐다.
- `DoWork`는 정확한 구현 본문을 검색·선택한 뒤 `OVERVIEW` 모드의 620자 제한으로 잘린 것이 원인이었다.
- 두 Java 흐름은 관찰된 복합 callable 검색이 질문 원문 앵커 계약을 우회해 잘못된 초기 경로를 고정한 것이 공통 원인이었다.
- R46 후보는 질문에 명시된 1순위 direct callable 본문을 예산 안에서 보존하고, 관찰된 callable 검색을 질문 앵커 뒤의 최대 1개 companion으로만 허용한다.
- fixture·저장소·프로젝트·질문별 production 규칙은 없다. 모두 query-time 변경이므로 재색인은 필요하지 않다.
- Code RAG 전체 280/280 통과. backend 전체 1,010건에서 기존 Ollama/Local Agent 7건 외 새 실패가 없고 9건은 skip이다.
- GPU backend 재빌드·재시작 완료. RTX 5060, proxy, LAN API가 정상이며 두 active index version·fingerprint·chunk 수는 R45와 동일하다.

## 다음 순서

1. 회귀 3건과 기존 통과 통제군을 focused Live E2E로 비교한다.
2. focused에서 회귀가 없을 때 balanced-20을 실행하고 목표 대비를 판정한다.
