# Demo Script (2 minutes)

A talk track for a screen recording that walks an **operations manager** through a risky trip and the admin dashboard. The viewer is a buyer: a travel, operations or duty-of-care lead. Speak to their problem and what they get, not the code. Lines in quotes are what you say; the rest is what you click.

## Before you record

- [ ] Open the [live app](https://travel-risk-platform.onrender.com) once a few minutes early so Render wakes up and the first check is fast.
- [ ] Pick tonight's risky route. Check the [NWS alerts map](https://www.weather.gov/) or [FAA NAS status](https://nasstatus.faa.gov/) for a city with storms or a ground stop, and run it once to confirm it scores **Medium or High**. Have a calm route ready too (for example New York, NY to San Francisco, CA, flight).
- [ ] Use only the public demo accounts on camera: the employee demo login and the **read-only demo admin** (`demo-admin@email.com` / `travel-risk-demo`, or **Use demo admin** on the login page). Never sign in with your real admin account while recording.
- [ ] Sign in as the demo admin in a second browser profile or private window before you start, and check that the seeded demo trips show on the dashboard.
- [ ] Download a fresh sample itinerary from *Your trips* → **Import** → Sample: CSV (the sample is dated from today, so its trips are inside the forecast window). Save it to your desktop as `acme-trips.csv`.
- [ ] Browser at 100% zoom, notifications off, bookmarks bar hidden.

## Talk track

### 0:00 to 0:15, the problem

*Show the login page.*

> "If you're responsible for people on the road, you usually find out about a ground stop or a winter storm when an employee calls you from the airport. By then it's a missed meeting, a rebooking fee and a lost day, and it's on you, because keeping travelers safe is part of the job. The warning signs were public the day before. They're just spread across six different sites nobody has time to check. Travel Risk Platform checks all of them for you. Enterprise tools like Everbridge focus on traveler safety. This focuses on whether the trip itself will be disrupted, before you book."

### 0:15 to 0:50, a risky trip

*Click **Use demo account**, sign in, enter the risky route and date, choose Flight, click **Assess trip risk**.*

> "I'm an employee flying [origin] to [destination] on [date]. In a few seconds the app has checked eight live sources in parallel: forecasts at both ends and the midpoint, National Weather Service alerts, airport weather, FAA delays and ground stops, and road closures."

*Point at the score, then scroll to the signals and evidence.*

> "It says **[High / Medium]**, [N] points, and it shows exactly why: [read the top one or two signals]. Every source shows whether it answered, so if one is down you know the score might be low, not wrong."

*Point at **Recommended action**, then click **Compare dates**.*

> "That check would take ten or fifteen minutes by hand; this took seconds. The recommendation is the part a manager acts on, and if I want to move the trip, Compare dates shows which nearby day is safer."

### 0:50 to 1:15, the company's own trips and policy

*Click **Save trip**. In *Your trips*, click **Import** and upload `acme-trips.csv`.*

> "Nobody is going to retype every trip. A company already has them in a booking export or a calendar, so I upload that file. Every trip is risk-checked and saved, and checked against the company's travel policy. This one says **[Needs approval]** because the policy says [read the rule]. Each company writes its own rules in a simple config file, no code change. And when conditions change, saved trips are rechecked and raise an alert, so nobody has to remember to look again."

### 1:15 to 1:40, the operations manager's view

*Switch to the private window where you're signed in as the read-only demo admin, and open the dashboard.*

> "Now I'm the operations manager. Every employee's upcoming trips, sorted by date, with the policy result, how many are high risk, and unread alerts, in one view. This is my morning check: which trips do I need to move today? Employees only ever see their own trips, and every assessment is kept, so if anyone asks what we knew and when, there's a record. This is a read-only demo admin on sample data, and the login is in the README, so you can try this view yourself."

*Scroll to **API monitoring**.*

> "And because it depends on outside data, it monitors itself, so I can tell a calm day from a broken feed. I never have to wonder whether 'Low' really means low."

### 1:40 to 1:55, a bug I caught

*Optional: show the README case study or PR #14 for a second.*

> "A score is only useful if you trust it. In testing, a New York to San Francisco flight scored High, 60 points, because statewide California road closures were each counted separately, on a trip that never touches a road. I rolled them into one signal and stopped scoring roads for flights. Now it's Low, and a driving trip with the same closures is Medium. False alarms train people to ignore alerts, so I treat them as bugs."

### 1:55 to 2:00, call to action

> "Rolling it out is simple: sign in with your company accounts, upload the trips you already book, and drop in your own policy file. If you send people on the road, let's run it on next week's trips and see which ones you'd want to move."

## 30-second pitch

> "Companies find out about travel disruptions when an employee calls from the airport, and by then it costs a missed meeting, a rebooking and a lost day, plus a duty-of-care problem. Travel Risk Platform tells the operations team, before anyone leaves, how likely a trip is to be disrupted and why. Enterprise tools like Everbridge focus on traveler safety. This focuses on whether the trip itself will be disrupted, before you book. It checks eight live sources in parallel (weather forecasts, National Weather Service alerts, airport weather, FAA ground stops and road closures), turns them into one Low, Medium or High score with the evidence attached, and alerts when a saved trip's risk changes. Companies upload the itineraries they already have and set their own travel policy in a config file, so a High risk trip is flagged for manager approval automatically. Managers get a company-wide dashboard; employees only see their own trips. It's a Spring Boot app on Postgres with Google sign-in, caching, rate limiting, monitoring and CI, deployed on Render. Every score is explainable, so managers can defend the call to move a trip. Give me next week's trips and I'll show you which ones are at risk."

## If something goes wrong on camera

- **A source shows "could not be reached":** say so, it's a feature. "One feed is down right now, and the app tells you instead of hiding it."
- **The route scores Low:** keep going and say "a calm day is the common case; here's what it looks like when it isn't," then switch to your backup route.
- **The first check is slow:** Render was asleep. Re-record, or cut the wait in editing.
