package mediathek.tool.datum;

import java.util.Date;

public class DatumFilm extends Date {
    /**
     * When no date is specified in the film list, set to undefined for proper sorting.
     * Fictious date is 1.1.1900
     */
    public static final DatumFilm UNDEFINED_FILM_DATE = new DatumFilm(0, 0, 1);

    public DatumFilm(long date) {
        super(date);
    }

    public DatumFilm(int year, int month, int date) {
        super(year, month, date);
    }

    @Override
    public String toString() {
        if (this.equals(UNDEFINED_FILM_DATE)) {
            return "";
        } else {
            return DateUtil.FORMATTER.format(DateUtil.convertToLocalDate(this));
        }
    }
}