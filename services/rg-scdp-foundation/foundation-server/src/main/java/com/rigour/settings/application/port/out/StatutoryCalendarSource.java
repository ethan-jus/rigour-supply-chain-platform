package com.rigour.settings.application.port.out;

import com.rigour.settings.domain.calendar.StatutorySourceYear;

public interface StatutoryCalendarSource {
    StatutorySourceYear fetch(int year);
}
