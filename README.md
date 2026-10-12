# ITMO-TimeSeriesDB

Собственная time-series база данных на Java 21 (упрощённый аналог Prometheus TSDB).

## Сборка

```
./gradlew build
```

## Пакеты и владельцы

| Пакет | Что внутри | Владелец |
|---|---|---|
| `org.tsdb.model` | Labels, Sample, Series, Matcher, Query, исключения | B |
| `org.tsdb.encoding` | BitWriter/BitReader, Chunk, Gorilla | A |
| `org.tsdb.index` | SeriesRegistry, InvertedIndex, Postings | B |
| `org.tsdb.wal` | Write-ahead log | A |
| `org.tsdb.head` | In-memory head | B |
| `org.tsdb.block` | Блоки на диске | A |
| `org.tsdb.compact` | Компакция, retention | A |
| `org.tsdb.query` | Queryable, QueryEngine, агрегации | B |
| `org.tsdb.db` | Фасад TimeSeriesDB, DbOptions | C |

A — Storage, B — Index & Query, C — API & Infra.

## Демо и бенчмарк

```
./gradlew demo                                  # 1000 серий за 4 часа → блок → запрос avg by host
./gradlew bench                                 # 1000 серий × 15 с × 24 ч = 5.76M точек
./gradlew bench -PbenchArgs="hours=2 warmup=0"  # быстрый прогон; ещё hosts, cores, stepMs, queries
```

Данные — `org.tsdb.bench.MetricsGenerator`: серии `cpu_usage{host, core}`, значение — случайное
блуждание в [0, 1] с шагом до ±0.02 и округлением до 0.01. Для Gorilla это неудобные данные:
значение меняется почти в каждой точке, а десятичные дроби после XOR дают много значащих бит.
Сценарий прогоняется 3 раза (2 — прогрев JIT), цифры по последнему прогону.

Результат (JDK 26, Windows 11, `./gradlew bench`):

| Метрика | Значение |
|---|---|
| Запись (`appendBatch` по 1000 точек, включая flush и compact) | ~815 000 точек/с (7.1 с на 5.76M) |
| Блоки на диске | 32.7 МБ = **5.68 байт/точку** (без сжатия — 16) |
| Весь каталог данных (с WAL и checkpoint'ом серий) | 32.8 МБ, сжатие 2.8x |
| Запрос avg by host за последний час по 100 сериям | ~3 мс (медиана из 50) |
