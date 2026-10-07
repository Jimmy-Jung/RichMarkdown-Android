# 보고서 시각자료

기준일: 2026-10-06

구조·호출 순서는 문서 안의 Mermaid로, 시간과 UTF-16 단위는 Excalidraw 개념도로 설명합니다.
아래 그림은 현재 Android 구현을 설명하며 Mermaid 그림은 최신 요청 ID를 확인한 뒤 화면에 반영하는 조건을 보여 줍니다. 그림에 남은 코드 식별자와 기술 용어는 [용어 안내](../glossary.md)에서 확인할 수 있습니다.

| 그림 | 핵심 주장 | SVG | 편집 원본 |
| --- | --- | --- | --- |
| 대기 요청 합치기 | 실행 하나와 가장 최근에 들어온 대기 입력 하나를 유지합니다. | [그림](core-coalescing.svg) | [원본](excalidraw/core-coalescing.excalidraw.md) |
| 최신 결과만 반영 | 이전 결과는 현재 화면에 반영하지 않습니다. | [그림](render-latest-wins.svg) | [원본](excalidraw/render-latest-wins.excalidraw.md) |
| UTF-16 범위 | `A😀B`의 이모지는 두 코드 단위를 차지하며 `[1,3)`으로 표현합니다. | [그림](highlight-utf16.svg) | [원본](excalidraw/highlight-utf16.excalidraw.md) |
| Mermaid 취소 | 표시 전 임시 영역에서 계산하고 최신 요청 ID의 웹 문서 구조(DOM)·높이만 반영합니다. | [그림](mermaid-request-cancellation.svg) | [원본](excalidraw/mermaid-request-cancellation.excalidraw.md) |

## 재생성

[build_diagrams.py](excalidraw/build_diagrams.py)는 `excalidraw-concept-diagrams`의 `slide_kit`와
`technical-document-writing`의 SVG 내보내기 도구를 사용합니다. 설치 경로는 환경 변수로 전달하며,
원본과 SVG는 스킬 없이 읽거나 편집할 수 있습니다.

```sh
PYTHONDONTWRITEBYTECODE=1 \
PYTHONPATH="$EXCALIDRAW_SKILL_ROOT/scripts" \
TECH_DOC_SKILL_ROOT="$TECH_DOC_SKILL_ROOT" \
python3 docs/report/assets/excalidraw/build_diagrams.py \
  --platform android --output-dir "$DOC_RENDER_OUTPUT"
```

`DOC_RENDER_OUTPUT`은 프로젝트 밖의 검증용 저장소로 지정합니다. 저장소 정책이 있으면 먼저
확인하며, `TMPDIR`·npm cache도 그 정책에 맞게 지정합니다. 중간 SVG와 검수 PNG는 출력 경로
아래에 두고 완성 SVG만 문서의 `assets/`에 복사합니다.
내보내기 도구는 `@moona3k/excalidraw-export@0.2.1`이며 최초 실행 시 내려받을 수 있습니다.

각 편집 원본의 Visual Brief는 그림의 핵심 주장, 시간 범위, 주체를 적은 설명이며 가상 예시의 범위도 명시합니다. PNG에서 겹침을 확인하고, 투명 SVG가 실제 문서 미리보기의 다크 배경에서도 읽히는지는 별도로 확인합니다.
