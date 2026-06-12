Analyze a MATSim simulation output directory for LLM agent replanning performance.

## What to do

1. Run the Python analysis tools on the output directory (default: the most recent run in `output/`):
   ```bash
   conda run -n matsim-ai matsim-analyze analyze $ARGUMENTS
   conda run -n matsim-ai matsim-analyze reasoning $ARGUMENTS
   conda run -n matsim-ai matsim-analyze bottlenecks $ARGUMENTS
   ```

2. If no directory is specified, find the most recent output directory:
   ```bash
   ls -td output/*/ | head -1
   ```

3. Interpret the results and provide actionable recommendations:
   - Which agents are struggling and why?
   - Which reasoning categories dominate?
   - Which proposed tools would have the most impact?
   - Are there failure patterns that suggest prompt changes?

4. If the analysis reveals new bottleneck patterns not covered by existing tool proposals, suggest new tool designs.

## Key metrics to highlight
- Success rate and failure modes
- Per-agent performance (especially the slowest/most-failing agents)
- Eliminable reasoning ratio (higher = more opportunity for tool speedup)
- Tool gap analysis (which tools would help most)
- Confusion signals (indicates the LLM is struggling with the task)

## Python environment
Always use `conda run -n matsim-ai` to run Python commands.
