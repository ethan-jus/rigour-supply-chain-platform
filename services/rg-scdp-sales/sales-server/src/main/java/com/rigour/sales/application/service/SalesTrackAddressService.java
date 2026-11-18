package com.rigour.sales.application.service;

import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.sales.application.port.out.AmapPoiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Addresses are resolved separately so provider latency never blocks the route itself. */
@Service
public class SalesTrackAddressService {
    private final SalesWorkAttendanceService attendance;
    private final AmapPoiClient maps;
    public SalesTrackAddressService(SalesWorkAttendanceService attendance, AmapPoiClient maps) {
        this.attendance = attendance;
        this.maps = maps;
    }
    public record PunchAddress(String key, String address, String status) {}
    public List<PunchAddress> ownPunchAddresses(LocalDate date) {
        // Reuse the exact tenant, employee and sensitive-track permission boundary.
        var track = attendance.track(date);
        var result = new ArrayList<PunchAddress>();
        var resolved = new java.util.HashMap<String, AmapPoiClient.Address>();
        for (int index = 0; index < track.punches().size(); index++) {
            var punch = track.punches().get(index);
            String key = "punch-" + index;
            if (punch.longitude() == null || punch.latitude() == null) {
                result.add(new PunchAddress(key, "", "NO_LOCATION"));
                continue;
            }
            String coordinates = punch.longitude().toPlainString() + "," + punch.latitude().toPlainString();
            try {
                var address = resolved.computeIfAbsent(coordinates,
                        ignored -> maps.reverseGcj02(punch.longitude(), punch.latitude()));
                boolean available = address != null && "RESOLVED".equals(address.status())
                        && address.formattedAddress() != null && !address.formattedAddress().isBlank();
                result.add(new PunchAddress(key, available ? address.formattedAddress() : "", available ? "RESOLVED" : "UNAVAILABLE"));
            } catch (AmapPoiException exception) {
                result.add(new PunchAddress(key, "", "UNAVAILABLE"));
            }
        }
        return List.copyOf(result);
    }
}
