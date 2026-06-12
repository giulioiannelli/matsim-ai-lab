# Tool-Calling Framework

> **Source files**: `src/main/java/tools/ITool.java`, `tools/Implement/*.java`

## The concept

The LLM doesn't directly modify MATSim objects. Instead, it calls **tools** — structured functions with defined inputs and outputs. This is the same pattern as OpenAI's function calling or Claude's tool use.

Each tool:
1. Has a **name** (e.g., `"router_tool"`)
2. Has a **JSON schema** describing its arguments
3. Receives arguments from the LLM, executes Java code, returns a result
4. Is either **dummy** (ends conversation) or **real** (result goes back to LLM)

## The ITool interface

```java
// tools/ITool.java — what every tool must implement
public interface ITool<T> {
    String getName();                  // "router_tool"
    String getDescription();           // Human-readable description for LLM
    boolean isDummy();                 // true = result NOT sent back to LLM
    Class<T> getOutputClass();         // Java type of the output (Plan.class, etc.)
    JsonObject getJsonSchema();        // OpenAI-format function schema
    
    IToolResponse<T> callTool(        // The actual execution
        String id,                     // Unique call ID
        Map<String, Object> arguments, // Parsed arguments from LLM
        IVectorDB vectorDB,            // Access to Qdrant
        Map<String, Object> context    // MATSim objects (TripRouter, facilities, etc.)
    );
    
    void verifyArguments(              // Pre-execution validation
        Map<String, Object> arguments,
        Map<String, Object> context,
        ErrorMessages em
    );
}
```

## The three tools

### 1. `extract_plan` — Dummy tool (ends conversation)

> **Source**: `tools/Implement/ExtractPlanTool.java`

The LLM calls this when it's done reasoning. It sends the complete modified plan as JSON.

```
LLM → extract_plan(plan: {activities: [...], legs: [...]})
     → Tool parses JSON → PlanDTO → MATSim Plan
     → isDummy() = true → conversation ENDS
     → Plan returned to LLMReplanningStrategyModule
```

**Key point**: Since `isDummy() = true`, the tool's response is NOT sent back to the LLM. The conversation loop in `DefaultChatManager` breaks out.

### 2. `router_tool` — Real tool (result goes back to LLM)

> **Source**: `tools/Implement/RouterTool.java`

The LLM calls this to compute a route between two locations. It uses MATSim's `TripRouter` — the same router the simulation uses.

```
LLM → router_tool(fromFacilityId: "10422_18", toFacilityId: "21554_16", 
                   mode: "car", departureTimeSeconds: 25315.0)
     → Tool gets TripRouter from context
     → TripRouter.calcRoute("car", fromFacility, toFacility, 25315.0, person)
     → Returns routed sub-chain as PlanDTO JSON
     → isDummy() = false → response sent BACK to LLM
     → LLM incorporates route into its plan reconstruction
```

**What it returns for different modes:**
- **car/bike**: Single leg with `NetworkRouteDTO` (list of link IDs)
- **PT**: Full chain: `walk` → `pt interaction` → `pt` → `pt interaction` → `walk`
- **walk**: Single leg with `GenericRouteDTO` (distance + travel time)

### 3. `pull_additional_context` — Real tool (result goes back to LLM)

> **Source**: `tools/Implement/PullAdditionalContextTool.java`

The LLM calls this to retrieve past travel experiences from the Qdrant vector database.

```
LLM → pull_additional_context(text: "morning commute experience")
     → Tool embeds query text via nomic-embed-text
     → Searches Qdrant for similar documents (filtered by personId)
     → Returns top-K matching experiences
     → isDummy() = false → response sent BACK to LLM
     → LLM uses past experiences to inform plan decisions
```

## Dummy vs Real — the key distinction

| Property | Dummy tool | Real tool |
|----------|-----------|-----------|
| `isDummy()` | `true` | `false` |
| Response sent to LLM? | No | Yes |
| Ends conversation? | Yes (loop breaks) | No (loop continues) |
| Example | `extract_plan` | `router_tool`, `pull_additional_context` |
| Purpose | Capture final output for MATSim | Provide information for further reasoning |

The conversation loop in `DefaultChatManager` checks after each tool call:

```java
// DefaultChatManager.java:367
if (!ifNonDummy) {     // All tools in this round were dummy
    stats.success = true;
    break;             // ← End the conversation
}
```

## How to add a new tool

1. Create a class in `tools/Implement/` implementing `ITool<T>`
2. Define arguments in the constructor:
   ```java
   public MyTool() {
       registerArgument(SimpleStringDTO.forArgument("myParam"));
       registerArgument(SimpleDoubleDTO.forArgument("myNumber"));
   }
   ```
3. Implement `callTool()` — extract args, get context objects, compute, return result
4. Implement `verifyArguments()` — validate inputs
5. Implement `getJsonSchema()` — the schema the LLM sees
6. Register in `LLMIntegrationModule.install()`:
   ```java
   toolManager.registerTool(new MyTool());
   ```
7. Update the system prompt to tell the LLM about the tool

## The tool response

```java
// DefaultToolResponse.java
return new DefaultToolResponse<>(
    callId,        // Unique ID from the LLM's tool_call
    toolName,      // "router_tool"
    responseJson,  // JSON string sent back to LLM (if real tool)
    javaOutput,    // Java object for MATSim (e.g., Plan)
    isDummy        // false = for LLM, true = NOT for LLM
);
```

The `responseJson` is what the LLM reads. The `javaOutput` is what MATSim uses. They can be different representations of the same data.

## Context objects — how tools access MATSim

Tools receive a `context` map with MATSim objects:

```java
// Set up in LLMReplanningStrategyModule.notifyStartup()
contextObject.put("tripRoutersProvider", this.tripRouterProvider);
contextObject.put("activityFacilities", scenario.getActivityFacilities());
contextObject.put("person", person);  // Added per-agent
```

Inside `RouterTool.callTool()`:
```java
ActivityFacilities facilities = (ActivityFacilities) contextObject.get("activityFacilities");
Provider<TripRouter> tripRouter = (Provider<TripRouter>) contextObject.get("tripRoutersProvider");
```

This is how tools bridge from the LLM world (JSON strings) to the MATSim world (Java objects).
