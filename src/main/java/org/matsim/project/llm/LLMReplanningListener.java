package org.matsim.project.llm;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;

import chatcommons.ChatCompletionClientImpl;
import chatcommons.ChatManagerContainer;
import chatcommons.DefaultChatManager;
import chatcommons.IChatManager;
import chatcommons.IChatMessage;
import chatcommons.Role;
import chatrequest.SimpleRequestMessage;
import chatresponse.IResponseMessage;
import gsonprocessor.PlanElementGson;
import gsonprocessor.PlanElementGsonDeserializer;
import gsonprocessor.PlanGson;
import matsimBinding.LLMConfigGroup;
import tools.IToolCall;
import tools.IToolManager;

/**
 * MATSim listener that selects agents at regular intervals and asks the LLM
 * to modify their daily plans via function calling.
 *
 * Writes a markdown conversation log per iteration to the output directory.
 */
public class LLMReplanningListener implements IterationEndsListener {

	private static final Logger log = LogManager.getLogger(LLMReplanningListener.class);

	private final Config config;
	private final Population population;
	private final IToolManager toolManager;
	private final ChatManagerContainer chatManagerContainer;
	private final CreatePlanTool createPlanTool;

	private final int replanInterval;
	private final int agentsPerBatch;

	private final Gson gson;

	private static final String SYSTEM_PROMPT =
			"You are an intelligent transport agent operating within a MATSim traffic simulation of Sioux Falls. "
			+ "You will be given a person's daily plan as JSON with activities and legs (trips). "
			+ "Each leg connects two activities. The plan starts and ends with activities. "
			+ "Available transport modes are: car, pt (public transit), walk, bike, car_passenger. "
			+ "Your task is to analyze the plan and suggest improvements to mode choice. "
			+ "Consider: if someone drives car for a short trip, walking or PT might be better. "
			+ "If someone has many car trips causing congestion, suggest alternatives. "
			+ "You MUST call the dummy_plan function with your modified plan. "
			+ "Keep the same activities (types, locations, end times) but you may change leg modes. "
			+ "Always include the personId in your function call.";

	private record AgentConversation(String personId, List<IChatMessage> history, boolean success) {}

	@Inject
	public LLMReplanningListener(Config config, Scenario scenario, IToolManager toolManager,
			ChatManagerContainer chatManagerContainer, CreatePlanTool createPlanTool) {
		this.config = config;
		this.population = scenario.getPopulation();
		this.toolManager = toolManager;
		this.chatManagerContainer = chatManagerContainer;
		this.createPlanTool = createPlanTool;
		this.replanInterval = 5;
		this.agentsPerBatch = 5;

		this.gson = new GsonBuilder()
				.registerTypeAdapter(PlanElementGson.class, new PlanElementGsonDeserializer())
				.serializeSpecialFloatingPointValues()
				.setPrettyPrinting()
				.create();
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		int iteration = event.getIteration();
		int lastIteration = config.controller().getLastIteration();

		if (iteration == lastIteration) return;
		if (iteration % replanInterval != 0) return;

		log.info("=== LLM Replanning at iteration {} ===", iteration);

		// Select random agents
		List<Person> allPersons = new ArrayList<>(population.getPersons().values());
		Collections.shuffle(allPersons);
		List<Person> selectedAgents = allPersons.subList(0, Math.min(agentsPerBatch, allPersons.size()));

		LLMConfigGroup llmConfig = (LLMConfigGroup) config.getModules().get(LLMConfigGroup.GROUP_NAME);
		ChatCompletionClientImpl chatClient = new ChatCompletionClientImpl(llmConfig);

		int modified = 0;
		List<AgentConversation> conversations = new ArrayList<>();

		for (Person person : selectedAgents) {
			try {
				DefaultChatManager chatManager = new DefaultChatManager(
						Id.create("replan_" + person.getId(), IChatManager.class),
						chatClient, toolManager, null);
				chatManager.setSystemMessage(SYSTEM_PROMPT);

				boolean success = replanAgent(person, chatManager);
				if (success) modified++;

				conversations.add(new AgentConversation(
						person.getId().toString(),
						new ArrayList<>(chatManager.getHistory()),
						success));
			} catch (Exception e) {
				log.warn("LLM replanning failed for agent {}: {}", person.getId(), e.getMessage());
			}
		}

		writeConversationLog(iteration, conversations);

		log.info("=== LLM Replanning complete: {}/{} agents modified ===", modified, selectedAgents.size());
	}

	private boolean replanAgent(Person person, DefaultChatManager chatManager) {
		Plan selectedPlan = person.getSelectedPlan();
		if (selectedPlan == null) return false;

		// Convert plan to JSON
		PlanGson planGson;
		try {
			planGson = PlanGson.createPlanGson(selectedPlan);
		} catch (Exception e) {
			log.debug("Could not serialize plan for {}: {}", person.getId(), e.getMessage());
			return false;
		}
		planGson.personId = person.getId().toString();
		String planJson = gson.toJson(planGson);

		log.info("Agent {}: sending plan to LLM ({} elements)", person.getId(),
				planGson.activitiesAndLegs.size());

		String userMessage = "Here is the daily plan for person " + person.getId()
				+ ". Analyze it and call the dummy_plan function with an improved plan. "
				+ "You may change leg modes but keep the same activities.\n\n" + planJson;

		// Submit and let the tool calling loop run
		chatManager.submit(new SimpleRequestMessage(Role.USER, userMessage));

		// Check if the LLM called dummy_plan via structured tool calling
		PlanGson modifiedPlanGson = createPlanTool.getGeneratedPlan(person.getId().toString());

		// Fallback: some models output the tool call as text content instead of
		// structured tool_calls. Try to parse it from the last assistant message.
		if (modifiedPlanGson == null) {
			modifiedPlanGson = tryParseTextToolCall(chatManager, person.getId().toString());
		}

		if (modifiedPlanGson == null) {
			log.info("Agent {}: LLM did not call dummy_plan, no modification", person.getId());
			return false;
		}

		// Convert to MATSim plan and add to agent
		Plan newPlan = modifiedPlanGson.getPlan();
		newPlan.setPerson(person);
		person.addPlan(newPlan);
		person.setSelectedPlan(newPlan);

		log.info("Agent {}: plan modified by LLM ({} elements -> {} elements)",
				person.getId(), planGson.activitiesAndLegs.size(),
				modifiedPlanGson.activitiesAndLegs.size());

		return true;
	}

	/**
	 * Fallback: some small models output the dummy_plan call as text content
	 * (a JSON string) rather than using the structured tool_calls format.
	 * Try to extract and parse it.
	 */
	private PlanGson tryParseTextToolCall(DefaultChatManager chatManager, String personId) {
		try {
			IChatMessage lastMsg = chatManager.getLastMessage();
			String content = lastMsg != null ? lastMsg.getContent() : null;
			if (content == null || content.isBlank()) return null;

			// The model may output JSON like {"name":"dummy_plan","parameters":{...}}
			// or just the arguments {activitiesAndLegs:[...], personId:...}
			// Try to find a JSON object containing activitiesAndLegs
			int braceStart = content.indexOf('{');
			int braceEnd = content.lastIndexOf('}');
			if (braceStart < 0 || braceEnd <= braceStart) return null;

			String jsonCandidate = content.substring(braceStart, braceEnd + 1);

			// If it's wrapped in {"name":"dummy_plan","parameters":{...}}, extract the parameters
			if (jsonCandidate.contains("\"parameters\"")) {
				com.google.gson.JsonObject wrapper = gson.fromJson(jsonCandidate, com.google.gson.JsonObject.class);
				if (wrapper.has("parameters")) {
					jsonCandidate = gson.toJson(wrapper.get("parameters"));
				}
			}

			PlanGson planGson = gson.fromJson(jsonCandidate, PlanGson.class);
			if (planGson != null && planGson.activitiesAndLegs != null && !planGson.activitiesAndLegs.isEmpty()) {
				if (planGson.personId == null) planGson.personId = personId;
				log.info("Agent {}: parsed plan from text content (fallback)", personId);
				return planGson;
			}
		} catch (Exception e) {
			log.debug("Agent {}: text tool call parsing failed: {}", personId, e.getMessage());
		}
		return null;
	}

	// --- Conversation logging ---

	private void writeConversationLog(int iteration, List<AgentConversation> conversations) {
		if (conversations.isEmpty()) return;

		String outputDir = config.controller().getOutputDirectory();
		Path logFile = Paths.get(outputDir, "llm-conversations-iter" + iteration + ".md");

		try (BufferedWriter w = new BufferedWriter(new FileWriter(logFile.toFile()))) {
			w.write("# LLM Replanning Conversations - Iteration " + iteration);
			w.newLine(); w.newLine();

			for (AgentConversation conv : conversations) {
				writeAgentConversation(w, conv);
			}

			log.info("Conversation log written to {}", logFile);
		} catch (IOException e) {
			log.warn("Failed to write conversation log: {}", e.getMessage());
		}
	}

	private void writeAgentConversation(BufferedWriter w, AgentConversation conv) throws IOException {
		w.write("## Agent: " + conv.personId());
		w.newLine();
		w.write("**Result:** " + (conv.success() ? "Plan modified" : "No modification"));
		w.newLine(); w.newLine();

		for (IChatMessage message : conv.history()) {
			String role = message.getRole().toString().toUpperCase();
			w.write("### " + role);
			w.newLine(); w.newLine();

			// Text content
			String content = message.getContent();
			if (content != null && !content.isBlank()) {
				if (content.contains("{") && content.length() > 200) {
					// Long JSON-containing content: wrap in code block
					w.write("```");
					w.newLine();
					w.write(content);
					w.newLine();
					w.write("```");
				} else {
					w.write(content);
				}
				w.newLine(); w.newLine();
			}

			// Tool calls (only on assistant responses)
			if (message instanceof IResponseMessage responseMsg) {
				List<IToolCall> toolCalls = responseMsg.getToolCalls();
				if (toolCalls != null && !toolCalls.isEmpty()) {
					w.write("**Tool Calls:**");
					w.newLine();
					for (IToolCall tc : toolCalls) {
						w.write("- **" + tc.getName() + "** (" + tc.getId() + ")");
						w.newLine();
						w.write("```json");
						w.newLine();
						w.write(tc.getArguments());
						w.newLine();
						w.write("```");
						w.newLine();
					}
					w.newLine();
				}
			}
		}

		w.write("---");
		w.newLine(); w.newLine();
	}
}
