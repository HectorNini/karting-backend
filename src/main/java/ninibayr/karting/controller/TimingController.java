package ninibayr.karting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.service.HeatService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@Tag(name = "Karting Timing API", description = "API для получения данных о заездах на картодроме Narvskaya")
public class TimingController {

    private final HeatService heatService;

    public TimingController(HeatService heatService) {
        this.heatService = heatService;
    }

    @Operation(summary = "Получить список всех заездов", description = "Возвращает список доступных заездов с их ID, именем и временем")
    @GetMapping("/heats")
    public HeatList getHeatList() {
        // Не оборачиваем исключения здесь: SiteRateLimitedException должен
        // дойти до GlobalExceptionHandler и превратиться в 503 + Retry-After,
        // а не в общий 500.
        return heatService.getHeatList();
    }

    @Operation(summary = "Получить детали конкретного заезда", description = "Возвращает имена гонщиков и результаты по кругам для указанного ID заезда")
    @GetMapping("/heat/{id}")
    public HeatData getHeatData(@PathVariable String id) {
        return heatService.getHeatData(id);
    }
}