# 02 — MATSim Plugin Development Patterns

MATSim is designed to be extended via plugins (called "modules"). You don't modify MATSim's source code — you write Java classes that MATSim calls at the right time.

## The plugin model, for Python developers

In Python, you might extend a framework like this:
```python
class MyPlugin:
    def on_iteration_start(self, ctx):
        ...
    def on_agent_replan(self, agent):
        ...

framework.register(MyPlugin())
```

MATSim does the same thing, but with Java's type system and a dependency injection framework called **Guice**. Guice is like Python's `dependency_injector` or FastAPI's `Depends()` — it automatically constructs objects and wires their dependencies.

The key idea: **you declare what you need, and Guice provides it.**

```java
public class MyModule extends AbstractModule {
    @Override
    public void install() {
        // Tell Guice: "when someone needs IVectorDB, create a VectorDBImplement"
        bind(IVectorDB.class).toProvider(VectorDbProvider.class);
    }
}

public class MyStrategy {
    @Inject   // ← "I need this, Guice please provide it"
    private IVectorDB vectorDB;
    
    // Guice automatically fills vectorDB before anyone uses MyStrategy
}
```

## In this guide

| File | What you'll learn | Key source file |
|------|-------------------|-----------------|
| [module-and-guice.md](module-and-guice.md) | AbstractModule, bindings, providers | `LLMIntegrationModule.java` |
| [replanning-strategy.md](replanning-strategy.md) | Custom PlanStrategyModule | `LLMReplanningStrategyModule.java` |
| [event-handler.md](event-handler.md) | Event handler pattern | `AgentExperienceEventHandlerV2.java` |
| [config-group.md](config-group.md) | Custom configuration | `LLMConfigGroup.java` |

## The four things a plugin typically does

1. **Defines a config group** — parameters the user can set (model name, port, etc.)
2. **Writes a Guice module** — wires everything together (bind interfaces to implementations)
3. **Implements a strategy** — modifies agent plans during replanning
4. **Registers event handlers** — listens to simulation events for data collection
