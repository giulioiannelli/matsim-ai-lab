# Execution Modes

> **Source file**: `matsimBinding/LLMIntegrationModule.java` (ConnectionType enum)

## Three ways to connect the LLM

The LLM can intervene at different points in the MATSim iteration loop. Each mode has different trade-offs.

```java
// LLMIntegrationModule.java
public static enum ConnectionType {
    replanning,          // Between iterations
    controllerlistener,  // Before each mobsim
    withinday            // During mobsim (real-time)
}
```

## Mode 1: `replanning` (recommended starting point)

```
Iteration N:
  Mobsim → Scoring → REPLANNING (LLM modifies plans here) → Iteration N+1
```

**How it works**: LLM is registered as a `PlanStrategy` via `LLMReplanningStrategyModule`. MATSim's `StrategyManager` assigns some agents to the LLM strategy. The LLM modifies their plans. MATSim scores the modified plans next iteration.

**Who has final word**: MATSim. Plans compete on score via `ExpBetaPlanSelector`. Bad LLM plans get low scores and are eventually removed from the agent's plan memory.

**Pros**: Safest mode. MATSim's scoring acts as a quality filter. Bad LLM decisions are self-correcting.

**Cons**: Feedback is delayed (LLM changes plan, but doesn't see the score until next iteration).

**Source**: `LLMReplanningStrategyModule.java`, `LLMReplanningStrategyProvider.java`

## Mode 2: `controllerlistener`

```
Iteration N:
  BEFORE MOBSIM (LLM modifies plans here) → Mobsim → Scoring → Iteration N+1
```

**How it works**: `LLMControllerListener` implements `BeforeMobsimListener`. It modifies plans right before the traffic simulation runs. The modified plans go directly into the mobsim.

**Who has final word**: The LLM. Plans are not filtered by scoring before execution — the agent uses whatever the LLM produces.

**Pros**: LLM has more control. Can see the current network state (from previous iteration's events).

**Cons**: No safety net. A bad LLM plan executes immediately and may produce unrealistic behavior.

**Source**: `LLMControllerListener.java`

## Mode 3: `withinday`

```
Iteration N:
  Mobsim starts → Agent is stuck in traffic → LLM MODIFIES PLAN IN REAL-TIME → Agent reroutes
```

**How it works**: `LLMWithinDayListener` hooks into the QSim and can modify agent plans during the simulation. When an agent encounters an unexpected situation (e.g., severe congestion), the LLM can reroute or change mode.

**Who has final word**: The LLM, in real-time.

**Pros**: Most reactive. Agents can adapt to emergent conditions during the day.

**Cons**: Extremely challenging. Inference latency blocks the simulation. Only feasible with very fast models or API-based inference.

**Source**: `LLMWithinDayListener.java`

## Comparison

| Aspect | replanning | controllerlistener | withinday |
|--------|-----------|-------------------|-----------|
| When LLM runs | Between iterations | Before mobsim | During mobsim |
| MATSim scoring | Filters plans | No filtering | No filtering |
| Safety | High | Medium | Low |
| Latency tolerance | Minutes OK | Minutes OK | Milliseconds needed |
| Complexity | Simple | Medium | High |
| Current status | Working | Untested | Untested |

## How to switch modes

In `RunSiouxFallsLLMAgents.java`:
```java
// Change this line:
new LLMIntegrationModule(LLMIntegrationModule.ConnectionType.replanning)
// To:
new LLMIntegrationModule(LLMIntegrationModule.ConnectionType.controllerlistener)
```

Note: `controllerlistener` and `withinday` modes have not been tested in this project yet. Start with `replanning`.
