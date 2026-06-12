# Config, Scenario, and Scoring

## Config — the settings dictionary

Everything in MATSim starts with a `Config` object. Think of it as a nested dictionary of all simulation parameters.

### Loading config

```java
// From a bundled scenario (JAR file)
URL context = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(context, "config_default.xml"));

// From a local file
Config config = ConfigUtils.loadConfig("/path/to/config.xml");

// Programmatic (no XML)
Config config = ConfigUtils.createConfig();
```

### Config groups

Config is organized into **groups** — each controlling one aspect:

| Group | What it controls | Python analogy |
|-------|-----------------|---------------|
| `config.controller()` | Iteration count, output dir | Training hyperparams |
| `config.qsim()` | Traffic simulation settings | Forward pass settings |
| `config.scoring()` | How plans are scored | Loss function params |
| `config.replanning()` | Which strategies, with what weights | Optimizer settings |
| `config.network()` | Network file path | Dataset path |
| `config.plans()` | Population file path | Labels path |

### Custom config groups

Plugins can add their own config groups. Our LLM plugin adds `LLMConfigGroup`:

```java
// src/main/java/matsimBinding/LLMConfigGroup.java
LLMConfigGroup llmConfig = new LLMConfigGroup();
llmConfig.setLlmHost("localhost");
llmConfig.setLlmPort(11434);
llmConfig.setModelName("qwen3.5");
// ... many more parameters
config.addModule(llmConfig);   // Register with MATSim config
```

MATSim doesn't know or care what `LLMConfigGroup` does — it just stores it. Our plugin code retrieves it later via Guice injection.

## Scenario — the loaded data

A `Scenario` is the in-memory representation of everything MATSim needs to simulate:

```java
Scenario scenario = ScenarioUtils.loadScenario(config);
// Now scenario contains:
scenario.getNetwork();           // Road network (nodes + links)
scenario.getPopulation();        // All agents with their plans
scenario.getActivityFacilities(); // Locations (home, work, shop, etc.)
scenario.getTransitSchedule();   // PT lines, stops, departures
scenario.getTransitVehicles();   // Buses, trams with capacity
```

### Network
A directed graph: **nodes** (intersections) connected by **links** (road segments). Each link has:
- Length, free-flow speed, capacity (vehicles/hour), number of lanes
- Allowed modes (car, bike, PT, etc.)

### Population
A collection of **Person** objects, each with one or more **Plan** objects. A Plan is an ordered sequence:

```
Activity(home, end=7:00) → Leg(car) → Activity(work, end=17:00) → Leg(car) → Activity(home)
```

### Facilities
Physical locations with coordinates and activity types. An Activity references a Facility:
- Facility "10422_18" is at coordinate (x, y) and supports "home" activities
- Facility "21554_16" supports "work" activities

This matters for the LLM: when the model calls `router_tool`, it provides `fromFacilityId` and `toFacilityId` — MATSim looks up the coordinates and computes the route.

## Scoring — the loss function

After each mobsim, MATSim scores every agent's plan. The score determines which plans survive.

### The scoring formula (simplified)

```
score = Σ(activity_utility) + Σ(leg_utility)

activity_utility = β_perf * typical_duration * ln(duration / typical_duration)
                   + late_arrival_penalty
                   + early_departure_penalty

leg_utility = mode_constant + β_travel * travel_time + β_distance * distance + β_monetary * cost
```

Where:
- `β_perf` ≈ +6/hr (reward for performing activities)
- `β_travel` ≈ -6/hr for car, -12/hr for walk (penalty for traveling)
- `mode_constant` varies by mode (car might be -1, PT might be -2)

### What this means in practice

- Spending 8 hours at work: good score (activity utility)
- Commuting 30 minutes by car: small penalty
- Commuting 30 minutes by walk: bigger penalty (walk has worse β)
- Arriving 1 hour late to work: big penalty
- A plan that balances activity time and travel time scores best

### Scoring and the LLM

When the LLM modifies a plan (e.g., switching car→PT), MATSim scores the new plan in the next iteration. If PT takes longer and the agent arrives late, the score drops. `ExpBetaPlanSelector` then makes it less likely this plan is used again. The LLM's decisions are evaluated by MATSim's scoring function — the LLM proposes, MATSim disposes.
