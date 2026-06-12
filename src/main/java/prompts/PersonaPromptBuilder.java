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
                + " Once you have the facts you need, decide and commit — don't re-check routes"
                + " or modes you have already looked up. Keeping the plan exactly as it is can be"
                + " the right call, but you must still call extract_plan with it to lock it in."
                + " As soon as you are satisfied, call extract_plan with your final plan.";
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
        lines.addAll(renderMobility(attrs));
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

    /**
     * Describe car access and driving licence together so the two never
     * contradict each other. A car the person owns but cannot legally drive
     * (no licence) must not read as "a car you can use whenever you want": that
     * mismatch with {@code available_modes} — which reports "Person does not
     * have a driving license" — makes the small model waste rounds trying to
     * reconcile the prompt with the tool. Mirrors the car gating in
     * {@link tools.Implement.AvailableModesTool}: a car is drivable only with
     * both a licence and an available car. The licence is only asserted when
     * the attribute is present, so populations that don't model licences are
     * unaffected.
     */
    private static List<String> renderMobility(Map<String, Object> attrs) {
        String carAvail = strAttr(attrs, "car_avail");
        if (carAvail == null) carAvail = strAttr(attrs, "carAvail");
        String car = carAvail == null ? null : carAvail.toLowerCase();
        boolean carOwned = "always".equals(car) || "sometimes".equals(car);

        String lic = strAttr(attrs, "hasLicense");
        Boolean hasLicense = lic == null ? null
                : ("yes".equalsIgnoreCase(lic) || "true".equalsIgnoreCase(lic) || "1".equals(lic));

        List<String> lines = new ArrayList<>();
        if (carOwned) {
            if (Boolean.FALSE.equals(hasLicense)) {
                lines.add("- There is a car in your household, but you don't have a driver's"
                        + " license, so you can't drive it yourself — you'd ride as a passenger"
                        + " or use other modes.");
            } else if (Boolean.TRUE.equals(hasLicense)) {
                lines.add("always".equals(car)
                        ? "- You have a car and can drive it whenever you want."
                        : "- You have a driver's license and a car you can use sometimes.");
            } else {
                lines.add("always".equals(car)
                        ? "- You have a car you can use whenever you want."
                        : "- You can use a car sometimes, not always.");
            }
        } else if ("never".equals(car)) {
            if (Boolean.TRUE.equals(hasLicense)) {
                lines.add("- You have a driver's license, but no car available.");
            } else if (Boolean.FALSE.equals(hasLicense)) {
                lines.add("- You don't have a car, and you don't have a driver's license.");
            } else {
                lines.add("- You don't have a car available.");
            }
        } else if (Boolean.TRUE.equals(hasLicense)) {
            lines.add("- You have a driver's license.");
        } else if (Boolean.FALSE.equals(hasLicense)) {
            lines.add("- You don't have a driver's license.");
        }
        return lines;
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
