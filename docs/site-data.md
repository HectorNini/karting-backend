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
| 8 | `wss://timing.batyrshin.name/ws` | Live-тайминг: позиции, круги, stint-ы, gap-ы | ✅ `LiveTimingClient` (WS) + ручная подача через `POST /api/live/push/raw` |
| 9 | `GET /heats?t={tournamentId}` | Список заездов чемпионата (все трассы) | ❌ |
| 10 | `GET /grids` | История гридов чемпионатов | ❌ |
| 11 | `GET /tracks/{slug}/live` | SPA-обёртка live-тайминга | ❌ |

Доступные трассы (из навбара сайта): `narvskaya` (в конфиге по умолчанию), `premium`, `drive`, `pulkovo`, `revo`; `igora` закомментирована в разметке.

---

## 1. Архитектура проекта

```
TimingController  ──►  HeatService  ──►  HeatListParser        (список заездов: пагинация, лимит 200 → HTTP-ответ)
     GET /api/heats          │        └►  HeatSyncService ─► HeatParser ─► HeatMapper ─► Postgres
     GET /api/heat/{id}      │                    ▲
                             └── кэш в БД ◄─────┘   (HeatSyncScheduler: день 30 с / ночь 10 мин,
                                                      только сегодняшние/недавние заезды)

LiveTimingController ──► LiveTimingService ──► SimpMessagingTemplate → /topic/live/{trackSlug}[/{heatId}]
     POST /api/live/push[/raw]        (приём сырых сообщений с wss://timing.batyrshin.name/ws и ретрансляция STOMP)
```

### Слои

| Пакет | Класс | Роль |
|-------|-------|------|
| `controller` | `TimingController` | REST заездов, Swagger-аннотации, `CORS *` |
| `controller` | `LiveTimingController` | приём live-данных (`/api/live/push`, `/api/live/push/raw`) |
| `config` | `WebSocketConfig` | STOMP-брокер `/topic`, эндпоинт `/ws` (+ SockJS) |
| `service` | `HeatService` | чтение из БД + фоллбэк на синхронизацию |
| `service` | `HeatSyncScheduler` | планировщик фонового обновления (день/ночь) |
| `service` | `SitePollingSchedule` | ночное окно и интервалы опроса |
| `service` | `HeatSyncService` | upsert заезда (`REQUIRES_NEW`), парсинг → маппинг → сохранение |
| `service` | `HeatListParser` | HTML → `HeatList` (список + пагинация + тип) |
| `service` | `HeatParser` | HTML → `ParsedHeatData` (детали заезда) |
| `service` | `HeatMapper` | `ParsedHeatData` ⇄ Entity ⇄ DTO |
| `service` | `LiveTimingService` | разбор live-JSON и broadcast в `/topic/live/**` |
| `service` | `TimingSiteFormats` | парсеры дат/времени сайта (`package-private`) |
| `service` | `ParsedHeatData` | промежуточная модель «чистые данные с сайта» |
| `dto` | `HeatList`, `HeatData`, `LiveHeatUpdate` | контракты REST API |
| `entity` | `TrackEntity`, `HeatEntity`, `DriverEntity`, `LapEntity`, `HeatStintEntity` | JPA |
| `repository` | 3 репозитория | Spring Data JPA |

### Схема БД (`V1`…`V5`)

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
heat_stints (id, heat_id → heats ON DELETE CASCADE, driver_id → drivers,
             stint_number, kart_number, lap_from, lap_to, laps_count,
             best_lap_millis, avg_lap_millis, dev_millis,
             UNIQUE(heat_id, driver_id, stint_number))
```

Миграции Flyway: `V1__init_schema`, `V2__lap_column_index`, `V3__normalize_lap_data`,
`V4__race_lap_and_stint` (`race_lap_number`, `stint_index`, `race_position`),
`V5__heat_stints` (таблица `heat_stints`; старые `stint_index`, посчитанные эвристикой,
обнуляются и дозаполняются пересинхронизацией).
Hibernate в режиме `validate` — схему создаёт только Flyway.

`heat_stints` — одна строка на стинт (отрезок на одном карте), а не на круг.
`laps.stint_index` (с 1) и `laps.kart_number` денормализованы: по ним приложение
рисует номер карта в каждой ячейке круга.

### Контракты API

```jsonc
// GET /api/heats  (последние до 200 заездов, пагинация по ?start_time автоматическая)
{ "trackName": "narvskaya",
  "heats": [ { "id": "132660", "name": "Heat 47",
               "date": "2026-10-21", "time": "20:39",
               "topResult": "1. Фёдор 1:26.127 , 2. Анастасия 1:31.577",
               "type": "HEAT" },                 // HEAT (заезд) | RACE (гонка/финал)
             { "id": "132359", "name": "Финал 3", "type": "RACE", … } ] }

// GET /api/heat/{id}
{ "heatName": "Heat 47", "raceDate": "2026-10-21", "raceTime": "20:39",
  "drivers": [ { "siteId": "485", "name": "Фёдор",
                 "kartNumber": 29,          // стартовый карт (S1)
                 "position": "P1",          // последняя позиция в гонке (span.p)
                 "totalLaps": 5,            // сколько кругов проехал
                 "bestLapMillis": 86127,    // лучший круг, мс
                 "columnIndex": 0,
                 "stints": [ { "number": 1, "kartNumber": 29,
                               "lapFrom": 1, "lapTo": 5, "lapsCount": 5,
                               "bestLapMillis": 86127, "avgLapMillis": 92371,
                               "devMillis": 4471 } ] } ],
  "laps": [ { "lapNumber": "1",           // НОМЕР КРУГА В ГОНКЕ
              "results": ["1:38.086 P1", "1:47.382 P2"],
              "details": [ { "lapTime": "1:38.086", "lapTimeMillis": 98086,
                             "position": "P1",         // позиция в гонке на круге (span.p)
                             "racePosition": "P1",     // то же значение (совместимость)
                             "raceLapNumber": 1, "stintIndex": 1,
                             "kartNumber": 29, "columnIndex": 0,
                             "gapToLeader": null, "gapToPrevious": null,
                             "bestLapOverall": false, "bestLapStint": false } ] } ] }
```

`stints` — по одной записи на смену карта, `lapFrom`/`lapTo` включительно.
Если карт не меняли, стинт всё равно один (синтетический).

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

* На одной странице ровно **50 заездов** (≈2–3 дня при текущей загрузке трассы). Параметр `?page=N` сервером игнорируется.
* Следующая страница отдаётся ссылкой `Next` с курсором:
  `<a class="page-link" href="/tracks/narvskaya/heats?start_time=1792335700"><b>Next</b></a>`,
  т.е. пагинация — по `?start_time={unix}`.
* `HeatListParser` обходит страницы по `Next` до лимита, сохраняя порядок «свежие сверху»:
  * `MAX_HEATS = 200` — в ответе `/api/heats` не более 200 заездов;
  * `MAX_PAGES = 20` — защита от бесконечного цикла;
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
    <tr><th scope="row">Gap</th>  <td class="text-center">…</td>…</tr>
    <tr><th scope="row">Best</th> <td class="text-center">1:26.127</td>…</tr>
    <tr><th scope="row">Avg</th>  <td class="text-center">1:32.371</td>…</tr>
    <tr><th scope="row">Dev</th>  <td class="text-center">4.471</td>…</tr>
    <tr><th scope="row">Pits</th> <td class="text-center">1</td>…</tr>
    <tr><th scope="row">Wloss</th><td class="text-center">0.670</td>…</tr>
    <!-- блок стинтов: S1..Sn × (kart, laps, best, avg, dev, wloss) -->
    <tr><th scope="row">S1 kart</th><td class="text-center">29</td>…</tr>
    <tr><th scope="row">S1 laps</th><td class="text-center">8</td>…</tr>
    <tr><th scope="row">S1 best</th><td class="text-center">29.926</td>…</tr>
    …
    <tr><th scope="row">S2 kart</th><td class="text-center">102</td>…</tr>
    <tr><th scope="row">S2 laps</th><td class="text-center">21</td>…</tr>
    …
  </tfoot>
</table>
```

Строки стинтов есть **только если карт меняли**. Пустая `<td>` = у этого пилота такого
стинта нет. `S<n> laps` — число кругов стинта, **включая заезд на пит** (последний круг
стинта). Сумма `laps` всех стинтов = числу кругов пилота.

Круг заезда в пит помечен классом `pitstop-cell`, а к времени дописан суффикс `Nl`
(число кругов стинта), например `1:35.048 7l` — парсер его срезает.

### 3.3 Что извлекает `HeatParser`

| Данные | Селектор | Куда |
|--------|----------|------|
| название заезда | `h4` (клон, без `<small>`, « » → пробелы) | `ParsedHeatData.name` |
| дата/время | `h4 small.text-body-secondary` → `d MMM yyyy, HH:mm` (EN) | `raceDate` / `raceTime` |
| пилоты | строка `thead` с `th` = `Driver` → `th[scope=col] a` → последний сегмент `href` | `drivers[].siteId`, `drivers[].name` |
| стартовые позиции | строка `thead` с числовыми `th[scope=col]` = `1`, `2`, … | `drivers[].startPosition` |
| номера картов | строка `thead` с `th` = `Kart` → `th[scope=col] a` → текст | `drivers[].kartNumber` (стартовый) |
| круги | `tbody tr` → `th` (номер круга) + `td` (текст без `span`) | `laps[].lapTime` |
| позиция в гонке | `span.p` | `laps[].position` и `laps[].racePosition` |
| разрыв до лидера | `span.g` | `laps[].gapToLeader` (строкой, как есть) |
| разрыв-интервал | `span.ig` | `laps[].gapToPrevious` (строкой, как есть) |
| лучший круг стинта | класс `best-stint-lap` на `<td>` | `laps[].bestLapStint` |
| лучший круг заезда | класс `best-race-lap` на `<td>` | `laps[].bestLapOverall` |
| **номер круга в гонке** | номер строки `tbody tr > th` | `laps[].lapNumber` = `raceLapNumber` |
| **стинты** | `tfoot` → `S<n> kart/laps/best/avg/dev` | `drivers[].stints[]` |
| **номер стинта и карт круга** | по диапазону `lapFrom..lapTo` стинта | `laps[].stintIndex`, `laps[].kartNumber` |
| **столбец пилота** | позиция `<td>`/`<th>` в строке | `laps[].columnIndex` |

#### Нумерация, стинты и позиции

* Нумерация кругов в таблице **сквозная по всей гонке** (`1..N`), а не по стинтам —
  поэтому `lapNumber == raceLapNumber == номер строки`.
* `span.p` — это **позиция в гонке** на этом круге (не внутри стинта и не пересчитанная
  нами). На первом круге сайт может показать `P2` там, где эвристика по времени давала `P1`.
* Стинты берутся из `tfoot`: `S<n> kart` — карт, `S<n> laps` — сколько кругов (включая
  заезд на пит). Диапазоны считаются накопительно от 1-го круга: S1 = `1..l1`,
  S2 = `l1+1..l1+l2`, … Последний круг стинта — это круг заезда в пит на старом карте.
* Если `S`-строк нет, создаётся один синтетический стинт на весь заезд (карт из шапки).
* `tfoot` также отдаёт `Best`/`Avg`/`Dev` за заезд и `Gap`/`Pits`/`Wloss`; парсер берёт
  `Best`/`Avg`/`Dev` (`bestTime` и т.д.), `Pits`/`Wloss` пока не используются.
* `columnIndex` — реальный индекс столбца пилота в таблице; по нему раскладываются
  круги и карты.

### 3.4 Что на странице есть, но **не извлекается**

| Данные | Селектор / формат |
|---------|-------------------|
| `Pits` / `Wloss` в `tfoot` | число пит-стопов и потеря времени (для статистики) |
| Соседние заезды | `h4 a[href*=/heats/]` (предыдущий/следующий) |
| Метаданные | `og:title`, `og:description`, `og:image` |
| PNG-превью | `GET /tracks/{slug}/heats/{id}/image` → `image/png` |
| Аватары | `thead img.avatar-thumb` |
| Отметка пит-стопа | скрипт `POST /tracks/{slug}/heats/{id}/laps` (форма `is_not_pitstop`) — редактирование, не чтение |

> `Best`/`Avg`/`Dev` из `tfoot`, стартовые позиции и погода теперь извлекаются (§3.3).
> Ранее здесь значились `span.g`, `span.ig`, `best-stint-lap`, `best-race-lap` и стинты —
> они тоже извлекаются.

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
* `laps[].stintNum` связывает круг со stint-ом, `laps[].kartNum` — карт круга (может быть `null`);
* `position` — текущая позиция в заезде;
* `mode` — режим: `0` — гонка (сайт сортирует по числу кругов, затем по `position`),
  иначе — по лучшему кругу;
* `pitstopTimeThreshold` — порог для учёта пит-стопов (в текущих данных приходит `70000`, но
  для отображения он не нужен);
* `pitlaneKarts` — номера картов, которые сейчас в питлейне (массив целых);
* `gapTime` — **интервал до машины впереди** на этом круге (не накопительный разрыв);
* `bestTime` на уровне пилота обычно `null` — лучшее время считаем сами из `laps[].time`.

### 6.1. Как подключиться и когда сессия «идёт»

Проверено вживую (октябрь 2026):

1. WebSocket требует заголовок `Origin: https://timing.batyrshin.name` — без него соединение
   отклоняется.
2. После `onopen` нужно отправить текстовое сообщение **`listen track:{slug}`**
   (например, `listen track:narvskaya`).
3. Сервер присылает **одно сообщение — последнее сохранённое состояние трассы**, даже если
   гонка закончилась много часов назад. Дальше — тишина, пока не начнётся новая сессия.
4. Во время живой сессии сообщения продолжают приходить (по мере прохождения кругов), а
   `elapsed` растёт.
5. Сообщение может приходить несколькими WebSocket-фреймами — их нужно склеивать до
   `EndOfMessage`.
6. Отдельного флага «гонка окончена» нет: признак завершения — тишина.

Поэтому «идёт прямо сейчас» определяется эвристикой: **≥ 2 сообщения с одним `id`** и
последнее не старше `karting.live.stale-seconds` (по умолчанию 180 с). Одно сообщение —
это реплей последнего состояния, а не живая гонка.

### 6.2. Что сделано в бэкенде

* `LiveTimingClient` — постоянное подключение к `wss://timing.batyrshin.name/ws`
  (`java.net.http.WebSocket`), заголовок `Origin`, отправка `listen track:{slug}`,
  переподключение с паузой 2→60 с (ночью — не чаще раза в 10 минут), склейка фреймов.
* `LiveTimingService` — разбор JSON (включая производные `totalLaps`, `bestLapTime`,
  `lastLapTime`, `lastGapTime`), состояние live/waiting/finished, автоматическое завершение
  по тишине (`@Scheduled` каждые 10 с), STOMP-рассылка, подтягивание завершённой гонки в БД
  через `HeatPrefetchService`.
* **Сообщения — это дельты, а не полный снимок.** Обычно приходит один гонщик и один круг.
  Сервис накапливает состояние так же, как официальный клиент сайта:
  * новый `id` сессии → состояние начинается заново;
  * тот же `id` → гонщики сопоставляются по `driver.id`, круги — по `laps[].number`
    (дубли не добавляются), `stint` обновляется;
  * `pitlaneKarts` меняется только если поле есть в сообщении: отсутствует → «не трогать»,
    `[]` → «питлейн опустел»;
  * `elapsed`/`pitstopTimeThreshold` при отсутствии сохраняются.
* `laps[].kartNum` сайт присылает не всегда. Сервис запоминает карт по каждому стинту из
  наблюдаемых `stint` и проставляет номер карта каждому кругу в снимке.
* После завершения гонки заезд перечитывается с HTML принудительно (`resyncOneAsync`):
  кэш мог быть сохранён старым парсером без стинтов.
* REST:
  * `GET /api/live/current` — `{live, status, heat, lastUpdateAt, ageMs, startedAt, messages, staleTimeoutMs}`
    (`status`: `LIVE` | `WAITING` | `FINISHED` | `IDLE`) — основной эндпоинт для приложения;
  * `GET /api/live/status` — диагностика: `live`, `status`, `messages`, `totalMessages`,
    `lastUpdateAt`, `ageMs`, `staleTimeoutMs`, `wsConnected`, `wsUrl`, `reconnectAttempts`,
    `lastError`, `connectedAt` (без `heat` и `startedAt`);
  * `POST /api/live/stop` — принудительно завершить сессию (тесты/ручное управление);
  * `POST /api/live/push` и `POST /api/live/push/raw` — подать сообщение вручную (симуляция);
    оба проходят через тот же конечный автомат, что и реальные данные.
* STOMP: `/topic/live/{slug}` и `/topic/live/{slug}/{heatId}` — обновления,
  `/topic/live/{slug}/status` — снимок состояния.
* Настройки: `karting.live.enabled`, `karting.live.ws-url`, `karting.live.origin`,
  `karting.live.stale-seconds` (проброшены в `compose.yaml` как `KARTING_LIVE_*`).

Контракт сообщения (реальный снимок «Финал 3», трасса `narvskaya`):

```json
{
  "id": 133304,
  "name": "Финал 3",
  "elapsed": 444968,
  "mode": 0,
  "pitstopTimeThreshold": 70000,
  "pitlaneKarts": [32, 22],
  "drivers": [
    {
      "driver": { "id": 238911, "name": "KIRILL ZOLOTOV" },
      "number": 21,
      "position": 1,
      "bestTime": null,
      "stint": { "kart": 29, "number": 7, "bestTime": null, "length": 0, "avgTime": null },
      "laps": [
        { "stintNum": 1, "number": 1, "time": 77399, "gapTime": 15, "kartNum": null }
      ]
    }
  ]
}
```

Живой пример лежит в тестах: `src/test/resources/live-sample.json`, проверки —
`LiveTimingServiceTest`.

### 6.3. Симуляция без реальной гонки

```bash
# отправить один и тот же снимок дважды — сессия станет live
curl -X POST http://localhost:8080/api/live/push/raw \
  -H 'Content-Type: application/json' \
  --data-binary @src/test/resources/live-sample.json
curl -X POST http://localhost:8080/api/live/push/raw \
  -H 'Content-Type: application/json' \
  --data-binary @src/test/resources/live-sample.json

# завершить
curl -X POST http://localhost:8080/api/live/stop

# проверить
curl http://localhost:8080/api/live/current
```

`id` в снимке можно оставить тем же, что у текущей сессии: счётчик сообщений
дойдёт до 2, и сессия станет `LIVE`. Если нужна отдельная новая сессия, поменяйте
`id` (а для наглядности — `name` и `elapsed`).

### 6.4. Как это показывает приложение

* `LiveService` опрашивает `GET /api/live/current`: каждые 2 с, пока идёт сессия, и каждые 5 с
  в тишине; опрос останавливается, когда приложение уходит в фон.
* На экране списка появляется пульсирующий баннер `LIVE` (название, число пилотов, круг,
  время, питлейн); тап открывает live-экран.
* Live-экран: секундомер (докручивается локально), тип события, позиции (по кругам, затем по
  позиции сервера), карт/стинт/номер, лучший и последний круг, интервал до машины впереди
  (`gapTime`), значок `ЛУЧШИЙ` для лучшего круга сессии, карты в питлейне, история кругов по
  тапу на пилота.
* Когда данные перестают приходить, баннер исчезает, а live-экран показывает «Сессия
  завершена» и кнопку перехода к обычному заезду.


Это **самый быстрый источник** на сайте: HTML-страница заезда отдаёт позиции и стинты,
но только по уже проеханным кругам и с задержкой на запрос страницы, а WebSocket — в реальном
времени и с интервалами. Минус: автор прямо пишет «протокол вебсокета subject to change»,
а сам сервер тайминга закрыт.

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
* **пагинация списка** по `?start_time=` с лимитом **200** заездов (`MAX_PAGES = 20`, дедуп по `id`);
* **тип события** `HEAT`/`RACE` (по иконке `stopwatch`/`trophy`);
* страница заезда → название, дата, время, пилоты (id, имя, стартовый карт, `columnIndex`,
  позиция в гонке, число кругов, лучший круг);
* круги: номер (= номер круга в гонке), время (+ миллисекунды), позиция в гонке (`span.p`),
  разрывы `.g`/`.ig`, отметки лучших кругов (стинт/заезд);
* **стинты и карты**: `tfoot` S1..Sn → `heat_stints` (диапазоны кругов, карт, best/avg/dev);
  каждому кругу проставляются `stintIndex` и `kartNumber` — в приложении номер карта виден
  в каждой ячейке круга, а смена карта подсвечена золотой полосой;
* upsert в БД с полным перезаписыванием кругов и стинтов (`orphanRemoval`);
* **пересинхронизация старых заездов**: `POST /api/heats/resync?limit=N` перечитывает заезды,
  у которых есть круги, но нет стинтов; при открытии такого заезда он ставится в очередь
  автоматически;
* **облегчённая фоновая синхронизация** (`HeatSyncScheduler`: днём каждые 30 с, ночью раз в 10 мин;
  только сегодняшние/недавние заезды);
* **live-тайминг**: постоянное подключение к WebSocket сайта (`LiveTimingClient`), определение
  «гонка идёт прямо сейчас» (≥2 сообщения + отсутствие тишины 180 с), `GET /api/live/current`,
  `GET /api/live/status`, `POST /api/live/stop`, ретрансляция в STOMP `/topic/live/**`,
  ручная подача сообщений (`POST /api/live/push[/raw]`), подтягивание завершённой гонки в БД;
* Swagger UI через springdoc.

### Доступно на сайте, но не используется

| Данные | Сложность получения |
|---------|---------------------|
| `Pits` / `Wloss` в `tfoot` | низкая — `tfoot tr` |
| `og:description` / `og:title` | низкая — `meta[property^=og:]` |
| Статистика пилота и heatmap-JSON | средняя — отдельная страница |
| Таблицы пилотов и картов | средняя — отдельные страницы |
| Список заездов чемпионата | средняя — `hrefNeedle` надо параметризовать |

> Ранее здесь значились разрывы, отметки лучших кругов и live-тайминг как «не используется» — теперь они реализованы.
> `Best`/`Avg`/`Dev`, стартовые позиции и погода тоже извлекаются (см. §3.3).
> Ограничение «история глубже 50 заездов» снято пагинацией (лимит 200, см. §2.3).

---

## 10. Найденные проблемы

> **Статус на 2026-10-26 (третья итерация):**
> 🟢 `10.1`–`10.3`, `10.7` — **исправлены**;
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

Ранее шедулер каждые 15 секунд обходил **все** заезды БД (до 200 запросов за проход). **Теперь
`HeatSyncScheduler` тикает раз в 30 с и запускает обновление по интервалу: днём — 30 с, ночью —
10 мин. Синхронизируются только сегодняшние/недавно обновлённые заезды; ночью — только
сегодняшние, стартовавшие в последние 2 часа** (`HeatService.updateBackgroundCache(night)`).

### 10.4 🟡 Топ-результат — разнородный свободный текст

`topResult` приходит тремя разными форматами (см. §2.4), причём в одном списке смешаны обычные заезды, заезды чемпионата и «Турнир …» — в одном JSON. Поле не структурировано, его нельзя надёжно разобрать.

### 10.5 🟡 Год в датах списка восстанавливается эвристикой

`resolveListDate` привязан к порядку «новые → старые» и к текущему календарному году. Для страницы, где заезды только за текущий сезон и список не пересекает границу года, год определяется как «текущий». Если список отдаст заезды прошлого года первыми (а не в конце), год уедет.

### 10.6 ⚪ Мелкое

* `lap_time` хранится как `VARCHAR(50)` — удобно для «DNF», но нет числового поля для сортировки/статистики; **частично решено**: добавлено числовое `lap_time_millis`. В `tfoot` сайт уже отдаёт готовые `Best`/`Avg`/`Dev`, которые можно использовать.
* `lapTime` парсится как «текст без `span`» — при появлении любого нового `span` в ячейке (например, иконки) значение станет мусорным; сейчас работает потому, что сайт их не добавляет.
* `application.properties` содержит креды БД в открытом виде, тогда как в `.env` те же данные продублированы — конфиг не читает `.env`.
* `GlobalExceptionHandler` отдаёт `ex.getMessage()` клиенту — внутренние сообщения (в т.ч. URL и стектрейс-контекст) утекают наружу.

### 10.7 🟢 ИСПРАВЛЕНО: стинты и позиции угадывались по `span.p`

Раньше смена стинта определялась эвристикой «`span.p` снова стал `P1`», а позиция в гонке
пересчитывалась по сумме времён кругов. Это давало неверные стинты (в особенности у кругов
выезда/заезда на пит) и позиции, расходящиеся с сайтом. **Теперь:**

* стинты берутся из `tfoot` (`S<n> kart` / `S<n> laps`), диапазоны кругов считаются накопительно;
* `span.p` — готовая **позиция в гонке** (не в стинте), `racePosition` = `position`;
* номер круга в гонке — номер строки `tbody` (сквозная нумерация), `raceLapNumber` = `lapNumber`;
* у круга заезда на пит срезается суффикс `Nl` из времени;
* старые неверные `stint_index` обнулены миграцией `V5` и дозаполняются пересинхронизацией.

Заодно исправлена уникальность при повторной синхронизации: перед вставкой новых стинтов
старые удаляются явным `flush()` — иначе Hibernate вставлял новые раньше orphan-removal и
падал на `unique (heat_id, driver_id, stint_number)`. Параллельные синхронизации одного
заезда (шедулер + открытие в приложении) сериализуются блокировкой строки заезда
(`PESSIMISTIC_WRITE`).

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
# список: не более 200 заездов, типы HEAT/RACE
curl http://localhost:8080/api/heats

# детали заезда: круги с raceLapNumber / stintIndex / kartNumber / racePosition,
# у гонщиков — stints[] (карт, lapFrom..lapTo)
curl http://localhost:8080/api/heat/112490
curl http://localhost:8080/api/heat/130516

# перечитать заезды, у которых есть круги, но нет стинтов (после обновления парсера)
curl -X POST "http://localhost:8080/api/heats/resync?limit=200"
```

Эталонные HTML-файлы для парсер-тестов лежат в `src/test/resources/`
(`heat112490.html` — 6 гонщиков по 3 стинта, `heat130516.html` — 12 гонщиков,
двое с 3 стинтами и DNF). Тесты: `HeatParserTest`, `HeatListParserTest`,
`HeatMapperTest`, `HeatSyncServiceTest`, `LiveTimingServiceTest`,
`HeatSyncSchedulerTest`, `SitePollingScheduleTest`.

---

## 12. Сводка

* Данные парсятся **только** из двух страниц: список заездов и страница заезда.
* Список заездов отдаётся с пагинацией (`?start_time=`) и ограничением в **200** заездов, с типом
  события `HEAT`/`RACE`.
* Страница заезда даёт название, дату/время, погоду, стартовые позиции, гонщиков, номера картов,
  все круги (время, позиция в гонке из `span.p`, разрывы, отметки лучших кругов) и **стинты**
  из `tfoot` — с картом и диапазоном кругов. Из этого собирается `heat_stints`, а каждому кругу
  проставляются `stintIndex` и `kartNumber`.
* В приложении номер карта показан в углу каждой ячейки круга, первый круг нового карта выделен
  золотой полосой, в шапке гонщика — стартовый карт и число стинтов, в таблице — цепочка
  `9 → 101 → 6`.
* **Исправлено:** привязка к индексам строк в `thead` (каждый пятый заезд парсился пустым),
  сдвиг колонок, агрессивная фоновая синхронизация, эвристика стинтов/позиций по `span.p`
  и падение повторной синхронизации на unique-констрейнте стинтов.
* Старые заезды дозаполняются пересинхронизацией: `POST /api/heats/resync?limit=N`
  (автоматически — при открытии заезда без стинтов).
* **Остаётся:** разнородный `topResult` (§10.4) и эвристика года в датах (§10.5).
* Live WebSocket — источник позиций, gap-ов и stint-ов в реальном времени; сообщения трактуются
  как дельты и мержатся по `driver.id` / номеру круга. Протокол нестабилен, поэтому на стороне
  приложения предусмотрен REST-фоллбэк, а завершённая гонка принудительно перечитывается из HTML.
