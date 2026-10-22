package ninibayr.karting.controller;

import ninibayr.karting.dto.LiveHeatUpdate;
import ninibayr.karting.service.LiveTimingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/live")
@CrossOrigin(origins = "*")
public class LiveTimingController {

    private final LiveTimingService liveTimingService;

    public LiveTimingController(LiveTimingService liveTimingService) {
        this.liveTimingService = liveTimingService;
    }

    @PostMapping("/push")
    public ResponseEntity<Map<String, String>> pushLiveUpdate(@RequestBody Map<String, Object> payload) {
        try {
            // Try to serialize and parse
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(payload);
            LiveHeatUpdate update = liveTimingService.parseFromJson(json);
            if (update != null) {
                liveTimingService.broadcastLiveUpdate(update);
                return ResponseEntity.ok(Map.of("status", "ok"));
            }
            return ResponseEntity.badRequest().body(Map.of("status", "error"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/push/raw")
    public ResponseEntity<Map<String, String>> pushLiveUpdateRaw(@RequestBody String json) {
        try {
            LiveHeatUpdate update = liveTimingService.parseFromJson(json);
            if (update != null) {
                liveTimingService.broadcastLiveUpdate(update);
                return ResponseEntity.ok(Map.of("status", "ok"));
            }
            return ResponseEntity.badRequest().body(Map.of("status", "error"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
