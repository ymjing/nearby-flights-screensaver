# Data sources

Every service, dataset and bundled asset the plugin uses is listed here: what it gives us, what
we send it, its terms, and what credit it needs. A new source is added to this page before it
ships. adsb.fi integration checked November 2026.

## Services the plugin calls

| Source | What we use it for | What we send | Terms | Credit |
|---|---|---|---|---|
| Your own receiver (readsb or tar1090 `aircraft.json`) | Positions (the usual primary source) | Nothing. The plugin only reads the file, on your network. | Your own data | None |
| [adsb.fi](https://adsb.fi) `/api/v3/lat/{lat}/lon/{lon}/dist/{nm}` | Live positions, default source | Location rounded to two decimals; radius padded by 1 nm | Personal, non-commercial use. Public API limit: 1 request/s; no daily cap stated. Default polling: 20 s. No API key. Honors Retry-After. [Terms](https://github.com/adsbfi/opendata) | adsb.fi credited on screen and linked in README |
| [adsb.im](https://adsb.im) `POST /api/0/routeset` (plain `http`) | Route (origin and destination) by callsign | Callsigns only, in one batch of up to 20 per request, no location and no aircraft position. Callsigns wait 30 seconds, and requests are at least 30 seconds apart. After an error the wait doubles up to 10 minutes; after a 401, 403 or 410 the plugin does not ask again for an hour. Cached 12 hours (misses 1 hour), in memory only. | The server's published API description (https://adsb.im/api) says ODbL 1.0, serving community [standing data](https://github.com/vradarserver/standing-data) (CC0) plus ACARS and adsb.lol-derived data. It sets the rules we follow: non-commercial use, a unique User-Agent with a contact (ours carries the project's GitHub page), plain `http` not https, and batched requests. The per-callsign `GET /api/0/route/...` is deprecated and answers 403 (checked 2026-11-16, public issue #15). adsb.lol's equivalent endpoint is deprecated in favour of static files with the same data. | Credited in the README |
| [adsbdb.com](https://www.adsbdb.com) `/aircraft/{hex}` and `/airline/{code}` | Registration, type and registered owner by hex code; airline name by operator code | A hex code, or an operator code. Never a location. | Server code is MIT. Its README credits PlaneBase for aircraft data. No stated rate limit or API terms. We query live and keep a bounded in-memory cache (12 hours), never a copy. | adsbdb.com and PlaneBase (in the README) |
| Your Home Assistant `weather.*` entity (optional, "Show weather") | The weather line under the clock: condition and temperature | Nothing. Kiosk Satellite hands the plugin the entity's state through its `host.read` capability (`ha.entity.` subscription); the plugin never gets Home Assistant's credentials and makes no network call. | Your own data, from whichever weather integration you chose in Home Assistant (for example OpenWeatherMap's, which has its own terms with you) | None |

## Bundled with the plugin

| Asset | What it is for | Terms | Credit |
|---|---|---|---|
| [OurAirports](https://ourairports.com/data/) subset | Airport markers on the scope. Large airports plus scheduled medium ones, compiled into `AirportData.java` by `scripts/gen-airports.py`. | Public domain ("released to the Public Domain, with no guarantee of accuracy"). Re-read 2026-10-08. | Not required, given in the README |
| ADS-B Radar aircraft icon pack | Aircraft silhouettes by type | "Free for personal and commercial use, with a backlink to ADS-B Radar", from the pack's own readme. | "Icons by ADS-B Radar for macOS - https://adsb-radar.com" (in the README and the icon file) |
| Airline table (`Airlines.java`, brand hues in `lib.js`) | Callsign prefix to airline name, and the chip colour | Written for this project from public facts. The hues are approximate. | None |
| US N-number from hex (`enrich/NNumber`) | A registration for a US hex code when nothing else gave one | The FAA/ICAO allocation of the US block A00001-ADF7C7 to N-numbers is a public rule, implemented from the rule. No data file, no network. | None |

## How the sources fit together

**Positions (every refresh, default 20 s, range 10–120 s)** — `SourceAdapter` → `ReadsbJsonSource`:
- *adsb.fi* `/api/v3/lat/{lat}/lon/{lon}/dist/{nm}` is the default, without a key.
  Location is rounded to 2 decimals and radius padded by 1 nm. Soft radius fetches the maximum
  configured area once, then selects the displayed radius locally.
- *Local feed* remains optional. Local + adsb.fi merges both sources.
- The operating schedule gates requests in device local time; default Monday–Friday, 09:00–17:00.
- 3 failed ticks in a row = stale (status line, dimmed display); backoff follows the failures and `Retry-After`.
- Selector then drops: on the ground, position fix older than 60 s, outside the radius, GA when hidden.

**Enrichment (background thread, 1 request/s, memory cache, never blocks a publish)** — `Enricher`:
- *adsbdb* `/aircraft/{hex}`: registration, type, and the registered owner's operator code (airline fallback).
- Live callsigns come from the position feed; no separate adsb.lol hex requests are made.
- *adsb.im* `POST /api/0/routeset` (batched, plain http): origin and destination (two airports only; dropped if the great circle misses the
  aircraft by more than 100 km).
- A route that does not fit the aircraft is simply not shown.
- Bundled `Airlines` table: airline name from the callsign prefix, so a name shows before any lookup. A prefix not in the table is looked up once by operator code (`adsbdb /v0/airline/<ICAO>`, the code only, never a location) and cached like the other lookups (hits 12 h, misses 1 h, memory only).
- Airline precedence: route answer, then callsign prefix (table), then the operator-code lookup, then the registered owner. Cache: hits 12 h, misses 1 h.

**Weather (optional, native view only)** — `WeatherSource` → `HaWeatherSource`: you pick a `weather.*` entity in settings
and the plugin follows it through Kiosk Satellite's `ha.entity.` subscription (push, no polling; the host gives the current
snapshot at once). Java turns it into `wx` (condition, °C, and the unit HA shows it in; with the Wind setting on also `wind_speed` as m/s, `wind_speed_unit` as the unit to show it in, and `wind_bearing` as degrees, a number or a compass point in HA). Only `available` snapshots with a
usable temperature and a known unit are shown; otherwise the line is simply not drawn. Another source (a keyed API, NWS) would
implement the same interface and be listed here first.

**Not used:** the renderer has no network.

**Airports (`ap`) and city names:** OurAirports `airports.csv` (public domain), subset compiled into `AirportData.java` by
`scripts/gen-airports.py` (large airports plus scheduled medium ones, code = IATA else ICAO; 3,285 airports, 98 KB).
`Airports.within` filters to the radius around the exact home. The city name for the "Show city names" setting is
chosen at build time from two sources, with no per-airport list: OurAirports' `municipality` (the town the airport sits in)
and the `Location` column of the Virtual Radar Server [standing-data](https://github.com/vradarserver/standing-data)
`airports/` folder (CC0; the place people name it for, but crowd-sourced, with some typos and region names). Both are cleaned
(brackets dropped, cut at the first comma or slash, " City" / "Island" suffixes dropped only when the airport's own name
backs it); then: they agree -> that; one is empty -> the other; exactly one appears in the airport's name -> that one;
else standing-data. Known limit, about 2 % of airports (reviewed 2026-10-29): a wrong or odd name that still fits, such as
NAT "Rio Grande do Norte", MQP "Mpumalanga", TPE "Taoyuan". Looked up locally; nothing is sent anywhere. Refresh by
re-downloading the OurAirports CSV and sparse-cloning standing-data's `airports/`, then rerunning the script with both paths.
