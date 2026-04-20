package prompts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.matsim.api.core.v01.population.Person;

/**
 * Renders per-person attributes into the persona-framed system prompt.
 *
 * <p>Reads what MATSim exposes on {@link Person#getAttributes()} and composes a
 * short first-person biography. Missing attributes are silently dropped rather
 * than rendered as "unknown"; the prompt stays readable when the population only
 * provides a subset.
 *
 * <p>Known attributes consumed (names follow MATSim conventions):
 * <ul>
 *   <li>{@code age} — integer years</li>
 *   <li>{@code sex} — {@code m} / {@code f}</li>
 *   <li>{@code car_avail} — {@code always} / {@code sometimes} / {@code never}</li>
 *   <li>{@code employed} — {@code yes} / {@code no}</li>
 *   <li>{@code hasLicense} — boolean-ish (yes/no/true/false)</li>
 *   <li>{@code bikeAvailability} — {@code always} / {@code sometimes} / {@code never}</li>
 * </ul>
 */
public final class PersonaPromptBuilder {

    private PersonaPromptBuilder() {}

    /**
     * Build the full persona system prompt, substituting person attributes into
     * {@link IndividualPrompt#personaSystemPromptTemplate}.
     */
    public static String buildSystemPrompt(Person person) {
        String personaLines = composePersonaLines(person);
        return IndividualPrompt.personaSystemPromptTemplate
                .replace("{{personaLines}}", personaLines);
    }

    /** Build the persona-variant user message, pairing the task prompt with the plan JSON. */
    public static String buildTaskPrompt(String planJson) {
        return IndividualPrompt.personaTaskPrompt
                + planJson
                + "\n\nReorganize it as you see fit — keep what feels right, change what doesn't."
                + " Talk through your choices as you go."
                + " When you're happy with it, call extract_plan with your final plan.";
    }

    /**
     * Compose the bullet-list block of "who you are" lines. Each non-null line
     * ends with a newline so the block drops cleanly into the template.
     */
    static String composePersonaLines(Person person) {
        Map<String, Object> attrs = person.getAttributes().getAsMap();
        List<String> lines = new ArrayList<>();

        renderAgeSex(attrs).ifPresent(lines::add);
        renderEmployment(attrs).ifPresent(lines::add);
        renderCar(attrs).ifPresent(lines::add);
        renderLicense(attrs).ifPresent(lines::add);
        renderBike(attrs).ifPresent(lines::add);

        if (lines.isEmpty()) {
            // No demographic signal at all — keep the section visible but neutral.
            lines.add("- A resident of this city.");
        }

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static Optional<String> renderAgeSex(Map<String, Object> attrs) {
        Integer age = intAttr(attrs, "age");
        String sexWord = sexWord(attrs);
        if (age != null && sexWord != null) {
            return Optional.of("- A " + age + "-year-old " + sexWord + ".");
        }
        if (age != null) {
            return Optional.of("- You are " + age + " years old.");
        }
        if (sexWord != null) {
            return Optional.of("- You are a " + sexWord + ".");
        }
        return Optional.empty();
    }

    private static Optional<String> renderEmployment(Map<String, Object> attrs) {
        String employed = strAttr(attrs, "employed");
        if ("yes".equalsIgnoreCase(employed) || "true".equalsIgnoreCase(employed)) {
            return Optional.of("- You work — you have a job to get to most days.");
        }
        if ("no".equalsIgnoreCase(employed) || "false".equalsIgnoreCase(employed)) {
            return Optional.of("- You are not employed right now.");
        }
        return Optional.empty();
    }

    private static Optional<String> renderCar(Map<String, Object> attrs) {
        String carAvail = strAttr(attrs, "car_avail");
        if (carAvail == null) carAvail = strAttr(attrs, "carAvail");
        if (carAvail == null) return Optional.empty();

        return switch (carAvail.toLowerCase()) {
            case "always" -> Optional.of("- You have a car you can use whenever you want.");
            case "sometimes" -> Optional.of("- You can use a car sometimes, not always.");
            case "never" -> Optional.of("- You don't have a car available.");
            default -> Optional.empty();
        };
    }

    private static Optional<String> renderLicense(Map<String, Object> attrs) {
        String lic = strAttr(attrs, "hasLicense");
        if (lic == null) return Optional.empty();
        boolean yes = "yes".equalsIgnoreCase(lic) || "true".equalsIgnoreCase(lic);
        return Optional.of(yes
                ? "- You have a driver's license."
                : "- You don't have a driver's license.");
    }

    private static Optional<String> renderBike(Map<String, Object> attrs) {
        String bike = strAttr(attrs, "bikeAvailability");
        if (bike == null) return Optional.empty();
        return switch (bike.toLowerCase()) {
            case "always" -> Optional.of("- You have a bike at home.");
            case "sometimes" -> Optional.of("- You can use a bike sometimes.");
            case "never" -> Optional.empty();
            default -> Optional.empty();
        };
    }

    private static String sexWord(Map<String, Object> attrs) {
        String s = strAttr(attrs, "sex");
        if (s == null) return null;
        return switch (s.toLowerCase()) {
            case "f", "female" -> "woman";
            case "m", "male" -> "man";
            default -> null;
        };
    }

    private static Integer intAttr(Map<String, Object> attrs, String key) {
        Object v = attrs.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String strAttr(Map<String, Object> attrs, String key) {
        Object v = attrs.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
