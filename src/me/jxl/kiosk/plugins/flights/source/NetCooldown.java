// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.source;

import java.util.function.LongSupplier;

/**
 * One "slow down" shared by everything that talks to adsb.lol: position polls, the wider Soft
 * radius probes and the hex lookups. The server limits per client, not per endpoint, so a 429 on
 * one request means every other request is also wasted (and keeps the limit tripped). Honours
 * Retry-After; without it waits at least {@link #MIN_MS}, doubling on consecutive 429s up to {@link
 * #MAX_MS}. Any success resets the doubling.
 */
public final class NetCooldown {
  static final long MIN_MS = 60_000, MAX_MS = 300_000;

  private final LongSupplier clock;
  private long untilMs;
  private int strikes;
  private long day = -1, nextRequestMs;
  private int requests;
  private java.util.function.Consumer<String> persist;
  private java.util.function.LongPredicate allowed;

  /** Restore only accounting, never credentials. Persist before sending a request. */
  public synchronized void configure(
      String state,
      java.util.function.Consumer<String> save,
      java.util.function.LongPredicate allowed) {
    this.persist = save;
    this.allowed = allowed;
    if (day < 0 && state != null && !state.isEmpty()) {
      try {
        String[] parts = state.split(":");
        day = Long.parseLong(parts[0]);
        requests = Integer.parseInt(parts[1]);
        untilMs = Long.parseLong(parts[2]);
        nextRequestMs = Long.parseLong(parts[3]);
        if (requests < 0) throw new IllegalArgumentException();
      } catch (RuntimeException e) {
        day = clock.getAsLong() / 86_400_000L;
        requests = 480; // damaged accounting must not reset the allowance
      }
    }
  }

  private void save() {
    if (persist != null) persist.accept(day + ":" + requests + ":" + untilMs + ":" + nextRequestMs);
  }

  public synchronized void acquire() throws SourceException {
    long now = clock.getAsLong();
    if (allowed != null && !allowed.test(now))
      throw new SourceException("Outside operating hours", null, 30);
    int left = remainingS();
    if (left > 0) throw new SourceException("adsb.lol cooling down", null, left);
    if (persist == null) return; // legacy adapters/tests have no accounting store
    long today = now / 86_400_000L;
    if (today > day) {
      day = today;
      requests = 0;
    }
    if (requests >= 480)
      throw new SourceException(
          "adsb.lol daily request budget reached",
          null,
          (int) Math.max(1, ((day + 1) * 86_400_000L - now + 999) / 1000));
    if (now < nextRequestMs)
      throw new SourceException(
          "adsb.lol waiting for next poll", null, (int) ((nextRequestMs - now + 999) / 1000));
    requests++;
    nextRequestMs = now + 65_000;
    try {
      save();
    } catch (RuntimeException e) {
      throw new SourceException("Cannot persist adsb.lol request budget", null, 60);
    }
  }

  public static int retryAfter(String header) {
    if (header == null) return 0;
    try {
      return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Long.parseLong(header.trim())));
    } catch (NumberFormatException e) {
      try {
        java.text.SimpleDateFormat f =
            new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
        f.setLenient(false);
        return (int)
            Math.max(
                0,
                Math.min(
                    Integer.MAX_VALUE,
                    (f.parse(header).getTime() - System.currentTimeMillis() + 999) / 1000));
      } catch (java.text.ParseException ignored) {
        return 0;
      }
    }
  }

  public NetCooldown() {
    this(System::currentTimeMillis);
  }

  public NetCooldown(LongSupplier clock) {
    this.clock = clock;
  }

  /** Seconds left to wait (rounded up), or 0 when requests may go. */
  public synchronized int remainingS() {
    long left = untilMs - clock.getAsLong();
    return left <= 0 ? 0 : (int) ((left + 999) / 1000);
  }

  /** The server refused with 429 (or 503): wait out its Retry-After, or our own minimum. */
  public synchronized void trip(int retryAfterS) {
    long own = Math.min(MAX_MS, MIN_MS << Math.min(strikes, 3));
    strikes++;
    long waitMs = Math.max(retryAfterS * 1000L, own);
    untilMs = Math.max(untilMs, clock.getAsLong() + waitMs);
    save();
  }

  /** A request succeeded: forget the doubling. */
  public synchronized void ok() {
    strikes = 0;
  }
}
