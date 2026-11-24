package me.jxl.kiosk.plugins.flights;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OperatingScheduleTest {
  private boolean allows(Map<String, Object> settings, String instant, String zone) {
    return new OperatingSchedule(settings)
        .allows(Instant.parse(instant).toEpochMilli(), TimeZone.getTimeZone(zone));
  }

  @Test
  void defaultsExcludeWeekendsAndRespectBoundaries() {
    Map<String, Object> m = new HashMap<>();
    assertFalse(allows(m, "2026-11-23T08:59:59Z", "UTC"));
    assertTrue(allows(m, "2026-11-23T09:00:00Z", "UTC"));
    assertTrue(allows(m, "2026-11-23T16:59:59Z", "UTC"));
    assertFalse(allows(m, "2026-11-23T17:00:00Z", "UTC"));
    assertFalse(allows(m, "2026-11-21T12:00:00Z", "UTC"));
    assertFalse(allows(m, "2026-11-22T12:00:00Z", "UTC"));
  }

  @Test
  void configurableDaysAndHoursAndTimezone() {
    Map<String, Object> m = new HashMap<>();
    m.put("runSaturday", true);
    m.put("scheduleStart", "10:30");
    m.put("scheduleEnd", "12:00");
    assertFalse(allows(m, "2026-11-21T18:29:59Z", "America/Los_Angeles"));
    assertTrue(allows(m, "2026-11-21T18:30:00Z", "America/Los_Angeles"));
    assertFalse(allows(m, "2026-11-21T20:00:00Z", "America/Los_Angeles"));
    // Summer uses daylight time, not a fixed UTC offset.
    assertTrue(allows(m, "2026-07-18T17:30:00Z", "America/Los_Angeles"));
  }

  @Test
  void overnightBelongsToStartingDay() {
    Map<String, Object> m = new HashMap<>();
    m.put("scheduleStart", "22:00");
    m.put("scheduleEnd", "02:00");
    assertTrue(allows(m, "2026-11-21T01:00:00Z", "UTC"));
    assertFalse(allows(m, "2026-11-21T02:00:00Z", "UTC"));
    assertFalse(allows(m, "2026-11-23T01:00:00Z", "UTC"));
  }

  @Test
  void disabledAndAllDayAndInvalid() {
    Map<String, Object> m = new HashMap<>();
    m.put("scheduleEnabled", false);
    assertTrue(allows(m, "2026-11-22T00:00:00Z", "UTC"));
    m.put("scheduleEnabled", true);
    m.put("scheduleStart", "09:00");
    m.put("scheduleEnd", "09:00");
    assertTrue(allows(m, "2026-11-23T00:00:00Z", "UTC"));
    for (String day : Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday"))
      m.put("run" + day, false);
    assertFalse(allows(m, "2026-11-23T12:00:00Z", "UTC"));
    m.put("scheduleStart", "25:00");
    assertThrows(IllegalArgumentException.class, () -> new OperatingSchedule(m));
    assertNotNull(PluginSettings.parse(m).error);
  }
}
