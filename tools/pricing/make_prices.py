#!/usr/bin/env python3
"""Computes the per-country price of the `unlock_all` product: the same purchasing power as BASE_EUR in France,
never above CAP_EUR (even in richer countries, at today's exchange rate), never below FLOOR_EUR.

Standard library only. Inputs (cached in store/play/pricing/data/, refreshed with --refresh):
- store/play/pricing/current_prices.psv : the current Google Play prices (country|currency|price|vat_rate|note)
- World Bank PA.NUS.PRVT.PP (private-consumption PPP, local currency per international dollar)
- World Bank PA.NUS.FCRF (official exchange rate, local currency per US dollar)
- World Bank country metadata (region and income level, for territories without data)
- open.er-api.com (market exchange rates, EUR base), used to convert the euro price into the billing currency

Usage:
  tools/pricing/make_prices.py [--refresh] [--out store/play/pricing/prices_YYYY-MM-DD.csv]
"""
import argparse
import csv
import json
import math
import os
import statistics
import sys
import urllib.request
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PRICING = os.path.join(ROOT, "store", "play", "pricing")
DATA = os.path.join(PRICING, "data")

BASE_EUR = 1.99  # price in France
CAP_EUR = 2.00  # hard ceiling after rounding to a price point ("2 euros maximum")
FLOOR_EUR = 0.50  # hard floor
PPP_YEARS = "2021:2025"

# Billing currencies with no decimals / three decimals in the Play Store (everything else: two).
ZERO_DECIMALS = {"JPY", "KRW", "CLP", "COP", "HUF", "IDR", "IQD", "XAF", "XOF", "VND", "MMK", "PYG", "RSD"}
THREE_DECIMALS = {"JOD"}

# Currencies whose rate moves fast: the price should be reviewed every quarter.
VOLATILE = {"ARS", "TRY", "NGN", "EGP", "ETB", "PKR", "GHS", "MMK", "BDT", "UZS", "KZT", "RUB", "UAH", "MNT", "LKR"}
# Countries whose official exchange rate is far from the market (the price level index is unreliable).
DISTORTED = {"LB", "VE", "ZW", "MM", "ET", "NG", "AR"}

# Territories without World Bank data are priced like a comparable economy.
COMPARABLE = {"VA": "IT", "MC": "FR", "LI": "CH", "GI": "GB"}

ISO2 = {
    "Albania": "AL", "Algeria": "DZ", "Angola": "AO", "Antigua & Barbuda": "AG", "Argentina": "AR", "Armenia": "AM",
    "Aruba": "AW", "Australia": "AU", "Austria": "AT", "Azerbaijan": "AZ", "Bahamas": "BS", "Bahrain": "BH",
    "Bangladesh": "BD", "Belarus": "BY", "Belgium": "BE", "Belize": "BZ", "Benin": "BJ", "Bermuda": "BM",
    "Bolivia": "BO", "Bosnia & Herzegovina": "BA", "Botswana": "BW", "Brazil": "BR", "British Virgin Islands": "VG",
    "Bulgaria": "BG", "Burkina Faso": "BF", "Cambodia": "KH", "Cameroon": "CM", "Canada": "CA", "Cape Verde": "CV",
    "Cayman Islands": "KY", "Central African Republic": "CF", "Chad": "TD", "Chile": "CL", "Colombia": "CO",
    "Comoros": "KM", "Congo - Brazzaville": "CG", "Congo - Kinshasa": "CD", "Costa Rica": "CR", "Croatia": "HR",
    "Cyprus": "CY", "Czechia": "CZ", "Côte d’Ivoire": "CI", "Denmark": "DK", "Djibouti": "DJ", "Dominica": "DM",
    "Dominican Republic": "DO", "Ecuador": "EC", "Egypt": "EG", "El Salvador": "SV", "Eritrea": "ER", "Estonia": "EE",
    "Ethiopia": "ET", "Fiji": "FJ", "Finland": "FI", "France": "FR", "Gabon": "GA", "Gambia": "GM", "Georgia": "GE",
    "Germany": "DE", "Ghana": "GH", "Gibraltar": "GI", "Greece": "GR", "Grenada": "GD", "Guatemala": "GT",
    "Guinea": "GN", "Guinea-Bissau": "GW", "Haiti": "HT", "Honduras": "HN", "Hong Kong": "HK", "Hungary": "HU",
    "Iceland": "IS", "India": "IN", "Indonesia": "ID", "Iraq": "IQ", "Ireland": "IE", "Israel": "IL", "Italy": "IT",
    "Jamaica": "JM", "Japan": "JP", "Jordan": "JO", "Kazakhstan": "KZ", "Kenya": "KE", "Kuwait": "KW",
    "Kyrgyzstan": "KG", "Laos": "LA", "Latvia": "LV", "Lebanon": "LB", "Liberia": "LR", "Libya": "LY",
    "Liechtenstein": "LI", "Lithuania": "LT", "Luxembourg": "LU", "Macao": "MO", "Malaysia": "MY", "Maldives": "MV",
    "Mali": "ML", "Malta": "MT", "Mauritius": "MU", "Mexico": "MX", "Micronesia": "FM", "Moldova": "MD",
    "Monaco": "MC", "Mongolia": "MN", "Morocco": "MA", "Mozambique": "MZ", "Myanmar (Burma)": "MM", "Namibia": "NA",
    "Nepal": "NP", "Netherlands": "NL", "New Zealand": "NZ", "Nicaragua": "NI", "Niger": "NE", "Nigeria": "NG",
    "North Macedonia": "MK", "Norway": "NO", "Oman": "OM", "Pakistan": "PK", "Panama": "PA", "Papua New Guinea": "PG",
    "Paraguay": "PY", "Peru": "PE", "Philippines": "PH", "Poland": "PL", "Portugal": "PT", "Qatar": "QA",
    "Romania": "RO", "Russia": "RU", "Rwanda": "RW", "Samoa": "WS", "San Marino": "SM", "Saudi Arabia": "SA",
    "Senegal": "SN", "Serbia": "RS", "Seychelles": "SC", "Sierra Leone": "SL", "Singapore": "SG", "Slovakia": "SK",
    "Slovenia": "SI", "Solomon Islands": "SB", "Somalia": "SO", "South Africa": "ZA", "South Korea": "KR",
    "Spain": "ES", "Sri Lanka": "LK", "St. Kitts & Nevis": "KN", "St. Lucia": "LC", "Suriname": "SR", "Sweden": "SE",
    "Switzerland": "CH", "Taiwan": "TW", "Tajikistan": "TJ", "Tanzania": "TZ", "Thailand": "TH", "Togo": "TG",
    "Tonga": "TO", "Trinidad & Tobago": "TT", "Tunisia": "TN", "Turkmenistan": "TM", "Turks & Caicos Islands": "TC",
    "Türkiye": "TR", "Uganda": "UG", "Ukraine": "UA", "United Arab Emirates": "AE", "United Kingdom": "GB",
    "United States": "US", "Uruguay": "UY", "Uzbekistan": "UZ", "Vanuatu": "VU", "Vatican City": "VA",
    "Venezuela": "VE", "Vietnam": "VN", "Yemen": "YE", "Zambia": "ZM", "Zimbabwe": "ZW",
}


# ---------------------------------------------------------------- data loading

def fetch_json(url):
    last = None
    for _ in range(3):
        try:
            with urllib.request.urlopen(url, timeout=90) as r:
                return json.load(r)
        except Exception as e:  # network hiccup: retry
            last = e
    raise SystemExit(f"cannot fetch {url}: {last}")


def cached(name, url, refresh):
    path = os.path.join(DATA, name)
    if os.path.exists(path) and not refresh:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    data = fetch_json(url)
    os.makedirs(DATA, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)
    return data


def wb_series(refresh, indicator, name):
    """{iso2: {year: value}} for an indicator, years PPP_YEARS."""
    url = f"https://api.worldbank.org/v2/country/all/indicator/{indicator}?format=json&date={PPP_YEARS}&per_page=5000"
    data = cached(name, url, refresh)
    out = {}
    for r in data[1]:
        if r["value"] is not None:
            out.setdefault(r["country"]["id"], {})[int(r["date"])] = float(r["value"])
    return out


def load_current():
    rows = []
    with open(os.path.join(PRICING, "current_prices.psv"), encoding="utf-8") as f:
        header = f.readline()
        assert header.startswith("country|"), header
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            country, currency, price, vat, note = line.split("|")
            rows.append({"country": country, "currency": currency, "price": float(price), "price_text": price,
                         "vat": vat, "note": note})
    return rows


# ---------------------------------------------------------------- pricing maths

def decimals_of(currency):
    if currency in ZERO_DECIMALS:
        return 0
    if currency in THREE_DECIMALS:
        return 3
    return 2


def nice_step(value):
    """A 1/2/5 x 10^k step of about 2% of value (never larger), for coarse prices."""
    raw = value * 0.02
    k = math.floor(math.log10(raw))
    for m in (5, 2, 1):
        if m * 10 ** k <= raw:
            return m * 10 ** k
    return 10 ** k


def price_points_near(value, decimals):
    """Candidate price points around value (an ascending list of at least a few points on the local grid)."""
    if value < 10:
        # x.x9 points: 0.49, 0.59, ... 1.99, 2.09, ...
        base = math.floor(value * 10)
        return [round((k / 10.0) - 0.01, 3) for k in range(max(base - 3, 1), base + 5)]
    if value < 100 and decimals >= 2:
        # k.49 and k.99
        base = math.floor(value * 2)
        return [round(m / 2.0 - 0.01, 3) for m in range(max(base - 3, 2), base + 5)]
    step = nice_step(value) if value >= 100 or decimals == 0 else 1.0
    if decimals == 0:
        step = max(step, 1.0)
    base = math.floor(value / step)
    return [round(k * step, 3) for k in range(max(base - 3, 1), base + 5)]


def choose_price(raw_b, cap_b, floor_b, decimals):
    """Nearest price point to raw_b that is within [floor_b, cap_b]."""
    pts = [p for p in price_points_near(raw_b, decimals)]
    # extend the list downwards/upwards if the constraints are outside of it
    while pts and pts[-1] < floor_b:
        pts = price_points_near(pts[-1] * 1.5, decimals)
    while pts and pts[0] > cap_b:
        pts = price_points_near(pts[0] / 1.5, decimals)
    ok = [p for p in pts if floor_b <= p <= cap_b]
    if not ok:
        raise SystemExit(f"no price point between {floor_b} and {cap_b} near {raw_b}: {pts}")
    return min(ok, key=lambda p: (abs(p - raw_b), p))


def fmt_price(value, decimals):
    return f"{value:.{decimals}f}"


def vat_rate(text):
    text = text.strip().rstrip("%")
    return float(text) / 100.0 if text else 0.0


def level_index(iso2, ppp, fcrf):
    """Price level relative to France, from the latest common year: (ppp/fx)/(ppp_fr/fx_fr). Returns (index, year)."""
    years = sorted(set(ppp.get(iso2, {})) & set(fcrf.get(iso2, {})), reverse=True)
    for y in years:
        if y in ppp.get("FR", {}) and y in fcrf.get("FR", {}):
            idx = (ppp[iso2][y] / fcrf[iso2][y]) / (ppp["FR"][y] / fcrf["FR"][y])
            return idx, y
    return None, None


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true", help="download the data again instead of reading the cache")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    current = load_current()
    ppp = wb_series(args.refresh, "PA.NUS.PRVT.PP", "worldbank_ppp_private_consumption.json")
    fcrf = wb_series(args.refresh, "PA.NUS.FCRF", "worldbank_official_exchange_rate.json")
    meta_raw = cached("worldbank_countries.json", "https://api.worldbank.org/v2/country?format=json&per_page=400", args.refresh)
    meta = {c["iso2Code"]: c for c in meta_raw[1] if c["region"]["value"] != "Aggregates"}
    fx_raw = cached("fx_eur.json", "https://open.er-api.com/v6/latest/EUR", args.refresh)
    rates = fx_raw["rates"]  # units of currency per EUR
    fx_date = fx_raw.get("time_last_update_utc", "")
    try:
        fx_day = datetime.strptime(fx_date[5:16], "%d %b %Y").strftime("%Y-%m-%d")
    except ValueError:
        fx_day = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    assert len(current) == len(set(r["country"] for r in current)), "duplicate country"
    for r in current:
        if r["country"] not in ISO2:
            raise SystemExit(f"no ISO2 code for {r['country']}")
        if r["currency"] not in rates:
            raise SystemExit(f"no exchange rate for {r['currency']} ({r['country']})")

    # Price level index of every country that has data; the others are estimated afterwards.
    index, year = {}, {}
    for r in current:
        iso = ISO2[r["country"]]
        idx, y = level_index(iso, ppp, fcrf)
        if idx is not None:
            index[iso], year[iso] = idx, y

    # Medians of the price level index by (region, income), by income alone and worldwide, for the fallbacks.
    by_group, by_income, everyone = {}, {}, []
    for iso, c in meta.items():
        if iso in index:
            by_group.setdefault((c["region"]["value"], c["incomeLevel"]["value"]), []).append(index[iso])
            by_income.setdefault(c["incomeLevel"]["value"], []).append(index[iso])
            everyone.append(index[iso])
    group_median = {k: statistics.median(v) for k, v in by_group.items()}
    income_median = {k: statistics.median(v) for k, v in by_income.items()}
    world_median = statistics.median(everyone)

    def estimate(iso):
        c = meta.get(iso)
        if c:
            key = (c["region"]["value"], c["incomeLevel"]["value"])
            if key in group_median:
                return group_median[key], f"median of {key[0]} / {key[1]}"
            if key[1] in income_median:
                return income_median[key[1]], f"median of all {key[1]} economies"
        return world_median, "world median"

    estimated, basis = {}, {}
    for r in current:
        iso = ISO2[r["country"]]
        if iso in index:
            continue
        if iso in COMPARABLE and COMPARABLE[iso] in index:
            index[iso], year[iso] = index[COMPARABLE[iso]], year[COMPARABLE[iso]]
            basis[iso] = f"like {COMPARABLE[iso]}"
        elif iso == "TW":
            index[iso], year[iso] = group_median[("East Asia & Pacific", "High income")], 0
            basis[iso] = "median of East Asia & Pacific / High income"
        else:
            index[iso], year[iso] = 0, 0
            index[iso], basis[iso] = estimate(iso)
        estimated[iso] = True

    out_rows = []
    for r in current:
        iso = ISO2[r["country"]]
        cur = r["currency"]
        dec = decimals_of(cur)
        rate = rates[cur]
        l_idx = index[iso]
        raw_eur = BASE_EUR * min(1.0, l_idx)
        floored = raw_eur < FLOOR_EUR
        raw_eur = max(raw_eur, FLOOR_EUR)
        raw_b = raw_eur * rate
        new_b = choose_price(raw_b, CAP_EUR * rate, FLOOR_EUR * rate, dec)
        if iso == "FR":
            new_b = BASE_EUR
        new_eur = new_b / rate
        vat = vat_rate(r["vat"])
        flags = []
        if cur in VOLATILE:
            flags.append("volatile_currency")
        if iso in DISTORTED:
            flags.append("official_rate_distortion")
        if iso in estimated:
            flags.append("estimated_ppp")
        if floored and iso not in DISTORTED:
            flags.append("floored")
        out_rows.append({
            "country": r["country"], "iso2": iso, "currency": cur,
            "current_price": r["price_text"], "new_price": fmt_price(new_b, dec),
            "change_pct": f"{(new_b / r['price'] - 1) * 100:.1f}",
            "new_price_eur_equiv": f"{new_eur:.2f}",
            "ppp_index_vs_france": f"{l_idx:.3f}",
            "capped": "yes" if l_idx >= 1.0 else "no",
            "floored": "yes" if floored else "no",
            "ppp_year": year[iso] or "",
            "estimated": ("yes: " + basis[iso]) if iso in estimated else "no",
            "vat_rate": r["vat"] or "none",
            "new_price_net_of_vat": fmt_price(new_b / (1 + vat), dec),
            "review_flag": ";".join(flags),
            "_sort_price": new_b,
        })

    out_rows.sort(key=lambda x: (x["currency"], x["_sort_price"], x["country"]))
    for x in out_rows:
        x["group_id"] = f"{x['currency']}-{x['new_price']}"

    columns = ["group_id", "country", "iso2", "currency", "current_price", "new_price", "change_pct",
               "new_price_eur_equiv", "ppp_index_vs_france", "capped", "floored", "ppp_year", "estimated",
               "vat_rate", "new_price_net_of_vat", "review_flag"]
    out = args.out or os.path.join(PRICING, f"prices_{fx_day}.csv")
    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore", lineterminator="\n")
        w.writeheader()
        w.writerows(out_rows)

    report(out_rows, rates, fx_day, out)


def report(rows, rates, fx_day, out):
    print(f"wrote {out}  ({len(rows)} countries, rates of {fx_day}, 1 EUR = {rates['USD']:.4f} USD)")
    eur = [float(r["new_price_eur_equiv"]) for r in rows]
    print(f"EUR-equivalent min {min(eur):.2f}  max {max(eur):.2f}  (hard cap {CAP_EUR:.2f}, floor {FLOOR_EUR:.2f})")
    fr = next(r for r in rows if r["iso2"] == "FR")
    print(f"France: {fr['currency']} {fr['new_price']}")
    print("distinct prices:", len({r['group_id'] for r in rows}))
    print("capped:", sum(r["capped"] == "yes" for r in rows), " floored:", sum(r["floored"] == "yes" for r in rows),
          " estimated:", sum(r["estimated"] != "no" for r in rows))
    over = [r for r in rows if float(r["new_price_eur_equiv"]) > CAP_EUR + 0.005]
    under = [r for r in rows if float(r["new_price_eur_equiv"]) < FLOOR_EUR - 0.005]
    print("above cap:", [r["country"] for r in over], " below floor:", [r["country"] for r in under])
    rich_not_capped = [r["country"] for r in rows if float(r["ppp_index_vs_france"]) >= 1 and r["capped"] != "yes"]
    print("index>=1 but not capped:", rich_not_capped)


if __name__ == "__main__":
    sys.exit(main())
