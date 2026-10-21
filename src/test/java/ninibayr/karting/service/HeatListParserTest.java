package ninibayr.karting.service;

import ninibayr.karting.dto.HeatList;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeatListParserTest {

    private final HeatListParser parser = new HeatListParser();

    @Test
    @DisplayName("parse: собирает ISO-дату и переносит год на границе января и декабря")
    void parse_shouldResolveIsoDatesAndYearRollover() {
        Document doc = Jsoup.parse("""
                <div class="list-group">
                  <div class="list-group-item heat-list-date"><b>Jan 5</b></div>
                  <div class="list-group-item">
                    <a href="/tracks/narvskaya/heats/100"><strong>Heat 1</strong></a>
                    <small>12:00</small>
                    <div class="col-12 mb-1 small">1. RK 50.000</div>
                  </div>
                  <div class="list-group-item heat-list-date"><b>Dec 31</b></div>
                  <div class="list-group-item">
                    <a href="/tracks/narvskaya/heats/99"><strong>Heat 99</strong></a>
                    <small>23:50</small>
                  </div>
                </div>
                """);

        HeatList list = parser.parse(doc, "narvskaya");

        assertThat(list.getHeats()).hasSize(2);
        assertThat(list.getHeats().get(0).getId()).isEqualTo("100");
        assertThat(list.getHeats().get(0).getDate()).endsWith("-01-05");
        assertThat(list.getHeats().get(0).getTime()).isEqualTo("12:00");
        assertThat(list.getHeats().get(1).getId()).isEqualTo("99");
        assertThat(list.getHeats().get(1).getDate()).endsWith("-12-31");
        assertThat(list.getHeats().get(1).getDate().substring(0, 4))
                .isEqualTo(String.valueOf(Integer.parseInt(list.getHeats().get(0).getDate().substring(0, 4)) - 1));
    }
}
