#!/usr/bin/env python3
"""Create the App Store products of a price test: one `<base id>.<suffix>`
per base subscription, same group, level, name, 7-day free trial and
countries, at a new US price that Apple equalizes for every other country.

  scripts/asc_price_variant.py b diamond.yearly=59.99 diamond.monthly=9.99 \
      premium.yearly=39.99 premium.monthly=5.99 [--screenshot paywall.png] [--submit]

Safe to re-run: each step is skipped when already done. The app sells these
to the half of installs that app_config `price_test` (value = suffix) turns on.
"""
import hashlib, os, sys, urllib.request
sys.path.insert(0, os.path.dirname(__file__))
from asc import call, token

APP = "6790178402"
PREFIX = "com.chessmaster."
NOTE = ("Price test: this is the same subscription as the product without the suffix, at a lower price. "
        "The app shows one of the two price sets on its upgrade sheet (Profile > Upgrade).")


def pages(path, tok):
    out, inc = [], []
    while path:
        d = call("GET", path, tok=tok)
        out += d["data"]; inc += d.get("included", [])
        path = d.get("links", {}).get("next")
    return out, inc


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    suffix, prices = args[0], dict(a.split("=") for a in args[1:] if "=" in a)
    shot = sys.argv[sys.argv.index("--screenshot") + 1] if "--screenshot" in sys.argv else None
    tok = token()
    groups = call("GET", f"/v1/apps/{APP}/subscriptionGroups?include=subscriptions&limit=10", tok=tok)
    group = groups["data"][0]["id"]
    subs = {s["attributes"]["productId"]: s for s in groups["included"]}
    territories = [t["id"] for t in pages("/v1/territories?limit=200", tok)[0]]

    for short, usd in prices.items():
        tok = token()   # tokens last 20 minutes; one product is ~350 calls
        base = subs[PREFIX + short]
        pid = f"{PREFIX}{short}.{suffix}"
        ba = base["attributes"]
        sub = subs.get(pid)
        if not sub:
            sub = call("POST", "/v1/subscriptions", {"data": {"type": "subscriptions", "attributes": {
                "name": f"{ba['name']} {suffix.upper()}", "productId": pid, "familySharable": False,
                "subscriptionPeriod": ba["subscriptionPeriod"], "groupLevel": ba["groupLevel"], "reviewNote": NOTE},
                "relationships": {"group": {"data": {"type": "subscriptionGroups", "id": group}}}}}, tok)["data"]
            print(pid, "created", sub["id"])
        sid = sub["id"]
        rel = lambda: {"subscription": {"data": {"type": "subscriptions", "id": sid}}}

        if not call("GET", f"/v1/subscriptions/{sid}/subscriptionLocalizations", tok=tok)["data"]:
            for loc in call("GET", f"/v1/subscriptions/{base['id']}/subscriptionLocalizations", tok=tok)["data"]:
                a = loc["attributes"]
                call("POST", "/v1/subscriptionLocalizations", {"data": {"type": "subscriptionLocalizations",
                     "attributes": {"locale": a["locale"], "name": a["name"], "description": a["description"]},
                     "relationships": rel()}}, tok)
            print(pid, "localized")

        call("POST", "/v1/subscriptionAvailabilities", {"data": {"type": "subscriptionAvailabilities",
             "attributes": {"availableInNewTerritories": True},
             "relationships": {**rel(), "availableTerritories": {"data": [
                 {"type": "territories", "id": t} for t in territories]}}}}, tok)

        priced = {p["relationships"]["territory"]["data"]["id"]
                  for p in pages(f"/v1/subscriptions/{sid}/prices?include=territory&limit=200", tok)[0]}
        usa, _ = pages(f"/v1/subscriptions/{sid}/pricePoints?filter[territory]=USA&limit=200", tok)
        point = next(p["id"] for p in usa if float(p["attributes"]["customerPrice"]) == float(usd))
        eq, _ = pages(f"/v1/subscriptionPricePoints/{point}/equalizations?include=territory&limit=200", tok)
        points = {"USA": point, **{p["relationships"]["territory"]["data"]["id"]: p["id"] for p in eq}}
        for terr, pp in points.items():
            if terr in priced: continue
            call("POST", "/v1/subscriptionPrices", {"data": {"type": "subscriptionPrices", "attributes": {},
                 "relationships": {**rel(), "territory": {"data": {"type": "territories", "id": terr}},
                                   "subscriptionPricePoint": {"data": {"type": "subscriptionPricePoints", "id": pp}}}}}, tok)
        print(pid, "priced", usd, "in", len(points), "countries")

        offered = {o["relationships"]["territory"]["data"]["id"]
                   for o in pages(f"/v1/subscriptions/{sid}/introductoryOffers?include=territory&limit=200", tok)[0]}
        for terr in points:
            if terr in offered: continue
            call("POST", "/v1/subscriptionIntroductoryOffers", {"data": {"type": "subscriptionIntroductoryOffers",
                 "attributes": {"duration": "ONE_WEEK", "offerMode": "FREE_TRIAL", "numberOfPeriods": 1},
                 "relationships": {**rel(), "territory": {"data": {"type": "territories", "id": terr}}}}}, tok)
        print(pid, "free trial set")

        if shot and not call("GET", f"/v1/subscriptions/{sid}/appStoreReviewScreenshot", tok=tok).get("data"):
            blob = open(shot, "rb").read()
            r = call("POST", "/v1/subscriptionAppStoreReviewScreenshots", {"data": {
                "type": "subscriptionAppStoreReviewScreenshots",
                "attributes": {"fileName": os.path.basename(shot), "fileSize": len(blob)}, "relationships": rel()}}, tok)["data"]
            for op in r["attributes"]["uploadOperations"]:
                req = urllib.request.Request(op["url"], data=blob[op["offset"]:op["offset"] + op["length"]],
                                             method=op["method"], headers={h["name"]: h["value"] for h in op["requestHeaders"]})
                urllib.request.urlopen(req, timeout=300).read()
            call("PATCH", f"/v1/subscriptionAppStoreReviewScreenshots/{r['id']}", {"data": {
                "type": "subscriptionAppStoreReviewScreenshots", "id": r["id"],
                "attributes": {"uploaded": True, "sourceFileChecksum": hashlib.md5(blob).hexdigest()}}}, tok)
            print(pid, "review screenshot uploaded")

        if "--submit" in sys.argv:
            call("POST", "/v1/subscriptionSubmissions", {"data": {"type": "subscriptionSubmissions", "relationships": rel()}}, tok)
            print(pid, "submitted for review")
        print(pid, call("GET", f"/v1/subscriptions/{sid}", tok=tok)["data"]["attributes"]["state"])


if __name__ == "__main__":
    main()
