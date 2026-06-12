# 01 — MATSim Fundamentals

MATSim (**M**ulti-**A**gent **T**ransport **Sim**ulation) is a framework for simulating how people move through a city. Think of it as: thousands of virtual people, each with a daily plan ("leave home at 7am, drive to work, eat lunch, drive home"), all trying to use the same road network simultaneously.

The simulation runs iteratively: each "day" is simulated, agents observe how it went (was I stuck in traffic?), and some adjust their plans for tomorrow. Over many iterations, the system converges toward a realistic traffic pattern.

## Key mental model

If you come from Python/ML, think of MATSim as:
- **Population** = your dataset (agents with plans)
- **Mobsim** = the forward pass (simulate one day)
- **Scoring** = the loss function (how good was each agent's day?)
- **Replanning** = the optimizer (agents adjust plans to improve score)
- **Iteration loop** = training epochs (repeat until convergence)

## In this guide

| File | What you'll learn |
|------|-------------------|
| [iteration-loop.md](iteration-loop.md) | The core simulation cycle: mobsim → scoring → replanning |
| [config-scoring-scenario.md](config-scoring-scenario.md) | How simulation is configured, how plans are scored |
| [events-and-replanning.md](events-and-replanning.md) | The event system and strategy framework |

## Key Java classes (don't need to memorize — just know they exist)

| Class | Role | Python analogy |
|-------|------|---------------|
| `Config` | All simulation settings | A big config dict |
| `Scenario` | Network + population + facilities | The loaded dataset |
| `Controler` | Runs the iteration loop | The training loop / `model.fit()` |
| `Plan` | One agent's daily schedule | A row in your dataset |
| `Activity` | "Be at work from 9-17" | A feature in the plan |
| `Leg` | "Drive from home to work" | The trip between activities |
