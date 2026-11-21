package ninibayr.karting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.dto.LiveHeatUpdate;
import ninibayr.karting.dto.LiveSnapshot;
import ninibayr.karting.exception.GlobalExceptionHandler;
import ninibayr.karting.exception.InvalidHeatIdException;
import ninibayr.karting.exception.SiteRateLimitedException;
import ninibayr.karting.service.HeatService;
import ninibayr.karting.service.LiveTimingClient;
import ninibayr.karting.service.LiveTimingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {TimingController.class, LiveTimingController.class, AdminController.class})
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = "karting.admin.endpoints-enabled=true")
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GlobalExceptionHandler exceptionHandler;

    @MockBean
    private HeatService heatService;

    @MockBean
    private LiveTimingService liveTimingService;

    @MockBean
    private LiveTimingClient liveTimingClient;

    @Test
    @DisplayName("GET /api/heats: возвращает список заездов")
    void heatList_returnsData() throws Exception {
        when(heatService.getHeatList()).thenReturn(new HeatList("Нарвская", List.of(
                new HeatList.HeatItem("136223", "Heat 30", "21.11.2026", "12:00", "1:19.510"))));

        mockMvc.perform(get("/api/heats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackName").value("Нарвская"))
                .andExpect(jsonPath("$.heats[0].id").value("136223"));
    }

    @Test
    @DisplayName("GET /api/heat/{id}: возвращает детали заезда")
    void heatData_returnsDetails() throws Exception {
        when(heatService.getHeatData("136223")).thenReturn(new HeatData(
                "Heat 30", LocalDate.of(2026, 11, 21), LocalTime.of(12, 0), List.of(), List.of()));

        mockMvc.perform(get("/api/heat/136223"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heatName").value("Heat 30"))
                .andExpect(jsonPath("$.raceDate").value("2026-11-21"));
    }

    @Test
    @DisplayName("GET /api/live/current: отдаёт снимок live-тайминга")
    void liveCurrent_returnsSnapshot() throws Exception {
        when(liveTimingService.getSnapshot()).thenReturn(new LiveSnapshot(
                true, "LIVE", null, 1_000L, 5L, 500L, 2, 180_000L));

        mockMvc.perform(get("/api/live/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.ageMs").value(5))
                .andExpect(jsonPath("$.staleTimeoutMs").value(180_000));
    }

    @Test
    @DisplayName("GET /api/live/status: показывает состояние WS и live-сессии")
    void adminStatus_returnsWsState() throws Exception {
        when(liveTimingService.getSnapshot()).thenReturn(new LiveSnapshot(
                true, "LIVE", null, 1_000L, 5L, 500L, 2, 180_000L));
        when(liveTimingService.getTotalMessages()).thenReturn(42L);
        when(liveTimingClient.isConnected()).thenReturn(true);
        when(liveTimingClient.getWsUrl()).thenReturn("wss://timing.batyrshin.name/ws");
        when(liveTimingClient.getAttempt()).thenReturn(0);
        when(liveTimingClient.getLastError()).thenReturn(null);
        when(liveTimingClient.getConnectedAt()).thenReturn(1_000L);

        mockMvc.perform(get("/api/live/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.totalMessages").value(42))
                .andExpect(jsonPath("$.wsConnected").value(true))
                .andExpect(jsonPath("$.wsUrl").value("wss://timing.batyrshin.name/ws"))
                .andExpect(jsonPath("$.connectedAt").value(1_000));
    }

    @Test
    @DisplayName("POST /api/live/stop: сообщает, была ли сессия живой")
    void adminStop_returnsWasLive() throws Exception {
        when(liveTimingService.stopLive()).thenReturn(true);

        mockMvc.perform(post("/api/live/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.wasLive").value(true));
    }

    @Test
    @DisplayName("POST /api/heats/resync: передаёт limit в сервис")
    void adminResync_passesLimit() throws Exception {
        when(heatService.resyncMissingStints(5)).thenReturn(3);

        mockMvc.perform(post("/api/heats/resync").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(3));
    }

    @Test
    @DisplayName("POST /api/live/push: принимает объект и сырой JSON")
    void adminPush_acceptsPayloadAndRawJson() throws Exception {
        when(liveTimingService.handleRawMessage(anyString())).thenReturn(new LiveHeatUpdate());

        mockMvc.perform(post("/api/live/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":136223,\"elapsed\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(post("/api/live/push/raw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":136223}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("POST /api/live/push: битый JSON даёт 400")
    void adminPush_rejectsInvalidPayload() throws Exception {
        when(liveTimingService.handleRawMessage(anyString())).thenReturn(null);

        mockMvc.perform(post("/api/live/push/raw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    @DisplayName("429 сайта превращается в 503 с Retry-After")
    void siteRateLimited_returnsServiceUnavailable() throws Exception {
        when(heatService.getHeatList()).thenThrow(new SiteRateLimitedException("слишком часто", 60, 429));

        mockMvc.perform(get("/api/heats"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.retryAfterSeconds").value(60))
                .andExpect(jsonPath("$.upstreamStatus").value(429));
    }

    @Test
    @DisplayName("Некорректный id заезда превращается в 400")
    void invalidHeatId_returnsBadRequest() throws Exception {
        when(heatService.getHeatData("abc")).thenThrow(new InvalidHeatIdException("abc"));

        mockMvc.perform(get("/api/heat/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("Общие исключения маппируются в 500")
    void genericExceptions_areMapped() {
        assertThat(exceptionHandler.handleRuntimeException(new IllegalStateException("boom"))
                .getStatusCode().value()).isEqualTo(500);
        assertThat(exceptionHandler.handleGeneralException(new Exception("boom"))
                .getStatusCode().value()).isEqualTo(500);
        assertThat(exceptionHandler.handleNotFound(new org.springframework.web.servlet.resource.NoResourceFoundException(
                HttpMethod.GET, "/missing")).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("ObjectMapper доступен контроллерам")
    void objectMapperIsConfigured(@Autowired ObjectMapper objectMapper) {
        assertThat(objectMapper.convertValue(Map.of("a", 1), Map.class)).containsEntry("a", 1);
    }
}
