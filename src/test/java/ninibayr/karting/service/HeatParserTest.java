package ninibayr.karting.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("parse: смена картов по S-строкам и круг в пит с суффиксом «l»")
    void parse_driveHeat_shouldFillStintsAndKarts() {
        ParsedHeatData data = heatParser.parse(Jsoup.parse("""
                <html><head><title>Финал А</title></head><body>
                <h4>Финал А<small class="text-body-secondary">24 Apr 2026, 14:00</small></h4>
                <table class="heat-result">
                  <thead>
                    <tr><th scope="row"></th><th scope="col">1</th></tr>
                    <tr><th scope="row">Driver</th>
                        <th scope="col"><a href="/tracks/narvskaya/drivers/901">Лидер</a></th></tr>
                    <tr><th scope="row">Kart</th>
                        <th scope="col"><a href="/tracks/narvskaya/karts/9">9</a></th></tr>
                  </thead>
                  <tbody>
                    <tr><th scope="row">1</th><td>40.000 <span class="p">P1</span></td></tr>
                    <tr><th scope="row">2</th><td>39.000 <span class="p">P1</span></td></tr>
                    <tr><th scope="row">3</th><td>38.000 <span class="p">P1</span></td></tr>
                    <tr><th scope="row">4</th><td>37.000 <span class="p">P1</span></td></tr>
                    <tr><th scope="row">5</th><td>36.000 <span class="p">P1</span></td></tr>
                    <tr><th scope="row">6</th><td>35.000 <span class="p">P1</span></td></tr>
                    <tr><th scope="row">7</th><td>1:35.000 7l <span class="p">P1</span></td></tr>
                    <tr><th scope="row">8</th><td>42.000 <span class="p">P1</span></td></tr>
                    <tr><th scope="row">9</th><td>41.000 <span class="p">P1</span></td></tr>
                    <tr><th scope="row">10</th><td>40.500 <span class="p">P1</span></td></tr>
                  </tbody>
                  <tfoot>
                    <tr><th scope="row">S1 kart</th><td>9</td></tr>
                    <tr><th scope="row">S1 laps</th><td>7</td></tr>
                    <tr><th scope="row">S2 kart</th><td>101</td></tr>
                    <tr><th scope="row">S2 laps</th><td>2</td></tr>
                    <tr><th scope="row">S2 best</th><td>1:14.719</td></tr>
                    <tr><th scope="row">S3 kart</th><td>6</td></tr>
                    <tr><th scope="row">S3 laps</th><td>1</td></tr>
                  </tfoot>
                </table>
                </body></html>
                """));

        assertThat(data.getName()).isEqualTo("Финал А");
        assertThat(data.getRaceDate()).isEqualTo(LocalDate.of(2026, 4, 24));
        assertThat(data.getDrivers()).hasSize(1);
        assertThat(data.getLaps()).hasSize(10);

        ParsedHeatData.ParsedDriver driver = data.getDrivers().get(0);
        List<ParsedHeatData.ParsedStint> stints = driver.getStints();
        assertThat(stints).extracting(ParsedHeatData.ParsedStint::getNumber)
                .containsExactly(1, 2, 3);
        assertThat(stints).extracting(ParsedHeatData.ParsedStint::getKartNumber)
                .containsExactly(9, 101, 6);
        assertThat(stints).extracting(ParsedHeatData.ParsedStint::getLapFrom)
                .containsExactly(1, 8, 10);
        assertThat(stints).extracting(ParsedHeatData.ParsedStint::getLapTo)
                .containsExactly(7, 9, 10);
        assertThat(stints.stream().mapToInt(ParsedHeatData.ParsedStint::getLapsCount).sum())
                .isEqualTo(10);
        for (int i = 1; i < stints.size(); i++) {
            assertThat(stints.get(i).getLapFrom())
                    .isEqualTo(stints.get(i - 1).getLapTo() + 1);
        }

        List<ParsedHeatData.ParsedLap> laps = lapsOf(data, driver.getSiteId());
        assertThat(laps).hasSize(10);
        assertThat(laps).allSatisfy(lap -> {
            assertThat(lap.getStintIndex()).isNotNull();
            assertThat(lap.getKartNumber()).isNotNull();
            assertThat(lap.getRacePosition()).startsWith("P");
        });
        assertThat(laps.get(6).getKartNumber()).isEqualTo(9);
        assertThat(laps.get(7).getKartNumber()).isEqualTo(101);
        assertThat(laps.get(8).getKartNumber()).isEqualTo(101);
        assertThat(laps.get(9).getKartNumber()).isEqualTo(6);

        // Круг заезда в пит: суффикс «7l» срезан, время больше 90 с.
        assertThat(laps.get(6).getLapTimeMillis()).isEqualTo(95_000L);
        assertThat(laps.get(6).getLapTime()).isEqualTo("1:35.000");
        assertThat(stints.get(1).getBestLapMillis()).isEqualTo(74_719L);
    }

    @Test
    @DisplayName("parse: DNF-гонщик с пустыми ячейками и разным числом стинтов")
    void parse_mixedHeat_shouldHandleDnfDrivers() {
        ParsedHeatData data = heatParser.parse(Jsoup.parse("""
                <html><body>
                <h4>Heat<small class="text-body-secondary">10 Oct 2026, 16:44</small></h4>
                <table class="heat-result">
                  <thead>
                    <tr><th scope="row"></th><th scope="col">1</th><th scope="col">2</th></tr>
                    <tr><th scope="row">Driver</th>
                        <th scope="col"><a href="/x/drivers/11">A</a></th>
                        <th scope="col"><a href="/x/drivers/12">B</a></th></tr>
                    <tr><th scope="row">Kart</th>
                        <th scope="col"><a href="/x/karts/20">20</a></th>
                        <th scope="col"><a href="/x/karts/33">33</a></th></tr>
                  </thead>
                  <tbody>
                    <tr><th scope="row">1</th><td>30.000 <span class="p">P1</span></td><td>31.000 <span class="p">P2</span></td></tr>
                    <tr><th scope="row">2</th><td>1:14.719 2l <span class="p">P1</span></td><td>32.000 <span class="p">P2</span></td></tr>
                    <tr><th scope="row">3</th><td>29.000 <span class="p">P1</span></td><td>33.000 <span class="p">P2</span></td></tr>
                    <tr><th scope="row">4</th><td>28.500 <span class="p">P1</span></td><td>34.000 <span class="p">P2</span></td></tr>
                    <tr><th scope="row">5</th><td>28.000 <span class="p">P1</span></td><td>35.000 <span class="p">P2</span></td></tr>
                    <tr><th scope="row">6</th><td></td><td>36.000 <span class="p">P1</span></td></tr>
                  </tbody>
                  <tfoot>
                    <tr><th scope="row">S1 kart</th><td>20</td><td>33</td></tr>
                    <tr><th scope="row">S1 laps</th><td>1</td><td>1</td></tr>
                    <tr><th scope="row">S2 kart</th><td>14</td><td>102</td></tr>
                    <tr><th scope="row">S2 laps</th><td>4</td><td>1</td></tr>
                    <tr><th scope="row">S2 best</th><td>1:14.719</td><td>32.000</td></tr>
                    <tr><th scope="row">S3 kart</th><td></td><td>33</td></tr>
                    <tr><th scope="row">S3 laps</th><td></td><td>4</td></tr>
                  </tfoot>
                </table>
                </body></html>
                """));

        assertThat(data.getDrivers()).hasSize(2);
        assertThat(data.getLaps()).hasSize(11);
        assertThat(data.getDrivers()).extracting(d -> d.getStints().size())
                .containsExactly(2, 3);
        assertThat(data.getDrivers()).extracting(d -> d.getStints().get(0).getKartNumber())
                .containsExactly(20, 33);

        ParsedHeatData.ParsedDriver first = data.getDrivers().get(0);
        assertThat(first.getStints()).extracting(ParsedHeatData.ParsedStint::getLapFrom)
                .containsExactly(1, 2);
        assertThat(first.getStints()).extracting(ParsedHeatData.ParsedStint::getLapTo)
                .containsExactly(1, 5);
        assertThat(first.getStints()).extracting(ParsedHeatData.ParsedStint::getKartNumber)
                .containsExactly(20, 14);

        ParsedHeatData.ParsedDriver second = data.getDrivers().get(1);
        assertThat(second.getStints()).extracting(ParsedHeatData.ParsedStint::getLapFrom)
                .containsExactly(1, 2, 3);
        assertThat(second.getStints()).extracting(ParsedHeatData.ParsedStint::getLapTo)
                .containsExactly(1, 2, 6);
        assertThat(second.getStints()).extracting(ParsedHeatData.ParsedStint::getKartNumber)
                .containsExactly(33, 102, 33);

        for (ParsedHeatData.ParsedDriver driver : data.getDrivers()) {
            List<ParsedHeatData.ParsedLap> laps = lapsOf(data, driver.getSiteId());
            assertThat(driver.getStints().stream()
                    .mapToInt(ParsedHeatData.ParsedStint::getLapsCount).sum())
                    .isEqualTo(laps.size());
            assertThat(laps.get(laps.size() - 1).getKartNumber())
                    .isEqualTo(driver.getStints().get(driver.getStints().size() - 1).getKartNumber());
        }

        // DNF: у первого гонщика нет круга 6, но суммы стинтов сходятся.
        assertThat(lapsOf(data, first.getSiteId())).hasSize(5);
        assertThat(lapsOf(data, second.getSiteId())).hasSize(6);
        assertThat(first.getStints().get(1).getBestLapMillis()).isEqualTo(74_719L);
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

    @Test
    @DisplayName("parse: компактный stress-тест на 12 гонщиков и 206 кругов")
    void parse_largeHeat_generatesAllLapsAndStints() {
        int driversCount = 12;
        int lapsCount = 206;
        StringBuilder html = new StringBuilder("""
                <html><body>
                <h4>Stress Heat<small class="text-body-secondary">10 Oct 2026, 16:44</small></h4>
                <table class="heat-result"><thead>
                """);
        html.append("<tr><th scope=\"row\"></th>");
        for (int i = 1; i <= driversCount; i++) {
            html.append("<th scope=\"col\">").append(i).append("</th>");
        }
        html.append("</tr><tr><th scope=\"row\">Driver</th>");
        for (int i = 0; i < driversCount; i++) {
            html.append("<th scope=\"col\"><a href=\"/x/drivers/").append(100 + i)
                    .append("\">D").append(i).append("</a></th>");
        }
        html.append("</tr><tr><th scope=\"row\">Kart</th>");
        for (int i = 0; i < driversCount; i++) {
            html.append("<th scope=\"col\"><a href=\"/x/karts/").append(200 + i)
                    .append("\">").append(200 + i).append("</a></th>");
        }
        html.append("</tr></thead><tbody>");
        for (int lap = 1; lap <= lapsCount; lap++) {
            html.append("<tr><th scope=\"row\">").append(lap).append("</th>");
            for (int i = 0; i < driversCount; i++) {
                html.append("<td>30.500 <span class=\"p\">P").append(i + 1).append("</span></td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table></body></html>");

        ParsedHeatData data = heatParser.parse(Jsoup.parse(html.toString()));

        assertThat(data.getDrivers()).hasSize(driversCount);
        assertThat(data.getDrivers()).extracting(ParsedHeatData.ParsedDriver::getSiteId)
                .containsExactly("100", "101", "102", "103", "104", "105",
                        "106", "107", "108", "109", "110", "111");
        assertThat(data.getLaps()).hasSize(driversCount * lapsCount);
        assertThat(data.getDrivers()).allSatisfy(driver -> {
            assertThat(driver.getStints()).hasSize(1);
            assertThat(driver.getStints().get(0).getKartNumber()).isNotNull();
        });
        assertThat(data.getLaps()).allSatisfy(lap -> {
            assertThat(lap.getRaceLapNumber()).isBetween(1, lapsCount);
            assertThat(lap.getStintIndex()).isEqualTo(1);
            assertThat(lap.getKartNumber()).isNotNull();
            assertThat(lap.getLapTime()).isEqualTo("30.500");
        });
    }

    private static List<ParsedHeatData.ParsedLap> lapsOf(ParsedHeatData data, String siteId) {
        return data.getLaps().stream()
                .filter(lap -> siteId.equals(lap.getDriverSiteId()))
                .toList();
    }
}
