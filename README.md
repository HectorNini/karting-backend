# Karting Backend

Сервис собирает данные с сайта тайминга картодрома
[timing.batyrshin.name](https://timing.batyrshin.name/tracks/narvskaya/heats),
хранит их в PostgreSQL и отдаёт мобильному приложению по REST, плюс показывает
гонки, которые идут прямо сейчас (live-тайминг).

## Стек

* Java 21, Spring Boot 3 (Web, Data JPA, Validation, Actuator, WebSocket/STOMP);
* PostgreSQL + Flyway;
* Jsoup для разбора HTML-страниц сайта;
* springdoc-openapi (Swagger UI на `/swagger-ui.html`).

## Быстрый старт

```bash
cp .env.example .env   # при необходимости поправьте пароль БД и трассу
docker compose up --build -d
```

Контейнеры: `karting_backend` (порт `8080`), `karting_postgres` (порт `5433`).

Проверка:

```bash
curl http://localhost:8080/api/heats
curl http://localhost:8080/api/live/current
curl http://localhost:8080/api/live/status
```

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|------------|--------------|------------|
| `KARTING_TRACK_SLUG` | `narvskaya` | трасса на сайте |
| `KARTING_BASE_URL` | `https://timing.batyrshin.name/tracks` | база URL сайта |
| `KARTING_USER_AGENT` | Chrome UA | заголовок для запросов к сайту |
| `KARTING_LIVE_ENABLED` | `true` | включает подключение к live-таймингу |
| `KARTING_LIVE_WS_URL` | `wss://timing.batyrshin.name/ws` | WebSocket сайта |
| `KARTING_LIVE_ORIGIN` | `https://timing.batyrshin.name` | обязательный заголовок `Origin` |
| `KARTING_LIVE_STALE_SECONDS` | `180` | тишина, после которой сессия считается завершённой |
| `KARTING_TZ` | `Europe/Moscow` | часовой пояс контейнера (время в расписании опроса) |
| `KARTING_POLLING_DAY_INTERVAL` | `30s` | интервал фонового опроса сайта днём |
| `KARTING_POLLING_NIGHT_INTERVAL` | `10m` | интервал фонового опроса сайта ночью |
| `KARTING_POLLING_NIGHT_START` / `KARTING_POLLING_NIGHT_END` | `23:00` / `08:00` | ночное окно (может пересекать полночь) |
| `KARTING_POLLING_DAY_PREFETCH_LIMIT` / `KARTING_POLLING_NIGHT_PREFETCH_LIMIT` | `30` / `5` | сколько последних заездов прогревать |
| `KARTING_POLLING_NIGHT_RECONNECT_DELAY` | `10m` | минимальная пауза переподключения live-WS ночью |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | — | доступ к БД |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | `admin` / `admin` | вход в Grafana |

### Ночной режим опроса сайта

Заезды идут примерно с 09:45 до 22:00, поэтому ночью фоновый опрос сайта
замедляется: днём — каждые 30 секунд, ночью — раз в 10 минут. В обычную ночь
фоновое обновление не обращается к сайту: оно трогает только заезды, которые
стартовали не позже двух часов назад (то есть ещё могли идти).

Окно и интервалы настраиваются переменными `KARTING_POLLING_*` из таблицы выше.
Ночью также уменьшается прогрев кэша (5 заездов вместо 30) и увеличивается
пауза между переподключениями live-WebSocket (10 минут вместо 60 секунд).

## Мониторинг

Метрики (Prometheus + Grafana + postgres_exporter + node_exporter) вынесены в
профиль `observability` и по умолчанию не запускаются:

```bash
docker compose --profile observability up -d
```

* Grafana — http://localhost:3000 (дашборд «Karting — нагрузка»);
* Prometheus — http://localhost:9090;
* Actuator — http://localhost:8081/actuator/prometheus (только `127.0.0.1`).

Что собирается, какие доменные метрики есть и как добавить свои — в
[`docs/monitoring.md`](docs/monitoring.md).

## REST API

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/heats` | список последних заездов (до 200) |
| `GET` | `/api/heat/{id}` | заезд с кругами (из БД или с сайта) |
| `GET` | `/api/live/current` | идёт ли гонка сейчас + последний снимок |
| `GET` | `/api/live/status` | диагностика live-подключения |
| `POST` | `/api/live/stop` | принудительно завершить live-сессию |
| `POST` | `/api/live/push` | подать сообщение live-тайминга вручную (симуляция) |
| `POST` | `/api/live/push/raw` | то же, тело — сырой JSON сайта |

`GET /api/live/current` возвращает:

```json
{
  "live": true,
  "status": "LIVE",
  "heat": { "heatId": "133304", "heatName": "Финал 3", "elapsed": 444968, "drivers": [] },
  "lastUpdateAt": 1792971409441,
  "ageMs": 1200,
  "messages": 5,
  "staleTimeoutMs": 180000
}
```

`status`: `LIVE` (идёт), `WAITING` (получено одно сообщение — ждём подтверждения),
`FINISHED` (сессия завершена), `IDLE` (данных ещё не было).

## Live-тайминг

Бэкенд постоянно держит WebSocket-подключение к сайту (`LiveTimingClient`),
отправляет `listen track:{slug}` и разбирает снимки. Сессия считается живой,
если пришло **минимум два сообщения** с одним `id` и последнее не старше
`stale-seconds` (сайт всегда присылает одно «последнее состояние» сразу после
подписки, даже если гонка давно закончилась).

Подробный протокол и устройство — в [`docs/site-data.md`](docs/site-data.md), §6.

## Тесты

```bash
mvn test
```

## Swagger

`http://localhost:8080/swagger-ui.html`
