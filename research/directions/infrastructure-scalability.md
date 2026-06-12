# Infrastructure & Scalability

## Current bottleneck

With qwen3.5 on RTX 3080 Laptop (16GB VRAM):
- ~24s per simple agent (car commute, 2 legs)
- ~3-8 min per complex agent (PT multimodal, multi-turn tool calling)
- 4 agents per iteration → ~2-5 min replanning per iteration
- Sequential processing (one agent at a time)

At 84,110 agents in Sioux Falls, even 1% AI agents (841) would take ~5-6 hours per iteration.

## Scaling strategies

### Short term: optimize what we have
- **Reduce thinking overhead**: simplify prompts, pre-process PT chains, provide few-shot examples
- **Limit tool iterations**: cap maxToolIterations to prevent runaway loops
- **Parallel agent processing**: process multiple agents concurrently (needs thread-safe LLM client)
- **Batch similar plans**: agents with similar plan structures could share a prompt template

### Medium term: faster inference
- **API-based models**: Claude, GPT-4o-mini, Fireworks — much faster, better at tool calling
- **Smaller fine-tuned models**: fine-tune qwen2.5:3b on successful plan modifications
- **Speculative decoding**: draft model + verification
- **Ollama batching**: if Ollama supports batch inference

### Long term: architectural changes
- **Memory-augmented agents**: Qdrant stores successful modifications; similar plans reuse past decisions without LLM call
- **Hierarchical replanning**: LLM makes high-level decisions (mode choice), MATSim handles routing
- **Embedding-based plan clustering**: group agents with similar plans, LLM decides for representative agent, apply to cluster
- **Distillation**: train a fast classifier on LLM decisions, use LLM only for edge cases

## Agent memory system (Qdrant)

Current: AgentExperienceEventHandlerV2 captures travel events and stores in Qdrant.
- Per-person metadata filtering
- Static context from file + dynamic experiences from simulation
- PullAdditionalContextTool lets LLM query past experiences

Future:
- Store successful plan modifications as templates
- Cross-agent learning: "agents in this area prefer PT during rush hour"
- Temporal decay: recent experiences weighted higher

## Infrastructure requirements at scale

| Scale | Agents | Est. time/iter | Infrastructure |
|-------|--------|----------------|---------------|
| Current | 4 | ~3 min | Local GPU + Ollama |
| Small | 50 | ~30 min | Local GPU, parallel |
| Medium | 500 | ~2 hr | API model (Claude/GPT), parallel |
| Large | 5000 | needs caching | API + memory-based shortcuts |
| Population | 84k | needs distillation | Hybrid: fast model + LLM for edge cases |
