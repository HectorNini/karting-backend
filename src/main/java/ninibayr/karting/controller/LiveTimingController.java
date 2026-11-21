package ninibayr.karting.controller;

import ninibayr.karting.dto.LiveSnapshot;
import ninibayr.karting.service.LiveTimingService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/live")
@CrossOrigin(origins = "*")
public class LiveTimingController {

    private final LiveTimingService liveTimingService;

    public LiveTimingController(LiveTimingService liveTimingService) {
        this.liveTimingService = liveTimingService;
    }

    /**
     * Текущее состояние: идёт ли сессия прямо сейчас и её последний снимок.
     * Основной и единственный публичный эндпоинт live-тайминга.
     */
    @GetMapping("/current")
    public LiveSnapshot current() {
        return liveTimingService.getSnapshot();
    }
}
