package ninibayr.karting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.LiveHeatUpdate;
import ninibayr.karting.dto.LiveSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Принимает сообщения live-тайминга с сайта, хранит последнее состояние,
 * определяет, идёт ли сессия прямо сейчас, и рассылает данные в STOMP.
 *
 * <p>Как определяется «идёт сейчас»: при подключении к сайту сервер всегда
 * присылает одно сообщение — последнее сохранённое состояние трассы (даже если
 * гонка давно закончилась), а дальше молчит. Во время живой сессии сообщения
 * продолжают приходить. Поэтому сессия считается живой, если получено
 * минимум два сообщения с одним и тем же id и последнее пришло не позже
 * {@code karting.live.stale-seconds} назад.</p>
 */
@Service
@Slf4j
public class LiveTimingService {

    public static final String STATUS_LIVE = "LIVE";
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_FINISHED = "FINISHED";
    public static final String STATUS_IDLE = "IDLE";

    /** Сколько сообщений с одним id нужно, чтобы считать сессию живой. */
    static final int LIVE_CONFIRM_MESSAGES = 2;

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final HeatPrefetchService heatPrefetchService;
    private final String trackSlug;
    private final long staleTimeoutMs;

    private final Object lock = new Object();

    private volatile LiveHeatUpdate current;
    private volatile String currentHeatId;
    private volatile int messagesForCurrentHeat;
    private volatile long lastMessageAt;
    private volatile boolean live;
    private volatile long startedAt;
    private volatile long totalMessages;
    /** Ручная остановка: показываем сессию завершённой, пока не придут новые данные. */
    private volatile boolean stoppedManually;

    /**
     * История картов по стинтам, собранная из live-обновлений:
     * driverId → (номер стинта → номер карта). Нужна, чтобы проставлять карт
     * каждому кругу: сайт обычно присылает {@code laps[].kartNum = null}.
     */
    private final Map<String, Map<Integer, Integer>> stintKarts = new HashMap<>();

    public LiveTimingService(SimpMessagingTemplate messagingTemplate,
                             ObjectMapper objectMapper,
                             HeatPrefetchService heatPrefetchService,
                             @Value("${karting.track.slug:narvskaya}") String trackSlug,
                             @Value("${karting.live.stale-seconds:180}") long staleSeconds) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.heatPrefetchService = heatPrefetchService;
        this.trackSlug = trackSlug;
        this.staleTimeoutMs = Math.max(10, staleSeconds) * 1000L;
    }

    // ------------------------------------------------------------------
    // Приём данных
    // ------------------------------------------------------------------

    public LiveHeatUpdate handleRawMessage(String json) {
        LiveHeatUpdate update = parseFromJson(json);
        if (update == null) {
            return null;
        }
        handleUpdate(update, System.currentTimeMillis());
        return update;
    }

    /** Вынесено отдельно, чтобы тесты могли подставить время. */
    void handleUpdate(LiveHeatUpdate update, long now) {
        boolean started = false;
        boolean ended = false;
        boolean wasLive;
        String endedHeatId = null;
        String endedHeatName = null;

        synchronized (lock) {
            wasLive = live;

            // Любые новые данные снимают ручную остановку.
            stoppedManually = false;

            String previousHeatId = currentHeatId;
            String previousHeatName = current != null ? current.getHeatName() : null;

            boolean newSession = update.getHeatId() != null && !update.getHeatId().equals(currentHeatId);
            if (newSession) {
                currentHeatId = update.getHeatId();
                messagesForCurrentHeat = 0;
                live = false;
                stintKarts.clear();
                log.info("Live-тайминг: новая сессия {} (id {})", update.getHeatName(), update.getHeatId());
            }

            messagesForCurrentHeat++;
            totalMessages++;
            lastMessageAt = now;

            // Сайт присылает не полное состояние, а дельту (обычно один гонщик
            // и один круг). Как и официальный клиент, накапливаем состояние сами.
            if (newSession || current == null) {
                current = update;
                if (current.getPitlaneKarts() == null) {
                    current.setPitlaneKarts(new ArrayList<>());
                }
            } else {
                current = merge(current, update);
            }

            recordStintKarts(update.getDrivers());
            if (current.getElapsed() != null) {
                startedAt = now - current.getElapsed();
            }

            // Одно сообщение — это «последнее состояние» с сервера, а не живая гонка.
            live = messagesForCurrentHeat >= LIVE_CONFIRM_MESSAGES;
            started = live && !wasLive;
            ended = !live && wasLive;
            if (ended) {
                // Новая сессия началась — сохраняем именно предыдущую.
                endedHeatId = previousHeatId;
                endedHeatName = previousHeatName;
            }
        }

        broadcastUpdate(update);

        if (started) {
            log.info("Live-тайминг: сессия идёт прямо сейчас — {} (id {})",
                    update.getHeatName(), update.getHeatId());
            broadcastStatus();
        } else if (ended) {
            log.info("Live-тайминг: сессия завершилась — {}", endedHeatName);
            broadcastStatus();
            persistHeat(endedHeatId);
        }
    }

    /**
     * Периодически проверяет, не замолчал ли сайт: если сообщений нет дольше
     * stale-таймаута, сессия считается завершённой.
     */
    @Scheduled(fixedDelay = 10000)
    public void checkStaleness() {
        LiveHeatUpdate finished = null;
        synchronized (lock) {
            if (live && lastMessageAt > 0 && System.currentTimeMillis() - lastMessageAt > staleTimeoutMs) {
                live = false;
                finished = current;
            }
        }
        if (finished != null) {
            log.info("Live-тайминг: нет данных {} с — считаем сессию завершённой ({})",
                    staleTimeoutMs / 1000, finished.getHeatName());
            broadcastStatus();
            persistHeat(finished.getHeatId());
        }
    }

    /** Принудительно завершить live-сессию (ручное управление/тесты). */
    public boolean stopLive() {
        boolean changed;
        synchronized (lock) {
            changed = live;
            live = false;
            stoppedManually = current != null;
        }
        if (changed) {
            log.info("Live-тайминг: остановлен вручную");
            broadcastStatus();
        }
        return changed;
    }

    /**
     * После завершения live-сессии подтягиваем заезд в БД, чтобы он появился
     * в обычном списке и открывался мгновенно. Именно принудительно: заезд мог
     * попасть в БД ещё во время гонки, и обычный прогрев его пропустит.
     */
    private void persistHeat(String heatId) {
        if (heatId == null || heatPrefetchService == null) {
            return;
        }
        try {
            heatPrefetchService.resyncOneAsync(heatId);
        } catch (Exception e) {
            log.debug("Не удалось поставить заезд {} на синхронизацию: {}", heatId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Слияние дельт
    // ------------------------------------------------------------------

    /**
     * Накапливает дельту в текущее состояние по правилам официального клиента:
     * гонщики — по id, круги — по номеру, стинт и питлейн берутся из дельты
     * только если реально пришли.
     */
    private LiveHeatUpdate merge(LiveHeatUpdate current, LiveHeatUpdate update) {
        if (update.getHeatName() != null) {
            current.setHeatName(update.getHeatName());
        }
        if (update.getTrackSlug() != null) {
            current.setTrackSlug(update.getTrackSlug());
        }
        if (update.getElapsed() != null) {
            current.setElapsed(update.getElapsed());
        }
        if (update.getMode() != null) {
            current.setMode(update.getMode());
        }
        if (update.getPitstopTimeThreshold() != null) {
            current.setPitstopTimeThreshold(update.getPitstopTimeThreshold());
        }
        if (update.getPitlaneKarts() != null) {
            // Пустой массив — «питлейн опустел»; отсутствие поля — «без изменений».
            current.setPitlaneKarts(update.getPitlaneKarts());
        }
        if (current.getPitlaneKarts() == null) {
            current.setPitlaneKarts(new ArrayList<>());
        }

        List<LiveHeatUpdate.LiveDriver> target = current.getDrivers();
        // Копия списка: в этот момент snapshot мог уже уйти в Jackson и
        // сериализоваться в другом потоке, менять список «на месте» нельзя.
        List<LiveHeatUpdate.LiveDriver> merged = target == null
                ? new ArrayList<>()
                : new ArrayList<>(target);
        Map<String, LiveHeatUpdate.LiveDriver> byDriverId = new HashMap<>();
        for (LiveHeatUpdate.LiveDriver driver : merged) {
            if (driver.getDriverId() != null) {
                byDriverId.put(driver.getDriverId(), driver);
            }
        }

        if (update.getDrivers() != null) {
            for (LiveHeatUpdate.LiveDriver incoming : update.getDrivers()) {
                LiveHeatUpdate.LiveDriver existing = incoming.getDriverId() != null
                        ? byDriverId.get(incoming.getDriverId())
                        : null;
                if (existing == null) {
                    merged.add(incoming);
                    if (incoming.getDriverId() != null) {
                        byDriverId.put(incoming.getDriverId(), incoming);
                    }
                } else {
                    mergeDriver(existing, incoming);
                }
            }
        }
        current.setDrivers(merged);
        return current;
    }

    private static void mergeDriver(LiveHeatUpdate.LiveDriver target, LiveHeatUpdate.LiveDriver incoming) {
        if (incoming.getDriverName() != null) {
            target.setDriverName(incoming.getDriverName());
        }
        if (incoming.getNumber() != null) {
            target.setNumber(incoming.getNumber());
        }
        if (incoming.getPosition() != null) {
            target.setPosition(incoming.getPosition());
        }
        if (incoming.getBestTime() != null) {
            target.setBestTime(incoming.getBestTime());
        }
        if (incoming.getStint() != null) {
            target.setStint(incoming.getStint());
        }

        List<LiveHeatUpdate.LiveLap> laps = target.getLaps();
        // Копия: старый список мог сериализоваться в момент обновления.
        List<LiveHeatUpdate.LiveLap> mergedLaps = laps == null
                ? new ArrayList<>()
                : new ArrayList<>(laps);
        Set<Integer> knownNumbers = new HashSet<>();
        for (LiveHeatUpdate.LiveLap lap : mergedLaps) {
            if (lap.getNumber() != null) {
                knownNumbers.add(lap.getNumber());
            }
        }
        if (incoming.getLaps() != null) {
            for (LiveHeatUpdate.LiveLap lap : incoming.getLaps()) {
                if (lap.getNumber() == null || knownNumbers.add(lap.getNumber())) {
                    mergedLaps.add(lap);
                }
            }
        }
        target.setLaps(mergedLaps);
        recomputeDerived(target);
    }

    private void recordStintKarts(List<LiveHeatUpdate.LiveDriver> drivers) {
        if (drivers == null) {
            return;
        }
        for (LiveHeatUpdate.LiveDriver driver : drivers) {
            if (driver.getDriverId() == null || driver.getStint() == null) {
                continue;
            }
            Integer number = driver.getStint().getNumber();
            Integer kart = driver.getStint().getKart();
            if (number != null && kart != null) {
                stintKarts.computeIfAbsent(driver.getDriverId(), k -> new HashMap<>()).put(number, kart);
            }
        }
    }

    /** Проставляет карт кругам, если сайт не прислал {@code kartNum}. */
    private void fillLapKarts(LiveHeatUpdate heat) {
        if (heat == null || heat.getDrivers() == null) {
            return;
        }
        for (LiveHeatUpdate.LiveDriver driver : heat.getDrivers()) {
            if (driver.getDriverId() == null || driver.getLaps() == null) {
                continue;
            }
            Map<Integer, Integer> karts = stintKarts.get(driver.getDriverId());
            if (karts == null || karts.isEmpty()) {
                continue;
            }
            for (LiveHeatUpdate.LiveLap lap : driver.getLaps()) {
                if (lap.getKartNum() == null && lap.getStintNum() != null) {
                    lap.setKartNum(karts.get(lap.getStintNum()));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Состояние
    // ------------------------------------------------------------------

    public LiveSnapshot getSnapshot() {
        return getSnapshot(System.currentTimeMillis());
    }

    LiveSnapshot getSnapshot(long now) {
        LiveSnapshot snapshot = new LiveSnapshot();
        synchronized (lock) {
            // Дозаполняем карты кругам по истории стинтов (сайт присылает их без kartNum).
            fillLapKarts(current);
            long age = lastMessageAt == 0 ? -1 : Math.max(0, now - lastMessageAt);
            boolean stillFresh = lastMessageAt > 0 && age <= staleTimeoutMs;
            boolean isLive = live && stillFresh;

            snapshot.setLive(isLive);
            snapshot.setHeat(current);
            snapshot.setLastUpdateAt(lastMessageAt == 0 ? null : lastMessageAt);
            snapshot.setAgeMs(age < 0 ? null : age);
            snapshot.setStartedAt(startedAt == 0 ? null : startedAt);
            snapshot.setMessages(messagesForCurrentHeat);
            snapshot.setStaleTimeoutMs(staleTimeoutMs);

            if (isLive) {
                snapshot.setStatus(STATUS_LIVE);
            } else if (current == null) {
                snapshot.setStatus(STATUS_IDLE);
            } else if (stoppedManually) {
                snapshot.setStatus(STATUS_FINISHED);
            } else if (stillFresh) {
                snapshot.setStatus(STATUS_WAITING);
            } else {
                snapshot.setStatus(STATUS_FINISHED);
            }
        }
        return snapshot;
    }

    public long getTotalMessages() {
        return totalMessages;
    }

    // ------------------------------------------------------------------
    // Рассылка
    // ------------------------------------------------------------------

    private void broadcastUpdate(LiveHeatUpdate update) {
        if (messagingTemplate == null || update == null) {
            return;
        }
        String slug = update.getTrackSlug() != null ? update.getTrackSlug() : trackSlug;
        try {
            messagingTemplate.convertAndSend("/topic/live/" + slug, update);
            if (update.getHeatId() != null) {
                messagingTemplate.convertAndSend("/topic/live/" + slug + "/" + update.getHeatId(), update);
            }
        } catch (Exception e) {
            log.debug("Не удалось разослать live-обновление: {}", e.getMessage());
        }
    }

    private void broadcastStatus() {
        if (messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSend("/topic/live/" + trackSlug + "/status", getSnapshot());
        } catch (Exception e) {
            log.debug("Не удалось разослать статус live: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Разбор JSON с сайта
    // ------------------------------------------------------------------

    public LiveHeatUpdate parseFromJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }

            LiveHeatUpdate update = new LiveHeatUpdate();
            if (root.hasNonNull("id")) {
                update.setHeatId(root.get("id").asText());
            }
            if (root.hasNonNull("name")) {
                update.setHeatName(root.get("name").asText());
            }
            update.setTrackSlug(trackSlug);
            if (root.hasNonNull("elapsed")) {
                update.setElapsed(root.get("elapsed").asLong());
            }
            if (root.hasNonNull("mode")) {
                update.setMode(root.get("mode").asInt());
            }
            if (root.hasNonNull("pitstopTimeThreshold")) {
                update.setPitstopTimeThreshold(root.get("pitstopTimeThreshold").asLong());
            }
            update.setPitlaneKarts(parsePitlane(root.get("pitlaneKarts")));

            List<LiveHeatUpdate.LiveDriver> drivers = new ArrayList<>();
            if (root.has("drivers") && root.get("drivers").isArray()) {
                for (JsonNode d : root.get("drivers")) {
                    drivers.add(parseDriver(d));
                }
            }
            update.setDrivers(drivers);
            return update;
        } catch (Exception e) {
            log.debug("Не удалось разобрать live-тайминг: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Возвращает {@code null}, если поля нет в сообщении: это значит «не менять
     * питлейн». Пустой массив — «питлейн опустел».
     */
    private List<Integer> parsePitlane(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<Integer> karts = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode kart : node) {
                if (kart != null && !kart.isNull()) {
                    karts.add(kart.asInt());
                }
            }
        }
        return karts;
    }

    private LiveHeatUpdate.LiveDriver parseDriver(JsonNode d) {
        LiveHeatUpdate.LiveDriver driver = new LiveHeatUpdate.LiveDriver();

        JsonNode drv = d.get("driver");
        if (drv != null && drv.isObject()) {
            if (drv.hasNonNull("id")) {
                driver.setDriverId(drv.get("id").asText());
            }
            if (drv.hasNonNull("name")) {
                driver.setDriverName(drv.get("name").asText());
            }
        }
        if (d.hasNonNull("number")) {
            driver.setNumber(d.get("number").asInt());
        }
        if (d.hasNonNull("position")) {
            driver.setPosition(d.get("position").asInt());
        }
        if (d.hasNonNull("bestTime")) {
            driver.setBestTime(d.get("bestTime").asLong());
        }
        driver.setStint(parseStint(d.get("stint")));
        driver.setLaps(parseLaps(d.get("laps")));

        // Производные значения: их сервер не отдаёт (bestTime обычно null).
        recomputeDerived(driver);
        return driver;
    }

    /**
     * Пересчитывает производные поля по списку кругов. Вызывается и при разборе
     * дельты, и после слияния: в дельте обычно один круг, а итог нужен по всем.
     */
    private static void recomputeDerived(LiveHeatUpdate.LiveDriver driver) {
        List<LiveHeatUpdate.LiveLap> laps = driver.getLaps();
        if (laps == null || laps.isEmpty()) {
            driver.setTotalLaps(0);
            driver.setBestLapTime(null);
            driver.setLastLapNumber(null);
            driver.setLastLapTime(null);
            driver.setLastGapTime(null);
            return;
        }
        List<LiveHeatUpdate.LiveLap> sorted = new ArrayList<>(laps);
        sorted.sort(Comparator.comparing(l -> l.getNumber() != null ? l.getNumber() : 0));

        Long best = null;
        LiveHeatUpdate.LiveLap lastTimed = null;
        for (LiveHeatUpdate.LiveLap lap : sorted) {
            if (lap.getTime() == null) {
                continue;
            }
            if (best == null || lap.getTime() < best) {
                best = lap.getTime();
            }
            lastTimed = lap;
        }
        driver.setTotalLaps((int) sorted.stream().filter(l -> l.getTime() != null).count());
        driver.setLastLapNumber(lastTimed != null ? lastTimed.getNumber() : null);
        driver.setLastLapTime(lastTimed != null ? lastTimed.getTime() : null);
        driver.setBestLapTime(best);
        if (driver.getBestTime() == null) {
            driver.setBestTime(best);
        }
        driver.setLastGapTime(findLastGap(sorted));
    }

    /** Последний непустой gapTime (интервал до машины впереди). */
    private static Long findLastGap(List<LiveHeatUpdate.LiveLap> sortedLaps) {
        for (int i = sortedLaps.size() - 1; i >= 0; i--) {
            Long gap = sortedLaps.get(i).getGapTime();
            if (gap != null) {
                return gap;
            }
        }
        return null;
    }

    private LiveHeatUpdate.Stint parseStint(JsonNode st) {
        if (st == null || !st.isObject()) {
            return null;
        }
        LiveHeatUpdate.Stint stint = new LiveHeatUpdate.Stint();
        if (st.hasNonNull("kart")) {
            stint.setKart(st.get("kart").asInt());
        }
        if (st.hasNonNull("number")) {
            stint.setNumber(st.get("number").asInt());
        }
        if (st.hasNonNull("bestTime")) {
            stint.setBestTime(st.get("bestTime").asLong());
        }
        if (st.hasNonNull("length")) {
            stint.setLength(st.get("length").asInt());
        }
        if (st.hasNonNull("avgTime")) {
            stint.setAvgTime(st.get("avgTime").asLong());
        }
        return stint;
    }

    private List<LiveHeatUpdate.LiveLap> parseLaps(JsonNode lapsNode) {
        List<LiveHeatUpdate.LiveLap> laps = new ArrayList<>();
        if (lapsNode == null || !lapsNode.isArray()) {
            return laps;
        }
        for (JsonNode l : lapsNode) {
            LiveHeatUpdate.LiveLap lap = new LiveHeatUpdate.LiveLap();
            if (l.hasNonNull("stintNum")) {
                lap.setStintNum(l.get("stintNum").asInt());
            }
            if (l.hasNonNull("number")) {
                lap.setNumber(l.get("number").asInt());
            }
            if (l.hasNonNull("time")) {
                lap.setTime(l.get("time").asLong());
            }
            if (l.hasNonNull("gapTime")) {
                lap.setGapTime(l.get("gapTime").asLong());
            }
            if (l.hasNonNull("kartNum")) {
                lap.setKartNum(l.get("kartNum").asInt());
            }
            laps.add(lap);
        }
        return laps;
    }
}
