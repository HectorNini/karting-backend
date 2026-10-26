package ninibayr.karting.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class HeatListParser {

    /** Максимальное число заездов, которое отдаём в списке. */
    private static final int MAX_HEATS = 200;
    /** Защита от бесконечного цикла при обходе страниц пагинации (10 заездов на страницу). */
    private static final int MAX_PAGES = 20;

    @Value("${karting.user-agent}")
    private String userAgent;

    @Value("${karting.track.slug}")
    private String trackSlug;

    @Value("${karting.base-url}")
    private String baseUrl;

    private final MeterRegistry meterRegistry;

    public HeatListParser(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.counter("karting.site.fetch.failures", "kind", "list");
    }

    public HeatList parseCurrentTrack() throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doParseCurrentTrack();
        } finally {
            sample.stop(Timer.builder("karting.list.parse")
                    .description("Полный цикл разбора списка заездов (все страницы)")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private HeatList doParseCurrentTrack() throws Exception {
        // Ключ — id заезда, значение — элемент. LinkedHashMap сохраняет порядок "свежие сверху".
        Map<String, HeatList.HeatItem> byId = new LinkedHashMap<>();

        String url = baseUrl + "/" + trackSlug + "/heats";
        Year year = Year.now();
        MonthDay[] carry = new MonthDay[]{null};
        LocalDate[] carryDate = new LocalDate[]{null};

        int page = 0;
        while (url != null && page < MAX_PAGES && byId.size() < MAX_HEATS) {
            long pageStartedAt = System.nanoTime();
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent(userAgent)
                        .timeout(10000)
                        .maxBodySize(0)
                        .get();
                recordFetch("ok", pageStartedAt);
                meterRegistry.counter("karting.list.pages").increment();

                List<HeatList.HeatItem> pageItems = parse(doc, trackSlug, year, carry, carryDate);
                for (HeatList.HeatItem item : pageItems) {
                    byId.putIfAbsent(item.getId(), item);
                    if (byId.size() >= MAX_HEATS) {
                        break;
                    }
                }

                // Ссылка на следующую страницу (?start_time=...)
                String nextHref = findNextHref(doc);
                if (nextHref == null || nextHref.isBlank()) {
                    break;
                }
                url = nextHref.startsWith("http") ? nextHref : baseUrl.substring(0, baseUrl.indexOf("/tracks")) + nextHref;
                page++;
            } catch (Exception e) {
                recordFetch("error", pageStartedAt);
                meterRegistry.counter("karting.site.fetch.failures", "kind", "list").increment();
                log.warn("Не удалось загрузить страницу списка заездов: {}", url, e);
                break;
            }
        }

        List<HeatList.HeatItem> heats = new ArrayList<>(byId.values());
        if (heats.size() > MAX_HEATS) {
            heats = heats.subList(0, MAX_HEATS);
        }
        meterRegistry.summary("karting.list.heats").record(heats.size());
        return new HeatList(trackSlug, heats);
    }

    private void recordFetch(String result, long startedAtNanos) {
        Timer.builder("karting.site.fetch")
                .description("Загрузка HTML-страницы с сайта тайминга")
                .tag("kind", "list")
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    private static String findNextHref(Document doc) {
        Element next = doc.selectFirst("a.page-link:has(b:matchesOwn((?i)next))");
        if (next == null) {
            // запасной вариант: ссылка с start_time и текстом Next
            for (Element a : doc.select("a[href*=start_time]")) {
                if (a.text().toLowerCase().contains("next")) {
                    next = a;
                    break;
                }
            }
        }
        return next != null ? next.attr("href") : null;
    }

    HeatList parse(Document doc, String slug) {
        Year year = Year.now();
        return new HeatList(slug,
                parse(doc, slug, year, new MonthDay[]{null}, new LocalDate[]{null}));
    }

    List<HeatList.HeatItem> parse(Document doc, String slug, Year year,
                                  MonthDay[] previousMonthDayCarry,
                                  LocalDate[] currentDateCarry) {
        List<HeatList.HeatItem> heats = new ArrayList<>();
        String hrefNeedle = "/tracks/" + slug + "/heats/";

        Year currentYear = year;
        MonthDay previousMonthDay = previousMonthDayCarry[0];
        LocalDate currentDate = currentDateCarry[0];

        Elements items = doc.select(".list-group-item");
        for (Element item : items) {
            if (item.hasClass("heat-list-date")) {
                Element dateElement = item.selectFirst("b");
                if (dateElement == null) {
                    dateElement = item.selectFirst("strong, b");
                }
                if (dateElement == null) {
                    continue;
                }
                MonthDay monthDay = TimingSiteFormats.parseListMonthDay(dateElement.text());
                if (monthDay == null) {
                    continue;
                }
                currentDate = TimingSiteFormats.resolveListDate(monthDay, previousMonthDay, currentYear);
                if (currentDate != null) {
                    currentYear = Year.from(currentDate);
                    previousMonthDay = monthDay;
                }
                continue;
            }

            Element link = item.selectFirst("a[href*='" + hrefNeedle + "']");
            if (link == null) {
                link = item.selectFirst("a[href*='/heats/']");
            }
            if (link == null) {
                continue;
            }

            String id = TimingSiteFormats.lastPathSegment(link.attr("href"));
            if (id == null || !id.matches("\\d+")) {
                continue;
            }

            HeatList.HeatItem heatItem = new HeatList.HeatItem();
            heatItem.setId(id);

            // Тип определяем по иконке внутри ссылки: stopwatch = заезд, trophy = гонка/финал.
            heatItem.setType(detectType(link));

            String name = link.text().replaceAll("\\s+", " ").trim();
            heatItem.setName(name);
            heatItem.setDate(currentDate != null ? currentDate.toString() : null);

            Element small = item.selectFirst("small");
            if (small != null) {
                String t = small.text().trim();
                if (t.contains(",")) {
                    heatItem.setTime(t.split(",")[0].trim());
                } else {
                    heatItem.setTime(t);
                }
            }

            Element resultDiv = item.selectFirst(".col-12.mb-1.small");
            if (resultDiv != null) {
                String top = resultDiv.text().replaceAll("\\s+", " ").trim();
                heatItem.setTopResult(top.isEmpty() ? null : top);
            }

            if (heatItem.getName() != null && !heatItem.getName().isEmpty()
                    && heats.stream().noneMatch(h -> h.getId().equals(heatItem.getId()))) {
                heats.add(heatItem);
            }
        }

        // сохраняем состояние между страницами
        previousMonthDayCarry[0] = previousMonthDay;
        currentDateCarry[0] = currentDate;
        return heats;
    }

    private static String detectType(Element link) {
        String html = link.html();
        if (html.contains("bi-trophy")) {
            return "RACE";
        }
        if (html.contains("bi-stopwatch")) {
            return "HEAT";
        }
        // fallback по названию
        String text = link.text().toLowerCase();
        if (text.contains("финал") || text.contains("гонка")) {
            return "RACE";
        }
        return "HEAT";
    }
}
