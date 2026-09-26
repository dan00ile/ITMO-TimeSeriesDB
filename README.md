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
