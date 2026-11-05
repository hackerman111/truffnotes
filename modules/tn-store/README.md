# `:tn-store` — Надёжное локальное хранение и восстановление

## Ответственность
Единственный владелец локальной документной БД (`document.sqlite` через `BundledSQLiteDriver`), журнала транзакций, staging/fsync неизменяемых ассетов (`assets/<prefix>/<sha256>`), контрольных версий (checkpoints/backups) и сборщика сиротских ресурсов (`Gc`).

## Публичный API и порты
- Реализует `StoragePort` и `ResourcePort` из `:tn-core`.
- Принимает `StorageRequest`: `Open`, `Read`, `StageDraftChunk`, `Commit`, `Flush`, `Close`, `Backup`, `Version`, `Collect`.
- Возвращает типизированный `Outcome<StorageReply, StorageError>` и `DurableAck`.

## Потоковая модель и бюджеты
- Ровно 1 writer-соединение на документ (`WAL`, `synchronous=FULL`, `foreign_keys=ON`).
- Ограниченные по памяти `ReadLease` для ленивой подгрузки страниц/чанков и spill старых записей `History` на диск.

## Команды проверки
- `rtk proxy ./gradlew :tn-store:testDebugUnitTest`
- `rtk proxy ./gradlew faultStore -Pseed=20261105 -Pruns=1000`
- `rtk proxy ./gradlew :tn-store:connectedDebugAndroidTest`
