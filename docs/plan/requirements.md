# Объём и прослеживаемость требований

Дата: 2026-11-05. Реализация: Kotlin/Android и независимые Gradle-модули с общим core. Приоритет зафиксирован пользователем: Android; при расхождениях — `truffnotes.md`. Ссылки: [план](../superpowers/plans/2026-11-05-truffnotes-android.md), [архитектура](../architecture/android-workspace.md), [правила ИИ](ai-execution.md).

## 1. Правила разрешения расхождений

- `truffnotes.md` задаёт продукт, обязательные возможности, Android-only и явные исключения. Goodnotes — детализация совместимых инструментов и сценариев, а не обязательная копия всех платформ, подписок и сервисов другого продукта.
- Поэтапная доступность/Pro/лимит 5 layers/отсутствие Zoom Window в Android-версии Goodnotes не ограничивают Inkspace: его собственное ТЗ прямо требует layers и Zoom Window.
- Полные группы, блокировка слоёв, Object Eraser и геометрические инструменты берутся из `truffnotes.md`, даже если Goodnotes их ограничивает.
- Рукопись остаётся editable vector data. Выражение «работа с формулами» означает обычные штрихи и элементы, а не Convert to Math.
- «Обвести и удержать» конкретизировано совместимым описанием Goodnotes: закончить окружность, поднять перо, затем удержать её линию. Draw-and-hold на конце штриха — другой жест. Они не должны одновременно срабатывать на одну последовательность ввода.
- P0/P1 в исходнике местами спорят с фразой «обязательный инструмент основного интерфейса». План допускает раннюю внутреннюю сборку без Zoom Window/групп, но **первая принятая пользовательская версия** требует F15 и весь gate G1.
- Все P0–P3 из `truffnotes.md` имеют фазы. P3 file sync остаётся опциональным для пользователя, но его реализация входит в полный план. Сроки/обещания магазинов Goodnotes не воспроизводятся.

Text Document, Study Sets и сетевое совместное редактирование с обсуждениями — самостоятельные подсистемы, отсутствующие в выбранном ТЗ Inkspace. В этом плане они имеют явный статус `X`, а не притворную реализацию через Textbox/Elements/SAF. Они потребуют отдельного изменения объёма; никакого CRDT-задела в ядре ради них не создаётся. Совместное открытие двух viewport одним пользователем входит в план.

## 2. Полный набор требований Inkspace

`Fxx` — owning phase; дополнительные фазы подключают UI/проверку. Для строк с перечнем gate требует **все** перечисленные возможности. Развёрнутые независимые acceptance cases создаются owning phase в `docs/evidence/Fxx/cases.json`, с IDs вида `R07.segment`, без удаления исходных подпунктов.

| ID | Источник `truffnotes.md` | Обязательный результат | Владелец / приёмка |
|---|---|---|---|
| R01 | 1, 4.1–4.2 | Android API 29+, планшеты нескольких производителей, офлайн без аккаунта, активный/пассивный стилус и палец | F00, F04, F23 |
| R02 | 2.1, 3.1–3.3 | Goodnotes-style основной UI; Documents/Grid/List/Favorites/Recent/Templates/Trash; New Notebook первым; обложка, бумага, формат/ориентация | F12 |
| R03 | 3.4–3.5 | Вкладки, reorder/close/reopen/restore; navigation/search/readonly/add/bookmark/export/More | F12, F17 |
| R04 | 3.6–3.7 | Инструменты и single/repeat/long press; независимые настройки/пресеты; порядок/размер/compact; dock top/bottom и сохранение по ориентации | F12, F15 |
| R05 | 3.8–3.12 | Вертикальная/горизонтальная прокрутка, Edit/Read Only, Focus, portrait/landscape, сохранение позиции и выделения | F04, F12, F15 |
| R06 | 4.3–4.4 | MotionEvent history/axes/pointer IDs, hover/buttons; CANCEL/FLAG_CANCELED; palm rejection и stylus-only | F06 |
| R07 | 6.1–6.4 | Editable stroke, Ball/Fountain/Pencil/Brush/Highlighter, pressure/tilt/ends/opacity/smoothing/patterns, сохранение углов | F06, F15 |
| R08 | 7 | Segment/Stroke/Object eraser, размер/shape/filter/active or visible layers/temporary button | F07, F15 |
| R09 | 8 | Freehand/rect/add/subtract/full/intersection selection; move/scale/rotate/flip/style/copy/cut/paste/duplicate/delete/z-order/layers | F07 |
| R10 | 8.3 | Nested groups, edit inside group, group/ungroup, единый transform без двойного применения | F07 |
| R11 | 9 | Line/arrow/rect/square/circle/ellipse/triangle/polygon/polyline/curve/arc, editing vertices/fill/arrows; hold-to-shape; connectors | F08 |
| R12 | 9.4, 5.6 | Ruler/protractor/guides/grids/snap angle/object alignment; пустой/линейка/клетка/точки/mm/isometric/custom | F08, F09, F19 |
| R13 | 10 | Scribble erase, Circle to Lasso, draw-and-hold, navigation gestures; conflict priorities; enable/timing/sensitivity/reset | F08, F15 |
| R14 | 11 | Zoom Window over Notebook/PDF: resize/zoom/move/auto-advance/next-line/indicator/pin/collapse | F15 |
| R15 | 3.9, 12 | Pages add/insert/duplicate/delete/reorder/copy/cross-document/rotate/export; thumbnail bulk ops; resize с preview и без потери outside objects | F09, F16 |
| R16 | 12.3 | Builtin/custom paper and covers, imported PDF/image templates, defaults, не менять уже созданные страницы при замене шаблона | F09, F11, F14 |
| R17 | 13 | Layer CRUD/duplicate/hide/lock/order/opacity/merge/group/move/export; active layer; undo deletion | F09 |
| R18 | 14.1 | Gallery/SAF/camera/clipboard/drag-drop images; transform/rect+freehand crop/opacity/flip/group/lock | F11 |
| R19 | 14.2 | Textbox, Android IME, font/size/color/bold/italic/underline/strike/highlight/alignment/spacing/lists; full-page typing без auto-page flow | F10 |
| R20 | 14.3 | Reusable editable Elements, collections CRUD/reorder/import/export, не растрировать рукописные заготовки | F11, F14 |
| R21 | 15.1–15.3 | PDF open/import/as-pages/on-canvas; annotate without changing original; thumbnails/outline/bookmarks/search/select-copy/link/back | F13 |
| R22 | 15.4 | PDF pages add/remove/duplicate/reorder/merge/extract/split/rotate/export | F16 |
| R23 | 15.5–15.6 | PDF+note / two notes / same doc views / canvas regions; independent cameras; source links и return | F16, F19 |
| R24 | 16.1–16.3 | File manager nested folders, create/rename/copy/duplicate/move/trash/restore/archive/favorite/import/export/properties, bulk/drag/autoscroll | F12, F17 |
| R25 | 16.4–16.5 | Nested colored tags и smart filters по folder/tag/type/create/modify/favorite/bookmarks без копий | F17 |
| R26 | 17.1 | Stable-ID links: document/page/frame/object/region/PDF/URL; переименование не ломает ссылки | F17, F19 |
| R27 | 17.2–17.3 | Search names/folders/tags/typed text/outline/bookmarks/PDF text, typed result и precise jump; **без handwriting search/OCR** | F17 |
| R28 | 18.1–18.4 | Portable versioned `.inknote`, private working storage, chosen SAF folder/URI grants, library import/export | F03, F14, F18 |
| R29 | 18.5 | Autosave вне input path, journal/atomic DB operations/integrity/recovery/conflicts, точный durable status | F03, F04, F23 |
| R30 | 18.6 | App trash/restore/permanent delete/clear/TTL, отмена пользовательского удаления | F17, F18 |
| R31 | 19.1 | Undo/Redo stroke/erase/transforms/style/groups/layers/pages, один logical gesture — одно undo | F02, F07, F09 |
| R32 | 19.2 | Checkpoint versions, restore/copy, deleted page recovery, bounded disk retention | F18 |
| R33 | 20 | Import inknote/PDF/PNG/JPEG/platform images; export native/vector PDF/flattened/standard annotations/PNG/JPEG/page range/region/full/multi-doc archive | F14, F16 |
| R34 | 21 | 60 FPS baseline, 90/120 best available; lazy pages/tiles, spatial index, nonblocking save; 100/500/1000 pages and 100000 strokes | F05, F23 |
| R35 | 2.2–2.3, 5.3–5.5, 22 P2 | Canvas Studio/Boards, infinite spaces, frames/minimap/named areas/free panels/multi-view; lossless UI mode change | F19 |
| R36 | 4.5–4.7 | Background/kill/rotate/resize/memory pressure, Android split screen, sharesheet/open-with/gallery/keyboard/back/SAF | F04, F22, F23 |
| R37 | 22 P3 | Audio record/multiple tracks/replay synced with handwriting, presentation, optional file sync | F20, F21 |
| R38 | Goodnotes 14–15, truff 14/22 P3 | Sticky notes with attached strokes/collapse, Tape reveal/pattern/filter, transient laser, timer/stopwatch/laps/session history | F11/F15; presentation integration F20 |
| R39 | Goodnotes 2/24/26, truff 3/6/10 | До 12 независимых color slots, HEX/eyedropper; hardware shortcuts по context; полный settings/reset | F15, F22 |
| R40 | 1.1, 23–24 | Офлайн end-to-end сценарий, сохранение редактируемости, безопасное обновление, отсутствие data loss в пределах durable guarantee | F23 |

## 3. Покрытие 50 критериев приёмки исходного ТЗ

Нумерация — `truffnotes.md`, раздел 23. Каждый номер должен стать отдельным UI/integration case, а не одной галочкой на диапазон.

| Номера | Owning phase | Дополнительная проверка |
|---|---|---|
| 1–7 | F12 | F17 для переносов/папок |
| 8–18 | F12 | F15 для настройки/Focus |
| 19–21 | F06 | F23 на реальном стилусе |
| 22–25 | F07 | F09 layers |
| 26–30 | F08 | F15 настройки жестов |
| 31–33 | F09 | F16 cross-document |
| 34–36 | F13 | F23 original PDF hash |
| 37 | F14 | F16 standard annotations |
| 38–46 | F04/F06/F22 | F23 API/device matrix и kill |
| 47 | F12 | Новая заметка без настроек |
| 48–50 | F19 | Hash object payloads до/после и undo continuity |

## 4. Каждый раздел справочника Goodnotes: включение или явное исключение

Статусы: `I` — включён; `A` — адаптирован к Android/ТЗ Inkspace; `X` — вне выбранного объёма с причиной. Заголовки 25–28 — справочные, не самостоятельные features.

| Раздел goodnotes_red.md | Статус | Реализация / решение |
|---|---|---|
| 1 Notebook / Whiteboard | I | R02/R15/R35, F09/F12/F19 |
| 1 Text Document / Study Set | X | Другие типы документов, не входят в типы ТЗ; не подменять Textbox |
| 2.1 editor/tool persistence/object menu | I | R03/R04, F12/F15; insert tools → Lasso, Text pin |
| 2.2 customization/dock/reset/undo position | A | R04/R39, F15; больше настроек допустимо по Inkspace |
| 2.3 independent color/thickness palettes | I | R07/R39, F15; 3 initial / max 12 color slots |
| 2.4 eyedropper | I | R39, F15; цвет внутри документа, включая PDF/image |
| 3 touch gestures and object/page drag | A | R06/R09/R13, F06–F09/F15/F22; Android system conflicts учитываются |
| 3 Apple-only Pencil gestures/window controls | X | Apple SDK/hardware отсутствуют; аналогичные Android buttons/hover — R06 |
| 4.1–4.3 Ball/Fountain/Brush/settings/draw-hold | I/A | R07/R11, F06/F08/F15; без Apple barrel roll |
| 5 Pencil | I | R07, F06; editable textured stroke |
| 6 Highlighter and text highlight distinction | I | R07/R19, F06/F10; разные сущности |
| 7.1 precision/segment/stroke eraser | A | R08, F07; precision + segment различаются clipping policy; Object mode добавлен ТЗ |
| 7.2 filters | I | R08, F07/F15; pen/pencil/highlighter/tape |
| 7.3 autodeselect/clear/scribble/S Pen | I/A | F07/F08/F15; S Pen через capability, не магазин Goodnotes |
| 8.1–8.2 Lasso/Circle gesture | I | R09/R13, F07/F08 |
| 8.3–8.6 menu/transforms/alignment/cross-page/copy/finger | A | R09/R10/R23; Convert commands исключены; остальные F07/F16 |
| 9.1 Edit Handwriting / word reflow | X | Требует распознавания слов; запрет OCR/ML handwriting в truff 1.2 |
| 9.2 handwriting → text | X | Явно исключено truff 1.2 |
| 9.3 handwriting → math / LaTeX | X | Явно исключено truff 1.2 |
| 9.4 handwriting recognition/search/language | X | R27 только известный typed/PDF text |
| 9.5 Apple Scribble | X | Не Android; системный Android IME не реализуется приложением и не даёт Convert stroke |
| 10.1 Shape/labels/attachment | I | R11/R19, F08/F10/F11 |
| 10.2 Connectors/anchors/control points/quick diagram | I | R11, F08; один transaction для создания связанной фигуры |
| 10.3 Ruler/options/units | I | R12, F08; protractor дополнительно из ТЗ |
| 11.1 Textbox/pin/default style | I | R19, F10/F15 |
| 11.2–11.3 formatting/fonts/lists/highlight | A | R19, F10; собственные Android font capabilities, не ограничение Goodnotes FAQ |
| 11.4 full-page typing | I | R19, F10; общий rich-text payload, без auto-pagination |
| 12.1–12.3 images/camera/crop | I | R18, F11 |
| 13.1–13.2 editable Elements/collections/local GIF | I/A | R20, F11/F14; local GIF bounded decoder, export poster frame |
| 13.2 GIPHY online / Marketplace / purchases | X | Сервисы/маркетплейс другого продукта не входят в local-first ТЗ |
| 14 Sticky Notes | I | R38, F11; parent attachments и export states |
| 15.1 Tape | I | R38, F11/F15; не redaction/security |
| 15.2 Laser/presentation | A | R38 laser F15; R37 presentation F20, Android display capability |
| 15.3 Time Keeper | A | R38, F15; Android timer/stopwatch/history |
| 16.1 Apple Pencil-specific API | X | Android-only; pressure/tilt/hover аналогично через MotionEvent |
| 16.2 palm rejection/posture/sensitivity | A | R06, F06/F15; без обещания распознать пассивный стилус отдельно от пальца |
| 16.3–16.4 S Pen/finger/mouse | A | R06/R36, F06/F22; стандартный Android ввод обязателен |
| 17.1 Zoom Window | A | R14, F15; переносим логику функции на Android |
| 17.2–17.3 navigation/sidebar | I | R03/R15, F09/F12/F15 |
| 18.1–18.3 pages/paper/custom templates | I | R15/R16, F09/F14; multi-page template first-page policy явно показана |
| 19.1 PDF annotations | I | R21, F13 |
| 19.2 internal/external links | A | R26, F17; stable-ID links шире ограничений Goodnotes |
| 19.3 outline | A | R15, F09; минимум 3 уровня, локальный safety cap 32 |
| 19.4 search | A | R27, F17; исключить handwriting/OCR portion |
| 19.5 editable/flattened PDF | A | R33, F14/F16; native формат гарантирует полную editability; не копировать случайные потери ссылок |
| 20.1 layers | A | R17, F09; все операции из Inkspace, не лимит 5/Pro |
| 20.2–20.3 locks/order/groups | A | R09/R10/R17, F07/F09; правила Inkspace |
| 21 Whiteboard/boards/minimap | I/A | R35, F19; collaboration часть исключена |
| 22.1–22.3 block editor/tables/block media | X | Отдельный Text Document вне типов Inkspace; rich text в Textbox не равен ему |
| 23.1 audio/note replay | A | R37, F20; Android implementation независимо от Apple availability |
| 23.2 comments/threads/resolve | X | Обсуждения совместного документа вне выбранного Inkspace ТЗ; Sticky — другой объект |
| 23.3 real-time collaboration | X | Multi-user протокол/сервер/permissions не заданы; file sync F21 не считается collaboration |
| 23.4 study cards/import decks | X | Study Set вне типов Inkspace |
| 23.5 multiple windows | A | R23/R36, F16/F22; один editor session, разные viewport |
| 24.1–24.5 file/edit/navigation/tool/text shortcuts | A | R39, F22; Android Ctrl/Alt mappings, system-reserved shortcuts не перехватывать |
| 24.6–24.7 Text Document block/table commands | X/A | Block/table-specific исключены; стандартные cursor/selection/IME команды включены в F10/F22 |
| 25 availability matrix | A | Hardware capabilities вместо ограничений тарифов/платформ Goodnotes |
| 26 settings map | I/A | R04/R13/R39, F15/F22; Apple/recognition settings исключены |
| 27.1–27.4 editing scenarios | A | F23; шаг Convert Math заменён сохранением исходной рукописи, handwriting PDF search не обещается |
| 27.5 common failures | A | Regression cases F06/F08/F13/F15/F22, availability объясняется capability state |
| 28 sources | reference | Источники описания поведения; не требование реализовать сайт справки |

## 5. Проверка покрытия при реализации

F00 создаёт `requirements.json` из R01–R40 и отдельных 50 acceptance cases. Для каждого case: source section, owner phase, scenario/test path, required device class, status, evidence path. Gradle task `requirementsCheck` проверяет уникальность ID, существование test/evidence references для `verified`, отсутствие необъяснённых skipped cases.

Coverage checker проверяет структуру ссылок, а не истинность прохождения теста. Итоговая приёмка требует команд/измерений из плана. Число зелёных unit tests само по себе не является покрытием всего функционала.
