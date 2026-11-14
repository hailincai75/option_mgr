package com.optionmgr.calendar.domain;

/** FEAT-009 user-facing texts. */
public final class TradingDayMessages {

    private TradingDayMessages() {
    }

    /** Q126: e.g. {@code 到期日 2027-03-26 不是美股交易日（Good Friday）}. */
    public static String expirationNotTradingDay(NonTradingDay day) {
        return "到期日 " + day.date() + " 不是美股交易日（" + day.name() + "）";
    }
}
