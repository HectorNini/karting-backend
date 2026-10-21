package ninibayr.karting.service;

import ninibayr.karting.dto.HeatList;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

@Component
public class HeatListParser {

    @Value("${karting.user-agent}")
    private String userAgent;

    @Value("${karting.track.slug}")
    private String trackSlug;

    @Value("${karting.base-url}")
    private String baseUrl;

    public HeatList parseCurrentTrack() throws Exception {
        String url = baseUrl + "/" + trackSlug + "/heats";
        Document doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(5000)
                .get();
        return parse(doc, trackSlug);
    }

    HeatList parse(Document doc, String slug) {
        List<HeatList.HeatItem> heats = new ArrayList<>();
        String hrefNeedle = "/tracks/" + slug + "/heats/";

        Year year = Year.now();
        MonthDay previousMonthDay = null;
        LocalDate currentDate = null;

        Elements items = doc.select(".list-group-item");
        for (Element item : items) {
            if (item.hasClass("heat-list-date")) {
                Element dateElement = item.selectFirst("b");
                if (dateElement == null) {
                    continue;
                }
                MonthDay monthDay = TimingSiteFormats.parseListMonthDay(dateElement.text());
                if (monthDay == null) {
                    continue;
                }
                currentDate = TimingSiteFormats.resolveListDate(monthDay, previousMonthDay, year);
                if (currentDate != null) {
                    year = Year.from(currentDate);
                    previousMonthDay = monthDay;
                }
                continue;
            }

            Element link = item.selectFirst("a[href*='" + hrefNeedle + "']");
            if (link == null) {
                continue;
            }

            String id = TimingSiteFormats.lastPathSegment(link.attr("href"));
            if (id == null || !id.matches("\\d+")) {
                continue;
            }

            HeatList.HeatItem heatItem = new HeatList.HeatItem();
            heatItem.setId(id);
            heatItem.setName(link.text().trim());
            heatItem.setDate(currentDate != null ? currentDate.toString() : null);

            Element small = item.selectFirst("small");
            if (small != null) {
                heatItem.setTime(small.text().trim());
            }

            Element resultDiv = item.selectFirst(".col-12.mb-1.small");
            if (resultDiv != null) {
                heatItem.setTopResult(resultDiv.text().trim());
            }

            if (!heatItem.getName().isEmpty() && heats.stream().noneMatch(h -> h.getId().equals(heatItem.getId()))) {
                heats.add(heatItem);
            }
        }
        return new HeatList(slug, heats);
    }
}
