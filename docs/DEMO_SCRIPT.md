# Demo Script (2 minutes)

A talk track for a screen recording that walks an **operations manager** through a risky trip and the admin dashboard. Speak to the manager's problem, not the code. Lines in quotes are what you say; the rest is what you click.

## Before you record

- [ ] Open the [live app](https://travel-risk-platform.onrender.com) once a few minutes early so Render wakes up and the first check is fast.
- [ ] Pick tonight's risky route. Check the [NWS alerts map](https://www.weather.gov/) or [FAA NAS status](https://nasstatus.faa.gov/) for a city with storms or a ground stop, and run it once to confirm it scores **Medium or High**. Have a calm route ready too (for example New York, NY to San Francisco, CA, flight).
- [ ] Save two or three trips as `employee1@email.com` and `employee5@email.com` so the admin dashboard has data.
- [ ] Have the admin credentials ready in a second browser profile or private window.
- [ ] Browser at 100% zoom, notifications off, bookmarks bar hidden.

## Talk track

### 0:00 to 0:15, the problem

*Show the login page.*

> "If you run operations for a company with people on the road, you find out about a ground stop or a winter storm at the airport, not the day before when you could still move the trip. The information is public, it's just spread across six different sites. I built one place that checks all of them."

### 0:15 to 0:50, a risky trip

*Click **Use demo account**, sign in, enter the risky route and date, choose Flight, click **Assess trip risk**.*

> "I'm an employee flying [origin] to [destination] on [date]. In a few seconds the app has checked eight live sources in parallel: forecasts at both ends and the midpoint, National Weather Service alerts, airport weather, FAA delays and ground stops, and road closures."

*Point at the score, then scroll to the signals and evidence.*

> "It says **[High / Medium]**, [N] points, and it shows exactly why: [read the top one or two signals]. Every source shows whether it answered, so if one is down you know the score might be low, not wrong."

*Point at **Recommended action**, then click **Compare dates**.*

> "The recommendation is the part a manager acts on. And if I want to move the trip, Compare dates shows which nearby day is safer."

### 0:50 to 1:10, saving and alerts

*Click **Save trip**, then **Check alerts**.*

> "I save the trip. When conditions change, a recheck compares the new score with the saved one and raises a notification, so nobody has to remember to look again."

### 1:10 to 1:40, the operations manager's view

*Switch to the admin window and open the dashboard.*

> "Now I'm the operations manager. I see every employee's upcoming trips sorted by date, how many are high risk, and unread alerts, in one view. Employees can only ever see their own trips; this view is admin-only."

*Scroll to **API monitoring**.*

> "And because this depends on outside data, it monitors itself: call counts, failures and slow calls per source, so I can tell the difference between a calm day and a broken feed."

### 1:40 to 1:55, a bug I caught

*Optional: show the README case study or PR #14 for a second.*

> "One story from building it: a New York to San Francisco flight scored High, 60 points, because statewide California road closures each counted separately on a trip that never touches a road. I rolled them into one signal and stopped scoring roads for flights. Now it's Low, and a driving trip with the same closures is Medium."

### 1:55 to 2:00, close

> "Live data, one score you can explain, and a view for the person who's responsible. Thanks for watching."

## 30-second pitch

> "Travel Risk Platform tells an operations team, before anyone leaves, how likely a trip is to be disrupted and why. It checks eight live sources in parallel (weather forecasts, National Weather Service alerts, airport weather, FAA ground stops and road closures), turns them into one Low, Medium or High score with the evidence attached, and alerts when a saved trip's risk changes. Managers get a company-wide dashboard; employees only see their own trips. It's a Spring Boot app on Postgres with Google sign-in, caching, rate limiting, monitoring and CI, deployed on Render. The part I'm proudest of is that every score is explainable, which is how I caught and fixed a bug where road closures made a cross-country flight look High risk."

## If something goes wrong on camera

- **A source shows "could not be reached":** say so, it's a feature. "One feed is down right now, and the app tells you instead of hiding it."
- **The route scores Low:** keep going and say "a calm day is the common case; here's what it looks like when it isn't," then switch to your backup route.
- **The first check is slow:** Render was asleep. Re-record, or cut the wait in editing.
