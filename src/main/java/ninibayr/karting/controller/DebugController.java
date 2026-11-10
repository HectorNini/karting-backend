package ninibayr.karting.controller;

import ninibayr.karting.dto.HeatData;
import ninibayr.karting.service.HeatSyncService;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Временная диагностика production. Удалить после выяснения причины 500
 * на синхронизации заездов. Доступ только по секретному заголовку.
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private static final String DEBUG_TOKEN = "kg-dbg-9f4c2e";

    private final HeatSyncService heatSyncService;
    private final DataSource dataSource;

    @Value("${karting.base-url}")
    private String baseUrl;

    @Value("${karting.track.slug}")
    private String trackSlug;

    @Value("${karting.user-agent}")
    private String userAgent;

    public DebugController(HeatSyncService heatSyncService, DataSource dataSource) {
        this.heatSyncService = heatSyncService;
        this.dataSource = dataSource;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config(
            @RequestHeader(value = "X-Debug-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseUrl", baseUrl);
        result.put("trackSlug", trackSlug);
        result.put("userAgent", userAgent);
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("os", System.getProperty("os.name"));
        result.put("timezone", java.util.TimeZone.getDefault().getID());
        result.put("locale", java.util.Locale.getDefault().toLanguageTag());
        result.put("defaultCharset", java.nio.charset.Charset.defaultCharset().name());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/http")
    public ResponseEntity<Map<String, Object>> http(
            @RequestParam String url,
            @RequestHeader(value = "X-Debug-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        if (!url.startsWith("https://timing.batyrshin.name/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is not allowed"));
        }
        long startedAt = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        try {
            org.jsoup.Connection.Response response = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(10_000)
                    .maxBodySize(0)
                    .ignoreHttpErrors(true)
                    .execute();
            result.put("ok", true);
            result.put("status", response.statusCode());
            result.put("contentType", response.contentType());
            result.put("finalUrl", response.url().toString());
            result.put("bodyLength", response.body().length());
            result.put("bodyPrefix", response.body().substring(0, Math.min(200, response.body().length())));
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("exception", exceptionChain(e));
        }
        result.put("elapsedMs", (System.nanoTime() - startedAt) / 1_000_000);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/sync/{id}")
    public ResponseEntity<Map<String, Object>> sync(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean parseOnly,
            @RequestHeader(value = "X-Debug-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        long startedAt = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("heatId", id);
        result.put("parseOnly", parseOnly);
        try {
            if (parseOnly) {
                // Parse без записи в БД: проверяем, доступна ли страница и что из неё извлекается.
                org.jsoup.Connection.Response response = Jsoup.connect(baseUrl + "/" + trackSlug + "/heats/" + id)
                        .userAgent(userAgent)
                        .timeout(10_000)
                        .maxBodySize(0)
                        .ignoreHttpErrors(true)
                        .execute();
                result.put("httpStatus", response.statusCode());
                result.put("httpBodyLength", response.body().length());
                if (response.statusCode() >= 400) {
                    result.put("ok", false);
                    result.put("bodyPrefix", response.body().substring(0, Math.min(500, response.body().length())));
                } else {
                    result.put("ok", true);
                }
            } else {
                HeatData data = heatSyncService.syncHeat(id);
                result.put("ok", true);
                result.put("heatName", data.getHeatName());
                result.put("drivers", data.getDrivers() == null ? null : data.getDrivers().size());
                result.put("laps", data.getLaps() == null ? null : data.getLaps().size());
            }
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("exception", exceptionChain(e));
        }
        result.put("elapsedMs", (System.nanoTime() - startedAt) / 1_000_000);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/db")
    public ResponseEntity<Map<String, Object>> db(
            @RequestHeader(value = "X-Debug-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "select current_database(), current_user, pg_encoding_to_char(encoding) " +
                                     "from pg_database where datname = current_database()")) {
                    if (rs.next()) {
                        result.put("database", rs.getString(1));
                        result.put("user", rs.getString(2));
                        result.put("encoding", rs.getString(3));
                    }
                }
                result.put("heats", scalar(connection, "select count(*) from heats"));
                result.put("laps", scalar(connection, "select count(*) from laps"));
                result.put("stints", scalar(connection, "select count(*) from heat_stints"));
                result.put("drivers", scalar(connection, "select count(*) from drivers"));
                result.put("readOnly", connection.isReadOnly());

                String siteId = "__debug_" + System.currentTimeMillis();
                try (PreparedStatement ps = connection.prepareStatement(
                        "insert into heats (site_id, name, track_id) " +
                                "values (?, ?, (select id from tracks where slug = 'narvskaya'))")) {
                    ps.setString(1, siteId);
                    ps.setString(2, "__debug__");
                    result.put("testInsertHeats", ps.executeUpdate());
                }

                Long trackId = null;
                try (PreparedStatement ps = connection.prepareStatement(
                        "select id from tracks where slug = 'narvskaya'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            trackId = rs.getLong(1);
                        }
                    }
                }
                result.put("trackId", trackId);

                connection.rollback();
                result.put("ok", true);
            } catch (Throwable e) {
                connection.rollback();
                result.put("ok", false);
                result.put("exception", exceptionChain(e));
            }
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("exception", exceptionChain(e));
        }
        return ResponseEntity.ok(result);
    }

    private static Long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    private static boolean authorized(String token) {
        return DEBUG_TOKEN.equals(token);
    }

    private static Map<String, Object> exceptionChain(Throwable throwable) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> chain = new ArrayList<>();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 12) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("class", current.getClass().getName());
            item.put("message", current.getMessage());
            chain.add(item);
            current = current.getCause();
            depth++;
        }
        result.put("chain", chain);
        result.put("stack", stackTrace(throwable));
        return result;
    }

    private static String stackTrace(Throwable throwable) {
        java.io.StringWriter writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        String text = writer.toString();
        return text.length() > 12_000 ? text.substring(0, 12_000) : text;
    }
}
