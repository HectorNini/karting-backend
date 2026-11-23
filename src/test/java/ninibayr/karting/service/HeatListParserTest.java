package ninibayr.karting.service;

import com.sun.net.httpserver.HttpServer;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.exception.SiteRateLimitedException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class HeatListParserTest {

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final HeatListParser parser = new HeatListParser(registry,
            new SiteRequestGate(0, 60_000, 600_000, registry), 0);

    @Test
    @DisplayName("parse: собирает ISO-дату и переносит год на границе января и декабря")
    void parse_shouldResolveIsoDatesAndYearRollover() {
        Document doc = Jsoup.parse("""
                <div class="list-group">
                  <div class="list-group-item heat-list-date"><b>Jan 5</b></div>
                  <div class="list-group-item">
                    <a href="/tracks/narvskaya/heats/100"><strong>Heat 1</strong></a>
                    <small>12:00</small>
                    <div class="col-12 mb-1 small">1. RK 50.000</div>
                  </div>
                  <div class="list-group-item heat-list-date"><b>Dec 31</b></div>
                  <div class="list-group-item">
                    <a href="/tracks/narvskaya/heats/99"><strong>Heat 99</strong></a>
                    <small>23:50</small>
                  </div>
                </div>
                """);

        HeatList list = parser.parse(doc, "narvskaya");

        assertThat(list.getHeats()).hasSize(2);
        assertThat(list.getHeats().get(0).getId()).isEqualTo("100");
        assertThat(list.getHeats().get(0).getDate()).endsWith("-01-05");
        assertThat(list.getHeats().get(0).getTime()).isEqualTo("12:00");
        assertThat(list.getHeats().get(1).getId()).isEqualTo("99");
        assertThat(list.getHeats().get(1).getDate()).endsWith("-12-31");
        assertThat(list.getHeats().get(1).getDate().substring(0, 4))
                .isEqualTo(String.valueOf(Integer.parseInt(list.getHeats().get(0).getDate().substring(0, 4)) - 1));
    }

    @Test
    @DisplayName("parse: определяет тип события по иконке (stopwatch=заезд, trophy=гонка)")
    void parse_shouldDetectEventType() {
        Document doc = Jsoup.parse("""
                <div class="list-group">
                  <div class="list-group-item heat-list-date"><b>Oct 21</b></div>
                  <div class="list-group-item">
                    <a href="/tracks/narvskaya/heats/132660"><strong><i class="bi bi-stopwatch"></i> Heat 47</strong></a>
                  </div>
                  <div class="list-group-item">
                    <a href="/tracks/narvskaya/heats/132359"><strong><i class="bi bi-trophy"></i> Финал 3</strong></a>
                  </div>
                </div>
                """);

        HeatList list = parser.parse(doc, "narvskaya");

        assertThat(list.getHeats()).hasSize(2);
        assertThat(list.getHeats().get(0).getType()).isEqualTo("HEAT");
        assertThat(list.getHeats().get(1).getType()).isEqualTo("RACE");
    }

    @Test
    @DisplayName("parseCurrentTrack: лимит в 200 заездов снят — отдаём все страницы")
    void parseCurrentTrack_shouldReturnAllHeatsBeyond200() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> pages = new LinkedHashMap<>();
        for (int page = 0; page < 5; page++) {
            StringBuilder html = new StringBuilder("<div class=\"list-group\">")
                    .append("<div class=\"list-group-item heat-list-date\"><b>Nov 22</b></div>");
            for (int i = 0; i < 50; i++) {
                long id = 1000L - page * 50 - i;
                html.append("<div class=\"list-group-item\">")
                        .append("<a href=\"/tracks/narvskaya/heats/").append(id).append("\">")
                        .append("<i class=\"bi bi-stopwatch\"></i> Heat ").append(id)
                        .append("</a><small>20:").append(String.format("%02d", i % 60))
                        .append("</small></div>");
            }
            if (page < 4) {
                html.append("<a class=\"page-link\" href=\"/tracks/narvskaya/heats?start_time=")
                        .append(1794999926L - page).append("\"><b>Next</b></a>");
            }
            html.append("</div>");
            String key = page == 0 ? "" : "start_time=" + (1794999926L - page + 1);
            pages.put(key, html.toString());
        }
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body = pages.getOrDefault(query == null ? "" : query, "")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            HeatListParser parserWithServer = new HeatListParser(registry,
                    new SiteRequestGate(0, 60_000, 600_000, registry), 0);
            ReflectionTestUtils.setField(parserWithServer, "baseUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/tracks");
            ReflectionTestUtils.setField(parserWithServer, "trackSlug", "narvskaya");
            ReflectionTestUtils.setField(parserWithServer, "userAgent", "test-agent");

            HeatList list = parserWithServer.parseCurrentTrack();

            assertThat(list.getHeats()).hasSize(250);
            assertThat(list.getHeats().get(0).getId()).isEqualTo("1000");
            assertThat(list.getHeats().get(249).getId()).isEqualTo("751");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("429: даже при наличии кэша отдаём 503 с Retry-After")
    void parseCurrentTrack_rateLimited_shouldThrowInsteadOfStaleCache() throws Exception {
        SiteRequestGate gate = mock(SiteRequestGate.class);
        HeatListParser parserWithGate = new HeatListParser(registry, gate, 0);
        ReflectionTestUtils.setField(parserWithGate, "cachedList",
                new HeatList("narvskaya", java.util.List.of()));
        ReflectionTestUtils.setField(parserWithGate, "cachedAt", System.currentTimeMillis());
        doThrow(new SiteRateLimitedException("HTTP 429", 60, 429))
                .when(gate).beforeRequest();

        assertThatThrownBy(parserWithGate::parseCurrentTrack)
                .isInstanceOf(SiteRateLimitedException.class);
        assertThat((long) ReflectionTestUtils.getField(parserWithGate, "cachedAt"))
                .isPositive();
    }

    @Test
    @DisplayName("invalidate: сброс кэша заставляет следующий запрос идти на сайт")
    void invalidate_clearsCachedList() {
        HeatList cached = new HeatList("narvskaya", java.util.List.of());
        ReflectionTestUtils.setField(parser, "cachedList", cached);
        ReflectionTestUtils.setField(parser, "cachedAt", System.currentTimeMillis());

        parser.invalidate();

        assertThat(ReflectionTestUtils.getField(parser, "cachedList")).isNull();
        assertThat((long) ReflectionTestUtils.getField(parser, "cachedAt")).isZero();
    }
}
