# Modules and Guice Dependency Injection

> **Source file**: `src/main/java/matsimBinding/LLMIntegrationModule.java`

## What problem does Guice solve?

Without Guice, you'd have to manually construct every object:

```java
// Manual wiring (painful, fragile)
LLMConfigGroup config = new LLMConfigGroup();
VectorDBImplement vectorDB = new VectorDBImplement(config);
ChatCompletionClientImpl chatClient = new ChatCompletionClientImpl(config);
DefaultToolManager toolManager = new DefaultToolManager();
LLMReplanningStrategyModule strategy = new LLMReplanningStrategyModule(
    config, scenario, vectorDB, chatClient, toolManager, ...);
```

With Guice, you declare the wiring once and let the framework handle construction:

```java
// Guice wiring (declare once, use everywhere)
bind(IVectorDB.class).toProvider(VectorDbProvider.class);
bind(IChatCompletionClient.class).toProvider(ChatClientProvider.class);
// Now any class with @Inject IVectorDB automatically gets a VectorDBImplement
```

## The LLMIntegrationModule — walked through

```java
// LLMIntegrationModule.java
public class LLMIntegrationModule extends AbstractModule {

    ConnectionType type;  // replanning, withinday, or controllerlistener

    // Pre-created objects (exist before Guice starts)
    private final DefaultToolManager toolManager = new DefaultToolManager();
    private final ChatManagerContainer container = new ChatManagerContainer();

    @Override
    public void install() {
```

### Part 1: Bind pre-created instances

```java
        // "When anyone asks for IToolManager, give them THIS exact object"
        bind(IToolManager.class).toInstance(toolManager);
        bind(ChatManagerContainer.class).toInstance(container);
```

`toInstance()` means: don't create a new one, use this specific object. We pre-create these so tools can be registered before the simulation starts.

### Part 2: Register tools

```java
        toolManager.registerTool(new ExtractPlanTool());   // dummy — ends conversation
        toolManager.registerTool(new RouterTool());         // real — calls TripRouter
        toolManager.registerTool(new PullAdditionalContextTool());  // real — queries Qdrant
```

Tools are registered in the tool manager. The LLM sees their JSON schemas and can call them by name.

### Part 3: Bind lazy-created singletons

```java
        // "When anyone needs IVectorDB, call VectorDbProvider.get() — but only once"
        bind(IVectorDB.class).toProvider(VectorDbProvider.class).in(Singleton.class);
        bind(IChatCompletionClient.class).toProvider(ChatClientProvider.class).in(Singleton.class);
```

**Providers** are factories. `VectorDbProvider.get()` reads the config and creates a `VectorDBImplement`. The `.in(Singleton.class)` means: create once, reuse forever.

Why use a provider instead of `toInstance()`? Because these objects need `Config` at construction time, and Config isn't available until Guice starts. Providers defer construction.

### Part 4: Register event handlers

```java
        this.addEventHandlerBinding().to(AgentExperienceEventHandlerV2.class).asEagerSingleton();
```

`.asEagerSingleton()` means: create immediately at startup, don't wait until someone asks for it. This is important because event handlers need to be registered before the first mobsim runs.

### Part 5: Conditional wiring by ConnectionType

```java
        if (this.type.equals(ConnectionType.replanning)) {
            // Register LLM as a replanning strategy
            this.addPlanStrategyBinding(LLMReplanningStrategyModule.StrategyName)
                .toProvider(LLMReplanningStrategyProvider.class);
            bind(LLMReplanningStrategyModule.class).asEagerSingleton();
            this.addControlerListenerBinding().to(LLMReplanningStrategyModule.class);
        }
        
        if (this.type.equals(ConnectionType.withinday)) {
            // Register LLM as a within-day listener (real-time)
            this.addControlerListenerBinding().to(LLMWithinDayListener.class);
            // ... QSim module for real-time replanning
        }
```

Different execution modes wire different components. The module adapts based on the `ConnectionType`.

## Provider pattern — creating objects that need config

```java
// LLMIntegrationModule.java (bottom of file)
class VectorDbProvider implements Provider<IVectorDB> {
    @Inject
    private Config config;  // Guice injects MATSim's Config automatically

    @Override
    public IVectorDB get() {
        LLMConfigGroup llmConfig = (LLMConfigGroup) config.getModules().get("llm");
        return new VectorDBImplement(llmConfig);
    }
}
```

The `@Inject` on `config` is the magic: Guice sees that `VectorDbProvider` needs a `Config`, and MATSim already has one bound. So Guice injects it automatically.

## Python analogy

If you've used FastAPI or Flask:

```python
# FastAPI equivalent of Guice binding
def get_vector_db(config: Config = Depends(get_config)):
    return VectorDBImplement(config)

@app.get("/replan")
def replan(db: IVectorDB = Depends(get_vector_db)):
    # db is automatically created with config injected
    ...
```

Guice does the same thing, but at application startup rather than per-request.

## Key Guice vocabulary

| Guice | Python equivalent | Meaning |
|-------|-------------------|---------|
| `bind(X).to(Y)` | `container.register(X, Y)` | "When X is needed, create Y" |
| `bind(X).toInstance(obj)` | `container.register(X, obj)` | "When X is needed, use this exact object" |
| `bind(X).toProvider(P)` | `container.register(X, factory=P)` | "When X is needed, call P.get()" |
| `.in(Singleton.class)` | `@singleton` | "Create only once, reuse" |
| `.asEagerSingleton()` | Created at import time | "Create NOW, not lazily" |
| `@Inject` | `@inject` / `Depends()` | "I need this, framework please provide" |
| `addOverridingModule()` | Plugin registration | "Add these bindings to the app" |
