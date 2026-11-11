package ninibayr.karting.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeatParserTest {

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final HeatParser heatParser = new HeatParser(registry,
            new SiteRequestGate(0, 60_000, 600_000, registry));

    @Test
    @DisplayName("parse: достаёт дату заезда и siteId гонщиков из HTML")
    void parse_shouldExtractDateAndDriverSiteIds() {
        Document doc = Jsoup.parse("""
                <html><head><title>Heat 33 ДЕТИ</title></head><body>
                <h4>
                  <a href="/tracks/narvskaya/heats/131187">&laquo;</a>
                  Heat 33 ДЕТИ
                  <a href="/tracks/narvskaya/heats/131197">&raquo;</a>
                  <small class="text-body-secondary">10 Oct 2026, 16:44</small>
                </h4>
                <table class="heat-result">
                  <thead>
                    <tr>
                      <th scope="row"></th>
                      <th scope="col">1</th>
                      <th scope="col">2</th>
                    </tr>
                    <tr>
                      <th scope="row">Driver</th>
                      <th scope="col"><a href="/tracks/narvskaya/drivers/7494">Игнатий</a></th>
                      <th scope="col"><a href="/tracks/narvskaya/drivers/152">Анастасия</a></th>
                    </tr>
                    <tr>
                      <th scope="row">Kart</th>
                      <th scope="col"><a href="/tracks/narvskaya/karts/33">33</a></th>
                      <th scope="col"><a href="/tracks/narvskaya/karts/39">39</a></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <th scope="row">1</th>
                      <td>2:22.523 <span class="p"> P1</span></td>
                      <td>2:28.348 <span class="p"> P2</span></td>
                    </tr>
                  </tbody>
                </table>
                </body></html>
                """);

        ParsedHeatData data = heatParser.parse(doc);

        assertThat(data.getName()).contains("Heat 33 ДЕТИ");
        assertThat(data.getRaceDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(data.getRaceTime()).isEqualTo(LocalTime.of(16, 44));
        assertThat(data.getDrivers()).extracting(ParsedHeatData.ParsedDriver::getSiteId)
                .containsExactly("7494", "152");
        assertThat(data.getDrivers()).extracting(ParsedHeatData.ParsedDriver::getKartNumber)
                .containsExactly(33, 39);
        assertThat(data.getLaps()).hasSize(2);
        assertThat(data.getLaps().get(0).getLapTime()).isEqualTo("2:22.523");
        assertThat(data.getLaps().get(0).getPosition()).isEqualTo("P1");
        assertThat(data.getLaps().get(0).getDriverSiteId()).isEqualTo("7494");
    }

    @Test
    @DisplayName("parse: без S-строк весь заезд — один стинт, карт из шапки")
    void parse_noStintRows_createsSingleStint() {
        ParsedHeatData data2 = heatParser.parse(Jsoup.parse("""
                <html><body>
                <h4>Heat<small class="text-body-secondary">10 Oct 2026, 16:44</small></h4>
                <table class="heat-result">
                  <thead>
                    <tr><th scope="row"></th><th scope="col">1</th></tr>
                    <tr><th scope="row">Driver</th><th scope="col"><a href="/x/drivers/1">Игнатий</a></th></tr>
                    <tr><th scope="row">Kart</th><th scope="col"><a href="/x/karts/33">33</a></th></tr>
                  </thead>
                  <tbody>
                    <tr><th scope="row">1</th><td>30.500 <span class="p"> P1</span></td></tr>
                    <tr><th scope="row">2</th><td>30.100 <span class="p"> P1</span></td></tr>
                    <tr><th scope="row">3</th><td>29.900 <span class="p"> P1</span></td></tr>
                  </tbody>
                </table>
                </body></html>
                """));

        ParsedHeatData.ParsedDriver driver = data2.getDrivers().get(0);
        assertThat(driver.getStints()).hasSize(1);
        assertThat(driver.getStints().get(0).getNumber()).isEqualTo(1);
        assertThat(driver.getStints().get(0).getLapFrom()).isEqualTo(1);
        assertThat(driver.getStints().get(0).getLapTo()).isEqualTo(3);
        assertThat(driver.getStints().get(0).getKartNumber()).isEqualTo(33);
        assertThat(data2.getLaps()).allSatisfy(lap -> {
            assertThat(lap.getStintIndex()).isEqualTo(1);
            assertThat(lap.getKartNumber()).isEqualTo(33);
        });
    }

    @Test
    @DisplayName("parse: драйв-заезд 112490 — 3 стинта, карт на каждом круге, время пита без «l»")
    void parse_driveHeat_shouldFillStintsAndKarts() {
        ParsedHeatData data = heatParser.parse(load("heat112490.html"));

        assertThat(data.getName()).isEqualTo("Финал А");
        assertThat(data.getRaceDate()).isEqualTo(LocalDate.of(2026, 4, 24));
        assertThat(data.getDrivers()).hasSize(6);
        assertThat(data.getLaps()).hasSize(6 * 42);

        assertThat(data.getDrivers()).extracting(d -> d.getStints().get(0).getKartNumber())
                .containsExactly(1, 6, 4, 9, 5, 3);

        for (ParsedHeatData.ParsedDriver driver : data.getDrivers()) {
            List<ParsedHeatData.ParsedStint> stints = driver.getStints();
            assertThat(stints).hasSize(3);
            assertThat(stints).extracting(ParsedHeatData.ParsedStint::getNumber)
                    .containsExactly(1, 2, 3);
            assertThat(stints.get(0).getLapFrom()).isEqualTo(1);
            assertThat(stints.get(2).getLapTo()).isEqualTo(42);
            assertThat(stints).allSatisfy(stint -> {
                assertThat(stint.getKartNumber()).isNotNull();
                assertThat(stint.getLapsCount()).isPositive();
            });
            assertThat(stints.stream().mapToInt(ParsedHeatData.ParsedStint::getLapsCount).sum())
                    .isEqualTo(42);
            for (int i = 1; i < stints.size(); i++) {
                assertThat(stints.get(i).getLapFrom())
                        .isEqualTo(stints.get(i - 1).getLapTo() + 1);
            }
        }

        // Четвёртая колонка: 9 → 101 → 6, смена после 7-го и 30-го круга.
        ParsedHeatData.ParsedDriver kartNine = data.getDrivers().get(3);
        List<ParsedHeatData.ParsedLap> laps = lapsOf(data, kartNine.getSiteId());
        assertThat(laps).hasSize(42);
        assertThat(laps).allSatisfy(lap -> {
            assertThat(lap.getStintIndex()).isNotNull();
            assertThat(lap.getKartNumber()).isNotNull();
        });
        assertThat(laps.get(6).getKartNumber()).isEqualTo(9);
        assertThat(laps.get(7).getKartNumber()).isEqualTo(101);
        assertThat(laps.get(29).getKartNumber()).isEqualTo(101);
        assertThat(laps.get(30).getKartNumber()).isEqualTo(6);

        // Круг заезда в пит: время больше 90 с, суффикс «7l» срезан.
        assertThat(laps.get(6).getLapTimeMillis()).isGreaterThan(90_000L);
        assertThat(laps.get(6).getLapTime()).matches("\\d+:\\d{2}\\.\\d{3}");

        // Позиция в гонке — из span.p, есть на каждом круге.
        assertThat(data.getLaps()).allSatisfy(lap -> {
            assertThat(lap.getRacePosition()).isNotNull();
            assertThat(lap.getRacePosition()).matches("P\\d+");
        });
        assertThat(laps.get(0).getRacePosition()).isNotNull();
    }

    @Test
    @DisplayName("parse: заезд 130516 — DNF-гонщики, по 2–3 стинта, суммы кругов сходятся")
    void parse_mixedHeat_shouldHandleDnfDrivers() {
        ParsedHeatData data = heatParser.parse(load("heat130516.html"));

        assertThat(data.getDrivers()).hasSize(12);
        assertThat(data.getLaps()).hasSize(206);

        assertThat(data.getDrivers()).extracting(d -> d.getStints().size())
                .containsExactly(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3);
        assertThat(data.getDrivers()).extracting(d -> d.getStints().get(0).getKartNumber())
                .containsExactly(19, 13, 32, 26, 27, 14, 15, 34, 18, 17, 20, 33);

        // Последние два гонщика меняли карт дважды: 20 → 101 → 14 и 33 → 102 → 33.
        ParsedHeatData.ParsedDriver first = data.getDrivers().get(10);
        assertThat(first.getStints()).extracting(ParsedHeatData.ParsedStint::getLapFrom)
                .containsExactly(1, 2, 7);
        assertThat(first.getStints()).extracting(ParsedHeatData.ParsedStint::getLapTo)
                .containsExactly(1, 6, 16);
        assertThat(first.getStints()).extracting(ParsedHeatData.ParsedStint::getKartNumber)
                .containsExactly(20, 101, 14);

        ParsedHeatData.ParsedDriver second = data.getDrivers().get(11);
        assertThat(second.getStints()).extracting(ParsedHeatData.ParsedStint::getLapFrom)
                .containsExactly(1, 2, 3);
        assertThat(second.getStints()).extracting(ParsedHeatData.ParsedStint::getLapTo)
                .containsExactly(1, 2, 16);

        // У каждого гонщика сумма кругов стинтов равна числу его кругов в таблице.
        for (ParsedHeatData.ParsedDriver driver : data.getDrivers()) {
            List<ParsedHeatData.ParsedLap> laps = lapsOf(data, driver.getSiteId());
            assertThat(driver.getStints().stream()
                    .mapToInt(ParsedHeatData.ParsedStint::getLapsCount).sum())
                    .isEqualTo(laps.size());
            assertThat(laps.get(laps.size() - 1).getKartNumber())
                    .isEqualTo(driver.getStints().get(driver.getStints().size() - 1).getKartNumber());
        }

        // Лучший круг второго стинта первого гонщика — 1:14.719.
        assertThat(data.getDrivers().get(0).getStints().get(1).getBestLapMillis())
                .isEqualTo(74_719L);
    }

    @Test
    @DisplayName("parse: пустой столбец шапки не сдвигает круги и стинты соседей")
    void parse_missingDriverColumn_keepsPhysicalColumns() {
        ParsedHeatData data = heatParser.parse(Jsoup.parse("""
                <html><body>
                <h4>Heat<small class="text-body-secondary">10 Oct 2026, 16:44</small></h4>
                <table class="heat-result">
                  <thead>
                    <tr>
                      <th scope="row"></th>
                      <th scope="col">1</th><th scope="col">2</th><th scope="col">3</th>
                    </tr>
                    <tr>
                      <th scope="row">Driver</th>
                      <th scope="col"></th>
                      <th scope="col"><a href="/x/drivers/1">A</a></th>
                      <th scope="col"><a href="/x/drivers/2">B</a></th>
                    </tr>
                    <tr>
                      <th scope="row">Kart</th>
                      <th scope="col">7</th>
                      <th scope="col"><a href="/x/karts/8">8</a></th>
                      <th scope="col"><a href="/x/karts/9">9</a></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr><th scope="row">1</th><td>—</td>
                        <td>30.000 <span class="p">P1</span></td>
                        <td>30.100 <span class="p">P2</span></td></tr>
                    <tr><th scope="row">2</th><td>—</td>
                        <td>30.200 <span class="p">P1</span></td>
                        <td>30.300 <span class="p">P2</span></td></tr>
                  </tbody>
                  <tfoot>
                    <tr><th scope="row">Best</th><td></td><td>30.000</td><td>30.100</td></tr>
                    <tr><th scope="row">S1 kart</th><td></td><td>8</td><td>9</td></tr>
                    <tr><th scope="row">S1 laps</th><td></td><td>2</td><td>2</td></tr>
                  </tfoot>
                </table>
                </body></html>
                """));

        assertThat(data.getDrivers()).extracting(ParsedHeatData.ParsedDriver::getSiteId)
                .containsExactly("1", "2");
        assertThat(data.getDrivers()).extracting(ParsedHeatData.ParsedDriver::getColumnIndex)
                .containsExactly(1, 2);
        assertThat(data.getDrivers()).extracting(d -> d.getStints().get(0).getKartNumber())
                .containsExactly(8, 9);

        assertThat(data.getLaps()).hasSize(4);
        assertThat(lapsOf(data, "1")).extracting(ParsedHeatData.ParsedLap::getLapTime)
                .containsExactly("30.000", "30.200");
        assertThat(lapsOf(data, "2")).extracting(ParsedHeatData.ParsedLap::getLapTime)
                .containsExactly("30.100", "30.300");
        assertThat(data.getLaps()).allSatisfy(lap -> {
            assertThat(lap.getColumnIndex()).isIn(1, 2);
            assertThat(lap.getKartNumber()).isIn(8, 9);
        });
        // Best/Avg/Dev из tfoot тоже должны попасть в «свои» столбцы.
        assertThat(data.getDrivers()).extracting(ParsedHeatData.ParsedDriver::getBestTime)
                .containsExactly("30.000", "30.100");
    }

    private static List<ParsedHeatData.ParsedLap> lapsOf(ParsedHeatData data, String siteId) {
        return data.getLaps().stream()
                .filter(lap -> siteId.equals(lap.getDriverSiteId()))
                .toList();
    }

    private static Document load(String resource) {
        try (InputStream in = HeatParserTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("resource %s", resource).isNotNull();
            return Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать " + resource, e);
        }
    }
}
