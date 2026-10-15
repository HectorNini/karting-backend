package ninibayr.karting.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Чистые данные, полученные с сайта, без привязки к БД
@Data
class ParsedHeatData {
    private String name;
    private List<ParsedDriver> drivers;
    private List<ParsedLap> laps;

    @Data
    static class ParsedDriver {
        private String siteId;
        private String name;
        private Integer kartNumber;
    }

    @Data
    static class ParsedLap {
        private Integer lapNumber;
        private String driverSiteId; // Чтобы связать круг с гонщиком
        private String lapTime;
        private String position;
    }
}


@Component
@Slf4j
public class HeatParser {

    public ParsedHeatData parse(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(5000)
                .get();

        ParsedHeatData data = new ParsedHeatData();
        data.setName(doc.title());
        data.setDrivers(new ArrayList<>());
        data.setLaps(new ArrayList<>());

        // 1. Парсим гонщиков и карты
        Elements headerRows = doc.select("table.heat-result thead tr");
        if (headerRows.size() >= 3) {
            Elements driverElements = headerRows.get(1).select("th[scope=col]");
            Elements kartElements = headerRows.get(2).select("th[scope=col]");

            for (int i = 1; i < driverElements.size(); i++) {
                Element driverEl = driverElements.get(i).selectFirst("a");
                Element kartEl = kartElements.get(i).selectFirst("a");

                if (driverEl != null) {
                    ParsedHeatData.ParsedDriver driver = new ParsedHeatData.ParsedDriver();
                    String href = driverEl.attr("href");
                    driver.setSiteId(href.substring(href.lastIndexOf('/') + 1));
                    driver.setName(driverEl.text().trim());

                    if (kartEl != null) {
                        try {
                            driver.setKartNumber(Integer.parseInt(kartEl.text().trim()));
                        } catch (NumberFormatException e) {
                            driver.setKartNumber(null);
                        }
                    }
                    data.getDrivers().add(driver);
                }
            }
        }

        // 2. Парсим круги
        Elements lapRows = doc.select("table.heat-result tbody tr");
        for (Element row : lapRows) {
            String lapNumberStr = row.selectFirst("th").text().trim();
            int lapNumber;
            try {
                lapNumber = Integer.parseInt(lapNumberStr);
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
                lap.setLapTime(td.text().replaceAll("\\s+", " ").trim());

                Element posEl = td.selectFirst("span.p");
                if (posEl != null) {
                    lap.setPosition(posEl.text().trim());
                }

                data.getLaps().add(lap);
            }
        }

        return data;
    }
}