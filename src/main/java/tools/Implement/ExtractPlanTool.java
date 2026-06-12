package tools.Implement;

import java.util.HashMap;
import java.util.Map;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import matsimdtobjects.PlanDTO;
import rag.IVectorDB;
import tools.DefaultToolResponse;
import tools.ErrorMessages;
import tools.ITool;
import tools.IToolResponse;
import tools.ToolArgument;
import tools.ToolArgumentDTO;
import tools.VerificationFailedException;

public class ExtractPlanTool implements ITool<Plan> {
	
	public static final String Name = "extract_plan";

    private final Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> arguments = new HashMap<>();
    private Map<String, Object> context = new HashMap<>();

    public ExtractPlanTool() {
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
        return Name;
    }

    @Override
    public Class<Plan> getOutputClass() {
        return Plan.class;
    }

    @Override
    public String getDescription() {
        return "Final dummy tool that extracts a complete MATSim plan from structured JSON. "
             + "Use this only when the full plan has been fully decided.";
    }

    @Override
    public boolean isDummy() {
        return true;
    }

    @Override
    public Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> getRegisteredArguments() {
        return arguments;
    }

    @Override
    public IToolResponse<Plan> callTool(String id, Map<String, Object> arguments, IVectorDB vectorDB, Map<String,Object> contextObject) {
        Plan plan = (Plan) arguments.get("plan");
        return ok(id, plan, "llm_plan");
    }

    /**
     * extract_plan is the terminal tool and must always yield a plan that
     * MATSim can apply. We override {@link ITool#call} to intercept any
     * parse/verification failure and fall back to the person's current
     * selected plan so the agent never ends a replanning round without a
     * plan. The resolution path is recorded in the response JSON under
     * {@code plan_source} so downstream analysis can distinguish
     * LLM-produced plans from fallbacks.
     */
    @Override
    public IToolResponse<Plan> call(String argumentsJson, String toolCallId, IVectorDB vectorDB, Map<String, Object> context) {
        Gson gson = new Gson();
        ErrorMessages em = new ErrorMessages();
        try {
            JsonObject parsed = JsonParser.parseString(argumentsJson).getAsJsonObject();
            Map<String, Object> baseObjects = new HashMap<>();
            for (Map.Entry<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> entry : getRegisteredArguments().entrySet()) {
                String key = entry.getKey();
                if (parsed.has(key)) {
                    Object base = entry.getValue().fromJson(parsed.get(key).toString(), gson, em);
                    baseObjects.put(key, base);
                }
            }
            verifyArguments(baseObjects, context, em);
            return callTool(toolCallId, baseObjects, vectorDB, context);
        } catch (Exception ex) {
            Plan fallback = originalPlanFromContext(context);
            if (fallback != null) {
                return ok(toolCallId, fallback, "fallback_original:" + ex.getClass().getSimpleName());
            }
            return handleErrorMessage(toolCallId, ex, em);
        }
    }

    private IToolResponse<Plan> ok(String id, Plan plan, String source) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "OK");
        response.addProperty("plan_source", source);
        return new DefaultToolResponse<>(id, getName(), response.toString(), plan, true);
    }

    private static Plan originalPlanFromContext(Map<String, Object> context) {
        if (context == null) return null;
        Object p = context.get("person");
        return (p instanceof Person) ? ((Person) p).getSelectedPlan() : null;
    }

    @Override
    public void verifyArguments(Map<String, Object> arguments, Map<String, Object> context, ErrorMessages em)
            throws VerificationFailedException {

        //List<String> errors = new ArrayList<>();
    	int numError = 0;
        if (arguments == null) {
            em.addErrorMessages("Arguments map is null.");
            numError++;
        } else {
            Object planObj = arguments.get("plan");

            if (planObj == null) {
                em.addErrorMessages("Missing required argument: plan.");
                numError++;
            } else if (!(planObj instanceof Plan)) {
                em.addErrorMessages("Argument 'plan' is not a MATSim Plan.");
                numError++;
            } else {
                Plan plan = (Plan) planObj;

                if (plan.getPlanElements() == null || plan.getPlanElements().isEmpty()) {
                    em.addErrorMessages("The extracted MATSim plan contains no plan elements.");
                    numError++;
                }
            }
        }
       
        if (numError!=0) {
            throw new VerificationFailedException(em.getErrorMessages());//should this be thrown or all errors should be collected first? 
        }
    }


}