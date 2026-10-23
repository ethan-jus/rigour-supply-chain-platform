package com.rigour.settings.domain.calendar;

import java.util.List;

public record StatutorySourceYear(
        int year, List<String> papers, List<StatutorySourceDay> days, String announcementText) {
    public StatutorySourceYear(int year, List<String> papers, List<StatutorySourceDay> days) {
        this(year, papers, days, null);
    }
}
