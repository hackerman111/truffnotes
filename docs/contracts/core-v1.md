# Core Contract v1 (`:tn-core`)

## 1. Назначение и границы модуля
`:tn-core` — единственный владелец базовых типов, единиц измерения, типизированных идентификаторов, контрактов данных, портов и функции валидации планов изменений (`validatePlan`).

### Строгие запреты для `:tn-core`:
- **Никаких зависимостей от Android SDK** (`android.*`, `androidx.*`).
- **Никаких зависимостей от SQL / баз данных** (`java.sql.*`, `javax.sql.*`, `androidx.sqlite.*`).
- **Никаких тяжёлых реализаций** (рендеринга, дискового ввода-вывода, PDF-парсинга, сглаживания кривых или укладки текста).
- Функциональные модули (`tn-ink`, `tn-objects`, `tn-pages`, `tn-text`, `tn-content`, `tn-render`, `tn-store`, `tn-library`, `tn-search`, `tn-pdf`, `tn-exchange`, `tn-workspace`, `tn-media`, `tn-sync`) зависят **только** от `:tn-core`. Любая прямая зависимость между функциональными модулями запрещена и блокируется задачей `architectureCheck`.

---

## 2. Типизированные идентификаторы и ревизии (`inkspace.core.id`)
- Все идентификаторы (`DocumentId`, `SpaceId`, `PageId`, `LayerId`, `ObjectId`, `AssetId`, `VersionId`, `GestureId`, `TransactionId`) являются обёртками (`@JvmInline value class`) над непустыми строками (128-bit UUIDv7 или SHA-256 дайджест для `AssetId`).
- Идентификаторы разных сущностей несовместимы по типу и никогда не переиспользуются.
- `Revision(val value: Long)` — монотонно возрастающий неотрицательный номер ревизии документа.
- **Разделение Applied и Durable:**
  - *Applied Revision* — ревизия, применённая в памяти актора документа (`DocumentState`).
  - *Durable Revision* — ревизия, подтверждённая записью на диск и `fsync` (`StorageRequest.Commit`).

---

## 3. Координатная система и инварианты точности (`inkspace.core.unit`)
- Единица измерения документа: **типографский пункт** (`1 pt = 1/72 inch`).
- `DocPoint` и `DocRect` используют `Double` и проверяют конечность (`isFinite()`) при создании. `NaN`, `+Infinity` и `-Infinity` отвергаются как при конструировании, так и на границе десериализации (`CoreCodec`).
- Поддерживаемый мировой диапазон: `abs(x), abs(y) <= 1e9 pt`.
- `CameraTransform` задаёт прямое (`docToView`) и обратное (`viewToDoc`) преобразование с масштабом `scale in 1e-4..1e4` и гарантирует ошибку roundtrip `<= 0.01 pt`.

---

## 4. Единый путь изменений и валидации (`inkspace.core.mutation`)
- Любое изменение документа оформляется как неизменяемый `EditPlan`:
  - `base: DocVersion` — базовая ревизия, относительно которой построен план.
  - `preconditions: List<Precondition>` — ожидаемые версии объектов.
  - `mutations: List<Mutation>` — непустой список атомарных мутаций (`InsertObject`, `RemoveObject`, `UpdatePayload`, `MoveObject`).
  - `gesture: GestureId`, `label: UndoLabel` — метаданные для группировки в истории Undo/Redo.
- Функция `validatePlan(document: DocumentRead, plan: EditPlan, limits: Limits): Outcome<ValidatedPlan, EditError>`:
  - Проверяет совпадение `plan.base.revision == document.version.revision` (иначе `EditError.StaleRevision`).
  - Проверяет предусловия, существование объектов (`ResidencyState`), отсутствие блокировок (`isLocked` -> `EditError.LockedEntity`).
  - Проверяет соблюдение лимитов `Limits` (`EditError.LimitExceeded`).
  - При любой ошибке весь план отвергается целиком без частичного применения. Конструктор `ValidatedPlan` закрыт (`internal`) и создаётся только внутри `:tn-core`.

---

## 5. Семантика потоков, очередей, ошибок и отмены (`inkspace.core.ports`, `inkspace.core.outcome`)
- **Ошибки:** Все операции возвращают типизированный `Outcome<T, E>` (`Outcome.Success` / `Outcome.Failure`). Исключения не используются для управления потоком выполнения; `CancellationException` корутин никогда не проглатывается.
- **Порты и переполнение (`SubmitError.QueueOverflow`):**
  - Асинхронные порты (`StoragePort`, `PdfPort`, `RenderPort`, `JobSink`) предоставляют неблокирующий метод `trySubmit(...)`.
  - Каждая очередь обязана иметь жёсткий предел ёмкости. При исчерпании лимита возвращается `Outcome.Failure(SubmitError.QueueOverflow(currentDepth, maxDepth))` — неограниченные очереди (`Channel.UNLIMITED`) запрещены.
- **Thread Confinement:**
  - Объекты `EditPlan`, `ObjectPayload`, `DocVersion`, `ChangeSet` глубоко неизменяемы и безопасны для передачи между потоками.
  - Чтение состояния через `DocumentRead` различает `ResidencyState.Present`, `ResidencyState.NotResident` и `ResidencyState.Missing`.
