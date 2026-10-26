package ninibayr.karting.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class HeatParser {

    private final MeterRegistry meterRegistry;

    @Value("${karting.user-agent}")
    private String userAgent;

    public HeatParser(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.counter("karting.site.fetch.failures", "kind", "heat");
    }

    public ParsedHeatData parse(String url) throws Exception {
        long startedAt = System.nanoTime();
        Document doc;
        try {
            doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(10000)
                    .maxBodySize(0)
                    .get();
        } catch (Exception e) {
            recordFetch("heat", "error", startedAt);
            meterRegistry.counter("karting.site.fetch.failures", "kind", "heat").increment();
            throw e;
        }
        recordFetch("heat", "ok", startedAt);
        return parse(doc);
    }

    private void recordFetch(String kind, String result, long startedAtNanos) {
        Timer.builder("karting.site.fetch")
                .description("Загрузка HTML-страницы с сайта тайминга")
                .tag("kind", kind)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    ParsedHeatData parse(Document doc) {
        ParsedHeatData data = new ParsedHeatData();
        data.setName(parseHeatName(doc));

        Element dateEl = doc.selectFirst("h4 small.text-body-secondary");
        LocalDateTime racedAt = TimingSiteFormats.parseHeatDateTime(dateEl != null ? dateEl.text() : null);
        if (racedAt != null) {
            data.setRaceDate(racedAt.toLocalDate());
            data.setRaceTime(racedAt.toLocalTime());
        }

        parseWeather(doc, data);
        parseStartPositions(doc, data);

        Elements headerRows = doc.select("table.heat-result thead tr");
        Element driverRow = null;
        Element kartRow = null;
        Element positionRow = null;

        for (Element row : headerRows) {
            Element th = row.selectFirst("th");
            if (th == null) {
                continue;
            }
            String label = th.text().trim();
            if (label.equalsIgnoreCase("Driver")) {
                driverRow = row;
            } else if (label.equalsIgnoreCase("Kart")) {
                kartRow = row;
            } else if (label.isEmpty()) {
                // header row with numbers might be positions row
                if (row.select("th[scope=col]").size() > 0 && positionRow == null) {
                    // check if it looks like position numbers
                    boolean hasNumbers = row.select("th[scope=col]").stream()
                            .anyMatch(el -> el.text().trim().matches("\\d+"));
                    if (hasNumbers) {
                        positionRow = row;
                    }
                }
            }
        }

        // If not found by label, fallback to old logic but be more defensive
        if (driverRow == null && headerRows.size() >= 2) {
            // skip avatar rows
            for (int i = 0; i < headerRows.size(); i++) {
                Element row = headerRows.get(i);
                if (row.select("th[scope=col] a[href*=/drivers/]").isEmpty()) {
                    continue;
                }
                // check if this row has driver links
                if (!row.select("th[scope=col] a[href*=/drivers/]").isEmpty()) {
                    driverRow = row;
                    if (i + 1 < headerRows.size()) {
                        Element candidate = headerRows.get(i + 1);
                        if (!candidate.select("th[scope=col] a[href*=/karts/]").isEmpty() || candidate.text().toLowerCase().contains("kart")) {
                            kartRow = candidate;
                        }
                    }
                    break;
                }
            }
        }

        if (driverRow != null) {
            Elements driverElements = driverRow.select("th[scope=col]");
            Elements kartElements = kartRow != null ? kartRow.select("th[scope=col]") : null;
            Elements posElements = positionRow != null ? positionRow.select("th[scope=col]") : null;

            for (int i = 0; i < driverElements.size(); i++) {
                Element driverCol = driverElements.get(i);
                Element driverEl = driverCol.selectFirst("a[href*=/drivers/]");
                if (driverEl == null) {
                    // try any link in this column
                    driverEl = driverCol.selectFirst("a");
                }

                Element kartEl = null;
                if (kartElements != null && i < kartElements.size()) {
                    kartEl = kartElements.get(i).selectFirst("a[href*=/karts/]");
                    if (kartEl == null) {
                        kartEl = kartElements.get(i).selectFirst("a");
                    }
                }

                if (driverEl == null) {
                    continue;
                }

                String siteId = TimingSiteFormats.lastPathSegment(driverEl.attr("href"));
                if (siteId == null || siteId.isBlank()) {
                    continue;
                }

                ParsedHeatData.ParsedDriver driver = new ParsedHeatData.ParsedDriver();
                driver.setSiteId(siteId);
                driver.setName(driverEl.text().trim());
                // Физический индекс столбца в таблице (0-based): по нему же
                // индексируются ячейки tbody и tfoot.
                driver.setColumnIndex(i);
                driver.setKartNumber(parseKartNumber(kartEl));
                if (posElements != null && i < posElements.size()) {
                    String sp = posElements.get(i).text().trim();
                    if (sp.matches("\\d+")) {
                        try {
                            driver.setStartPosition(Integer.parseInt(sp));
                        } catch (Exception ignored) {
                        }
                    }
                }
                data.getDrivers().add(driver);
            }
        }

        Elements lapRows = doc.select("table.heat-result tbody tr");
        Map<Integer, ParsedHeatData.ParsedDriver> driversByColumn = driversByColumn(data);

        for (Element row : lapRows) {
            Element lapHeader = row.selectFirst("th");
            if (lapHeader == null) {
                continue;
            }
            // Первая колонка — номер круга гонки (нумерация сквозная по всей гонке).
            int raceLap;
            try {
                String lh = lapHeader.text().trim();
                if (!lh.matches("\\d+")) {
                    continue;
                }
                raceLap = Integer.parseInt(lh);
            } catch (NumberFormatException e) {
                continue;
            }

            Elements tdElements = row.select("td");
            for (int i = 0; i < tdElements.size(); i++) {
                Element td = tdElements.get(i);
                // Столбец i — физический; гонщик может отсутствовать (пустая/битая
                // ячейка шапки), тогда пропускаем именно этот столбец.
                ParsedHeatData.ParsedDriver driver = driversByColumn.get(i);
                if (driver == null) {
                    continue;
                }

                Element posEl = td.selectFirst("span.p");
                String position = posEl != null ? posEl.text().trim() : "";
                Long lapTimeMillis = parseLapTimeMillis(td);

                // Пустая ячейка = этот гонщик уже финишировал / ещё не стартовал.
                if (lapTimeMillis == null && position.isEmpty()) {
                    continue;
                }

                ParsedHeatData.ParsedLap lap = new ParsedHeatData.ParsedLap();
                lap.setLapNumber(raceLap);
                lap.setRaceLapNumber(raceLap);
                lap.setDriverSiteId(driver.getSiteId());
                lap.setColumnIndex(driver.getColumnIndex());
                lap.setLapTime(parseLapTime(td));
                lap.setLapTimeMillis(lapTimeMillis);
                lap.setPosition(position.isEmpty() ? null : position);
                lap.setRacePosition(position.isEmpty() ? null : position);

                Element gapLeader = td.selectFirst("span.g");
                if (gapLeader != null) {
                    String g = gapLeader.text().trim();
                    lap.setGapToLeader(g.isEmpty() ? null : g);
                }

                Element gapInterval = td.selectFirst("span.ig");
                if (gapInterval != null) {
                    String g = gapInterval.text().trim();
                    lap.setGapToPrevious(g.isEmpty() ? null : g);
                }

                if (td.hasClass("best-race-lap")) {
                    lap.setBestLapOverall(true);
                }
                if (td.hasClass("best-stint-lap")) {
                    lap.setBestLapStint(true);
                }

                data.getLaps().add(lap);
            }
        }

        parseFooterStats(doc, data);
        parseStints(doc, data);
        applyStintsToLaps(data);

        return data;
    }

    // ------------------------------------------------------------------
    // Стинты и карты
    // ------------------------------------------------------------------

    /** Строка footer'а вида «S1 kart», «S2 laps», «S3 best»… */
    private static final Pattern STINT_ROW = Pattern.compile("(?i)^S(\\d+)\\s+(kart|laps|best|avg|dev)$");

    /**
     * Разбирает блок S1..Sn в tfoot. Для каждого гонщика строит отрезки стинтов
     * и проставляет стартовый карт (S1 kart). Если S-строк нет (карт не меняли),
     * создаёт один стинт на весь заезд.
     */
    private static void parseStints(Document doc, ParsedHeatData data) {
        if (data.getDrivers().isEmpty()) {
            return;
        }

        Map<Integer, Map<Integer, Integer>> karts = new HashMap<>();
        Map<Integer, Map<Integer, Integer>> lapCounts = new HashMap<>();
        Map<Integer, Map<Integer, Long>> bests = new HashMap<>();
        Map<Integer, Map<Integer, Long>> avgs = new HashMap<>();
        Map<Integer, Map<Integer, Long>> devs = new HashMap<>();

        for (Element tr : doc.select("table.heat-result tfoot tr")) {
            Element th = tr.selectFirst("th");
            if (th == null) {
                continue;
            }
            Matcher m = STINT_ROW.matcher(th.text().trim());
            if (!m.matches()) {
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String kind = m.group(2).toLowerCase(Locale.ROOT);
            Elements tds = tr.select("td");
            for (int i = 0; i < tds.size(); i++) {
                String text = tds.get(i).text().trim();
                if (text.isEmpty()) {
                    // Пустая ячейка = у гонщика нет такого стинта.
                    continue;
                }
                switch (kind) {
                    case "kart" -> putValue(karts, i, number, parseInteger(text));
                    case "laps" -> putValue(lapCounts, i, number, parseInteger(text));
                    case "best" -> putValue(bests, i, number, parseSecondsToMillis(text));
                    case "avg" -> putValue(avgs, i, number, parseSecondsToMillis(text));
                    case "dev" -> putValue(devs, i, number, parseSecondsToMillis(text));
                    default -> {
                    }
                }
            }
        }

        for (ParsedHeatData.ParsedDriver driver : data.getDrivers()) {
            int column = driver.getColumnIndex();
            List<ParsedHeatData.ParsedLap> driverLaps = new ArrayList<>();
            for (ParsedHeatData.ParsedLap lap : data.getLaps()) {
                if (driver.getSiteId() != null && driver.getSiteId().equals(lap.getDriverSiteId())) {
                    driverLaps.add(lap);
                }
            }
            if (driverLaps.isEmpty()) {
                continue;
            }

            Map<Integer, Integer> counts = lapCounts.getOrDefault(column, Map.of());
            List<Integer> numbers = new ArrayList<>(counts.keySet());
            Collections.sort(numbers);

            List<ParsedHeatData.ParsedStint> stints = new ArrayList<>();
            int from = 1;
            for (Integer number : numbers) {
                Integer count = counts.get(number);
                if (count == null || count <= 0) {
                    continue;
                }
                ParsedHeatData.ParsedStint stint = new ParsedHeatData.ParsedStint();
                stint.setNumber(number);
                stint.setLapsCount(count);
                stint.setLapFrom(from);
                stint.setLapTo(from + count - 1);
                stint.setKartNumber(karts.getOrDefault(column, Map.of()).get(number));
                stint.setBestLapMillis(bests.getOrDefault(column, Map.of()).get(number));
                stint.setAvgLapMillis(avgs.getOrDefault(column, Map.of()).get(number));
                stint.setDevMillis(devs.getOrDefault(column, Map.of()).get(number));
                from = stint.getLapTo() + 1;
                stints.add(stint);
            }

            if (stints.isEmpty()) {
                // Карт не меняли: на сайте нет S-строк, считаем весь заезд одним стинтом.
                int firstLap = driverLaps.stream()
                        .map(ParsedHeatData.ParsedLap::getRaceLapNumber)
                        .filter(java.util.Objects::nonNull)
                        .min(Integer::compareTo)
                        .orElse(1);
                int lastLap = driverLaps.stream()
                        .map(ParsedHeatData.ParsedLap::getRaceLapNumber)
                        .filter(java.util.Objects::nonNull)
                        .max(Integer::compareTo)
                        .orElse(firstLap);
                ParsedHeatData.ParsedStint stint = new ParsedHeatData.ParsedStint();
                stint.setNumber(1);
                stint.setLapFrom(firstLap);
                stint.setLapTo(lastLap);
                stint.setLapsCount(driverLaps.size());
                stint.setKartNumber(driver.getKartNumber());
                stints.add(stint);
            }

            driver.setStints(stints);
            Integer startKart = stints.get(0).getKartNumber();
            if (startKart != null) {
                driver.setKartNumber(startKart);
            }
        }
    }

    private static <T> void putValue(Map<Integer, Map<Integer, T>> target,
                                     int column, int stint, T value) {
        if (value == null) {
            return;
        }
        target.computeIfAbsent(column, k -> new HashMap<>()).put(stint, value);
    }

    /** Проставляет каждому кругу номер стинта и карт. */
    private static void applyStintsToLaps(ParsedHeatData data) {
        Map<String, ParsedHeatData.ParsedDriver> bySiteId = new HashMap<>();
        for (ParsedHeatData.ParsedDriver driver : data.getDrivers()) {
            if (driver.getSiteId() != null) {
                bySiteId.put(driver.getSiteId(), driver);
            }
        }
        for (ParsedHeatData.ParsedLap lap : data.getLaps()) {
            ParsedHeatData.ParsedDriver driver = bySiteId.get(lap.getDriverSiteId());
            if (driver == null) {
                continue;
            }
            ParsedHeatData.ParsedStint stint = findStint(driver.getStints(), lap.getRaceLapNumber());
            if (stint != null) {
                lap.setStintIndex(stint.getNumber());
                lap.setKartNumber(stint.getKartNumber());
            } else {
                lap.setStintIndex(1);
                lap.setKartNumber(driver.getKartNumber());
            }
        }
    }

    private static ParsedHeatData.ParsedStint findStint(List<ParsedHeatData.ParsedStint> stints,
                                                        Integer raceLap) {
        if (stints == null || stints.isEmpty() || raceLap == null) {
            return null;
        }
        for (ParsedHeatData.ParsedStint stint : stints) {
            if (stint.getLapFrom() == null || stint.getLapTo() == null) {
                continue;
            }
            if (raceLap >= stint.getLapFrom() && raceLap <= stint.getLapTo()) {
                return stint;
            }
        }
        // Круг за пределами посчитанных диапазонов (например, страница обновилась
        // посреди круга) — считаем, что действует последний стинт.
        ParsedHeatData.ParsedStint last = stints.get(stints.size() - 1);
        if (last.getLapTo() != null && raceLap > last.getLapTo()) {
            return last;
        }
        return null;
    }

    private static Integer parseInteger(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseHeatName(Document doc) {
        Element heading = doc.selectFirst("h4");
        if (heading != null) {
            Element clone = heading.clone();
            Element small = clone.selectFirst("small");
            if (small != null) {
                small.remove();
            }
            String name = clone.text()
                    .replace('\u00ab', ' ')
                    .replace('\u00bb', ' ')
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return doc.title();
    }

    private static Integer parseKartNumber(Element kartEl) {
        if (kartEl == null) {
            return null;
        }
        String text = kartEl.text().trim();
        // try to extract number if wrapped
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Pattern PIT_LAP_SUFFIX = Pattern.compile("\\s+\\d+l\\s*$");

    private static String parseLapTime(Element td) {
        Element clone = td.clone();
        clone.select("span").remove();
        String time = clone.text().replaceAll("\\s+", " ").trim();
        // На круге заезда в пит сайт дописывает «Nl» — число кругов стинта.
        time = PIT_LAP_SUFFIX.matcher(time).replaceFirst("").trim();
        return time.isEmpty() ? null : time;
    }

    private static Long parseLapTimeMillis(Element td) {
        return parseSecondsToMillis(parseLapTime(td));
    }

    /** «29.278» или «1:35.048» → миллисекунды. */
    private static Long parseSecondsToMillis(String text) {
        if (text == null) {
            return null;
        }
        String time = text.trim().replace(',', '.');
        if (time.isEmpty()) {
            return null;
        }
        try {
            if (time.contains(":")) {
                String[] parts = time.split(":");
                long minutes = Long.parseLong(parts[0].trim());
                double seconds = Double.parseDouble(parts[1].trim());
                return minutes * 60_000L + Math.round(seconds * 1000.0);
            }
            return Math.round(Double.parseDouble(time) * 1000.0);
        } catch (Exception e) {
            return null;
        }
    }

    /** Гонщики по физическому индексу столбца таблицы. */
    private static Map<Integer, ParsedHeatData.ParsedDriver> driversByColumn(ParsedHeatData data) {
        Map<Integer, ParsedHeatData.ParsedDriver> byColumn = new HashMap<>();
        for (ParsedHeatData.ParsedDriver driver : data.getDrivers()) {
            byColumn.put(driver.getColumnIndex(), driver);
        }
        return byColumn;
    }

    private static void parseWeather(Document doc, ParsedHeatData data) {
        Elements divs = doc.select("div");
        for (Element d : divs) {
            String txt = d.text();
            if (txt != null && (txt.contains("℃") || txt.contains("°C"))) {
                // try to extract simple weather string
                String weather = txt.replaceAll("\\s+", " ").trim();
                if (!weather.isEmpty()) {
                    data.setWeather(weather);
                    break;
                }
            }
        }
    }

    private static void parseStartPositions(Document doc, ParsedHeatData data) {
        // already handled in header parsing if found
    }

    private static void parseFooterStats(Document doc, ParsedHeatData data) {
        Elements footRows = doc.select("table.heat-result tfoot tr");
        for (Element tr : footRows) {
            Element th = tr.selectFirst("th");
            if (th == null) {
                continue;
            }
            String label = th.text().trim().toLowerCase();
            Elements tds = tr.select("td");
            if (tds.isEmpty()) {
                continue;
            }
            // apply to drivers by physical column index
            Map<Integer, ParsedHeatData.ParsedDriver> byColumn = driversByColumn(data);
            for (int i = 0; i < tds.size(); i++) {
                ParsedHeatData.ParsedDriver driver = byColumn.get(i);
                if (driver == null) {
                    continue;
                }
                String val = tds.get(i).text().trim();
                if (val.isEmpty()) {
                    continue;
                }
                if (label.equals("best")) {
                    driver.setBestTime(val);
                } else if (label.equals("avg")) {
                    driver.setAvgTime(val);
                } else if (label.equals("dev")) {
                    driver.setDevTime(val);
                }
            }
        }
    }
}
