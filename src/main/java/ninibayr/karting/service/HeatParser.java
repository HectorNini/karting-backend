package ninibayr.karting.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class HeatParser {

    @Value("${karting.user-agent}")
    private String userAgent;

    public ParsedHeatData parse(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(10000)
                .maxBodySize(0)
                .get();
        return parse(doc);
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

            int columnIndex = 0;
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
                driver.setColumnIndex(columnIndex);
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
                columnIndex++;
            }
        }

        Elements lapRows = doc.select("table.heat-result tbody tr");
        int rowNumber = 0;
        // На каждого гонщика (колонку) свой счётчик кругов в гонке и текущий стинт.
        int[] raceLapCounter = new int[data.getDrivers().size()];
        int[] stintCounter = new int[data.getDrivers().size()];

        for (Element row : lapRows) {
            Element lapHeader = row.selectFirst("th");
            if (lapHeader == null) {
                continue;
            }
            // Номер строки (он же номер круга внутри стинта на сайте).
            int rowLap;
            try {
                String lh = lapHeader.text().trim();
                if (!lh.matches("\\d+")) {
                    continue;
                }
                rowLap = Integer.parseInt(lh);
            } catch (NumberFormatException e) {
                continue;
            }
            rowNumber++;

            Elements tdElements = row.select("td");
            for (int i = 0; i < tdElements.size() && i < data.getDrivers().size(); i++) {
                Element td = tdElements.get(i);
                ParsedHeatData.ParsedDriver driver = data.getDrivers().get(i);

                Element posEl = td.selectFirst("span.p");
                String stintPosition = posEl != null ? posEl.text().trim() : "";
                boolean hasTime = parseLapTimeMillis(td) != null;

                // Пустая ячейка = этот гонщик уже финишировал / ещё не стартовал.
                if (!hasTime && stintPosition.isEmpty()) {
                    continue;
                }

                // Детект нового стинта: позиция снова P1 после того как гонщик уже
                // проехал хотя бы один круг (сайт перезапускает нумерацию позиций
                // в стинте при смене карта).
                if (stintPosition.equalsIgnoreCase("P1") && rowLap > 1) {
                    stintCounter[i]++;
                }
                raceLapCounter[i]++;

                ParsedHeatData.ParsedLap lap = new ParsedHeatData.ParsedLap();
                lap.setLapNumber(rowLap);
                lap.setRaceLapNumber(raceLapCounter[i]);
                lap.setStintIndex(stintCounter[i]);
                lap.setDriverSiteId(driver.getSiteId());
                lap.setColumnIndex(driver.getColumnIndex());
                lap.setLapTime(parseLapTime(td));
                lap.setLapTimeMillis(parseLapTimeMillis(td));
                lap.setPosition(stintPosition.isEmpty() ? null : stintPosition);

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

        // Позиция в гонке на каждом круге: считаем по накопленному времени.
        computeRacePositions(data);

        return data;
    }

    /**
     * Считает настоящую позицию в гонке для каждого круга каждой колонки.
     * Позиция определяется по суммарному времени гонщика на этом круге гонки
     * (т.е. по количеству пройденных кругов и накопленному времени).
     */
    private static void computeRacePositions(ParsedHeatData data) {
        // группируем круги по номеру круга в гонке
        java.util.Map<Integer, java.util.List<ParsedHeatData.ParsedLap>> byRaceLap = new java.util.TreeMap<>();
        for (ParsedHeatData.ParsedLap lap : data.getLaps()) {
            if (lap.getRaceLapNumber() == null) {
                continue;
            }
            byRaceLap.computeIfAbsent(lap.getRaceLapNumber(), k -> new java.util.ArrayList<>()).add(lap);
        }

        // накопленное время по колонкам
        java.util.Map<Integer, Long> cumulative = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, java.util.List<ParsedHeatData.ParsedLap>> e : byRaceLap.entrySet()) {
            java.util.List<ParsedHeatData.ParsedLap> lapsOnLap = e.getValue();
            final java.util.Map<Integer, Long> cumulativeSnapshot = new java.util.HashMap<>(cumulative);
            lapsOnLap.sort(java.util.Comparator.comparingLong(l -> {
                Long acc = cumulativeSnapshot.getOrDefault(l.getColumnIndex(), 0L);
                Long t = l.getLapTimeMillis();
                return acc + (t != null ? t : Long.MAX_VALUE / 4);
            }));
            int pos = 1;
            for (ParsedHeatData.ParsedLap lap : lapsOnLap) {
                lap.setRacePosition("P" + pos);
                Long t = lap.getLapTimeMillis();
                if (t != null) {
                    cumulative.merge(lap.getColumnIndex(), t, Long::sum);
                }
                pos++;
            }
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

    private static String parseLapTime(Element td) {
        Element clone = td.clone();
        clone.select("span").remove();
        String time = clone.text().replaceAll("\\s+", " ").trim();
        return time.isEmpty() ? null : time;
    }

    private static Long parseLapTimeMillis(Element td) {
        String time = parseLapTime(td);
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            if (time.contains(":")) {
                String[] parts = time.split(":");
                String minutes = parts[0];
                String seconds = parts.length > 1 ? parts[1] : "0";
                double sec = Double.parseDouble(seconds);
                long minMs = Long.parseLong(minutes) * 60_000L;
                long secMs = Math.round(sec * 1000.0);
                return minMs + secMs;
            } else {
                double sec = Double.parseDouble(time);
                return Math.round(sec * 1000.0);
            }
        } catch (Exception e) {
            return null;
        }
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
            // apply to drivers in order of columns
            for (int i = 0; i < tds.size() && i < data.getDrivers().size(); i++) {
                ParsedHeatData.ParsedDriver driver = data.getDrivers().get(i);
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
