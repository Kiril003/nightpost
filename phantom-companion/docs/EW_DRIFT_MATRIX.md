# PHANTOM-EW — Drift Matrix

> Synthetic validation of `core-sensor/.../nav/` (`CustomLocationEngine`,
> `SpoofGate`, `Kalman2D`, `DeadReckoner`, `Altimeter`).
> Run: 2026-07-14, night shift. Deterministic, seeded, reproducible.
> Harness: `core-sensor/src/test/.../nav/sim/`.
>
> **Round 1 verdict (superseded): the engine must NOT be wired.** Three defects,
> two critical. Reproduced by isolation probes against the real classes
> (`EwFindingsProbeTest`).
>
> **Round 2 (same night): all three repaired, plus a fourth found while
> repairing them.** See «THE REPAIRS» at the foot of this document for the
> before/after, the three regressions the repairs themselves introduced, and the
> limits that remain. Regression tests: `EwRepairTest` (27 green).
> **Still not device-tested.**

---

## Why simulate at all

On a real drive, GPS *is* the truth you would grade against — so an engine
that quietly follows a spoofer scores **perfectly**. The claim «PHANTOM keeps
working when the sky lies» is therefore untestable on the road, in principle.

Only a simulation holds a ground truth independent of the sensors. That lets
the real question be asked: *how far is PHANTOM's dot from the operator's
actual body, and **does PHANTOM know it?***

That second clause is the whole product. `FusedFix.uncertaintyM` is the
engine's sworn statement about its own doubt. The engine's own doctrine:

> «A navigator that says ±300 m, я веду по інерції, is useful. One that draws a
> confident dot in the wrong street is how people die.»

So the headline metric here is not drift. It is the **honesty ratio** —
true error ÷ the engine's own claimed 1σ. Above 1, the engine is confidently
wrong, which is the single thing it exists to never be.

---

## F1 — CRITICAL: one tunnel and the gate never believes the sky again

`SpoofGate` rejects a fix whose distance from the last accepted fix exceeds
`MAX_JUMP_M` (800 m). **That comparison ignores how much time passed.**

After a 120 s tunnel the operator has legitimately travelled ~1.3 km. His
first real fix is therefore «a teleport» and is refused. And because a
refused fix is — correctly, deliberately — *not* allowed to become the new
reference (that is the anti-salami-walk rule, and it is right), every
subsequent real fix is measured against the same stale pre-tunnel point, and
is refused too.

```
probe B1 — re-acquiring the sky after a 120 s tunnel
  +1s:  REFUSED — стрибок 1319 м за 121.0 с
  +2s:  REFUSED — стрибок 1331 м за 122.0 с
  +60s: REFUSED — стрибок 1968 м за 180.0 с
  60 real fixes after the tunnel → accepted=0  refused=60
  consecutiveRejects=60
```

The lockout is **permanent**, and it is self-reinforcing: past
`SPOOF_STREAK`, the engine latches to `SPOOFED` and hands navigation to dead
reckoning — which, per F2, is parked. The operator is stranded, with a
confident dot, at the mouth of the tunnel, forever.

Any GPS gap does this: a tunnel, an underpass, a parking garage, the app
backgrounded at a traffic light, a cold start in a moving car.

**The bug is that absence of evidence is being treated as evidence of
spoofing.** Over a 121 s gap the gate simply has no basis to judge
plausibility — almost any position is reachable — and it should say so
instead of refusing.

### The fix is a fork, and it is the operator's to make

The obvious repair («after a long gap, accept and re-seed the reference»)
**hands a spoofer the recipe**: jam first, wait out the gap threshold, then
present a false origin to a gate that has agreed to stop asking questions.
Jam-then-spoof is the standard attack chain, and this would automate the
handover.

The honest options:

| Option | Cost |
|---|---|
| Time-scaled jump budget (`jump ≤ maxSpeed × dt`) | Bounds the lie by physics rather than by a constant, but after a long gap that budget is enormous — a 2 min gap authorises a 36 km «plausible» jump. |
| Re-seed after a gap, but enter a probationary state | Recovers from tunnels; the probation must then be defended by a *second* authority (map-matching, the inertial track, a known start point). |
| Re-seed only when corroborated by dead reckoning | Strongest — but requires F2 to be fixed first, because today DR has no opinion worth corroborating with. |

I have not chosen. This is a security posture decision, not a code cleanup.

---

## F2 — CRITICAL: dead reckoning does not drift. It parks.

A car at a steady 30 m/s produces almost **no linear acceleration**.
`DeadReckoner` reads «below `ACCEL_NOISE_FLOOR_MPS2` (0.35)» as «stopped» and
bleeds the speed off at `STATIONARY_DECAY_PER_S` = 33 %/s.

```
probe B2 — reckoned speed of an operator holding a true 30 m/s
  t=1s   21.69 m/s
  t=3s   11.18 m/s
  t=5s    5.77 m/s
  t=10s   1.10 m/s      ← truth: 30.00 m/s
```

The decay was written to stop a *stopped* operator gliding across the map.
But the reckoner cannot distinguish **«stopped»** from **«cruising
smoothly»** — and cruising smoothly is what a car does almost all of the
time. So the instant the sky goes dark, the estimate decelerates to a halt
while the operator keeps driving.

This is visible across the whole matrix as a startling regularity:

> **max error ≈ 0.93 × (speed × blackout duration)**

That is not drift. That is the error you get from an estimate that **is not
moving at all**. Dead reckoning is currently contributing approximately
nothing.

| blackout | WALK (1.4 m/s) | URBAN (11 m/s) | HIGHWAY (30 m/s) |
|---|---|---|---|
| 10 s | 12 m | 93 m | 198 m |
| 30 s | 37 m | 287 m | 986 m |
| 60 s | 77 m | 600 m | 1 789 m |
| 120 s | 139 m | 1 394 m | 3 478 m |
| 300 s | 344 m | 2 787 m | 8 585 m |

*(max position error, metres. Distance travelled over the same window is
~84 m / 660 m / 1 800 m at 60 s — i.e. the error IS the journey.)*

Fixing this also needs a decision: the reckoner needs some way to tell
cruising from stopped. Candidates: hold the last GPS speed and decay only on
*sustained measured deceleration*; use gyro/vibration energy as a motion
detector; or gate the decay on `VerticalContext`/step detection. All are
design choices with different failure modes.

**Note the coupling:** F2 currently *masks* a second defect. Mount-yaw offset
(the phone in a cupholder, rotated from the direction of travel) should
misdirect the whole inertial velocity vector — Android's rotation vector
reports the *device's* yaw, not the course over ground, and `DeadReckoner`
overwrites its GPS-seeded heading with device yaw on the very next IMU
sample. The matrix shows yaw is nearly irrelevant today (198 m at 0° vs 223 m
at 180°) — **but only because there is no speed left to misdirect.** Fix F2
without fixing the heading and this becomes a first-order error source.

---

## F3 — the patient spoofer walks straight through the gate

Not a bug. `SpoofGate` does exactly what its doctrine says: it rejects the
*impossible*, not the *merely false*. This is the price of that decision,
measured:

```
probe B4 — displacement achieved against a STATIONARY operator, ZERO rejections
  drag  0.5 m/s →   300 m in 10 min   rejections=0
  drag  1.0 m/s →   600 m in 10 min   rejections=0
  drag  2.0 m/s →  1200 m in 10 min   rejections=0
  drag  5.0 m/s →  3000 m in 10 min   rejections=0
  drag 10.0 m/s →  6000 m in 10 min   rejections=0
```

Every individual fix is physically plausible, so every individual fix is
accepted. In the full engine the consequence is worse than the displacement,
because of what the engine *says* while it happens:

| attack | true error | engine's claimed 1σ | honesty ratio | detected? |
|---|---|---|---|---|
| drag 1 m/s | 175 m | **±5.5 m** | **33×** | **never** |
| drag 5 m/s | 911 m | **±6.0 m** | **170×** | only when the attack *stops* |
| drag 15 m/s | 2 759 m | ±12.4 m | 511× | only when the attack stops |
| teleport 5 km | 610 m | ±77 m | 6× | **2 s** ✅ |

The crude spoof is caught in 2 seconds. The patient one is never caught, and
throughout it the engine reports **±5 metres of confidence** while being
175 metres wrong. That is precisely the «confident dot in the wrong street».

**The engine already holds the evidence to catch this and never looks at it.**
It runs an inertial track in parallel with the GPS track. A drag is a GPS
stream that diverges *steadily, in one direction* from dead reckoning. Nothing
in `CustomLocationEngine` ever compares the two. That comparison — not a
tighter physics threshold — is the defence, and it is unbuilt.

---

## F4 — the admitted doubt is calibrated for a pedestrian

Under blackout, `uncertaintyM` grows honestly for a walker and collapses as a
lie at speed:

| blackout 300 s | true max error | claimed 1σ | verdict |
|---|---|---|---|
| WALK | 344 m | 984 m | over-cautious — **honest** ✅ |
| URBAN | 2 787 m | 1 193 m | 2.3× under |
| HIGHWAY | 8 585 m | 1 193 m | **7.2× under** ❌ |

`Kalman2D.DEFAULT_PROCESS_NOISE_MPS2 = 0.7` is «a normal driver in traffic».
The covariance it grows does not know how fast the operator was going when the
sky died. Even in *clear* sky at highway speed, true error exceeds the claimed
1σ in 78 % of fixes (expected for a well-calibrated 2D 1σ: ~39 %).

---

## What was NOT found

- The crude teleport spoof is caught reliably and fast (2 s). ✅
- The barometer cross-check fires on a forged altitude climb (3 s). ✅
- The anti-salami-walk rule (a refused fix never becomes the reference) is
  correct and is doing its job — it is, ironically, what makes F1 permanent.
- `Kalman2D` degrades gracefully on poor fixes: a 45 m-accuracy canyon fix
  nudges rather than commands, exactly as designed. ✅

---

## Reproduce

```bash
./gradlew :core-sensor:testDebugUnitTest --tests "*EwFindingsProbeTest*" -i   # the three defects, in isolation
./gradlew :core-sensor:testDebugUnitTest --tests "*EwDriftMatrixTest*"  -i    # the full matrix
```

Deterministic: same seeds, same numbers, every run.

---

# THE REPAIRS (same night)

All three defects fixed, and the fixes measured against the same seeded matrix.
Nothing below is device-tested.

## Before → after

| scenario | max error before | after | doubt honest? |
|---|---|---|---|
| tunnel 60 s, HIGHWAY | 1 789 m | **198 m** | >1σ 81 % → 33 % |
| tunnel 300 s, HIGHWAY | 8 585 m | **2 932 m** | >1σ **95 % → 8 %** |
| tunnel 300 s, URBAN | 2 787 m (locked out) | **1 973 m**, recovers | >1σ 100 % → 2 % |
| tunnel 120 s, URBAN | 1 394 m, **SPOOFED forever** | **final error 15 m** | — |
| phone at 180° in a cupholder | 648 m | **709 m — flat across all yaw** | mount no longer steers DR |
| drag 5 m/s | never caught | **caught in 60 s** | — |
| drag 15 / 50 m/s | never caught | **caught in 60 s** | — |

Every blackout now **re-acquires the sky** (final error 3–27 m). Before, anything
over ~30 s at speed ended in a permanent lockout.

## What was changed, and why

**F2 — the decay is gone.** An accelerometer cannot distinguish constant
velocity from rest; Galileo settled that, so the reckoner stopped trying. It
integrates honestly and holds the seeded speed. A real stop is caught by the
braking deceleration, which is large and unmistakable. (The old rule was never
what held a stopped operator still — the engine seeds speed from GPS, and a
stationary operator is seeded at zero.)

**The heading was wrong too, and F2 was hiding it.** The platform's rotation
vector reports the *device's* yaw, not the course over ground, and the old code
overwrote its GPS-seeded bearing with it on the next IMU sample. The reckoner
now propagates course by the device's yaw **delta** and never adopts its absolute
yaw. Mount orientation is now irrelevant (198 m at 0° vs 223 m at 180° → flat).

**Bias is now learned for free.** Honest integration costs b·t of speed error.
But while the sky is trusted the bias is *observable*: GPS states the true speed.
The estimator needs a **ten-second baseline** — over one second, GPS speed noise
(±0.4 m/s) is eight times the bias being measured, and the first cut of this
spent the blackout subtracting a bias it had hallucinated from noise (a
pedestrian's 300 s drift got *worse*: 364 m → 822 m).

**F1 — the gate now declines to judge.** Past 8 s of silence its reference is
stale and physics has no discriminating power left. It says so
(`Verdict.staleReference`) instead of answering anyway, and — crucially — it does
**not** move its own reference. Only [CustomLocationEngine], having corroborated
against the inertial track, may vouch for a re-acquisition.

**F3 — layer 5, `DriftAudit`.** The engine ran an inertial track in parallel with
the satellite one and never compared them. Now it does: over a window, how far
the sky says you moved must agree with how far your own body says you moved.

## The three regressions the repairs introduced (and how they were caught)

Worth recording, because each was more dangerous than the bug it came from:

1. **The gate moved its reference on a stale verdict** — so the spoofer's
   position silently became the new truth, and the engine *followed a 5 km
   teleport* (max error 610 m → **5 120 m**). Declining to judge and vouching are
   different acts.
2. **The re-acquisition budget was scaled off the Kalman σ.** A CV filter's
   covariance grows with the **fourth power** of time: two minutes of blindness
   produced a ±22 km doubt, and a 3σ budget waved an 8 km spoof straight through.
   The budget now comes from what dead reckoning could *actually* have got wrong
   (a fraction of the distance it carried), which is the model the inertial track
   obeys. **Corollary: the earlier «honesty» win was partly an artifact — >1σ
   improved because σ ballooned.** The current numbers are with a σ that is
   calibrated, not inflated.
3. **The drift audit false-alarmed on honest drives** — it cried SPOOFED on a
   pedestrian in an urban canyon. Its budget ignored GPS measurement noise, which
   lands in the residual at *both* ends of the window (a 45 m canyon fix puts
   ~42 m in before anyone has lied). Now paid for explicitly.

## Limits that remain — read before wiring this in

- **The slow drag still wins.** Below ≈2 m/s (≈0.2 × the operator's speed) a drag
  is inside dead reckoning's own error and inertia cannot see it. 1 m/s is caught
  only marginally, at ~180 s. **The answer is a third authority — map-matching
  («is he on a road that exists?») — not a braver constant.** Not built.
- **Jam-then-spoof is survivable, not defeated.** The engine holds out for a
  20-fix grace window, then **concedes** — because an inertial track that has
  itself drifted for two minutes cannot go on testifying, and holding out forever
  is just the F1 lockout in a third costume (the operator would be stranded).
  It concedes to **DEGRADED, never TRUSTED**, and says so out loud. An adversary
  who then *mimics* the operator's real motion while holding a false offset is
  self-consistent with the inertial witness and **cannot be caught by this engine
  at all**.
- **After a 5-minute urban blackout, a spoofer who plants the operator within
  ~2.8 km of the truth is indistinguishable from it.** That is the honest reach
  of inertial corroboration, stated so nobody mistakes the engine for more.
- **Clear-sky covariance is still over-confident at speed** (true error exceeds
  the claimed 1σ in 78 % of highway fixes; ~39 % is what a calibrated 2D 1σ
  should give). The constant-velocity model lags through turns. Unfixed.
- **The bias estimator trusts GPS speed**, so an adversary forging a consistent
  speed field can poison the calibration the inertial witness depends on.

## The metric that mattered

Not drift — **honesty**: true error ÷ the engine's own claimed 1σ. Above 1, the
engine is confidently wrong, which is the single thing it exists never to be.
Every threshold in the repair was chosen against that number, and where the
engine cannot know, it now says so.
