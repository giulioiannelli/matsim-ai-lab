Compare two MATSim simulation runs to measure the impact of tool or prompt changes.

## Input
$ARGUMENTS — Two output directory paths, optionally with labels: `dir_a dir_b [--label-a baseline --label-b experiment]`

## What to do

1. Run the Python comparison tool:
   ```bash
   conda run -n matsim-ai matsim-analyze compare $ARGUMENTS
   ```

2. If only one directory is given, compare it against the most recent other run in `output/`.

3. Interpret the deltas:
   - **Duration decrease** = tool/prompt is speeding up reasoning
   - **Success rate increase** = fewer agent failures
   - **Eliminable ratio decrease** = tools are replacing wasted reasoning
   - **Token decrease** = more efficient conversations
   - **Mode share changes** = LLM agents are making different transport decisions

4. Provide a clear verdict: did the experiment improve, regress, or have no effect?

5. If regression occurred, analyze why and suggest fixes.

## Python environment
Always use `conda run -n matsim-ai` to run Python commands.
