# tn-test-support

## Ответственность
- Фикстуры документов и тестовые генераторы (`Documents`, `TestData`).
- Ограниченные fake-реализации системных портов (`FakeGeometryPort`, `FakeTextPort`, `FakeResourcePort`, `FakeStoragePort`).
- Тестовый планировщик и тестовые часы (`TestClock`).
- Вспомогательные assertions для contract-тестов.

## Owner состояния
Локальное изолированное состояние в тестовых сценариях.

## Команды
- Тесты модуля: `rtk proxy ./gradlew :tn-test-support:test`
