# Writing an Event Handler

> **Source file**: `matsimBinding/AgentExperienceEventHandlerV2.java`

## The pattern

Event handlers listen to simulation events (departures, arrivals, link traversals) and collect data. In this project, the event handler captures trip experiences and stores them in Qdrant for the LLM's RAG memory.

## Implementation

1. Implement one or more `*EventHandler` interfaces
2. Register in your Guice module with `addEventHandlerBinding()`
3. MATSim calls `handleEvent()` for every matching event during mobsim

```java
public class AgentExperienceEventHandlerV2 implements
        PersonDepartureEventHandler,
        PersonArrivalEventHandler,
        LinkEnterEventHandler,
        LinkLeaveEventHandler {

    @Inject  // Guice injects these
    public AgentExperienceEventHandlerV2(Scenario scenario, IVectorDB vectorDb) {
        this.scenario = scenario;
        this.vectorDb = vectorDb;
    }

    @Override
    public void handleEvent(PersonDepartureEvent event) {
        // Agent started a trip — record departure info
        activeTrips.put(event.getPersonId(), new TripState(event));
    }

    @Override
    public void handleEvent(PersonArrivalEvent event) {
        // Agent finished a trip — compute travel time, store experience
        TripState trip = activeTrips.remove(event.getPersonId());
        String narrative = buildExperienceText(trip);
        vectorDb.insert(narrative, metadata);
    }
}
```

Registration:
```java
// LLMIntegrationModule.java
this.addEventHandlerBinding().to(AgentExperienceEventHandlerV2.class).asEagerSingleton();
```

## Key design points

- Only track AI agents (check `isAI` attribute) to reduce overhead
- Use `ConcurrentHashMap` for thread-safe state tracking
- Build human-readable narratives, not raw data — the LLM reads these
- Store with metadata (personId, mode, timeBand) for filtered retrieval
