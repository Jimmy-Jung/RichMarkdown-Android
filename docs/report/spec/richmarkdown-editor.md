# richmarkdown-editor 명세

기준일: 2026-10-08 · 현재 소스 확인 · 0.3.0 배포 모듈 · 실행 결과는 [검수 기록](../validation.md#2026-10-08-030-릴리스-검증)

[모듈 설정](../../../richmarkdown-editor/build.gradle.kts)은 네임스페이스 `io.github.jimmyjung.richmarkdown.editor`, minSdk 30, compileSdk 37을 선언하고 `richmarkdown`을 `api`로 노출합니다. 배포 좌표는 [모듈 속성](../../../richmarkdown-editor/gradle.properties)의 `io.github.jimmy-jung:richmarkdown-editor`이며, 0.3.0 배포 파일 생성·대조 결과는 [검수 기록](../validation.md#2026-10-08-030-릴리스-검증)에 있습니다. 구조와 흐름은 [아키텍처](../architecture/richmarkdown-editor.md)에서 확인할 수 있습니다.

모든 범위는 Kotlin `String`과 같은 UTF-16 단위의 `[location, location + length)`입니다. 아래 요구 사항은 현재 소스의 동작을 설명하며, 실제 실행 통과는 검수 기록과 구분합니다.

## 공개 진입점

| API | 입력·기본값·책임 |
| --- | --- |
| `BlockEditorModel(markdown)` / `BlockEditorModel(blocks)` | Markdown을 블록으로 나눠 파싱하거나 블록 목록으로 시작합니다. 가변 클래스이며 동기화하지 않으므로 한 스레드(보통 메인)에서만 사용합니다. |
| `BlockEditorModel` 읽기 | `blocks`, `documentText`, `markdown`, `currentSelection`, `currentDocumentSelection`, `canUndo`, `canRedo`, `block(id)`, `documentRange(…)`, `blockSelection(range)`, `numberedListOrdinal(id)`를 제공합니다. |
| `BlockEditorModel` 편집 | `replaceDocumentText`, `replaceDocumentBlocks`, `updateText`, `replaceText`, `splitBlock`, `insertSoftBreak`, `backspaceAtStart`, `insert`, `duplicate`, `delete`, `transform`, `indent`, `outdent`, `moveUp`, `moveDown`, `move`, `applyInlineFormat`, `applyShortcut`, `undo`, `redo`를 제공합니다. 실패하면 null 또는 `false`입니다. |
| `EditorBlock` | `id`·`kind`·`text`·`inlineMarks`·`indentLevel`을 가진 불변 값입니다. `fromMarkdown`으로 한 블록을 파싱하고 `markdown`으로 직렬화합니다. 같은 문서의 ID 중복 방지는 앱 책임입니다. |
| `EditorBlockKind` | `Paragraph`, `Heading(level)`, `BulletedList`, `NumberedList`, `ToDo(isChecked)`, `Quote`, `Code(language)`, `Equation`입니다. 표시용 이름은 제공하지 않습니다. |
| `EditorRange`, `BlockSelection`, `InlineFormat`, `InlineMark` | 범위, 블록 안 선택, 서식 네 종류(`Bold`·`Italic`·`Strikethrough`·`Code`), 서식 범위입니다. `InlineFormat` 선언 순서는 payload의 숫자 값이므로 바꾸지 않습니다. |
| `InlineMarkdownCodec` | `parse`, `serialize`, `normalized`를 제공하는 인라인 Markdown 코덱입니다. |
| `BlockDocumentPasteboardPayload` | `encode(blocks): ByteArray?`, `decode(data): List<EditorBlock>?`입니다. 저장 형식이나 서버 API가 아닙니다. |
| `BlockDocumentEditText` | `setState(blocks, selection, canUndo, canRedo, parsesDollarMath = false, theme = Default, alignment = Default, sourceMarkdown = null)`, `performToolbarAction`, 콜백 `onReplaceText`·`onSelectionChange`·`onToolbarAction`·`onReplaceDocumentBlocks`, `inputAccessory`를 제공합니다. 기본 여백은 좌·위·우 16dp, 아래 96dp입니다. |
| `compose.BlockDocumentTextEditor` | 위 `setState` 인자와 콜백을 Compose 매개변수로 받습니다. `modifier`, `onReplaceDocumentBlocks = null`, `parsesDollarMath = false`, `theme`, `blockAlignment`, `sourceMarkdown = null`, `inputAccessory = null`이 선택 인자입니다. |
| `MarkdownStyler`, `BlockAlignmentConfiguration` | 블록을 서식 있는 문자열로 바꾸는 함수와 코드(기본 시작 정렬)·수식(기본 가운데) 블록 정렬 설정입니다. |
| `EditorToolbarAction`, `BlockEditorInputAccessory` | 도구 모음 명령 12종과 `update(kind, canUndo, canRedo)`·`bind(perform)` 계약입니다. |

## 모델과 코덱 요구

| ID | 요구와 현재 구현 | 근거·테스트 |
| --- | --- | --- |
| AE-01 | 공개 API는 음수·문서 밖·정수 범위를 넘는 범위를 거절합니다. 끝 위치를 더하기 전에 위치와 길이를 검사합니다. | `TextBoundaries.kt`; `invalidPublicEditRangesAreRejected`, `invalidRangesAreRejected` |
| AE-02 | 시작이나 끝이 서로게이트 쌍 가운데인 범위는 거절합니다. iOS는 내림 보정합니다. | `containsScalarAligned`; `surrogateMiddleRangesAreRejected` |
| AE-03 | 블록 분할과 바로가기 접두어 제거는 `BreakIterator` 문자 묶음 경계에서만 허용합니다. | `isGraphemeBoundary`; `graphemeBoundariesGateSplitAndShortcut` |
| AE-04 | 블록은 생성과 `copy`마다 제목 수준 1~3, 들여쓰기 0~3으로 맞추고 잘못되거나 빈 서식 범위를 버립니다. | `EditorBlock.kt`; `directIndentValuesAreClamped`, `directHeadingValuesAreClamped`, `invalidInlineMarkRangesAreIgnored` |
| AE-05 | `documentText`는 블록 본문을 `\n`으로 잇고 문서 끝에 빈 문단을 항상 둡니다. 코드·수식 외 블록의 줄바꿈은 블록 경계로 펼칩니다. | `normalized`; `projectsBlocksIntoContinuousDocument`, `initialTextNormalizesLineBreaksByBlockKind`, `distinguishesBlockBreakFromCodeSoftBreak` |
| AE-06 | `replaceDocumentText`는 Enter를 분할로, 블록 시작 Backspace를 문단이 아닌 블록의 문단 변환·앞 블록과 병합·앞 코드/수식 블록 끝으로 caret 이동으로 환원합니다. 빈 블록 Enter는 문단으로 바꾸고 이미 빈 문단이면 변경하지 않습니다. 전체 문서 교체는 Markdown을 해석하지 않습니다. | `replaceDocumentText`; `replacesAcrossBlockBoundaries`, `documentEnterExitsEmptyList`, `documentBackspaceUsesBlockBoundaryRules`, `emptyParagraphEnterIsNoOp`, `wholeDocumentPlainTextReplacementDoesNotParseMarkdown` |
| AE-07 | Markdown 입력과 `replaceDocumentText`의 교체 문자열에 있는 CRLF·CR은 LF로 바꿉니다. | `normalizingLineEndings`; `lineEndingsAreNormalizedToLineFeed` |
| AE-08 | `markdown`은 같은 깊이의 연속 번호 항목마다 번호를 다시 매기고, 문단 첫 블록 문법 기호를 이스케이프하며, 코드 fence를 본문의 가장 긴 백틱 연속보다 길게 씁니다. 기울임은 `<em>`으로 직렬화합니다. 원래 마커 표기·빈 문단 개수·UUID는 보존하지 않습니다. | `EditorBlock.markdown`; `documentMarkdownRestoresToDoAndRenumbersLists`, `literalParagraphMarkersRoundTrip`, `codeFenceContentsRoundTrip`, `ambiguousDocumentMarkdownRoundTrip` |
| AE-09 | 코덱은 인라인 수식 구간(`\(...\)`, `$...$`, `$$...$$`) 안의 `*`·`_`를 서식으로 읽지 않습니다. 인라인 코드와 겹치는 다른 서식은 잘라 냅니다. 서식 범위는 UTF-16입니다. | `InlineMarkdownCodec.kt`; `keepsInlineLatexAsPlainText`, `inlineCodeClipsOverlappingMarks`, `storesInlineMarkRangesAsUTF16Offsets`, `parsesMarkdownInlineMarkersIntoSemanticRanges` |
| AE-10 | `applyInlineFormat`은 범위 전체가 이미 같은 서식이면 그 범위만 해제하고, 아니면 추가합니다. 코드·수식 블록과 빈 범위는 null입니다. | `applyInlineFormat`; `inlineFormatToggleSubtractsSelectedRange` |
| AE-11 | 정리한 블록 목록이 실제로 바뀐 편집만 실행 취소 기록에 남기며 기록은 최대 100개입니다. 새 편집은 다시 실행 기록을 지우고, 실행 취소·다시 실행은 여러 블록에 걸친 문서 선택도 복원합니다. 연속 입력을 하나로 합치지 않습니다. | `apply`, `undo`, `redo`; `historyKeepsAtMostHundredEntries`, `editCommandsAreUndoable`, `textInputParticipatesInHistory`, `historyRestoresCrossBlockDocumentSelection` |
| AE-12 | 끝 빈 문단은 이동 대상이나 목적지가 아닙니다. `insert(after = null)`은 끝 빈 문단 앞에 넣습니다. | `moveUp`, `moveDown`, `insert`; `moveBoundaries` |
| AE-13 | payload는 version 1 JSON이며 블록이 없거나 256 KiB를 넘으면 `encode`가 null입니다. `decode`는 크기·UTF-8·JSON 문법·중첩 깊이 16·version·필수 필드를 검사하고, 제목·들여쓰기를 맞추고 서식을 정리하며 블록마다 새 UUID를 만듭니다. | `BlockDocumentPasteboardPayload.kt`; `encodesSwiftCodableShape`, `roundTripPreservesKindsTextMarksAndIndentWithNewIds`, `decodeClampsAndNormalizesTamperedPayload`, `rejectsInvalidPayloads` |

## 편집 뷰 요구

| ID | 요구와 현재 구현 | 근거·테스트 |
| --- | --- | --- |
| AE-14 | 스타일링한 문자열은 `documentText`와 같습니다. 채움 문자를 넣지 않고 수식은 원문 위 `ReplacementSpan`으로 표시합니다. | `MarkdownStyler.kt`; `styledStringEqualsDocumentTextForVariedDocument`, `styledDocumentPreservesOffsetsAndUsesTextLists` |
| AE-15 | 사용자 편집은 마지막으로 그린 문자열 기준의 교체 하나로 바꿔 `onReplaceText`에 전달합니다. 기록된 편집 범위나 조합 시작 범위를 우선하고, 없으면 서로게이트 쌍을 가르지 않는 비교를 사용합니다. | `DocumentEdits.kt`; `coordinatorUsesTextViewEditRange`, `anchoredReplacementKeepsAmbiguousInsertionPoint`, `diffNeverSplitsSurrogatePairs` |
| AE-16 | 편집 콜백 뒤에는 모델이 편집을 무시하거나 변형해도 앱의 최신 상태로 다시 그립니다. View 호스트는 콜백 안 `setState`, Compose 래퍼는 다음 재구성의 `setState`로 맞춥니다. | `reconcile`, `BlockDocumentTextEditor`; `resyncsAfterIgnoredOrTransformedEdits`, `composeWrapperResyncsAfterIgnoredAndAcceptedEdits`, `backspaceAfterRenderedInlineMathMatchesModel` |
| AE-17 | IME 조합 중에는 모델 알림·다시 그리기·문자열 교체를 하지 않고, 조합이 끝나면 조합 시작 범위로 한 번 전달합니다. | `syncFromView`, `ReconcilingInputConnection`; `markedTextCommitsOneReplacement`, `markedTextPreservesAmbiguousInsertionRange` |
| AE-18 | 조합 중 `performToolbarAction`은 조합을 확정해 한 번 전달하고 IME를 다시 시작한 뒤 확정 뒤 선택으로 명령을 보냅니다. 기존 단어를 다시 조합하는 중이면 교체 없이 명령만 보냅니다. `Done`은 포커스를 놓고 키보드를 닫습니다. | `performToolbarAction`; `toolbarCommitsCompositionOnceThenUsesCommittedSelection`, `toolbarDuringRecomposedExistingWordSendsNoReplacement` |
| AE-19 | 문서 밖 선택은 문서 안으로 제한합니다. 선택이 닿은 수식 블록·인라인 수식은 원문으로 표시하고, 그 안의 선택을 문자 묶음 경계로 맞춥니다. | `sourceAlignedSelection`; `coordinatorClampsOverflowingSelections`, `equationSourceTransitionAlignsUnicodeCaret`, `equationEditingRangeAndSourceAlignment`, `invalidStylerSelectionsAreIgnored` |
| AE-20 | 블록 종류별 글꼴·색·들여쓰기·목록 번호·체크박스·인용 바를 적용하고, 테마·다크 모드·글꼴 배율·정렬 설정을 따릅니다. 달러 수식 인식은 `parsesDollarMath`로 켭니다. | `MarkdownStyler.kt`, `EditorSpans.kt`; `nestedNumberedListsShowTheirOwnOrdinals`, `semanticInlineMarksDriveAttributes`, `themeChangesEditorTypographyAndColor`, `blockAlignmentIsInjectable`, `quoteKeepsBodyColorAndGetsBarAttribute`, `indentLevelsDriveLeadingMargin`, `dynamicTypeScalesMonospacedFonts`, `dollarMathRenderingIsOptInAndPreservesOffsets`, `darkModeRecolorsDocument` |
| AE-21 | 인라인 수식은 렌더러와 같은 규칙으로 찾고 인라인 코드 안은 제외합니다. 블록 본문이 UTF-8 256 KiB를 넘으면 인라인 수식을 찾지 않습니다. 캐시에 없는 수식은 원문으로 보여 주고 화면 연결 중에 요청합니다. 블록 수식 비트맵은 텍스트 폭에 맞춰 줄입니다. | `inlineMathSpans`, `requestMath`, `DisplayMathSpan`; `inlineMathUsesCanonicalScannerAndSurroundingFont`, `codeStaysLiteralAndEquationUsesAttachment`, `equationAttachmentMaterializesInLayout`, `displayEquationAttachmentExpandsTextLine`, `missingInlineMathIsRenderedAfterAttach` |
| AE-22 | 전체 선택 복사는 일반 텍스트 `sourceMarkdown`과 블록 payload를 함께 싣습니다. 붙여넣기는 payload를 우선 복원하고, 처리하지 못하면 일반 텍스트로 붙여넣습니다. | `copyBlockDocument`, `pasteBlockDocument`; `clipboardPayloadRoundTripsBlocks` |
| AE-23 | Ctrl+Z, Ctrl+Shift+Z, 텍스트 메뉴의 실행 취소·다시 실행은 `EditorToolbarAction.Undo`·`Redo`로 앱에 전달합니다. | `onKeyShortcut`, `onTextContextMenuItem`; `undoShortcutsAndMenuRouteToToolbarActions` |
| AE-24 | `inputAccessory`를 지정하면 `bind`로 명령 전달 함수를 넘기고, 선택 블록이나 실행 취소 가능 여부가 바뀔 때 `update`를 호출합니다. | `updateAccessory`; `inputAccessoryTracksActiveBlock` |
| AE-25 | 수식 이미지 요청은 화면 연결 동안만 진행하고 분리되면 취소합니다. 텍스트는 인스턴스 상태로 저장하지 않으며, Compose 영구 폐기 때 앱 콜백 연결을 끊습니다. | `onAttachedToWindow`, `onDetachedFromWindow`, `getFreezesText`, `releaseHost`; 분리·폐기 경로의 전용 테스트는 없습니다. |

## 앱 책임

- 앱은 `BlockEditorModel`을 소유합니다. View 호스트는 편집 콜백에서 모델을 바꿨으면 같은 호출 안에서 `setState`로 새 상태를 넘깁니다.
- 도구 모음 명령의 실행은 앱이 연결합니다. 데모의 [BlockEditorActions.kt](../../../demo/src/main/kotlin/io/github/jimmyjung/richmarkdown/demo/BlockEditorActions.kt)가 iOS 데모의 `perform`에 대응하는 예입니다.
- 도구 모음 버튼은 `bind`로 받은 함수를 호출해야 합니다. 그래야 편집기가 IME 조합 확정과 현재 선택 첨부를 처리합니다.
- 화면 회전이나 프로세스 재생성 뒤 문서를 유지하려면 앱이 `model.markdown` 등으로 저장하고 복원합니다.
- 렌더 모듈의 내부 API를 공유하므로 `richmarkdown`과 `richmarkdown-editor`는 같은 버전을 함께 사용합니다.

## 근거와 실행 확인

[JVM 테스트](../../../richmarkdown-editor/src/test/)는 `BlockEditorModelTest` 31개, `BlockMarkdownCodecTest` 16개, `AndroidPortContractTest` 7개, `BlockDocumentPasteboardPayloadTest` 4개, `DocumentEditsTest` 4개입니다. 앞의 두 파일은 iOS 모델·코덱 테스트를 옮긴 것입니다. [Android 테스트](../../../richmarkdown-editor/src/androidTest/)는 `MarkdownStylerTest` 13개, `BlockDocumentEditTextTest` 14개, `BlockDocumentTextEditorComposeTest` 3개입니다.

2026-10-08에 위 테스트를 모두 실행해 통과했습니다. 실행 환경, iOS 소스와의 차등 비교, 확인하지 않은 범위는 [검수 기록](../validation.md#2026-10-08-블록-편집기-추가-검증)에 있습니다.
