package matsimBinding.panel;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure selection rule for panel replanning: which panel agents get an LLM
 * query this iteration, and why. No MATSim state; unit-testable.
 *
 * <p>Priority (worst experience first): agents stuck in the last mobsim run,
 * then agents never reviewed by the LLM, then agents whose executed score
 * dropped, worst drop first, restricted to the worst {@code quantile} of the
 * panel's drops. The first {@code budget} survivors are chosen.
 */
public final class PanelSelection {

    public enum Reason { STUCK, NEVER_REVIEWED, SCORE_DROP }

    /** One panel agent's experience since its last replanning. */
    public record Candidate(Id<Person> id, boolean stuck, boolean reviewed, Double scoreDrop) {}

    public record Choice(Id<Person> id, Reason reason, double scoreDrop) {}

    private PanelSelection() {}

    public static List<Choice> choose(List<Candidate> panel, int budget, double quantile) {
        List<Choice> ranked = new ArrayList<>();
        List<Candidate> drops = new ArrayList<>();
        for (Candidate c : panel) {
            double drop = c.scoreDrop() == null ? 0.0 : c.scoreDrop();
            if (c.stuck()) {
                ranked.add(new Choice(c.id(), Reason.STUCK, drop));
            } else if (!c.reviewed()) {
                ranked.add(new Choice(c.id(), Reason.NEVER_REVIEWED, drop));
            } else if (drop > 0) {
                drops.add(c);
            }
        }
        drops.sort(Comparator.comparingDouble((Candidate c) -> c.scoreDrop()).reversed());
        int eligible = (int) Math.ceil(drops.size() * quantile);
        for (int i = 0; i < Math.min(eligible, drops.size()); i++) {
            Candidate c = drops.get(i);
            ranked.add(new Choice(c.id(), Reason.SCORE_DROP, c.scoreDrop()));
        }
        // Stuck and never-reviewed keep their panel order (seeded); drops are
        // already worst-first. Sorting by reason ordinal is stable.
        ranked.sort(Comparator.comparingInt(ch -> ch.reason().ordinal()));
        return ranked.subList(0, Math.min(budget, ranked.size()));
    }
}
