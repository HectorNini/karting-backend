package ninibayr.karting.exception;

/** Идентификатор заезда из URL не является числовым id сайта тайминга. */
public class InvalidHeatIdException extends RuntimeException {

    public InvalidHeatIdException(String heatSiteId) {
        super("Некорректный идентификатор заезда: " + shorten(heatSiteId));
    }

    private static String shorten(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() > 64 ? value.substring(0, 64) + "…" : value;
    }
}
