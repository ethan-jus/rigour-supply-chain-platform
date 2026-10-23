package com.rigour.settings.application.port.out;

import com.rigour.settings.application.model.StatutoryCalendarSnapshot;
import com.rigour.settings.domain.calendar.StatutorySourceYear;

import java.time.Instant;
import java.util.*;

public interface StatutoryCalendarStore {
    Optional<StatutoryCalendarSnapshot> find(int year);

    Set<Integer> years();

    boolean acquire(int year, String token, Instant now);

    void complete(int year, String token, StatutorySourceYear content, String version, Instant now);

    void failed(int year, String token, String status, Instant now);
}
