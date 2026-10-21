package ninibayr.karting.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class HeatParserTest {

    private final HeatParser heatParser = new HeatParser();

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
}
