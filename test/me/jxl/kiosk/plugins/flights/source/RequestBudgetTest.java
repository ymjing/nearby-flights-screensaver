package me.jxl.kiosk.plugins.flights.source;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class RequestBudgetTest {
  @Test
  void spacingBudgetRestartAndUtcReset() throws Exception {
    AtomicLong now = new AtomicLong(86_400_000L);
    AtomicReference<String> saved = new AtomicReference<>("");
    NetCooldown c = new NetCooldown(now::get);
    c.configure("", saved::set, t -> true);
    c.acquire();
    assertThrows(SourceException.class, c::acquire);
    NetCooldown restored = new NetCooldown(now::get);
    restored.configure(saved.get(), saved::set, t -> true);
    assertThrows(SourceException.class, restored::acquire);
    for (int i = 1; i < 480; i++) {
      now.addAndGet(65_000);
      restored.acquire();
    }
    now.addAndGet(65_000);
    assertThrows(SourceException.class, restored::acquire);
    now.set(2 * 86_400_000L);
    restored.acquire();
  }

  @Test
  void scheduleAndPersistenceFailureBlockBeforeNetwork() {
    NetCooldown c = new NetCooldown(() -> 100_000L);
    c.configure("", s -> fail("must not save outside schedule"), t -> false);
    assertThrows(SourceException.class, c::acquire);
    c.configure(
        "",
        s -> {
          throw new IllegalStateException();
        },
        t -> true);
    assertThrows(SourceException.class, c::acquire);
  }

  @Test
  void fullCooldownSurvivesRestart() {
    AtomicLong now = new AtomicLong(86_400_000L);
    AtomicReference<String> saved = new AtomicReference<>("");
    NetCooldown c = new NetCooldown(now::get);
    c.configure("", saved::set, t -> true);
    c.trip(43200);
    NetCooldown restored = new NetCooldown(now::get);
    restored.configure(saved.get(), saved::set, t -> true);
    assertEquals(43200, restored.remainingS());
    assertEquals(43200, ReadsbJsonSource.parseRetryAfter("43200"));
    java.text.SimpleDateFormat f =
        new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
    int seconds =
        NetCooldown.retryAfter(f.format(new java.util.Date(System.currentTimeMillis() + 7200000)));
    assertTrue(seconds >= 7199 && seconds <= 7200);
  }
}
