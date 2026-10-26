# Мониторинг и метрики

Локальный стек наблюдаемости: **Spring Boot Actuator + Micrometer → Prometheus →
Grafana**, плюс `postgres_exporter` (БД) и `node_exporter` (Docker-VM). Всё
поднимается на этой же машине под профилем `observability`, наружу не смотрит.

## Запуск

```bash
# обычный запуск приложения (без мониторинга)
docker compose up -d

# приложение + мониторинг
docker compose --profile observability up -d

# остановить только мониторинг
docker compose --profile observability stop prometheus grafana node-exporter postgres-exporter
```

Адреса (все слушают только `127.0.0.1`, т.е. доступны лишь с этой машины):

| Что | URL | Доступ |
|-----|-----|--------|
| Grafana | http://localhost:3000 | `admin` / `admin` (из `.env`, смените при необходимости) |
| Дашборд | http://localhost:3000/d/karting-load | открывается сразу после логина |
| Prometheus | http://localhost:9090 | без авторизации |
| Actuator (backend) | http://localhost:8081/actuator/prometheus | без авторизации |
| Health | http://localhost:8081/actuator/health | без авторизации |
| postgres_exporter | http://localhost:9187/metrics | без авторизации |
| node_exporter | только внутри docker-сети | — |

Actuator вынесен на отдельный порт `8081` (`management.server.port`), чтобы
служебные эндпоинты не попадали на публичный `8080`. В Docker порт `8081`
проброшен только на `127.0.0.1`; Prometheus обращается к `app:8081` по
внутренней сети.

## Что собирается

### Стандартные метрики Spring Boot

* `http_server_requests_seconds_*` — RPS, p50/p95/p99, 4xx/5xx по `uri` и `status`
  (гистограмма включена в `application.properties`);
* `jvm_memory_used_bytes`, `jvm_memory_max_bytes` — heap;
* `jvm_gc_pause_seconds_*` — паузы GC;
* `jvm_threads_live_threads` — число потоков;
* `process_cpu_usage`, `system_cpu_usage` — CPU процесса и системы;
* `hikaricp_connections_*` — пул соединений с БД (active / idle / pending,
  время получения, таймауты);
* `process_uptime_seconds`.

### Метрики БД (`postgres_exporter`)

* `pg_stat_activity_count` — соединения по состояниям;
* `pg_database_size_bytes` — размер БД;
* `pg_stat_database_*` — транзакции, блокировки, deadlocks, cache hit ratio.

### Метрики Docker-VM (`node_exporter`)

* `node_cpu_seconds_total` — загрузка CPU;
* `node_memory_*` — память;
* `node_filesystem_*` — диски.

### Доменные метрики приложения

| Метрика | Тип | Смысл |
|---------|-----|-------|
| `karting_site_fetch_seconds{kind,result}` | timer (гистограмма) | загрузка HTML-страницы сайта: `kind=list` (список) или `kind=heat` (заезд) |
| `karting_site_fetch_failures_total{kind}` | counter | ошибки загрузки страниц сайта |
| `karting_list_parse_seconds` | timer (гистограмма) | полный цикл разбора списка (все страницы) |
| `karting_list_pages_total` | counter | сколько страниц списка реально загружено |
| `karting_list_heats` | summary | сколько заездов вернул парсер |
| `karting_heat_sync_seconds{result}` | timer (гистограмма) | синхронизация заезда: fetch + парсинг + запись в БД |
| `karting_heat_cache_requests_total{hit}` | counter | попадание/промах кэша при `GET /api/heat/{id}` |
| `karting_prefetch_queue` | gauge | заездов ждёт в очереди прогрева |
| `karting_prefetch_scheduled` | gauge | заездов уже поставлено на прогрев |
| `karting_polling_night` | gauge | ночной режим опроса сайта (1/0) |
| `karting_polling_interval_seconds` | gauge | текущий интервал фонового опроса, секунды |
| `karting_polling_runs_total{mode}` | counter | запуски фонового обновления: `mode=day` или `mode=night` |
| `karting_live_ws_connected` | gauge | WebSocket к live-таймингу подключён (1/0) |
| `karting_live_ws_reconnects_total` | counter | переподключения WebSocket |
| `karting_live_messages_total` | counter | получено live-сообщений |
| `karting_live_parse_errors_total` | counter | ошибок разбора live-JSON |
| `karting_live_sessions_total` | counter | сколько live-сессий началось |
| `karting_live_active` | gauge | идёт ли live-сессия (1/0) |
| `karting_live_message_age_seconds` | gauge | возраст последнего live-сообщения |

Кардинальность под контролем: в метках нет `heat_id` — только `uri`, `status`,
`kind`, `result`, `hit`, `mode`.

### Ночной режим опроса

Заезды идут примерно с 09:45 до 22:00, поэтому ночью сайт опрашивается реже:
интервал меняется с 30 секунд на 10 минут. В обычную ночь фоновое обновление
не находит кандидатов и вообще не обращается к сайту.

* окно по умолчанию — 23:00–08:00, настраивается `KARTING_POLLING_NIGHT_START`
  и `KARTING_POLLING_NIGHT_END`;
* интервалы — `KARTING_POLLING_DAY_INTERVAL` и `KARTING_POLLING_NIGHT_INTERVAL`;
* ночью прогреваются только 5 последних заездов вместо 30
  (`KARTING_POLLING_*_PREFETCH_LIMIT`);
* live-WebSocket ночью переподключается не чаще раза в 10 минут
  (`KARTING_POLLING_NIGHT_RECONNECT_DELAY`).

Проверить режим можно в Prometheus: `karting_polling_night` (1 ночью),
`karting_polling_interval_seconds` (600 ночью, 30 днём) и
`karting_polling_runs_total{mode="night"}`. В логах при смене режима пишется
строка «Опрос сайта: ночной режим, интервал PT10M».

## Дашборд

`ops/grafana/dashboards/karting-load.json` провижнится автоматически в папку
**Karting** (Grafana перечитывает файл раз в 30 с, перезапуск не нужен).

Панели:

1. RPS, доля 5xx, p95 `GET /api/heats`, uptime, состояние live-WS, очередь прогрева.
2. RPS и p95 по эндпоинтам; 5xx по эндпоинтам.
3. JVM heap, HikariCP, соединения Postgres, размер БД.
4. p95 загрузки страниц сайта, ошибки загрузки, p95 синхронизации заезда.
5. Hit ratio кэша заездов, интенсивность live-сообщений/реконнектов, CPU backend и системы.
6. Память и CPU Docker-VM, паузы GC, число потоков JVM.

Дашборд использует источник `Prometheus` с фиксированным `uid=prometheus`
(провижнится из `ops/grafana/provisioning/datasources/prometheus.yml`).

## Почему node_exporter, а не cAdvisor

На этом Docker Desktop включён containerd-snapshotter, и cAdvisor не может
сопоставить контейнеры с overlayfs-слоями (`failed to identify the read-write
layer ID`), поэтому контейнерные метрики не собираются. Вместо него стоит
`node_exporter` с метриками Docker-VM (CPU/память/диски), а нагрузка самого
backend видна из `process_*` / `jvm_*`, нагрузка БД — из `pg_*`.

Если позже понадобятся именно метрики контейнеров, варианты: переключить Docker
Desktop на классический image store и вернуть cAdvisor, либо использовать
Docker API-exporter.

## Данные и сброс

Prometheus хранит метрики 15 дней в томе `karting_prometheus_data`, Grafana —
состояние в `karting_grafana_data`. Полный сброс:

```bash
docker compose --profile observability down
docker volume rm karting-backend_karting_prometheus_data karting-backend_karting_grafana_data
```

(имя проекта может отличаться; посмотрите `docker volume ls`).

## Как добавить свою метрику

1. Внедрите `MeterRegistry` в компонент.
2. Создайте счётчик/таймер/гейдж, желательно один раз в конструкторе:

```java
this.syncTimer = Timer.builder("karting.heat.sync")
        .tag("result", "ok")
        .publishPercentileHistogram()
        .register(meterRegistry);
```

3. Для гистограмм (`histogram_quantile`) обязательно
   `.publishPercentileHistogram()`; иначе будут только `_count`/`_sum`/`_max`.
4. Не добавляйте в метки идентификаторы заездов/гонщиков — только
   ограниченные множества значений.
5. Проверьте: `curl -s localhost:8081/actuator/prometheus | findstr karting_`.

## Алерты

Пока не настроены (по решению на этом этапе). Когда понадобятся — включать в
Grafana Alerting (contact point Telegram/email) по метрикам:

* доля 5xx > 5% за 5 минут;
* p95 `GET /api/heats` > 3 с;
* `hikaricp_connections_pending > 0` дольше 5 минут;
* heap > 90% после GC;
* `pg_stat_activity_count` близко к `max_connections`;
* `karting_live_ws_connected == 0` дольше 5 минут в момент сессии;
* `karting_site_fetch_failures_total` растёт > 20% запросов за 10 минут.

## Диагностика

```bash
docker compose --profile observability ps
docker compose logs -f prometheus grafana
curl -s localhost:9090/-/ready
curl -s localhost:9090/api/v1/targets   # health всех целей
```

Частые проблемы:

* **Порт занят** — `node_exporter` на хосте не публикуется специально; если
  меняете порты Grafana/Prometheus, правьте `compose.yaml`.
* **Дашборд пустой** — проверьте, что цель `karting-backend` в Prometheus `UP`,
  и что прошло хотя бы 30–60 с после запросов к API (метрики появляются после
  первого запроса/скрейпа).
* **`NaN` у p95** — в окне `rate()` ещё нет двух точек; подождите пару скрейпов.
* **Grafana не видит дашборд** — файл должен лежать в
  `ops/grafana/dashboards/*.json`, а провайдер — в
  `ops/grafana/provisioning/dashboards/`.
