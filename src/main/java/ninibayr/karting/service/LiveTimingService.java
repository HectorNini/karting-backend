package ninibayr.karting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.LiveHeatUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class LiveTimingService {

    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${karting.track.slug:narvskaya}")
    private String trackSlug;

    public LiveTimingService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    public void fetchLiveTiming() {
        try {
            // Try to fetch from site's live endpoint - but site uses WS, this is a fallback
            // We'll proxy the live data if available
        } catch (Exception e) {
            // Silently ignore
        }
    }

    public void broadcastLiveUpdate(LiveHeatUpdate update) {
        if (update == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSend("/topic/live/" + update.getTrackSlug(), update);
            if (update.getHeatId() != null) {
                messagingTemplate.convertAndSend("/topic/live/" + update.getTrackSlug() + "/" + update.getHeatId(), update);
            }
        } catch (Exception e) {
            log.debug("Failed to broadcast live update: {}", e.getMessage());
        }
    }

    public LiveHeatUpdate parseFromJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            LiveHeatUpdate update = new LiveHeatUpdate();
            if (root.has("id")) {
                update.setHeatId(root.get("id").asText());
            }
            if (root.has("name")) {
                update.setHeatName(root.get("name").asText());
            }
            update.setTrackSlug(trackSlug);
            if (root.has("elapsed")) {
                update.setElapsed(root.get("elapsed").asLong());
            }
            if (root.has("mode")) {
                update.setMode(root.get("mode").asInt());
            }
            List<LiveHeatUpdate.LiveDriver> drivers = new ArrayList<>();
            if (root.has("drivers") && root.get("drivers").isArray()) {
                for (JsonNode d : root.get("drivers")) {
                    LiveHeatUpdate.LiveDriver driver = new LiveHeatUpdate.LiveDriver();
                    if (d.has("driver")) {
                        JsonNode drv = d.get("driver");
                        if (drv.has("id")) {
                            driver.setDriverId(drv.get("id").asText());
                        }
                        if (drv.has("name")) {
                            driver.setDriverName(drv.get("name").asText());
                        }
                    }
                    if (d.has("number")) {
                        driver.setNumber(d.get("number").asInt());
                    }
                    if (d.has("position")) {
                        driver.setPosition(d.get("position").asInt());
                    }
                    if (d.has("bestTime")) {
                        driver.setBestTime(d.get("bestTime").asLong());
                    }
                    if (d.has("stint")) {
                        JsonNode st = d.get("stint");
                        LiveHeatUpdate.Stint stint = new LiveHeatUpdate.Stint();
                        if (st.has("kart")) {
                            stint.setKart(st.get("kart").asInt());
                        }
                        if (st.has("number")) {
                            stint.setNumber(st.get("number").asInt());
                        }
                        if (st.has("bestTime") && !st.get("bestTime").isNull()) {
                            stint.setBestTime(st.get("bestTime").asLong());
                        }
                        if (st.has("length")) {
                            stint.setLength(st.get("length").asInt());
                        }
                        if (st.has("avgTime") && !st.get("avgTime").isNull()) {
                            stint.setAvgTime(st.get("avgTime").asLong());
                        }
                        driver.setStint(stint);
                    }
                    if (d.has("laps") && d.get("laps").isArray()) {
                        List<LiveHeatUpdate.LiveLap> laps = new ArrayList<>();
                        for (JsonNode l : d.get("laps")) {
                            LiveHeatUpdate.LiveLap lap = new LiveHeatUpdate.LiveLap();
                            if (l.has("stintNum")) {
                                lap.setStintNum(l.get("stintNum").asInt());
                            }
                            if (l.has("number")) {
                                lap.setNumber(l.get("number").asInt());
                            }
                            if (l.has("time")) {
                                lap.setTime(l.get("time").asLong());
                            }
                            if (l.has("gapTime") && !l.get("gapTime").isNull()) {
                                lap.setGapTime(l.get("gapTime").asLong());
                            }
                            laps.add(lap);
                        }
                        driver.setLaps(laps);
                    }
                    drivers.add(driver);
                }
            }
            update.setDrivers(drivers);
            return update;
        } catch (Exception e) {
            log.debug("Failed to parse live timing json: {}", e.getMessage());
            return null;
        }
    }
}
