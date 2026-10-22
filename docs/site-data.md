# Источник данных `timing.batyrshin.name` — структура, форматы, что можно спарсить

Документ описывает:

1. как устроен текущий код проекта;
2. какие данные есть на сайте тайминга, по каким URL и в каком виде;
3. что из этого уже парсится, а что доступно, но игнорируется;
4. найденные при разборе проблемы.

Срез сделан **2026-10-22**, на живых страницах. Все примеры HTML приведены как есть (сокращены по длине, но структура и атрибуты сохранены).

> **Обновление 2026-10-22 (вторая итерация).** С момента первого среза многое исправлено и добавлено:
> пагинация списка заездов (`?start_time=`), тип события (`HEAT`/`RACE`), корректная позиция в гонке и
> номер круга при смене карта, WebSocket live-тайминг, облегчённая фоновая синхронизация.
> Актуальные разделы помечены ниже; исторические проблемы (§10) оставлены с пометкой статуса.

---

## 0. Карта источников

| # | URL | Что отдаёт | Используется проектом |
|---|-----|-----------|-----------------------|
| 1 | `GET /tracks/{slug}/heats` | Список заездов трассы (50 шт. на страницу) | ✅ `HeatListParser` |
| 1b | `GET /tracks/{slug}/heats?start_time={unix}` | Следующая страница списка | ✅ `HeatListParser` (пагинация) |
| 2 | `GET /tracks/{slug}/heats/{id}` | Таблица результатов заезда | ✅ `HeatParser` |
| 3 | `GET /tracks/{slug}/drivers/{id}` | Карточка пилота: статистика, история, heatmap | ❌ |
| 4 | `GET /tracks/{slug}/drivers` | Таблица пилотов (по лучшему кругу) | ❌ |
| 5 | `GET /tracks/{slug}/karts` | Таблица картов (по лучшему кругу) | ❌ |
| 6 | `GET /tracks/{slug}/karts/{id}?p=1d` | Карточка карта | ❌ |
| 7 | `GET /tracks/{slug}/heats/{id}/image` | PNG-превью заезда | ❌ |
| 8 | `wss://timing.batyrshin.name/ws` | Live-тайминг: позиции, круги, stint-ы, gap-ы | ⚙️ проксируется: `POST /api/live/push/raw` → STOMP |
| 9 | `GET /heats?t={tournamentId}` | Список заездов чемпионата (все трассы) | ❌ |
| 10 | `GET /grids` | История гридов чемпионатов | ❌ |
| 11 | `GET /tracks/{slug}/live` | SPA-обёртка live-тайминга | ❌ |

Доступные трассы (из навбара сайта): `narvskaya` (в конфиге по умолчанию), `premium`, `drive`, `pulkovo`, `revo`; `igora` закомментирована в разметке.

---

## 1. Архитектура проекта

```
TimingController  ──►  HeatService  ──►  HeatListParser        (список заездов: пагинация, лимит 100 → HTTP-ответ)
     GET /api/heats          │        └►  HeatSyncService ─► HeatParser ─► HeatMapper ─► Postgres
     GET /api/heat/{id}      │                    ▲
                             └── кэш в БД ◄─────┘   (@Scheduled fixedDelay=30s, только сегодняшние/недавние заезды)

LiveTimingController ──► LiveTimingService ──► SimpMessagingTemplate → /topic/live/{trackSlug}[/{heatId}]
     POST /api/live/push[/raw]        (приём сырых сообщений с wss://timing.batyrshin.name/ws и ретрансляция STOMP)
```

### Слои

| Пакет | Класс | Роль |
|-------|-------|------|
| `controller` | `TimingController` | REST заездов, Swagger-аннотации, `CORS *` |
| `controller` | `LiveTimingController` | приём live-данных (`/api/live/push`, `/api/live/push/raw`) |
| `config` | `WebSocketConfig` | STOMP-брокер `/topic`, эндпоинт `/ws` (+ SockJS) |
| `service` | `HeatService` | чтение из БД + фоллбэк на синхронизацию; шедулер |
| `service` | `HeatSyncService` | upsert заезда (`REQUIRES_NEW`), парсинг → маппинг → сохранение |
| `service` | `HeatListParser` | HTML → `HeatList` (список + пагинация + тип) |
| `service` | `HeatParser` | HTML → `ParsedHeatData` (детали заезда) |
| `service` | `HeatMapper` | `ParsedHeatData` ⇄ Entity ⇄ DTO |
| `service` | `LiveTimingService` | разбор live-JSON и broadcast в `/topic/live/**` |
| `service` | `TimingSiteFormats` | парсеры дат/времени сайта (`package-private`) |
| `service` | `ParsedHeatData` | промежуточная модель «чистые данные с сайта» |
| `dto` | `HeatList`, `HeatData`, `LiveHeatUpdate` | контракты REST API |
| `entity` | `TrackEntity`, `HeatEntity`, `DriverEntity`, `LapEntity` | JPA |
| `repository` | 3 репозитория | Spring Data JPA |

### Схема БД (`V1`…`V4`)

```
tracks (id, slug UNIQUE, name)
drivers (id, site_id, name, track_id → tracks, UNIQUE(site_id, track_id))
heats  (id, site_id UNIQUE, track_id → tracks, name, race_date, race_time, last_updated)
laps   (id, heat_id → heats ON DELETE CASCADE, driver_id → drivers,
        kart_number, column_index, lap_number, race_lap_number, stint_index,
        lap_time VARCHAR(50), lap_time_millis BIGINT,
        position VARCHAR(10), race_position VARCHAR(20),
        gap_to_leader VARCHAR(50), gap_to_previous VARCHAR(50),
        best_lap_overall BOOLEAN, best_lap_stint BOOLEAN)
```

Миграции Flyway: `V1__init_schema`, `V2__lap_column_index`, `V3__normalize_lap_data`,
`V4__race_lap_and_stint` (`race_lap_number`, `stint_index`, `race_position`).
Hibernate в режиме `validate` — схему создаёт только Flyway.

### Контракты API

```jsonc
// GET /api/heats  (последние до 100 заездов, пагинация по ?start_time автоматическая)
{ "trackName": "narvskaya",
  "heats": [ { "id": "132660", "name": "Heat 47",
               "date": "2026-10-21", "time": "20:39",
               "topResult": "1. Фёдор 1:26.127 , 2. Анастасия 1:31.577",
               "type": "HEAT" },                 // HEAT (заезд) | RACE (гонка/финал)
             { "id": "132359", "name": "Финал 3", "type": "RACE", … } ] }

// GET /api/heat/{id}
{ "heatName": "Heat 47", "raceDate": "2026-10-21", "raceTime": "20:39",
  "drivers": [ { "siteId": "485", "name": "Фёдор", "kartNumber": 29,
                 "position": "P1",          // текущая позиция в гонке
                 "totalLaps": 5,            // сколько кругов проехал
                 "bestLapMillis": 86127,    // лучший круг, мс
                 "columnIndex": 0 } ],
  "laps": [ { "lapNumber": "1",           // НОМЕР КРУГА В ГОНКЕ
              "results": ["1:38.086 P1", "1:47.382 P2"],
              "details": [ { "lapTime": "1:38.086", "lapTimeMillis": 98086,
                             "position": "P1",         // позиция в стинте (как на сайте)
                             "racePosition": "P1",     // позиция в гонке (вычислена)
                             "raceLapNumber": 1, "stintIndex": 0,
                             "kartNumber": 29, "columnIndex": 0,
                             "gapToLeader": null, "gapToPrevious": null,
                             "bestLapOverall": false, "bestLapStint": false } ] } ] }
```

---

## 2. Список заездов — `GET /tracks/{slug}/heats`

### 2.1 Структура

Список — Bootstrap `list-group`, два типа элементов:

```html
<!-- заголовок дня -->
<div class="list-group-item py-3 lh-tight list-group-item-light heat-list-date">
  <b>Oct 21</b>
</div>

<!-- заезд (иконка stopwatch) -->
<div class="list-group-item list-group-item-action py-3 lh-tight">
  <div class="d-flex w-100 align-items-center justify-content-between">
    <div>
      <a href="/tracks/narvskaya/heats/132660">
        <strong class="mb-1"><i class="bi bi-stopwatch"></i> Heat 47</strong>
      </a>
      <a href="#/" id="heatSelect132660" class="heatSelector muted d-none">…</a>
    </div>
    <small>20:39</small>
  </div>
  <div class="col-12 mb-1 small">
    1. <b><a href="/tracks/narvskaya/drivers/485">Фёдор</a></b>
    1:26.127
    ,
    2. <b><a href="/tracks/narvskaya/drivers/152">Анастасия</a></b>
    1:31.577
  </div>
</div>

<!-- гонка/финал (иконка trophy) -->
<div class="list-group-item list-group-item-action py-3 lh-tight">
  <div class="d-flex w-100 align-items-center justify-content-between">
    <div>
      <a href="/tracks/narvskaya/heats/132359">
        <strong class="mb-1"><i class="bi bi-trophy"></i> Финал 3</strong>
      </a>
      …
    </div>
  </div>
</div>
```

**Два типа событий** различаются по иконке внутри ссылки на заезд:

| Иконка | Тип (`type`) | Что это |
|--------|--------------|---------|
| `bi bi-stopwatch` | `HEAT` | обычный заезд |
| `bi bi-trophy` | `RACE` | гонка / финал (`Финал 1..N`, `SWS Junior Финал`, championship-гонки) |

Fallback: если иконки нет — тип определяется по названию (`финал`/`гонка` → `RACE`, иначе `HEAT`).

### 2.2 Селекторы, которые использует `HeatListParser`

| Поле | Селектор | Источник значения |
|------|----------|-------------------|
| заголовок дня | `.list-group-item.heat-list-date > b` | `Oct 21` (без года) |
| id заезда | `a[href*='/tracks/{slug}/heats/']` → последний сегмент пути | `132660` (только цифры) |
| название | текст этой же ссылки | `Heat 47`, `Heat 32 ДЕТИ`, `Финал 3` |
| время | `small` | `20:39` |
| топ-результат | `.col-12.mb-1.small` | свободный текст (см. §2.4) |
| тип | иконка внутри ссылки (`bi-stopwatch` / `bi-trophy`) | `HEAT` / `RACE` (см. §2.1) |
| дата | вычисляется из заголовка дня | `2026-10-21` |

Дата на сайте указана **без года**, год восстанавливается эвристикой `TimingSiteFormats.resolveListDate`: список идёт от новых к старым, если месяц «прыгнул» вверх (январь после декабря) — это прошлый год.

### 2.3 Ограничения выборки и пагинация

* На одной странице ровно **50 заездов** (4 дня). Параметр `?page=N` сервером игнорируется.
* Следующая страница отдаётся ссылкой `Next` с курсором:
  `<a class="page-link" href="/tracks/narvskaya/heats?start_time=1792335700"><b>Next</b></a>`,
  т.е. пагинация — по `?start_time={unix}`.
* `HeatListParser` обходит страницы по `Next` до лимита, сохраняя порядок «свежие сверху»:
  * `MAX_HEATS = 100` — в ответе `/api/heats` не более 100 заездов;
  * `MAX_PAGES = 10` — защита от бесконечного цикла;
  * дедупликация по `id` (`LinkedHashMap`) на случай пересечения страниц.
* Сверенные счётчики на живых данных: 100 заездов = 95 `HEAT` + 5 `RACE`.

### 2.4 Формат `topResult` — три разных варианта

Один и тот же DTO-поле содержит несогласованные форматы:

```text
# обычный заезд (пробелы, без скобок)
1. Фёдор 1:26.127 , 2. Анастасия 1:31.577

# заезд чемпионата (скобки + разрыв)
1. Sergegt (51.107) , 2. Yury Ilinykh +0.497 (51.056) , 3. Вик +8.133 (51.078)

# список чемпионата (разрыв перед скобками, +Nl = отстал на круг)
1. ARTEMRACING (30.501) , 2. Daria Hazieva +20.264 (30.794) , 5. SergS8 +1l (30.612)
```

Комментарий в DTO (`// Например: "1. RK (1:14.719)"`) описывает только третий вариант. Поле нигде не парсится — отдаётся как есть.

---

## 3. Страница заезда — `GET /tracks/{slug}/heats/{id}`

### 3.1 Шапка

```html
<title>Heat 47</title>
<meta property="og:title"       content="Heat 47. 21 Oct 2026, 20:39" />
<meta property="og:description" content="1. Фёдор - 86.127, 2. Анастасия - 91.577" />
<meta property="og:image"       content="http://timing.batyrshin.name/tracks/narvskaya/heats/132660/image" />

<h4>
  <a class="btn btn-outline-secondary stat-dd-btn" href="/tracks/narvskaya/live">…</a>
  <a href="/tracks/narvskaya/heats/132633">&laquo;</a>      <!-- предыдущий заезд -->
  <i class="bi bi-stopwatch"></i> Heat 47
  <a href="/tracks/narvskaya/heats/132633">&raquo;</a>      <!-- следующий заезд -->
  <small class="text-body-secondary" style="white-space: nowrap">21 Oct 2026, 20:39</small>
</h4>

<!-- погода -->
<div>
  <img class="theme-inverted" src="/img/icons/thermometer-half.svg">+8℃,
  <img class="theme-inverted" src="/img/icons/sun.svg" /> clear sky
</div>
```

### 3.2 Таблица результатов

```html
<table class="table table-sm heat-result fix-header">
  <thead>
    <tr>
      <th scope="row"></th>
      <th scope="col" class="text-center ">1</th>     <!-- стартовая позиция -->
      <th scope="col" class="text-center ">2</th>
    </tr>
    <!-- ↓ строка аватаров: ЕСТЬ ТОЛЬКО У ЧАСТИ ЗАЕЗДОВ -->
    <tr class="link-subtle">
      <th scope="row"></th>
      <th scope="col" class="text-center ">
        <img class="avatar-thumb" src="/tracks/narvskaya/drivers/336/avatar?max_size=32"/>
      </th>
      <th scope="col" class="text-center ">
        <img class="avatar-thumb" src="/img/noavatar.png"/>
      </th>
    </tr>
    <tr class="align-top link-subtle">
      <th scope="row">Driver</th>
      <th scope="col" class="text-center ">
        <a href="/tracks/narvskaya/drivers/485">Фёдор</a>
      </th>
      <th scope="col" class="text-center ">
        <a href="/tracks/narvskaya/drivers/152">Анастасия</a>
      </th>
    </tr>
    <tr>
      <th scope="row">Kart</th>
      <th scope="col" class="text-center ">
        <a href="/tracks/narvskaya/karts/29"><span class="badge bg-secondary kart">29</span></a>
      </th>
      <th scope="col" class="text-center ">
        <a href="/tracks/narvskaya/karts/20"><span class="badge bg-secondary kart">20</span></a>
      </th>
    </tr>
  </thead>

  <tbody>
    <tr>
      <th scope="row">1</th>                              <!-- номер круга -->
      <td nowrap class="text-center ">
        1:38.086
        <span class="p"> P1</span>                         <!-- позиция -->
        <span class="g"></span>                           <!-- разрыв до лидера -->
        <span class="ig"></span>                          <!-- разрыв-интервал -->
      </td>
      <td nowrap class="text-center ">
        1:47.382
        <span class="p"> P2</span>
        <span class="g"> +9.296</span>
        <span class="ig"> +9.296</span>
      </td>
    </tr>
    <!-- классы на td: best-stint-lap, best-race-lap -->
  </tbody>

  <tfoot>
    <tr><th scope="row">Best</th><td class="text-center kart-n">1:26.127</td>…</tr>
    <tr><th scope="row">Avg</th> <td class="text-center kart-n">1:32.371</td>…</tr>
    <tr><th scope="row">Dev</th> <td class="text-center kart-n">4.471</td>…</tr>
  </tfoot>
</table>
```

### 3.3 Что извлекает `HeatParser`

| Данные | Селектор | Куда |
|--------|----------|------|
| название заезда | `h4` (клон, без `<small>`, « » → пробелы) | `ParsedHeatData.name` |
| дата/время | `h4 small.text-body-secondary` → `d MMM yyyy, HH:mm` (EN) | `raceDate` / `raceTime` |
| пилоты | `thead tr` #1 → `th[scope=col] a` → последний сегмент `href` | `drivers[].siteId`, `drivers[].name` |
| номера картов | `thead tr` #2 → `th[scope=col] a` → текст | `drivers[].kartNumber` |
| круги | `tbody tr` → `th` (номер круга) + `td` (текст без `span`) | `laps[].lapTime` |
| позиция в стинте | `span.p` | `laps[].position` |
| разрыв до лидера | `span.g` | `laps[].gapToLeader` (строкой, как есть) |
| разрыв-интервал | `span.ig` | `laps[].gapToPrevious` (строкой, как есть) |
| лучший круг стинта | класс `best-stint-lap` на `<td>` | `laps[].bestLapStint` |
| лучший круг заезда | класс `best-race-lap` на `<td>` | `laps[].bestLapOverall` |
| **номер круга в гонке** | счётчик на пилота (см. ниже) | `laps[].raceLapNumber` |
| **индекс стинта** | определяется при сбросе `span.p` в `P1` | `laps[].stintIndex` |
| **позиция в гонке** | вычисляется по сумме времён кругов | `laps[].racePosition` |
| **столбец пилота** | позиция `<td>`/`<th>` в строке | `laps[].columnIndex` |

#### Ключевое различие `position` vs `racePosition`

* `span.p` на сайте — это **позиция в стинте** (внутри одного карта), а не в гонке.
* **Номер круга в гонке** (`raceLapNumber`) — счётчик на каждого пилота (не номер строки таблицы).
* **Смена стинта** фиксируется, когда `span.p` после первого круга снова становится `P1` — в этот момент
  `stintIndex` увеличивается.
* **Позиция в гонке** (`racePosition`) считается по накопленному времени кругов: на каждом круге
  пилоты сортируются по сумме их времён, и каждому присваивается уникальная позиция (P1, P2, …).
* Дубли `span.p` (например `P1` у нескольких пилотов на одном круге) в `racePosition` устраняются.
* `columnIndex` — реальный индекс столбца пилота в таблице; именно по нему позиции круга
  раскладываются в правильные колонки (раньше позиционная нумерация могла «съезжать»).

### 3.4 Что на странице есть, но **не извлекается**

| Данные | Селектор / формат |
|---------|-------------------|
| Итоги `tfoot` | `Best` / `Avg` / `Dev` (Dev = стандартное отклонение, сек) |
| Стартовые позиции | `thead tr` #0 → `th[scope=col]` = `1`, `2`, … |
| Погода | блок под `h4`: `+8℃, clear sky` |
| Соседние заезды | `h4 a[href*=/heats/]` (предыдущий/следующий) |
| Метаданные | `og:title`, `og:description`, `og:image` |
| PNG-превью | `GET /tracks/{slug}/heats/{id}/image` → `image/png` |
| Отметка пит-стопа | скрипт `POST /tracks/{slug}/heats/{id}/laps` (форма `is_not_pitstop`) — редактирование, не чтение |

> Ранее в этом разделе значились `span.g`, `span.ig`, `best-stint-lap`, `best-race-lap` — теперь они
> извлекаются (см. §3.3).

### 3.5 Семантика `.g` / `.ig` (проверено эмпирически)

На странице есть JS-переключатель `toggleGap()` с тремя режимами:

* `Show gap (leader)` → показывается `.g` — **разрыв до лидера**;
* `Show gap (interval)` → показывается `.ig` — **разрыв до участника впереди**;
* `Hide gap` → не показывается ничего.

Проверка на заездах 132660 и 132633 (4 пилота, 7 кругов) показала:

* на первом круге `.g` точно совпадает с разницей накопленных времён до лидера;
* на последующих кругах `.g` **не** равен ни разнице накопленных времён, ни разнице кругов (например, заезд 132633, круг 2: `.g = +2.288` при разнице накопленных `11.462` и разнице кругов `9.400`);
* при равных временах в строке позиция может совпадать у нескольких пилотов (в 132660 на круге 3 оба имеют `P1`), а `.g` получает префикс `(~…)` — признак приблизительного значения;
* отдельно встречается запись `+1l` — «отстал на круг».

**Вывод:** точные правила вычисления gap-ов завязаны на внутреннюю модель сайта (stint-ы, штрафы, досчёт кругов) и в HTML не выражены. Значения `.g`/`.ig` стоит **сохранять как есть строкой**, не пытаясь пересчитать.

---

## 4. Карточка пилота — `GET /tracks/{slug}/drivers/{id}`

```html
<h2>Фёдор <a href="/tracks/narvskaya/drivers/485/compare">…</a></h2>

<div style="margin-bottom: 10px;">
  <h5>Stat for 3 months</h5>
  <b>Laps:</b> 326<br/>
  <b>Heats:</b> 41
</div>

<table class="table compact tbl-rounded link-subtle header-emphasised-devider">
  <thead><tr><th>Heat</th><th>Kart</th><th>Time</th><th>When</th></tr></thead>
  <tbody>
    <tr>
      <td><a href="/tracks/narvskaya/heats/132660"><b>Heat 47</b></a></td>
      <td><a href="/tracks/narvskaya/karts/29"><span class="badge bg-secondary kart">29</span></a></td>
      <td>1:26.127</td>
      <td style="white-space: nowrap">Oct 21, 20:39</td>
    </tr>
  </tbody>
</table>

<script id="calendar-heatmap-data" type="application/json">
{"minDate":"2026-07-20","maxDate":"2026-10-22",
 "values":{"2026-07-26":5, "2026-07-28":6, … }}
</script>
```

Доступные данные:

* агрегаты за 3 месяца: `Laps`, `Heats`;
* история выступлений: заезд, карт, **лучший круг**, дата-время (`MMM d, HH:mm`);
* **JSON** активности по дням — единственный готовый JSON-эндпоинт на сайте;
* ссылка `/drivers/{id}/compare` (сравнение пилотов) — разобран не был.

---

## 5. Таблицы пилотов и картов

```html
<!-- GET /tracks/{slug}/drivers -->
<table class="table compact tbl-rounded link-subtle header-emphasised-devider">
  <thead><tr><th>#</th><th>Driver</th><th>Kart</th><th>Time в†‘</th><th>Heat</th><th>When</th></tr></thead>
  <tbody>
    <tr>
      <td>1</td>
      <td class="text-nowrap"><a href="/tracks/narvskaya/drivers/268486">Andreev</a></td>
      <td><a href="/tracks/narvskaya/karts/22"><span class="badge bg-secondary kart">22</span></a></td>
      <td>51.234</td>
      <td><a href="/tracks/narvskaya/heats/132609"><b>Heat 31</b></a></td>
      <td style="white-space: nowrap">Oct 21, 16:31</td>
    </tr>
  </tbody>
</table>

<!-- GET /tracks/{slug}/karts — колонки: Kart | Best time | Driver | Heat | When -->
```

Заголовок колонки времени пилотов на сайте содержит служебные символы (`Time в†‘`) — при парсинге по номеру колонки это важно.

---

## 6. Live-тайминг — `wss://timing.batyrshin.name/ws`

`GET /tracks/{slug}/live` отдаёт React-SPA (`/live/assets/index-*.js`). Соединение:

```js
const f = new URLSearchParams(location.search).get("dev")
  ? new WebSocket("ws://" + location.hostname + ":9001")
  : new WebSocket("wss://" + location.hostname + "/ws");
```

Формат сообщений (взят из фикстуры `tests/fixtures/gg5a.txt` репозитория
[`0x62ash/live-timing`](https://github.com/0x62ash/live-timing)):

```json
{
  "id": 105454,
  "name": "Гонки Gods 5й - Гонка 60 кругов",
  "elapsed": 32510,
  "mode": 0,
  "pitstopTimeThreshold": null,
  "drivers": [
    {
      "driver": { "id": 60719, "name": "GlebkProPitStop" },
      "number": 5,
      "position": 1,
      "bestTime": 28517,
      "stint": { "kart": 5, "number": 1, "bestTime": null, "length": 1, "avgTime": null },
      "laps": [ { "stintNum": 1, "number": 1, "time": 28517, "gapTime": null } ]
    }
  ]
}
```

Ключевое:

* **все времена в миллисекундах** (`time`, `bestTime`, `avgTime`, `gapTime`, `elapsed`);
* есть **stint-структура** (`stint.number`, `stint.length`, `stint.bestTime`, `stint.avgTime`) — пит-стопы и смены картов;
* `laps[].stintNum` связывает круг со stint-ом;
* `position` — текущая позиция в заезде;
* `mode` — режим (гонка/квалификация);
* `pitstopTimeThreshold` — порог для учёта пит-стопов.

Это **самый богатый источник** на сайте: HTML-страницы не отдают ни позицию в реальном времени, ни gap-ы, ни stint-ы. Минус: автор прямо пишет «протокол вебсокета subject to change», а сам сервер тайминга закрыт.

---

## 7. Чемпионаты

### `GET /heats?t={tournamentId}`

Та же структура `list-group-item`, но:

* ссылки на заезды ведут на **любую трассу**: `/tracks/premium/heats/132668`;
* в `<small>` — время и трасса: `22:15, premium`;
* тег чемпионата: `<span class="badge bg-tag">ГГ2026</span>`;
* формат результата — третий вариант из §2.4 (разрыв перед скобками, `+1l`);
* бывают битые ссылки на пилотов с пустым slug: `/tracks//drivers/238`.

Список турниров (из навбара): `t=16` → ГГ2026, `t=15` → OPEN2025, `t=14` → ГГ2025, `t=13` → OPEN2024, `t=12` → BATTLE2024, `t=11` → SPRING2024, `t=9` → ГГ2024, `t=8` → BATTLE2023, `t=7` → OPEN2023, `t=6` → ГГ2023, `t=3` → BATTLE2022, `t=2` → OPEN2022, `t=1` → ГГ2022.

### `GET /grids`

История гридов: карточки вида «Гонка», «Квалификация», «Битва Студентов Финал А», «Пит-Стоп Осень 7 этап Финал» и т.п. Разметка — на Bootstrap-карточках без таблиц (в отличие от списка заездов).

---

## 8. Справочник форматов значений

| Значение | Формат | Примеры | Где |
|----------|--------|---------|-----|
| Время круга | `m:ss.mmm` или `s.mmm` | `1:38.086`, `51.234` | `td` без `span` |
| Дата+время заезда | `d MMM yyyy, HH:mm` (EN) | `21 Oct 2026, 20:39` | `h4 small.text-body-secondary` |
| Дата в списке | `MMM d` (EN, без года) | `Oct 21` | `.heat-list-date > b` |
| Дата в карточке пилота | `MMM d, HH:mm` | `Oct 21, 20:39` | `td` в таблице истории |
| Время в списке | `HH:mm` | `20:39` | `small` |
| Позиция | `P<n>` | `P1`, `P12` | `span.p` |
| Разрыв | `+S.mmm`, `(~S.mmm)`, `+Nl`, пусто | `+9.296`, `(~21.883)`, `+1l` | `span.g` / `span.ig` |
| Отклонение | `S.mmm` | `4.471` | `tfoot` → `Dev` |
| id пилота | целое | `485` | последний сегмент `/drivers/{id}` |
| id карта | целое | `29` | последний сегмент `/karts/{id}` |
| id заезда | целое | `132660` | последний сегмент `/heats/{id}` |

> Все даты на сайте — **английская локаль без года в списке**. Парсер использует `Locale.ENGLISH` — при смене локали сайта формат поедет.

---

## 9. Что реализовано, а что нет

### Реализовано

* список заездов трассы → `HeatList` (id, name, date, time, topResult, **type**);
* **пагинация списка** по `?start_time=` с лимитом **100** заездов (`MAX_PAGES = 10`, дедуп по `id`);
* **тип события** `HEAT`/`RACE` (по иконке `stopwatch`/`trophy`);
* страница заезда → название, дата, время, пилоты (id, имя, карт, `columnIndex`, текущая позиция,
  число кругов, лучший круг);
* круги: номер, время (+ миллисекунды), позиция в стинте, **позиция в гонке**, **номер круга в гонке**,
  **индекс стинта**, разрывы `.g`/`.ig`, отметки лучших кругов (стинт/заезд);
* upsert в БД с полным перезаписыванием кругов (`orphanRemoval`);
* **облегчённая фоновая синхронизация** (`fixedDelay = 30s`, только сегодняшние/недавно обновлённые заезды);
* **live-тайминг**: приём сырых сообщений (`POST /api/live/push[/raw]`) и ретрансляция в STOMP `/topic/live/**`;
* Swagger UI через springdoc.

### Доступно на сайте, но не используется

| Данные | Сложность получения |
|---------|---------------------|
| Лучший/средний круг и отклонение (`tfoot`) | низкая — `tfoot tr` |
| Стартовые позиции | низкая — `thead tr` #0 |
| Погода | низкая — блок под `h4` |
| `og:description` / `og:title` | низкая — `meta[property^=og:]` |
| Статистика пилота и heatmap-JSON | средняя — отдельная страница |
| Таблицы пилотов и картов | средняя — отдельные страницы |
| Список заездов чемпионата | средняя — `hrefNeedle` надо параметризовать |

> Ранее здесь значились разрывы, отметки лучших кругов и live-тайминг как «не используется» — теперь они реализованы.
> Ограничение «история глубже 50 заездов» снято пагинацией (лимит 100, см. §2.3).

---

## 10. Найденные проблемы

> **Статус на 2026-10-22 (вторая итерация):**
> 🟢 `10.1`, `10.2`, `10.3` — **исправлены**;
> 🟡 `10.4`, `10.5` — остаются (не критичны);
> ⚪ `10.6` — частично (см. пометки).

### 10.1 🟢 ИСПРАВЛЕНО: заезды с аватарами теряются целиком

Ранее `HeatParser` жёстко адресовал строки шапки по индексу (`get(1)` / `get(2)`), из-за чего при
наличии строки аватаров индексы сдвигались и все пилоты отбрасывались. **Теперь строки ищутся по
содержимому** (`th` с текстом `Driver` / `Kart`), а не по индексу — заезды с аватарами (например `132633`,
`132359`) разбираются корректно.

### 10.2 🟢 ИСПРАВЛЕНО: сдвиг колонок

Ранее номер карта/круги раскладывались по позиции в списке пилотов, а `columnIndex` рос только для
пилотов со ссылкой — при пропуске пилота колонки «съезжали». **Теперь круги раскладываются по
физическому `columnIndex` столбца**, а не по порядковому индексу, что исключает приписывание времени
чужому пилоту.

### 10.3 🟢 ИСПРАВЛЕНО: фоновая синхронизация долбит сайт

Ранее шедулер каждые 15 секунд обходил **все** заезды БД (≈200 запросов/мин). **Теперь период `30s` и
синхронизируются только сегодняшние/недавно обновлённые заезды** (`HeatService.updateBackgroundCache`).

### 10.4 🟡 Топ-результат — разнородный свободный текст

`topResult` приходит тремя разными форматами (см. §2.4), причём в одном списке смешаны обычные заезды, заезды чемпионата и «Турнир …» — в одном JSON. Поле не структурировано, его нельзя надёжно разобрать.

### 10.5 🟡 Год в датах списка восстанавливается эвристикой

`resolveListDate` привязан к порядку «новые → старые» и к текущему календарному году. Для страницы, где заезды только за текущий сезон и список не пересекает границу года, год определяется как «текущий». Если список отдаст заезды прошлого года первыми (а не в конце), год уедет.

### 10.6 ⚪ Мелкое

* `lap_time` хранится как `VARCHAR(50)` — удобно для «DNF», но нет числового поля для сортировки/статистики; **частично решено**: добавлено числовое `lap_time_millis`. В `tfoot` сайт уже отдаёт готовые `Best`/`Avg`/`Dev`, которые можно использовать.
* `lapTime` парсится как «текст без `span`» — при появлении любого нового `span` в ячейке (например, иконки) значение станет мусорным; сейчас работает потому, что сайт их не добавляет.
* `application.properties` содержит креды БД в открытом виде, тогда как в `.env` те же данные продублированы — конфиг не читает `.env`.
* `GlobalExceptionHandler` отдаёт `ex.getMessage()` клиенту — внутренние сообщения (в т.ч. URL и стектрейс-контекст) утекают наружу.

`topResult` приходит тремя разными форматами (см. §2.4), причём в одном списке смешаны обычные заезды, заезды чемпионата и «Турнир …» — в одном JSON. Поле не структурировано, его нельзя надёжно разобрать.

### 10.5 Год в датах списка восстанавливается эвристикой

`resolveListDate` привязан к порядку «новые → старые» и к текущему календарному году. Для страницы, где заезды только за текущий сезон и список не пересекает границу года, год определяется как «текущий». Если список отдаст заезды прошлого года первыми (а не в конце), год уедет.

### 10.6 Мелкое

* `lap_time` хранится как `VARCHAR(50)` — удобно для «DNF», но нет числового поля для сортировки/статистики; в `tfoot` сайт уже отдаёт готовые `Best`/`Avg`/`Dev`, которые можно использовать.
* `lapTime` парсится как «текст без `span`» — при появлении любого нового `span` в ячейке (например, иконки) значение станет мусорным; сейчас работает потому, что сайт их не добавляет.
* `application.properties` содержит креды БД в открытом виде, тогда как в `.env` те же данные продублированы — конфиг не читает `.env`.
* `GlobalExceptionHandler` отдаёт `ex.getMessage()` клиенту — внутренние сообщения (в т.ч. URL и стектрейс-контекст) утекают наружу.

---

## 11. Как перепроверить

```bash
# список заездов
curl -A "Mozilla/5.0" https://timing.batyrshin.name/tracks/narvskaya/heats

# следующая страница списка (курсор пагинации)
curl -A "Mozilla/5.0" "https://timing.batyrshin.name/tracks/narvskaya/heats?start_time=1792335700"

# страница заезда (обычный)
curl -A "Mozilla/5.0" https://timing.batyrshin.name/tracks/narvskaya/heats/132660

# страница заезда (с аватарами → 4 строки в thead)
curl -A "Mozilla/5.0" https://timing.batyrshin.name/tracks/narvskaya/heats/132633

# страница заезда чемпионата (другая структура шапки)
curl -A "Mozilla/5.0" https://timing.batyrshin.name/tracks/narvskaya/heats/132359
```

Проверка через локальный backend:

```bash
# список: не более 100 заездов, типы HEAT/RACE
curl http://localhost:8080/api/heats

# детали заезда: круги с raceLapNumber / stintIndex / racePosition
curl http://localhost:8080/api/heat/132633
```

Скрипт-обходник выборки заездов с проверкой структуры шапки — `Survey.java` (см. историю анализа);
эталонные HTML-файлы сохраняются в `%TEMP%\opencode\`.

---

## 12. Сводка

* Данные парсятся **только** из двух страниц: список заездов и страница заезда.
* Список заездов отдаётся с пагинацией (`?start_time=`) и ограничением в **100** заездов, с типом
  события `HEAT`/`RACE`.
* Страница заезда даёт больше, чем раньше: разрывы, отметки лучших кругов, **позицию в гонке**,
  номер круга и стинт. Отдельные страницы пилотов/картов и WebSocket live-тайминга пока не используются
  (live проксируется через `/api/live/push`).
* **Исправлено:** привязка к индексам строк в `thead` (каждый пятый заезд парсился пустым),
  сдвиг колонок и агрессивная фоновая синхронизация.
* **Остаётся:** разнородный `topResult` (§10.4) и эвристика года в датах (§10.5).
* Live WebSocket — источник позиций, gap-ов и stint-ов в реальном времени; протокол нестабилен,
  поэтому на стороне приложения предусмотрен REST-фоллбэк.
