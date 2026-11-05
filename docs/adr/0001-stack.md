# ADR 0001: Фиксация стека технологий, версий toolchain, SDK и внешних библиотек

- **Статус:** Принято (Frozen)
- **Дата:** 2026-11-05
- **Владелец:** Фаза F00
- **Связанные документы:**
  - [Архитектура](../../docs/architecture/android-workspace.md)
  - [План реализации](../../docs/superpowers/plans/2026-11-05-truffnotes-android.md)
  - [Матрица требований](../../docs/plan/requirements.md)
  - [Правила ИИ](../../docs/plan/ai-execution.md)

---

## 1. Контекст

Truffnotes (Inkspace) разрабатывается как высокопроизводительный, надежный локальный редактор векторных рукописных заметок исключительно для Android-планшетов (API 29+). В соответствии с исходным ТЗ (`truffnotes.md`) и утвержденным планом реализации, проект требует:
1. Предсказуемой векторной производительности (60 FPS baseline, 120 FPS target, задержка пера pen-to-photon <= 50 ms на 60 Гц и <= 35 ms на 120 Гц).
2. Полной сохранности подтвержденных транзакций (Crash safety: 0 потерянных durable ревизий при внезапном kill или ENOSPC).
3. Работы на широком спектре планшетов (Samsung с S Pen, Lenovo/Xiaomi с активными стилусами, бюджетные устройства ~4 GiB RAM с пассивным вводом/пальцем).
4. Поддержки современных требований Android 15/16, включая обязательное выравнивание ELF LOAD-сегментов нативную библиотек по границе **16 KiB** (0x4000).

## 2. Принятые решения

### 2.1. Toolchain и Gradle
- **JDK:** OpenJDK 17 (компиляция и toolchain; целевой JVM target = 17).
- **Gradle:** 9.1.0 (Gradle wrapper с SHA-256 верификацией: `a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806`).
- **Android Gradle Plugin (AGP):** 9.0.1.
- **Kotlin:** 2.3.20 (Compose Compiler plugin интегрирован в Kotlin 2.x).
- **Gradle Version Catalog:** `gradle/libs.versions.toml` — единый источник версий для всех модулей.

### 2.2. Android SDK и ABI
- **minSdk:** 29 (Android 10.0, Q). Обеспечивает поддержку `RenderNode`, `HardwareRenderer`, системного `StaticLayout.Builder`, расширенных параметров `MotionEvent`.
- **compileSdk:** 35 (Android 15).
- **targetSdk:** 35 (Android 15).
- **Production ABI:** `arm64-v8a` (64-bit ARM).
- **Test / Emulator ABI:** `x86_64`.
- **Page Size:** Обязательная совместимость со страницами памяти **4 KiB** и **16 KiB** (`16384 bytes`). Все native `.so` зависимости должны иметь `Align = 0x4000` в секциях `LOAD`.

### 2.3. Графический стек и холст
- **UI экранов:** Jetpack Compose (BOM `2026.03.01`, Material3). Compose отвечает за навигацию, диалоги, списки библиотеки, панели инструментов.
- **Холст документа:** `SurfaceView` / Android hardware `Canvas` и `RenderNode`.
- **Потоковая модель рендера:** Выделенный однопоточный `render executor`. UI-поток не выполняет пересчет геометрии и отрисовку штрихов.
- **Replay drawing:** Единый исполнитель команд `CanvasReplay` для экрана (`SurfaceView`), создания растровых снимков (`Bitmap`) и векторного PDF-оверлея (`android.graphics.pdf.PdfDocument`).
- **Запрет:** Собственный Vulkan/OpenGL движок не разрабатывается; второй brush/geometry pipeline для экспорта или Canvas Studio запрещен.

### 2.4. Хранилище и SQLite
- **Драйвер:** `androidx.sqlite:sqlite-bundled-android:2.7.1` (включает встроенную сборку SQLite 3.4x с FTS5).
- **Конфигурация SQLite:** `PRAGMA journal_mode = WAL`, `PRAGMA synchronous = FULL`, `PRAGMA foreign_keys = ON`.
- **Верификация 16 KiB:** `jni/arm64-v8a/libsqliteJni.so` в `sqlite-bundled-android:2.7.1` имеет подтвержденное выравнивание `LOAD` сегментов `0x4000` (16 KiB).
- **Потоки:** Ровно 1 writer thread на открытый документ. Никаких прямых вызовов SQLite из UI-потока или Engine actor.

### 2.5. PDF-стек
- **Библиотека:** `io.legere:pdfiumandroid:2.0.0` (с транзитивными `pdfiumandroid-core:2.0.0` и `pdfiumandroid-api:2.0.0`).
- **Лицензия:** Apache License 2.0 (PDFium / Google).
- **Изоляция:** Выделенный служебный процесс Android (`:pdfservice`) с отдельным single-threaded executor для всех вызовов PDFium.
- **Верификация 16 KiB:** В `pdfiumandroid-core:2.0.0`:
  - `jni/arm64-v8a/libpdfium.so`: Align = `0x4000` (16 KiB)
  - `jni/arm64-v8a/libpdfiumandroid.so`: Align = `0x4000` (16 KiB)
- **Возможности:** Подтверждена поддержка `openPage`, `renderPage` (в `Surface` и `Bitmap`), `openTextPage` (`textPageGetText`, `findStart`), `getPageLinks`, `getTableOfContents`, `deletePage`, `saveAsCopy`, а также наличие экспортированных нативных функций для манипуляции страницами и аннотациями (`FPDF_ImportPages`, `FPDF_SaveAsCopy`, `FPDFPage_CreateAnnot`, `FPDFPageObj_CreateNewPath`).

### 2.6. Сериализация и Корутины
- **Формат записей:** `kotlinx.serialization` (версия `2.3.20`), CBOR для канонического бинарного представления ревизий документа (`.inknote`).
- **Асинхронность:** `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`.
- **Очереди:** Bounded channels с жесткими лимитами памяти (`Limits.kt`). `GlobalScope`, `runBlocking` на Main/Engine и `Channel.UNLIMITED` запрещены.

### 2.7. Архитектурные границы модулей
- `:tn-core`: Чистая Kotlin/JVM библиотека. Никаких зависимостей от Android SDK, SQLite, Canvas, Compose или сетевых библиотек.
- Функциональные модули (`tn-ink`, `tn-objects`, `tn-pages`, `tn-text`, `tn-content`, `tn-render`, `tn-store`, `tn-library`, `tn-search`, `tn-pdf`, `tn-exchange`, `tn-workspace`, `tn-media`, `tn-sync`): знают **только** `:tn-core`. Прямые импорты соседних модулей запрещены.
- `:tn-runtime`: Единственный модуль связывания реализаций функций.
- `:tn-platform`: Реализация системных портов Android (Input, SAF, IME, Surface, Audio, IPC).
- `:tn-ui`: Compose-экраны и представления (знает только `:tn-core` и UI-библиотеки).
- `:app`: Android application модуль, composition root.

---

## 3. Последствия и ограничения

1. **Производительность:** Разделение на Engine actor, Render executor и Store writer гарантирует, что медленные операции диска или PDF-рендеринга не приводят к пропуску кадров при рукописном вводе.
2. **Безопасность данных:** Модель `AppliedRevision` != `DurableRevision`. UI показывает статус `Saving...` до тех пор, пока от Store writer не получен подтвержденный `DurableAck` после выполнения `fsync`.
3. **16 KiB готовность:** Проверены и зафиксированы SHA-256 хеши и ELF заголовки нативных библиотек. Проект готов к запуску на Android 15/16 с 16 KiB размером страницы без эмуляционных трансляторов.
4. **Отсутствие внешних облачных зависимостей:** Редактор полностью автономен и работоспособен офлайн с первого запуска.
