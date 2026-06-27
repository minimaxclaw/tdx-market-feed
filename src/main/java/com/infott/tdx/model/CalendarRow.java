package com.infott.tdx.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * TableView 数据模型 —— 交易日历的一行。
 */
public class CalendarRow {

    private final StringProperty eventDate;
    private final StringProperty weekday;
    private final StringProperty holidayName;

    public CalendarRow(String eventDate, String weekday, String holidayName) {
        this.eventDate   = new SimpleStringProperty(eventDate);
        this.weekday     = new SimpleStringProperty(weekday);
        this.holidayName = new SimpleStringProperty(holidayName);
    }

    public String getEventDate()   { return eventDate.get(); }
    public String getWeekday()     { return weekday.get(); }
    public String getHolidayName() { return holidayName.get(); }

    public StringProperty eventDateProperty()   { return eventDate; }
    public StringProperty weekdayProperty()     { return weekday; }
    public StringProperty holidayNameProperty() { return holidayName; }
}
