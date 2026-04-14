---
name: matsim-build
description: "Compile, test, or package the MATSim project. Explains Java errors in Python-developer-friendly terms. Use when: 'build', 'compile', 'test', 'package', 'fix build errors', 'mvnw', or encountering Java compilation errors."
argument-hint: "[action: compile|test|package]"
allowed-tools: ["Bash(./mvnw *)", "Bash(java *)", "Read", "Grep"]
---

# MATSim Build Helper

You are helping a researcher who is comfortable with Python but weak on Java. When explaining Java errors, draw analogies to Python concepts.

## Actions

Based on the argument (default: `compile`):

| Argument | Command |
|----------|---------|
| `compile` | `./mvnw compile` |
| `test` | `./mvnw test -Dtest=RunMatsimTest` |
| `package` | `./mvnw clean package -DskipTests` |
| `clean` | `./mvnw clean compile` |

## On compilation failure

1. Read the error output carefully
2. Identify the Java file and line number
3. Read the relevant source file
4. Explain the error using Python analogies:
   - Missing import → "Like a Python `ImportError`"
   - Type mismatch → "Like a Python `TypeError`"
   - Null pointer → "Like Python's `AttributeError: 'NoneType'`"
   - Cannot find symbol → "Like Python's `NameError`"
   - Access modifier issues → "Like Python's name mangling with `__`"

## Common MATSim/Java patterns to know

- `Id.createPersonId("x")`, `Id.createLinkId("x")` — typed IDs, not plain strings
- `OptionalTime.isDefined()` — check before `.seconds()`, similar to Python's `Optional`
- Guice `@Inject` — dependency injection, like Python's constructor with default parameters
- `AbstractModule.install()` — Guice module composition
- `controler` (not controller) — MATSim's deliberate spelling

## Known build issues

- If protobuf conflicts: check `pom.xml` for `protobuf-javalite` exclusion in bicycle contrib
- Fat JAR needs `Multi-Release: true` in shade plugin manifest
- Kotlin stdlib must be pinned to 1.9.10
