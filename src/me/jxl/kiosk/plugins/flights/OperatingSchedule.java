// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

/** Device-local hours; overnight windows belong to the weekday on which they start. */
public final class OperatingSchedule {
  private final boolean enabled;
  private final int start, end;
  private final boolean[] days = new boolean[8];

  public OperatingSchedule(Map<String, Object> settings) {
    enabled = !Boolean.FALSE.equals(settings.get("scheduleEnabled"));
    start = time(settings.get("scheduleStart"), "09:00");
    end = time(settings.get("scheduleEnd"), "17:00");
    String[] names = {
      "", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    };
    for (int i = 1; i <= 7; i++) {
      Object v = settings.get("run" + names[i]);
      days[i] = v instanceof Boolean ? (Boolean) v : i >= 2 && i <= 6;
    }
  }

  private static int time(Object value, String fallback) {
    String s = value == null ? fallback : String.valueOf(value).trim();
    if (!s.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
      throw new IllegalArgumentException("Operating times must use HH:mm (00:00–23:59)");
    return Integer.parseInt(s.substring(0, 2)) * 60 + Integer.parseInt(s.substring(3));
  }

  public boolean allows(long now) {
    return allows(now, TimeZone.getDefault());
  }

  public boolean allows(long now, TimeZone zone) {
    if (!enabled) return true;
    Calendar c = Calendar.getInstance(zone);
    c.setTimeInMillis(now);
    int day = c.get(Calendar.DAY_OF_WEEK);
    int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    if (start == end) return days[day];
    if (start < end) return days[day] && minute >= start && minute < end;
    if (minute >= start) return days[day];
    return minute < end && days[day == 1 ? 7 : day - 1];
  }
}
