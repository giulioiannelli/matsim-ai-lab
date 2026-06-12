# Writing a Custom Replanning Strategy

> **Source files**: `matsimBinding/LLMReplanningStrategyModule.java`, `matsimBinding/LLMReplanningStrategyProvider.java`

## The pattern

To add a custom replanning strategy to MATSim, you need three things:

1. **PlanStrategyModule** — the actual logic (what to do with a plan)
2. **Provider** — creates a PlanStrategy that combines a selector + your module
3. **Registration** — wire it into the Guice module and config

## 1. The PlanStrategyModule

```java
// LLMReplanningStrategyModule.java
public class LLMReplanningStrategyModule implements PlanStrategyModule, StartupListener {

    public static final String StrategyName = "LLMPlanner";

    @Inject LLMConfigGroup llmConfig;      // Guice injects these
    @Inject Scenario scenario;
    @Inject IToolManager toolManager;
    // ... more injected dependencies

    @Override
    public void prepareReplanning(ReplanningContext ctx) {
        // Called ONCE before any plans are processed
        // Set up per-iteration state (loggers, counters)
    }

    @Override
    public void handlePlan(Plan plan) {
        // Called ONCE PER AGENT assigned to this strategy
        this.planToReplan.add(plan);  // Just collect; don't process yet
    }

    @Override
    public void finishReplanning() {
        // Called ONCE after all plans collected
        // Process all plans (call LLM for each agent)
        this.planToReplan.forEach(plan -> {
            // serialize → LLM → tools → extract_plan → copy back
        });
    }
}
```

The three methods are called in order: `prepare` → `handle` (N times) → `finish`. This batch pattern allows efficient processing.

## 2. The Provider

```java
// LLMReplanningStrategyProvider.java
public class LLMReplanningStrategyProvider implements Provider<PlanStrategy> {

    @Inject
    private LLMReplanningStrategyModule module;

    @Override
    public PlanStrategy get() {
        PlanStrategyImpl.Builder builder = new PlanStrategyImpl.Builder(
            new ExpBetaPlanSelector<>(1.0)  // Select plans probabilistically by score
        );
        builder.addStrategyModule(module);  // Add our LLM module
        return builder.build();
    }
}
```

The provider combines a **selector** (which plan to start from) with a **module** (what to do with it). `ExpBetaPlanSelector` favors higher-scoring plans.

## 3. Registration

In the Guice module:
```java
// LLMIntegrationModule.java
this.addPlanStrategyBinding(LLMReplanningStrategyModule.StrategyName)
    .toProvider(LLMReplanningStrategyProvider.class);
```

In the config:
```java
// RunSiouxFallsLLMAgents.java
config.replanning().addStrategySettings(
    new StrategySettings()
        .setStrategyName("LLMPlanner")
        .setWeight(1.0)
);
```

## Python analogy

```python
# Python equivalent of the strategy pattern
class LLMStrategy:
    def prepare(self, iteration):
        self.plans_to_replan = []

    def handle_plan(self, plan):
        self.plans_to_replan.append(plan)

    def finish(self):
        for plan in self.plans_to_replan:
            modified = call_llm(plan)
            plan.update(modified)

# Registration
strategy_manager.register("LLMPlanner", LLMStrategy(), weight=1.0)
```

The Java version is more verbose but follows the exact same logic.
