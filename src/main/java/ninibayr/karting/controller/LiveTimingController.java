package ninibayr.karting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ninibayr.karting.dto.LiveSnapshot;
import ninibayr.karting.service.LiveTimingClient;
import ninibayr.karting.service.LiveTimingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/live")
@CrossOrigin(origins = "*")
public class LiveTimingController {

    private final LiveTimingService liveTimingService;
    private final LiveTimingClient liveTimingClient;
    private final ObjectMapper objectMapper;

    public LiveTimingController(LiveTimingService liveTimingService,
                                LiveTimingClient liveTimingClient,
                                ObjectMapper objectMapper) {
        this.liveTimingService = liveTimingService;
        this.liveTimingClient = liveTimingClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Текущее состояние: идёт ли сессия прямо сейчас и её последний снимок.
     * Основной эндпоинт для приложения.
     */
    @GetMapping("/current")
    public LiveSnapshot current() {
        return liveTimingService.getSnapshot();
    }

    /** Диагностика: состояние подключения к сайту тайминга. */
    @GetMapping("/status")
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

    /**
     * Ручная остановка live-сессии. Нужна для тестов и на случай «зависшей»
     * сессии: приложение сразу перестанет показывать её как живую.
     */
    @PostMapping("/stop")
    public Map<String, Object> stop() {
        boolean wasLive = liveTimingService.stopLive();
        return Map.of("status", "ok", "wasLive", wasLive);
    }

    /** Симуляция: подать сообщение live-тайминга как объект. */
    @PostMapping("/push")
    public ResponseEntity<Map<String, String>> pushLiveUpdate(@RequestBody Map<String, Object> payload) {
        try {
            return push(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", String.valueOf(e.getMessage())));
        }
    }

    /** Симуляция: подать сырой JSON, как его присылает сайт. */
    @PostMapping("/push/raw")
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
