# tn-core

## Ответственность
- Единый владелец базовых типов, единиц измерения, уникальных идентификаторов (128-bit typed IDs), инвариантов конечных координат и валидации пределов (`Limits`).
- Контракты мутаций и изменений: `DocumentRead`, `EditPlan`, `ValidatedPlan`, `AppliedEdit`, `ChangeSet`, `Limits`, `Outcome`.
- Типизированные payload объектов (`StrokePayload`, `ShapePayload`, `TextPayload`, `ImagePayload`, `ConnectorPayload`, `GroupPayload`, `StickyPayload`, `TapePayload`, `LinkAnchorPayload`).
- Интерфейсы портов ввода/вывода (`GeometryPort`, `TextPort`, `ResourcePort`, `StoragePort`, `PdfPort`, `RenderPort`, `JobSink`).
- Кодек сериализации (CBOR / JSON).

## Owner состояния
Не владеет изменяемым состоянием. Предоставляет неизменяемые контракты и чистые функции валидации планов (`validatePlan`).

## Бюджеты и инварианты
- Поддерживаемый диапазон мировых координат: `abs(world coordinate) <= 1e9 pt`.
- Диапазон масштабирования камеры: `1e-4 .. 1e4`.
- Ограничение ошибки roundtrip преобразований камеры: `<= 0.01 pt`.
- Запрещены `NaN` и `Infinity` на всех координатных путях.
- Никаких зависимостей от Android SDK (`android.*`) и SQL (`java.sql.*`).

## Команды
- Тесты модуля: `rtk proxy ./gradlew :tn-core:test`
- Проверка архитектуры: `rtk proxy ./gradlew architectureCheck`
- Проверка публичного API: `rtk proxy ./gradlew apiCheck`
