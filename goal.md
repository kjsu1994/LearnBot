# LearnBot 범용 Code RAG 목표

## 완료 기준

- balanced-20 16/20 이상, Java/C# 각각 8/10 이상
- claims, files, symbols, implementation bodies 각각 90% 이상
- citation 100%, hallucination 0건, 성공 요청 P95 60초 이하
- fixture와 겹치지 않는 Java/C# holdout 각각 80% 이상

## 설계 원칙

- fixture ID, 저장소 identity, 질문 문구, 프로젝트 고유 심볼을 production에 넣지 않는다.
- LLM 계획은 가설이며 active index의 typed operation과 Code Intelligence IR로 검증한다.
- 검색·탐색에서 확보한 근거만 답변 컨텍스트까지 손실 없이 전달한다.
- 근거가 부족하면 추측하지 않고 PARTIAL, DISCOVERY 또는 INSUFFICIENT로 답한다.

## 측정 기록

- R37 최고점: 14/20, claims .825, files .983, symbols .850, implementation .771
- R42 clean: 11/20, claims .788, files .917, symbols .800, implementation .704
- R45: 13/20, Java 4/10, C# 9/10, claims .863, files .933, symbols .888, implementation .796
- quota 또는 proxy 오류가 섞인 실행은 공식 점수로 사용하지 않는다.
