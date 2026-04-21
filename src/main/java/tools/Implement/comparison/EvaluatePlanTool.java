package tools.Implement.comparison;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;

import matsimdtobjects.PlanDTO;
import rag.IVectorDB;
import tools.DefaultToolResponse;
import tools.ErrorMessages;
import tools.ITool;
import tools.IToolResponse;
import tools.ToolArgument;
import tools.ToolArgumentDTO;
import tools.VerificationFailedException;

/**
 * Computes observable metrics on a candidate plan — totals, mode mix, and a
 * short list of structural warnings. Does not score the plan or rank it
 * against alternatives. The LLM is the arbiter of whether the trade-offs are
 * acceptable.
 */
public class EvaluatePlanTool implements ITool<String> {

    private final Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> arguments = new HashMap<>();

    public EvaluatePlanTool() {
        registerArgument(
            new ToolArgument<>(
                "plan",
                PlanDTO.class,
                PlanDTO.toDTOFromBaseObject(),
                PlanDTO.getJsonSchema()
            )
        );
    }

    @Override
    public String getName() {
        return "evaluate_plan";
    }

    @Override
    public Class<String> getOutputClass() {
        return String.class;
    }

    @Override
    public String getDescription() {
        return "Computes observable metrics on a candidate plan: total travel time, total "
             + "distance, per-mode duration share, and structural warnings (e.g. a car leg "
             + "whose origin is not where the car was parked). Returns numbers only — "
             + "no score, no judgment. Use this before calling extract_plan to sanity-check "
             + "a day you are considering.";
    }

    @Override
    public boolean isDummy() {
        return false;
    }

    @Override
    public Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> getRegisteredArguments() {
        return arguments;
    }

    @Override
    public IToolResponse<String> callTool(String id, Map<String, Object> args,
                                          IVectorDB vectorDB, Map<String, Object> context) {
        Plan plan = (Plan) args.get("plan");
        PlanMetrics metrics = compute(plan);
        String json = metrics.toJson().toString();
        return new DefaultToolResponse<>(id, getName(), json, json, false);
    }

    @Override
    public void verifyArguments(Map<String, Object> args, Map<String, Object> context,
                                ErrorMessages em) throws VerificationFailedException {
        List<String> errors = new ArrayList<>();
        if (args == null) {
            errors.add("Arguments map is null.");
        } else {
            Object p = args.get("plan");
            if (p == null) errors.add("Missing required argument: plan.");
            else if (!(p instanceof Plan)) errors.add("Argument 'plan' is not a MATSim Plan.");
            else {
                Plan plan = (Plan) p;
                if (plan.getPlanElements() == null || plan.getPlanElements().isEmpty()) {
                    errors.add("Plan contains no plan elements.");
                }
            }
        }
        em.getErrorMessages().addAll(errors);
        if (!errors.isEmpty()) throw new VerificationFailedException(errors);
    }

    static PlanMetrics compute(Plan plan) {
        double totalTime = 0.0;
        double totalDist = 0.0;
        int legCount = 0;
        int activityCount = 0;
        Map<String, Double> modeSeconds = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        String homeFacility = null;
        String carLocation = null;
        String bikeLocation = null;
        String lastRealFacility = null;
        double lastActivityEnd = Double.NEGATIVE_INFINITY;
        String lastActivityType = null;
        String pendingLegMode = null;
        String pendingLegOrigin = null;

        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity act) {
                boolean interaction = isInteractionActivity(act);
                if (!interaction) activityCount++;

                String facId = facilityKey(act);
                if (!interaction) {
                    double endTime = act.getEndTime().orElse(Double.NaN);
                    if (!Double.isNaN(endTime) && lastActivityEnd != Double.NEGATIVE_INFINITY
                            && endTime < lastActivityEnd) {
                        warnings.add("Activity '" + act.getType()
                                + "' ends before preceding activity '" + lastActivityType
                                + "' (" + endTime + " < " + lastActivityEnd + ").");
                    }
                    if (!Double.isNaN(endTime)) lastActivityEnd = endTime;
                    lastActivityType = act.getType();
                    if (homeFacility == null) homeFacility = facId;
                }

                if (pendingLegMode != null && !interaction) {
                    if ("car".equals(pendingLegMode)) carLocation = facId;
                    if ("bike".equals(pendingLegMode)) bikeLocation = facId;
                    pendingLegMode = null;
                    pendingLegOrigin = null;
                }

                if (!interaction) lastRealFacility = facId;
            } else if (pe instanceof Leg leg) {
                legCount++;
                double tt = leg.getTravelTime().orElse(0.0);
                if (Double.isNaN(tt)) tt = 0.0;
                totalTime += tt;
                if (leg.getRoute() != null) {
                    double d = leg.getRoute().getDistance();
                    if (!Double.isNaN(d)) totalDist += d;
                }
                String mode = leg.getRoutingMode() != null ? leg.getRoutingMode() : leg.getMode();
                modeSeconds.merge(mode, tt, Double::sum);

                if (pendingLegMode == null) {
                    pendingLegMode = mode;
                    pendingLegOrigin = lastRealFacility;

                    if ("car".equals(mode)) {
                        String required = carLocation != null ? carLocation : homeFacility;
                        if (required != null && pendingLegOrigin != null
                                && !required.equals(pendingLegOrigin)) {
                            warnings.add("Car leg from " + pendingLegOrigin
                                    + " but the car is at " + required + ".");
                        }
                    }
                    if ("bike".equals(mode)) {
                        String required = bikeLocation != null ? bikeLocation : homeFacility;
                        if (required != null && pendingLegOrigin != null
                                && !required.equals(pendingLegOrigin)) {
                            warnings.add("Bike leg from " + pendingLegOrigin
                                    + " but the bike is at " + required + ".");
                        }
                    }
                }
            }
        }

        return new PlanMetrics(totalTime, totalDist, legCount, activityCount, modeSeconds, warnings);
    }

    private static String facilityKey(Activity act) {
        if (act.getFacilityId() != null) return act.getFacilityId().toString();
        if (act.getLinkId() != null) return "link:" + act.getLinkId().toString();
        return "unknown";
    }

    private static boolean isInteractionActivity(Activity act) {
        return act.getType() != null && act.getType().toLowerCase().contains("interaction");
    }
}
