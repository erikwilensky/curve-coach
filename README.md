# Curve Coach

An Android app for motorcycle riders that looks at the road ahead, finds the bends,
and speaks an entry speed through your helmet headset: *"Right 40, in 200, then left 35."*

Road shapes come from OpenStreetMap. No route needs to be planned: the app assumes you
stay on the road you are on.

## Install on your phone

1. On your phone, open the **Releases** page of this repository and download `curve-coach.apk` from **latest**.
2. Open the file. Android asks you to allow installs from your browser or Files app; allow it.
3. Open Curve Coach, pick a riding style, tap **Start ride**, and allow location.

Each build is signed with the same key, so new builds install over the old one.

## How the speed is worked out

Entry speed = √(g × radius × grip), where grip is how hard you are willing to corner, in g.

| Style   | Grip   | Lean (about) | 30 m bend | 50 m bend | 100 m bend |
|---------|--------|--------------|-----------|-----------|------------|
| Relaxed | 0.25 g | 14°          | 30 km/h   | 35 km/h   | 55 km/h    |
| Normal  | 0.30 g | 17°          | 30 km/h   | 40 km/h   | 60 km/h    |
| Sporty  | 0.40 g | 22°          | 35 km/h   | 50 km/h   | 70 km/h    |

Speeds are rounded down to the nearest 5.

By default a bend is only called when you are faster than its advised speed. Turn on
"Call every bend" to hear them all.

## Layout

- `core/`: plain Kotlin, no Android. Road model, path tracing, bend detection, speed advice,
  and what to say. Everything here is unit tested.
- `app/`: the Android app. GPS, map downloads and storage, voice.

The ride state is published from `RideService.listener`, so an Android Auto screen
or rider scoring can plug in later without touching the bend logic.

## Tests

```
./gradlew :core:test                                   # offline unit tests
./gradlew :core:test -PliveRoads --tests '*LiveRoads*'  # rides real Thai roads from OpenStreetMap
```

The live test writes every callout to `core/build/live-roads/`. CI uploads it as the `live-roads` artifact.

## Safety

Advice only. OpenStreetMap can be wrong, rough or out of date, and the app cannot see
gravel, wet patches or traffic. Always ride to what you can see.
