# Custom Config Groups

> **Source file**: `matsimBinding/LLMConfigGroup.java`

## The pattern

MATSim config is extensible. Plugins define their own config groups by extending `ReflectiveConfigGroup`. Parameters are automatically serialized to/from XML.

```java
public class LLMConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "llm";

    private String llmHost = "localhost";
    private int llmPort = 1234;
    private String modelName = "qwen3.5";
    // ... 40+ parameters

    public LLMConfigGroup() {
        super(GROUP_NAME);
    }

    // Getters and setters
    public void setLlmHost(String host) { this.llmHost = host; }
    public String getLlmHost() { return llmHost; }
}
```

## Registration and retrieval

```java
// In your runner:
LLMConfigGroup llmConfig = new LLMConfigGroup();
llmConfig.setModelName("qwen3.5");
config.addModule(llmConfig);

// Later, in any @Inject class:
LLMConfigGroup llmConfig = (LLMConfigGroup) config.getModules().get("llm");
```

## Python analogy

```python
# Python equivalent
@dataclass
class LLMConfig:
    llm_host: str = "localhost"
    llm_port: int = 1234
    model_name: str = "qwen3.5"

config = MATSimConfig()
config.register_module("llm", LLMConfig())
```

See [config-reference.md](../04-running-a-simulation/config-reference.md) for all LLMConfigGroup parameters.
