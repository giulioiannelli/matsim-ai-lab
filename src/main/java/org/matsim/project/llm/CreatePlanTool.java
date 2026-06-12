package org.matsim.project.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import gsonprocessor.PlanElementGson;
import gsonprocessor.PlanElementGsonDeserializer;
import gsonprocessor.PlanGson;
import rag.IVectorDB;
import tools.DefaultToolResponse;
import tools.ITool;
import tools.IToolResponse;
import tools.ToolArgument;
import tools.ToolArgumentDTO;
import tools.VerificationFailedException;

/**
 * Tool that the LLM calls to return a modified MATSim agent plan.
 * The LLM calls this via function calling with a JSON plan (activitiesAndLegs array).
 * The tool parses it into a PlanGson object and stores it for retrieval by the listener.
 */
public class CreatePlanTool implements ITool<PlanGson> {

	private final Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> arguments = new HashMap<>();
	private Map<String, Object> context = new HashMap<>();

	/** Shared storage: personId -> most recent LLM-generated PlanGson */
	private final ConcurrentHashMap<String, PlanGson> generatedPlans = new ConcurrentHashMap<>();

	private final Gson gson;

	public CreatePlanTool() {
		this.gson = new GsonBuilder()
				.registerTypeAdapter(PlanElementGson.class, new PlanElementGsonDeserializer())
				.serializeNulls()
				.serializeSpecialFloatingPointValues()
				.create();
	}

	@Override
	public String getName() {
		return "dummy_plan";
	}

	@Override
	public String getDescription() {
		return "Create a Plan Object for MATSim execution. "
				+ "Call this function with the modified daily plan containing activities and legs.";
	}

	@Override
	public boolean isDummy() {
		return false;
	}

	@Override
	public Class<PlanGson> getOutputClass() {
		return PlanGson.class;
	}

	@Override
	public Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> getRegisteredArguments() {
		return arguments;
	}

	@Override
	public Map<String, Object> getContextObject() {
		return context;
	}

	@Override
	public void setContextObject(Map<String, Object> context) {
		this.context = context;
	}

	/**
	 * Override call() to directly parse the full PlanGson JSON from the LLM's
	 * function call arguments, bypassing the per-argument DTO pattern.
	 */
	@Override
	public IToolResponse<PlanGson> call(String argumentsJson, String toolCallId, IVectorDB vectorDB) {
		try {
			PlanGson planGson = gson.fromJson(argumentsJson, PlanGson.class);

			if (planGson == null || planGson.activitiesAndLegs == null || planGson.activitiesAndLegs.isEmpty()) {
				return new DefaultToolResponse<>(toolCallId, getName(),
						"{\"error\": \"Empty or invalid plan\"}", null, false);
			}

			// Store for retrieval by the listener
			if (planGson.personId != null) {
				generatedPlans.put(planGson.personId, planGson);
			}

			String responseJson = gson.toJson(Map.of(
					"status", "Plan accepted",
					"activities", planGson.activitiesAndLegs.size(),
					"personId", planGson.personId != null ? planGson.personId : "unknown"));

			return new DefaultToolResponse<>(toolCallId, getName(), responseJson, planGson, false);
		} catch (Exception e) {
			return new DefaultToolResponse<>(toolCallId, getName(),
					"{\"error\": \"" + e.getMessage() + "\"}", null, false);
		}
	}

	@Override
	public IToolResponse<PlanGson> callTool(String id, Map<String, Object> arguments, IVectorDB vectorDB) {
		// Not used — call() is overridden directly
		throw new UnsupportedOperationException("Use call() directly");
	}

	@Override
	public void verifyArguments(Map<String, Object> arguments, Map<String, Object> context)
			throws VerificationFailedException {
		// No per-argument verification needed
	}

	@Override
	public JsonObject getJsonSchema() {
		return buildPlanSchema();
	}

	public PlanGson getGeneratedPlan(String personId) {
		return generatedPlans.remove(personId);
	}

	public ConcurrentHashMap<String, PlanGson> getGeneratedPlans() {
		return generatedPlans;
	}

	private static JsonObject buildPlanSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("name", "dummy_plan");
		schema.addProperty("description", "Create a Plan Object for MATSim execution. "
				+ "Call this function with the modified daily plan.");

		JsonObject parameters = new JsonObject();
		parameters.addProperty("type", "object");

		JsonObject properties = new JsonObject();

		// personId
		JsonObject personIdProp = new JsonObject();
		personIdProp.addProperty("type", "string");
		personIdProp.addProperty("description", "Id of the person the plan belongs to.");
		properties.add("personId", personIdProp);

		// activitiesAndLegs array
		JsonObject activitiesAndLegs = new JsonObject();
		activitiesAndLegs.addProperty("type", "array");
		activitiesAndLegs.addProperty("description",
				"Alternates between activities and legs, starting and ending with an activity.");

		// Activity schema
		JsonObject activitySchema = new JsonObject();
		activitySchema.addProperty("type", "object");
		JsonObject actProps = new JsonObject();

		JsonObject idProp = new JsonObject();
		idProp.addProperty("type", "string");
		idProp.addProperty("description",
				"Activity id: activityType + '___' + occurrence index (e.g. home___0, work___0)");
		actProps.add("id", idProp);

		JsonObject actTypeProp = new JsonObject();
		actTypeProp.addProperty("type", "string");
		actTypeProp.addProperty("description", "Type of activity");
		JsonArray actTypeEnum = new JsonArray();
		for (String t : new String[] { "home", "work", "shop", "leisure", "education", "errands", "plugin",
				"plugout" }) {
			actTypeEnum.add(t);
		}
		actTypeProp.add("enum", actTypeEnum);
		actProps.add("activityType", actTypeProp);

		JsonObject endTimeProp = new JsonObject();
		endTimeProp.addProperty("type", "number");
		endTimeProp.addProperty("description", "End time in seconds (0-86400)");
		actProps.add("endTime", endTimeProp);

		JsonObject carLocProp = new JsonObject();
		carLocProp.addProperty("type", "string");
		carLocProp.addProperty("description", "Current car location after this activity");
		actProps.add("carLocation", carLocProp);

		activitySchema.add("properties", actProps);
		JsonArray actRequired = new JsonArray();
		actRequired.add("activityType");
		actRequired.add("endTime");
		actRequired.add("carLocation");
		activitySchema.add("required", actRequired);

		// Leg schema
		JsonObject legSchema = new JsonObject();
		legSchema.addProperty("type", "object");
		JsonObject legProps = new JsonObject();

		JsonObject modeProp = new JsonObject();
		modeProp.addProperty("type", "string");
		modeProp.addProperty("description", "Transport mode");
		JsonArray modeEnum = new JsonArray();
		for (String m : new String[] { "car", "car_passenger", "walk", "pt", "bike" }) {
			modeEnum.add(m);
		}
		modeProp.add("enum", modeEnum);
		legProps.add("mode", modeProp);

		legSchema.add("properties", legProps);
		JsonArray legRequired = new JsonArray();
		legRequired.add("mode");
		legSchema.add("required", legRequired);

		// oneOf for items
		JsonArray oneOf = new JsonArray();
		oneOf.add(activitySchema);
		oneOf.add(legSchema);
		JsonObject items = new JsonObject();
		items.add("oneOf", oneOf);
		activitiesAndLegs.add("items", items);

		properties.add("activitiesAndLegs", activitiesAndLegs);
		parameters.add("properties", properties);

		JsonArray required = new JsonArray();
		required.add("activitiesAndLegs");
		required.add("personId");
		parameters.add("required", required);

		schema.add("parameters", parameters);
		return schema;
	}
}
