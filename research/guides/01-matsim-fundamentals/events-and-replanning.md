# Events and Replanning

## The event system — MATSim's data stream

During the mobsim, things happen: agents depart, vehicles enter links, buses arrive at stops. Each of these is an **event** — a timestamped data point broadcast to all registered listeners.

### Event flow

```
QSim (traffic simulation)
  │
  ├─ Agent 9285 departs at 07:01 by car
  │   → PersonDepartureEvent(person=9285, time=25260, link=56_2, mode=car)
  │   → Broadcast to ALL registered EventHandlers
  │
  ├─ Vehicle enters link 56_3
  │   → LinkEnterEvent(vehicle=9285, time=25265, link=56_3)
  │
  ├─ Vehicle leaves link 56_3
  │   → LinkLeaveEvent(vehicle=9285, time=25290, link=56_3)
  │
  └─ Agent 9285 arrives at work
      → PersonArrivalEvent(person=9285, time=25655, link=47_2)
```

### Common event types

| Event | Fired when | Data |
|-------|-----------|------|
| `PersonDepartureEvent` | Agent starts a trip | personId, time, linkId, mode |
| `PersonArrivalEvent` | Agent reaches destination | personId, time, linkId |
| `LinkEnterEvent` | Vehicle enters a road segment | vehicleId, time, linkId |
| `LinkLeaveEvent` | Vehicle exits a road segment | vehicleId, time, linkId |
| `VehicleEntersTrafficEvent` | Vehicle starts moving | vehicleId, time, linkId |
| `PersonEntersVehicleEvent` | Agent boards a vehicle | personId, vehicleId, time |
| `TransitDriverStartsEvent` | PT driver begins shift | driverId, vehicleId |

### Writing an event handler

You implement one or more `*EventHandler` interfaces:

```java
// AgentExperienceEventHandlerV2.java — simplified
public class AgentExperienceEventHandlerV2 implements
        PersonDepartureEventHandler,
        PersonArrivalEventHandler {

    @Override
    public void handleEvent(PersonDepartureEvent event) {
        // Record trip start: who, when, where, by what mode
        activeTrips.put(event.getPersonId(), new TripState(event));
    }

    @Override
    public void handleEvent(PersonArrivalEvent event) {
        // Trip ended — compute travel time, store experience
        TripState trip = activeTrips.get(event.getPersonId());
        double travelTime = event.getTime() - trip.departureTime;
        
        // Build a text narrative and insert into Qdrant
        String text = "Person traveled by " + trip.mode + ", took " + travelTime + "s";
        vectorDb.insert(text, metadata);
    }
}
```

This is how the LLM's RAG memory gets populated — the event handler watches what happens during the simulation and writes experience narratives to the vector database.

### Registering a handler

In your Guice module:
```java
// LLMIntegrationModule.java:65
this.addEventHandlerBinding().to(AgentExperienceEventHandlerV2.class).asEagerSingleton();
```

MATSim automatically calls your handler for every matching event during the mobsim.

## The replanning framework — how plans change

### Strategies

A **PlanStrategy** is a recipe for modifying an agent's plan. It has two parts:

1. **Selector**: Which of the agent's stored plans to start from?
   - `ExpBetaPlanSelector`: Probabilistic, favors high-scoring plans
   - `WorstPlanForRemoval`: Select (and remove) the worst plan
   - `RandomPlanSelector`: Pick any plan at random

2. **Module(s)**: What to do with the selected plan?
   - `ReRoute`: Keep activities, recalculate routes
   - `SubtourModeChoice`: Change transport mode for a sub-tour
   - `TimeAllocationMutator`: Shift activity times
   - `LLMReplanningStrategyModule`: Send to LLM (our custom module)

### Strategy weights

Each strategy has a **weight** that determines how often it's chosen:

```java
// In RunSiouxFallsLLMAgents.java
config.replanning().addStrategySettings(
    new StrategySettings()
        .setStrategyName("LLMPlanner")
        .setWeight(1.0)          // relative weight
);
```

If you have strategies with weights [ReRoute=2.0, SubtourModeChoice=1.0, LLMPlanner=1.0], then:
- ReRoute is used 50% of the time (2/4)
- SubtourModeChoice 25% (1/4)
- LLMPlanner 25% (1/4)

### The PlanStrategyModule interface

Our LLM replanning implements `PlanStrategyModule`:

```java
// LLMReplanningStrategyModule.java — the three methods
public class LLMReplanningStrategyModule implements PlanStrategyModule {

    public void prepareReplanning(ReplanningContext ctx) {
        // Called ONCE at the start of replanning
        // Set up loggers, reset state
    }

    public void handlePlan(Plan plan) {
        // Called ONCE PER AGENT assigned to this strategy
        // Just collect the plan; don't process yet
        this.planToReplan.add(plan);
    }

    public void finishReplanning() {
        // Called ONCE after all plans collected
        // NOW process all plans (call LLM for each)
        for (Plan plan : planToReplan) {
            // Serialize → send to LLM → get modified plan → copy back
        }
    }
}
```

The three-phase pattern (prepare → handle → finish) lets MATSim batch work efficiently. We collect all plans first, then process them — this could be parallelized in the future.

### Plan memory

Each agent keeps up to ~5 plans in memory (configurable). After replanning generates a new plan, MATSim:
1. Adds the new plan to the agent's memory
2. If memory is full, removes the worst-scoring plan
3. Next iteration: selects a plan probabilistically (higher scores preferred)

This means a bad LLM plan doesn't permanently harm the agent — it just gets a low score and eventually gets removed. The agent falls back to better plans from previous iterations.
