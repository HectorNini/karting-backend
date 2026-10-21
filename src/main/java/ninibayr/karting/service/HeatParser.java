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
                .timeout(5000)
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

        Elements headerRows = doc.select("table.heat-result thead tr");
        if (headerRows.size() >= 3) {
            Elements driverElements = headerRows.get(1).select("th[scope=col]");
            Elements kartElements = headerRows.get(2).select("th[scope=col]");

            int columnIndex = 0;
            for (int i = 0; i < driverElements.size(); i++) {
                Element driverEl = driverElements.get(i).selectFirst("a");
                Element kartEl = i < kartElements.size() ? kartElements.get(i).selectFirst("a") : null;

                if (driverEl == null) {
                    continue;
                }

                String siteId = TimingSiteFormats.lastPathSegment(driverEl.attr("href"));
                if (siteId == null) {
                    continue;
                }

                ParsedHeatData.ParsedDriver driver = new ParsedHeatData.ParsedDriver();
                driver.setSiteId(siteId);
                driver.setName(driverEl.text().trim());
                driver.setColumnIndex(columnIndex);
                driver.setKartNumber(parseKartNumber(kartEl));
                data.getDrivers().add(driver);
                columnIndex++;
            }
        }

        Elements lapRows = doc.select("table.heat-result tbody tr");
        for (Element row : lapRows) {
            Element lapHeader = row.selectFirst("th");
            if (lapHeader == null) {
                continue;
            }
            int lapNumber;
            try {
                lapNumber = Integer.parseInt(lapHeader.text().trim());
            } catch (NumberFormatException e) {
                continue;
            }

            Elements tdElements = row.select("td");
            for (int i = 0; i < tdElements.size() && i < data.getDrivers().size(); i++) {
                Element td = tdElements.get(i);
                ParsedHeatData.ParsedDriver driver = data.getDrivers().get(i);

                ParsedHeatData.ParsedLap lap = new ParsedHeatData.ParsedLap();
                lap.setLapNumber(lapNumber);
                lap.setDriverSiteId(driver.getSiteId());
                lap.setColumnIndex(driver.getColumnIndex());
                lap.setLapTime(parseLapTime(td));

                Element posEl = td.selectFirst("span.p");
                if (posEl != null) {
                    String position = posEl.text().trim();
                    lap.setPosition(position.isEmpty() ? null : position);
                }

                data.getLaps().add(lap);
            }
        }

        return data;
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
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseLapTime(Element td) {
        Element clone = td.clone();
        clone.select("span").remove();
        return clone.text().replaceAll("\\s+", " ").trim();
    }
}
