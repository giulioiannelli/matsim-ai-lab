# The Iteration Loop

This is the heart of MATSim. Everything else exists to serve this loop.

## The cycle

```
Iteration 0:
  1. MOBSIM    — Simulate one full day of traffic (all agents move simultaneously)
  2. SCORING   — Grade each agent's day (travel time, activity duration, etc.)
  3. REPLANNING — Some agents modify their plans for tomorrow

Iteration 1:
  1. MOBSIM    — Re-simulate with updated plans
  2. SCORING   — Re-grade
  3. REPLANNING — Some agents modify again

...repeat until lastIteration...

Iteration N:
  1. MOBSIM    — Final simulation
  2. SCORING   — Final scores
  → Write output files
```

## What happens in each phase

### 1. Mobsim (QSim)

The Queue Simulation. Every agent executes their plan simultaneously on the road network:
- Agents depart at their planned times
- Cars queue on road links (realistic congestion)
- PT vehicles follow transit schedules
- When links are full, vehicles wait (traffic jam)

As agents move, **events** fire: `PersonDepartureEvent`, `LinkEnterEvent`, `PersonArrivalEvent`, etc. These events are the data stream that feeds analysis and (in our case) the LLM's memory.

Duration: typically a few seconds per iteration for small scenarios, minutes for large ones.

### 2. Scoring

After everyone has finished their day, MATSim calculates a **score** for each agent's plan. The score is a number (can be negative) that measures "how good was today?"

The scoring function considers:
- **Activity utility**: Were you at work long enough? Did you arrive on time?
- **Travel disutility**: How long did you spend traveling? (negative contribution)
- **Mode-specific constants**: Car might have a different base utility than PT
- **Monetary costs**: Tolls, tickets

Higher score = better day. The score drives which plans survive to the next iteration.

### 3. Replanning

Some agents (not all) get to modify their plan. MATSim selects agents probabilistically and assigns them to **strategies**:

Built-in strategies:
- `ReRoute` — Keep the same activities and mode, but find a faster route
- `SubtourModeChoice` — Try a different transport mode (e.g., switch car→PT)
- `TimeAllocationMutator` — Shift departure times slightly
- `ChangeExpBeta` — Keep the current plan (exploitation, not exploration)

**Our LLM strategy** (`LLMPlanner`): Send the plan to an LLM, which reasons about it and returns a modified version using tool calls.

Each strategy has a **weight** that determines how often it's used. An agent keeps up to ~5 plans in memory and probabilistically selects among them (higher-scoring plans are preferred).

## Convergence

Over many iterations:
1. Bad plans get low scores and are eventually removed
2. Good plans get high scores and survive
3. Agents gradually learn better routes and departure times
4. The system reaches a **user equilibrium** — no agent can unilaterally improve

In our project, we care about what happens when some agents use LLM reasoning instead of heuristic strategies. Do they converge faster? Do they find different solutions?

## In code

```java
// RunSiouxFallsLLMAgents.java — the setup
Config config = ConfigUtils.loadConfig(url);                    // Load settings
config.controller().setLastIteration(10);                       // 11 iterations (0-10)
Scenario scenario = ScenarioUtils.loadScenario(config);         // Load network + population
Controler controler = new Controler(scenario);                  // Create the orchestrator
controler.addOverridingModule(new LLMIntegrationModule(...));   // Add LLM plugin
controler.run();                                                // Start the loop
```

The `controler.run()` call is where the iteration loop lives. You don't write the loop yourself — MATSim's `Controler` handles it. You plug in your modules (strategies, event handlers) and MATSim calls them at the right time.

## Timeline of one iteration

```
t=0     IterationStartsEvent fires
        → All listeners notified
        
t=0     Replanning phase
        → StrategyManager selects agents
        → Each strategy's handlePlan() called
        → finishReplanning() called (this is where LLM runs)
        
t=0     BeforeMobsimEvent fires
        → Plans written to file if configured
        
t=0     QSim starts
        → Agents depart, move, arrive
        → Events fire continuously
        → AgentExperienceEventHandler captures trips
        
t=30h   QSim ends (simulation day over)
        
t=30h   AfterMobsimEvent fires
        
t=30h   Scoring phase
        → Each plan gets a score
        
t=30h   IterationEndsEvent fires
        → Stats written, analysis runs
```

Note: MATSim fires replanning BEFORE the mobsim in each iteration (except iteration 0 which just runs the initial plans). So iteration 1's replanning uses scores from iteration 0.
