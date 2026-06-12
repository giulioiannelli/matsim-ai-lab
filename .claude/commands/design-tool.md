Design a new Java tool for the MATSim LLM plugin following the ITool<T> pattern.

## Input
$ARGUMENTS — Natural language description of what the tool should do.

## What to do

1. **Analyze the need**: Read relevant reasoning analysis and bottleneck reports to understand why this tool is needed. If no analysis exists, run `/analyze-run` first.

2. **Check existing tools**: Read the existing tools in `src/main/java/tools/Implement/` to avoid duplication and follow established patterns. Use `RouterTool.java` as the primary reference for "real" tools.

3. **Design the tool** following this checklist:

   ### New Tool Checklist
   - [ ] Create class in `src/main/java/tools/Implement/` implementing `ITool<T>`
   - [ ] Choose output type T (what MATSim receives)
   - [ ] Register arguments in constructor using `SimpleStringDTO`/`SimpleDoubleDTO`/`SimpleBooleanDTO`
   - [ ] Implement `getName()` — unique snake_case identifier
   - [ ] Implement `getDescription()` — clear description for the LLM
   - [ ] Implement `isDummy()` — false for tools whose response goes to LLM, true for terminal tools
   - [ ] Implement `getOutputClass()` — return class of T
   - [ ] Implement `callTool()` — extract args from map, get context objects, compute result, build JSON response, return `DefaultToolResponse`
   - [ ] Implement `verifyArguments()` — validate all inputs, check context has required objects
   - [ ] Register in `LLMIntegrationModule.install()` (line ~54): `toolManager.registerTool(new MyTool())`
   - [ ] Update system prompt in `prompts/IndividualPrompt.java` or prompt text files to tell the LLM about the new tool
   - [ ] Compile: `./mvnw compile`
   - [ ] Plan a test simulation to verify the tool appears in JSONL logs

4. **Context access**: Tools can access MATSim services via the `Map<String, Object> context` parameter:
   - `"person"` → Person object (attributes, selected plan)
   - `"activityFacilities"` / `"facilities"` → ActivityFacilities
   - `"tripRoutersProvider"` → Provider<TripRouter>

5. **DTO patterns available**:
   - `SimpleStringDTO.forArgument("name")` — string arg
   - `SimpleDoubleDTO.forArgument("name")` — numeric arg
   - `SimpleBooleanDTO.forArgument("name")` — boolean arg
   - `SerializableClassWrapperDTO.forClass("name", MyClass.class)` — complex POJO
   - `PlanDTO` — full MATSim plan (used by extract_plan and validate_timing)

6. **Output the complete Java file** and the registration line. Also output the prompt update text.
