package ninibayr.karting.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

final class TimingSiteFormats {

    static final DateTimeFormatter HEAT_DATE_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    static final DateTimeFormatter LIST_MONTH_DAY =
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private TimingSiteFormats() {
    }

    static LocalDateTime parseHeatDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), HEAT_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static MonthDay parseListMonthDay(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return MonthDay.parse(text.trim(), LIST_MONTH_DAY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Список заездов идёт от новых к старым. Заголовок без года ("Oct 20").
     * Если месяц скачет вверх (январь → декабрь при движении вниз по списку) — это прошлый год.
     */
    static LocalDate resolveListDate(MonthDay monthDay, MonthDay previousMonthDay, Year year) {
        if (monthDay == null) {
            return null;
        }
        Year resolvedYear = year;
        if (previousMonthDay != null && monthDay.getMonthValue() > previousMonthDay.getMonthValue()) {
            resolvedYear = year.minusYears(1);
        }
        return monthDay.atYear(resolvedYear.getValue());
    }

    static String lastPathSegment(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        int slash = href.lastIndexOf('/');
        String segment = slash >= 0 ? href.substring(slash + 1) : href;
        return segment.isBlank() ? null : segment;
    }
}
