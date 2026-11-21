package ninibayr.karting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ninibayr.karting.dto.LiveSnapshot;
import ninibayr.karting.service.HeatService;
import ninibayr.karting.service.LiveTimingClient;
import ninibayr.karting.service.LiveTimingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Мутирующие и диагностические эндпоинты.
 *
 * <p>По умолчанию выключены: включаются только явной настройкой
 * {@code karting.admin.endpoints-enabled=true} (например, в локальном
 * compose-файле). Обычный API остаётся только для чтения.</p>
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(prefix = "karting.admin", name = "endpoints-enabled", havingValue = "true")
@Tag(name = "Karting Admin API", description = "Диагностика и ручные действия. Выключено по умолчанию")
public class AdminController {

    private final HeatService heatService;
    private final LiveTimingService liveTimingService;
    private final LiveTimingClient liveTimingClient;
    private final ObjectMapper objectMapper;

    public AdminController(HeatService heatService,
                           LiveTimingService liveTimingService,
                           LiveTimingClient liveTimingClient,
                           ObjectMapper objectMapper) {
        this.heatService = heatService;
        this.liveTimingService = liveTimingService;
        this.liveTimingClient = liveTimingClient;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Пересинхронизировать старые заезды",
            description = "Перечитывает с сайта до `limit` заездов, у которых есть круги, но нет данных о стинтах")
    @PostMapping("/heats/resync")
    public Map<String, Object> resyncMissingStints(
            @RequestParam(defaultValue = "20") int limit) {
        int updated = heatService.resyncMissingStints(limit);
        return Map.of("status", "ok", "updated", updated);
    }

    /** Диагностика: состояние подключения к сайту тайминга. */
    @GetMapping("/live/status")
    public Map<String, Object> status() {
        LiveSnapshot snapshot = liveTimingService.getSnapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("live", snapshot.isLive());
        result.put("status", snapshot.getStatus());
        result.put("messages", snapshot.getMessages());
        result.put("totalMessages", liveTimingService.getTotalMessages());
        result.put("lastUpdateAt", snapshot.getLastUpdateAt());
        result.put("ageMs", snapshot.getAgeMs());
        result.put("staleTimeoutMs", snapshot.getStaleTimeoutMs());
        result.put("wsConnected", liveTimingClient.isConnected());
        result.put("wsUrl", liveTimingClient.getWsUrl());
        result.put("reconnectAttempts", liveTimingClient.getAttempt());
        result.put("lastError", liveTimingClient.getLastError());
        result.put("connectedAt", liveTimingClient.getConnectedAt() > 0 ? liveTimingClient.getConnectedAt() : null);
        return result;
    }

    /** Ручная остановка live-сессии. */
    @PostMapping("/live/stop")
    public Map<String, Object> stop() {
        boolean wasLive = liveTimingService.stopLive();
        return Map.of("status", "ok", "wasLive", wasLive);
    }

    /** Симуляция: подать сообщение live-тайминга как объект. */
    @PostMapping("/live/push")
    public ResponseEntity<Map<String, String>> pushLiveUpdate(@RequestBody Map<String, Object> payload) {
        try {
            return push(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", String.valueOf(e.getMessage())));
        }
    }

    /** Симуляция: подать сырой JSON, как его присылает сайт. */
    @PostMapping("/live/push/raw")
    public ResponseEntity<Map<String, String>> pushLiveUpdateRaw(@RequestBody String json) {
        return push(json);
    }

    private ResponseEntity<Map<String, String>> push(String json) {
        if (liveTimingService.handleRawMessage(json) != null) {
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
        return ResponseEntity.badRequest().body(Map.of("status", "error"));
    }
}
