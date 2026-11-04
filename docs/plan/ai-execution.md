# Контракт ИИ-исполнителя

Применяется ко всем фазам [плана](../superpowers/plans/2026-11-05-truffnotes-android.md). Архитектура и API: [android-workspace.md](../architecture/android-workspace.md). Объём: [requirements.md](requirements.md).

## 1. Что является заданием

1. Только Android. `truffnotes.md` имеет приоритет над `goodnotes_red.md` при расхождениях. Исключённые функции нельзя незаметно вернуть через «полезную зависимость».
2. Вся текущая работа — реализация конкретной выбранной фазы. Этот комплект документов сам по себе не означает, что уже реализована хотя бы одна фаза.
3. Начать с F00. Переходить дальше можно после выполнения зависимостей и их gates. Подготовить mocks для независимого контракта можно, объявлять интеграцию проверенной по mock нельзя.
4. Прочитать текущие `AGENTS.md`, проектные документы и состояние Git перед изменениями. Следовать требованию RTK: shell-команды через `rtk` / `rtk proxy`. В текущем каталоге Git пока не инициализирован; не считать наличие служебного read-only каталога `.git` доказательством репозитория.
5. Не менять исходные `goodnotes_red.md`, `truffnotes.md`, `images.zip`. Если нужны изображения — сначала инвентаризация архива и проверка прав/назначения; не считать все картинки разрешёнными assets продукта.

## 2. Непереступаемые границы

- Функциональный Gradle-модуль зависит только от `tn-core` из внутренних production-пакетов. Runtime связывает реализации. Нельзя обойти правило через api/implementation/runtime/test dependency, variant/composite-build, reflection или callback.
- Core содержит общие types/contracts и один apply/validate/inverse, а не всё приложение. Новый interface требует реального consumer и contract tests. Не строить runtime plugin protocol, reflection/service locator или generic ECS без отдельной доказанной необходимости.
- Все изменения модели проходят `validatePlan` → `applyPlan`. Toolbar, hardware key, gesture, undo, import, recovery используют этот же механизм. Disk recovery использует versioned write-set validation; нельзя дублировать бизнес-редуктор в SQL/UI.
- Smoothing/brush geometry, hit testing, text shaping, archive codec, command registry и camera transform имеют по одному владельцу. Перед новым helper найти существующий и всех потребителей. Не копировать алгоритм для Canvas Studio, PDF, Zoom или второго viewport.
- Изменяемый DocumentState доступен только engine actor. Snapshots/requests глубоко immutable: `val List` и `@Immutable` не заменяют immutable backing. Mutable collections/arrays/ByteBuffer после handoff не изменяются и не возвращаются в pool до release. Per-sample Point allocation запрещена; использовать encapsulated primitive chunks.
- Не передавать MotionEvent/Activity/View, mutable Canvas/Paint/Path/Bitmap и PDF handles между исполнителями. Native PDF calls, включая close/finalizer, сериализуются. Kotlin/GC не гарантируют отсутствие data races: actor confinement, owner guards, immutable publication, deterministic/stress tests обязательны.
- Никаких `!!`, `TODO()`, `error()` и непойманных исключений на production путях ввода/import/storage/native binding/lifecycle. Ошибки возвращаются typed Outcome; CancellationException не проглатывается. Invariant checks допустимы только в закрытом коде с доказанными preconditions.
- Все очереди, payloads, pins, jobs, caches и history имеют limits и overflow policy. Unbounded queue — ошибка архитектуры. Оптимизация не может выбрасывать реальные input samples или durable writes.
- I/O, PDF, compression, indexing и тяжёлый layout не выполняются на Android Main/engine critical path. Engine actor не ждёт worker через await/withContext: результат приходит stamped message. Запрещены GlobalScope, Channel.UNLIMITED, unbounded launch и runBlocking на Main/engine. Нельзя «починить ANR» увеличением timeout.
- Не заменять editable объекты bitmap-слоем ради скорости. Только caches могут быть растровыми. `.inknote` обязан сохранять исходную модель и реальный ввод.
- Не обещать атомарный SAF overwrite, фоновые callbacks после kill, безошибочную palm rejection пассивного стилуса или сохранность ещё не durable данных.
- Не менять сохранённый формат, API, budgets, numerical tolerance, minSdk или expected test result для прохождения проверки. Такие изменения оформляются отдельным ADR и пересмотром затронутых требований; несовместимость/сужение функции требует решения пользователя.
- Не добавлять аккаунты, сетевую телеметрию, обязательное облако, OCR/генеративный AI и новые permissions «на будущее». Optional file sync включается пользователем и не меняет offline path.

## 3. Рабочая единица и критерий завершения

Каждая задача содержит: фазу, Requirement IDs, owned paths, потребляемый/выдаваемый API, зависимости, observable result, тест и команду проверки. Для существенного поведения сначала создать regression/contract test, убедиться, что он ловит отсутствие/ошибку поведения, затем реализовать минимальный код и выполнить проверку.

Нельзя объявить задачу готовой только по факту компиляции, наличию файлов, screenshot или ответу другого агента. Нельзя выдавать headless benchmark за pen-to-photon, emulator за S Pen, fake storage за SAF, graceful shutdown за process kill.

Статусы: `planned`, `in_progress`, `implemented_unverified`, `verified`, `blocked`. Фаза получает `verified`, только если выполнены **все** её gates и доступны evidence. Недоступный планшет/отсутствующий profiler → соответствующий gate остаётся незавершённым, без ослабления проверки.

По завершении задачи создать/обновить `docs/evidence/Fxx/report.md`:

```text
Phase / task / Requirement IDs:
Revision or source fingerprint:
Changed paths:
Public API/schema changes:
Commands and exit codes:
Device / API / firmware / ABI / GPU / build profile:
Fixture IDs and hashes:
Tests: passed / failed / skipped (with reasons):
Measurements: raw path, p50/p95/p99, sample count:
Remaining limitations / failing acceptance criteria:
Status:
```

Raw traces не заменяются одной итоговой цифрой. CI хранит отчёты и checksums fixtures; личные документы в fixtures/logs не попадают. Когда Git доступен — небольшой reviewable commit на задачу, без чужих правок и без обязательного squash всей фазы. Вне Git фиксируются fingerprints; нельзя выдумывать commit hash.

## 4. Параллельная разработка

После F01 контракт конкретной capability замораживается. После F02/F03/F04 независимые команды работают по DAG плана. Разработка на mocks разрешена, integration gate требует реальных реализаций.

Одна задача — один владелец записи в конкретные paths. Общие файлы (`tn-core`, Gradle settings/version catalog/build-logic, migrations registry, runtime dispatch, public API dump) имеют отдельного интегратора. Для независимых задач — отдельные branches/worktrees, если Git поддерживает их; общий checkout с одновременными правками одного файла запрещён.

Передать каждому исполнителю: его фазу/границы, текущую версию core API, fixtures, budgets, команды, ожидаемый output. Прямо предупредить: он не один в кодовой базе, чужие изменения не откатывать. Если нужен отсутствующий контракт — запрос интегратору; не импортировать соседний модуль и не создавать второй аналог API.

Смена API: предложение с consumers → additive contract + tests → миграция mocks/реализаций → integration test → удаление старого API после миграции. Несвязанные фазы не останавливаются. Breaking change не прячется в feature commit.

Нельзя запустить нескольких агентов лишь для обхода контроля фазы. Использовать делегирование только когда пользователь/локальные правила разрешают его; граф допускает и последовательное выполнение одним исполнителем.

## 5. Обязательные проверки

С F01+ Gradle convention plugins предоставляют root tasks:

```sh
rtk proxy ./gradlew ktlintCheck detekt
rtk proxy ./gradlew jvmTestAll
rtk proxy ./gradlew architectureCheck apiCheck requirementsCheck
rtk proxy ./gradlew androidUnitTestAll lint assembleDebug
```

`jvmTestAll` агрегирует `:tn-core:test`, tests других pure JVM модулей и quality harness. `androidUnitTestAll` агрегирует `testDebugUnitTest` только Android library/app модулей. Эти root tasks создаёт F01; после добавления нового модуля он автоматически включается в нужную группу. Пустой/несуществующий task не считается успешной проверкой.

Device checks: `rtk proxy ./gradlew connectedDebugAndroidTest`; сборка production variant — `rtk proxy ./gradlew assembleRelease`. Capabilities `base`, `media`, `sync` проверяются через явную Gradle test matrix/runtime config: и включённые, и выключенные комбинации, а не одна сборка со всеми функциями.

JVM tests проверяют чистую модель и protocols; Android instrumentation — actual SQLite driver/Canvas/IME/Surface/PDF binding. Coroutines: `kotlinx-coroutines-test`/TestCoroutineScheduler для deterministic interleavings плюс многопоточные stress/linearizability tests, owner guards и alias/pool-reuse tests. Однопоточный test dispatcher не доказывает отсутствие гонок на production executors.

StrictMode/LeakCanary/heap dumps/Perfetto фиксируют Main I/O, Activity/Surface/resource leaks, managed allocations/GC и native/graphics memory. Native sanitizer используется только для поддержанной debug-сборки внешней зависимости; отсутствие такого build явно фиксируется и не заменяется выдуманным PASS.

Performance gates — release/profileable Android + реальные планшеты; JVM microbench их не заменяет. Tests используют сгенерированные/лицензированные данные. После schema change обязательны upgrade и recovery проверки. Доступные готовые Android/Kotlin компоненты используются перед собственной реализацией, но не создают второй document/brush/text pipeline.

## 6. Когда остановить зависимую работу

Остановить соответствующий путь, сохранить evidence и сообщить точную причину, если:

- обнаружена потеря durable данных, corrupt output или неограниченная память;
- нужная возможность PDF backend/Surface/IME не прошла F00;
- конфликтующие исходные требования нельзя решить указанным приоритетом;
- вход требует unsupported required feature или миграция не может сохранить данные;
- ресурс/permission/device недоступен и без него нельзя проверить gate.

Продолжать независимые задачи, если они не зависят от проваленного контракта. Не заменять незавершённую функцию disabled-кнопкой и не отмечать её выполненной. Финальный ответ исполнителя: что работает, чем проверено, что ещё не проверено/не проходит; без заявлений «гонки невозможны» или «максимально быстро» без оговорённой области доказательства.
