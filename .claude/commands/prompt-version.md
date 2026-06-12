Manage prompt versions for LLM agent replanning.

## Input
$ARGUMENTS — Action: `list`, `create <name>`, `diff <v1> <v2>`, or `show <name>`

## What to do

### `list`
List all prompt versions available in `prompts/` directory and the static strings in `src/main/java/prompts/IndividualPrompt.java`.

### `create <name>`
Create a new prompt version file at `prompts/<name>.txt`. Guide the user through:
- Base template (which existing prompt to start from)
- What to change (tool references, workflow steps, persona awareness)
- Token budget target (shorter prompts = faster inference)

### `diff <v1> <v2>`
Show differences between two prompt versions.

### `show <name>`
Display the full text of a prompt version.

## Prompt design principles
1. **Tool-first**: Prescribe a workflow of tool calls rather than listing rules
2. **Minimal**: Every token in the prompt costs inference time — cut ruthlessly
3. **Persona-aware**: Reference persona_memory tool when available
4. **Constraint via tools**: Don't tell the LLM rules that a tool enforces — let the tool do it
5. **Test**: Every prompt change must be tested with a simulation run and compared via `/compare-runs`

## Prompt locations
- Static strings: `src/main/java/prompts/IndividualPrompt.java`
- Version files: `prompts/` directory at project root (to be created)
