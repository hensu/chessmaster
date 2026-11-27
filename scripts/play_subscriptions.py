#!/usr/bin/env python3
"""Create the Play subscriptions the Android app expects (same product ids as iOS).

  scripts/play_subscriptions.py            # create what is missing, activate base plans and trial offers
  scripts/play_subscriptions.py --list     # show what exists
  scripts/play_subscriptions.py --variant b diamond.yearly=59.99 ...   # price-test products `<id>.b`

Each subscription has one base plan (`monthly` or `yearly`, auto-renewing) and an offer
`free-trial` (1 week free, new customers who never had any subscription in the app).
US price as on iOS; other countries are Google's converted prices.
Key: ~/.config/chessai/play-service-account.json. Needs PyJWT + cryptography.
"""
import json, os, sys, time, urllib.error, urllib.parse, urllib.request
import jwt

PACKAGE = "com.hensu.chessmaster"
KEY = os.path.expanduser("~/.config/chessai/play-service-account.json")
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
REGIONS = "2026/01"

PRODUCTS = [
    ("com.chessmaster.premium.monthly", "monthly", "P1M", 7, 990000000, "Platinum Monthly",
     "Unlimited AI game reviews"),
    ("com.chessmaster.premium.yearly", "yearly", "P1Y", 49, 990000000, "Platinum Yearly",
     "Unlimited AI game reviews"),
    ("com.chessmaster.diamond.monthly", "monthly", "P1M", 12, 990000000, "Diamond Monthly",
     "Every review by our most advanced AI"),
    ("com.chessmaster.diamond.yearly", "yearly", "P1Y", 79, 990000000, "Diamond Yearly",
     "Every review by our most advanced AI"),
]


def token():
    sa = json.load(open(KEY))
    now = int(time.time())
    assertion = jwt.encode(
        {"iss": sa["client_email"], "scope": "https://www.googleapis.com/auth/androidpublisher",
         "aud": sa["token_uri"], "iat": now, "exp": now + 3000},
        sa["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion}).encode()
    return json.loads(urllib.request.urlopen(sa["token_uri"], body).read())["access_token"]


def call(tok, method, url, data=None, ok404=False, ok403=False):
    req = urllib.request.Request(url, data=json.dumps(data).encode() if data is not None else None,
                                 method=method, headers={"Authorization": "Bearer " + tok,
                                                         "Content-Type": "application/json"})
    try:
        text = urllib.request.urlopen(req, timeout=120).read().decode()
        return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        if (ok404 and e.code == 404) or (ok403 and e.code == 403):
            return None
        sys.exit(f"{method} {url.split('/applications/')[-1]} {e.code} {e.read().decode()[:1200]}")


def main():
    tok = token()
    if "--list" in sys.argv:
        for s in call(tok, "GET", f"{API}/subscriptions").get("subscriptions", []):
            print(s["productId"], [(b["basePlanId"], b.get("state")) for b in s.get("basePlans", [])])
            for b in s.get("basePlans", []):
                offers = call(tok, "GET", f"{API}/subscriptions/{s['productId']}/basePlans/{b['basePlanId']}/offers")
                print("   offers", [(o["offerId"], o.get("state")) for o in offers.get("subscriptionOffers", [])])
        return

    products = PRODUCTS
    if "--variant" in sys.argv:
        # Price-test products: `<base id>.<suffix>` at a new US price, e.g.
        #   --variant b diamond.yearly=59.99 diamond.monthly=9.99 premium.yearly=39.99 premium.monthly=5.99
        i = sys.argv.index("--variant")
        suffix, prices = sys.argv[i + 1], dict(a.split("=") for a in sys.argv[i + 2:] if "=" in a)
        products = []
        for pid, plan, period, _, _, title, benefit in PRODUCTS:
            short = pid.removeprefix("com.chessmaster.")
            if short in prices:
                units, cents = prices[short].split(".")
                products.append((f"{pid}.{suffix}", plan, period, int(units), int(cents) * 10_000_000,
                                 f"{title} {suffix.upper()}", benefit))
    for pid, plan, period, units, nanos, title, benefit in products:
        usd = {"currencyCode": "USD", "units": str(units), "nanos": nanos}
        existing = call(tok, "GET", f"{API}/subscriptions/{pid}", ok404=True)
        if existing is None:
            # Google's converted prices for every country; without permission for that call the
            # subscription starts US-only and the other countries are added in Play Console.
            conv = call(tok, "POST", f"{API}/pricing:convertRegionPrices", {"price": usd}, ok403=True)
            if conv is None:
                conv = {"convertedRegionPrices": {"US": {"price": usd}}}
            regional = [{"regionCode": code, "newSubscriberAvailability": True, "price": r["price"]}
                        for code, r in sorted(conv.get("convertedRegionPrices", {}).items())]
            for r in regional:
                if r["regionCode"] == "US":
                    r["price"] = usd
            other = conv.get("convertedOtherRegionsPrice", {})
            body = {
                "packageName": PACKAGE, "productId": pid,
                "listings": [{"languageCode": "en-US", "title": title, "benefits": [benefit]}],
                "basePlans": [{
                    "basePlanId": plan,
                    "autoRenewingBasePlanType": {"billingPeriodDuration": period,
                                                 "gracePeriodDuration": "P3D",
                                                 "resubscribeState": "RESUBSCRIBE_STATE_ACTIVE",
                                                 "legacyCompatible": True},
                    "regionalConfigs": regional,
                    **({"otherRegionsConfig": {"usdPrice": other["usdPrice"], "eurPrice": other["eurPrice"],
                                               "newSubscriberAvailability": True}} if other.get("eurPrice") else {}),
                }],
            }
            call(tok, "POST", f"{API}/subscriptions?productId={pid}&regionsVersion.version={REGIONS}", body)
            print("created", pid, f"({len(regional)} regions)")
            existing = call(tok, "GET", f"{API}/subscriptions/{pid}")
        bp = next(b for b in existing["basePlans"] if b["basePlanId"] == plan)
        if bp.get("state") != "ACTIVE":
            call(tok, "POST", f"{API}/subscriptions/{pid}/basePlans/{plan}:activate", {})
            print("  base plan", plan, "activated")
        offers = call(tok, "GET", f"{API}/subscriptions/{pid}/basePlans/{plan}/offers").get("subscriptionOffers", [])
        offer = next((o for o in offers if o["offerId"] == "free-trial"), None)
        if offer is None:
            regions = [r["regionCode"] for r in bp.get("regionalConfigs", [])]
            body = {
                "packageName": PACKAGE, "productId": pid, "basePlanId": plan, "offerId": "free-trial",
                "phases": [{"recurrenceCount": 1, "duration": "P1W",
                            "regionalConfigs": [{"regionCode": c, "free": {}} for c in regions],
                            "otherRegionsConfig": {"free": {}}}],
                "regionalConfigs": [{"regionCode": c, "newSubscriberAvailability": True} for c in regions],
                "otherRegionsConfig": {"otherRegionsNewSubscriberAvailability": True},
                "targeting": {"acquisitionRule": {"scope": {"anySubscriptionInApp": {}}}},
            }
            offer = call(tok, "POST",
                         f"{API}/subscriptions/{pid}/basePlans/{plan}/offers?offerId=free-trial"
                         f"&regionsVersion.version={REGIONS}", body)
            print("  offer free-trial created")
        if offer.get("state") != "ACTIVE":
            call(tok, "POST", f"{API}/subscriptions/{pid}/basePlans/{plan}/offers/free-trial:activate", {})
            print("  offer free-trial activated")
    print("done")


if __name__ == "__main__":
    main()
