# App Store Optimization playbook

What we did for Chess AI (Nov 2026), written so another app (e.g. fintwit.ai) can repeat it.

## 1. Find real search demand first

- App Store search volume isn't public, so use Google Keyword Planner (US) as a proxy for what people type. Pull volumes for 30–60 candidate phrases: category words, feature words, "how to" words, competitor-adjacent words.
- Expect your pet phrase to be small. "ai chess coach" had ~260 searches/mo; "chess analysis/analyzer" had 22K, "chess puzzles" 27K, "chess coach" 4.4K, "learn chess" 3.6K. Target the big generic intents your app genuinely serves and keep the "AI" angle for the name and screenshots.
- Check what the top competitor ranks for and where it's weak. Gaps are queries where it only ranks via user forum or blog pages, which makes them easy wins for both ASO and SEO.

## 2. Metadata fields (all indexed; only change with a new app version)

- **Name (30 chars):** brand plus the strongest keyword phrase, e.g. "Chess AI: Your Personal Coach". Changing the name is costly, so do it rarely.
- **Subtitle (30 chars):** the 2–3 biggest intents, phrased for humans, e.g. "Game Review, Puzzles & Lessons". It's indexed and shown in search results.
- **Keyword field (100 chars):**
  - Comma-separated, no spaces, singular forms.
  - Never repeat words already in the name or subtitle; Apple combines words across fields.
  - Never include the app's category word (it's free) or competitor trademarks; that's a rejection risk.
  - Fill all 100 characters with single words that combine into phrases, e.g. `analysis,analyzer,learn,trainer,tutor,beginner,engine,tactics,computer,offline,friend,mate,openings`.
- **Promotional text (170):** not indexed, but can change anytime without review. Use it for timely hooks.
- **Description:** not indexed on iOS. Write it for conversion: benefits first, scannable sections.

## 3. Screenshots (conversion, the biggest lever after ranking)

- The first 2–3 screenshots are all most people see in search results. Lead with the differentiator, e.g. "Your Personal 24×7 AI Chess Coach" over the coach explaining a real mistake.
- One idea per screenshot, with a big 2-line headline: 3–5 words, key phrase in an accent color, and a short sub-line under it.
- Show the real app UI inside a device frame on a branded background. The content must be what the app actually does in the submitted build. Apple rejects screenshots of features that aren't in the build; we shipped the daily puzzle in the same build as the screenshot that shows it.
- Order by value: core differentiator → its proof (report) → analysis → personalization → content depth (puzzles) → social (play with friends). Up to 10 allowed; 6 is plenty.
- Build them as HTML and render with headless Chrome at exactly 1290×2796 (6.7"/6.9" slot). Keep the HTML sources in the repo so headlines can be edited in seconds.
- Upload only to the largest iPhone slot (Dynamic Island, large display). Clear the older slots so they fall back ("Using Existing Assets") instead of showing stale images.
- Read every word of staged demo content (AI text, numbers) against the board or data before shipping. We caught "level for 18 moves" when the mistake came on move 10.

## 4. Ratings (ranking and conversion)

- Call `AppStore.requestReview` at happy moments: wins, finishing a valuable AI output, streak/daily completion, a run of successes. Never after a loss, an error or a paywall.
- Ask often and let Apple throttle: the system shows it at most 3 times per 365 days per user. Our gates are only "has used the core feature once" and "7 days since our last ask". Don't add once-per-version gates; they silently starve the prompt.
- Don't stack it on another system alert (notification permission) and wait ~1.5s for the UI to settle.
- No "Do you like us?" pre-screen (guideline 5.6.1). Track a `review_requested` event with the trigger.
- It never appears in TestFlight builds, and in App Store builds only if the user hasn't turned off In-App Ratings in Settings. Test in a debug build, where it always shows.

## 5. Keep testing

- Product Page Optimization (ASC → Product Page Optimization) A/B tests up to 3 screenshot/icon variants on the live version without a release. Run one at a time for 2–4 weeks.
- Custom Product Pages: separate pages with their own screenshots for ads, creator links and blog CTAs (append `ppid=`).
- Re-pull keyword volumes each quarter. Swap the weakest keyword words with each release and watch App Store Connect → Analytics → Sources (App Store Search impressions/downloads).

## 6. Off-store channels that feed the store

- **SEO blog:** posts on gap queries (step 1), each ending in an App Store CTA with `ct=` campaign tokens. Submit the sitemap in Google Search Console.
- **Shareable output:** a share card or Wordle-style result text with your URL. Every share is a free ad.
- **Creator offer codes:** one custom code per creator plus a 15% commission, attributed server-side from the transaction's `offerIdentifier`.
- **Friend referral:** both players get a free month via one-time offer codes, which keeps it inside IAP rules.
