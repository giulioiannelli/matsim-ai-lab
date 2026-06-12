package org.matsim.project;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;

import chatcommons.ChatCompletionClientImpl;
import chatcommons.DefaultChatManager;
import chatcommons.IChatManager;
import chatcommons.Role;
import chatrequest.SimpleRequestMessage;
import matsimBinding.LLMConfigGroup;
import matsimBinding.LLMIntegrationModule;
import tools.DefaultToolManager;

/**
 * Runs the MATSim equil scenario with LLM plugin integration.
 * Uses Ollama (via LM_STUDIO backend type) on localhost:11434.
 *
 * Usage: ./mvnw compile exec:java -Dexec.mainClass="org.matsim.project.RunMatsimWithLLM"
 */
public class RunMatsimWithLLM {

	public static void main(String[] args) {

		// --- 1. Load MATSim config ---
		Config config;
		if (args == null || args.length == 0 || args[0] == null) {
			config = ConfigUtils.loadConfig("scenarios/equil/config.xml");
		} else {
			config = ConfigUtils.loadConfig(args);
		}
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		// --- 2. Configure LLM ---
		LLMConfigGroup llmConfig = new LLMConfigGroup();
		llmConfig.setBackend(LLMConfigGroup.BackendType.OPENAI_COMPAT);
		llmConfig.setLlmHost("localhost");
		llmConfig.setLlmPort(11434);
		llmConfig.setLlmPath("/v1/chat/completions");
		llmConfig.setModelName("qwen2.5:7b");
		llmConfig.setUseHttps(false);
		llmConfig.setTemperature(0.7);
		llmConfig.setMaxTokens(2048);
		llmConfig.setAuthorization("ollama");
		llmConfig.setSystemMessage("You are an AI assistant integrated with MATSim transport simulation.");
		llmConfig.setEnableContextRetrieval(false);

		config.addModule(llmConfig);

		// --- 3. Load scenario ---
		Scenario scenario = ScenarioUtils.loadScenario(config);

		// --- 4. Set up Controler with LLM module ---
		Controler controler = new Controler(scenario);
		LLMIntegrationModule llmModule = new LLMIntegrationModule();
		controler.addOverridingModule(llmModule);

		// --- 5. Run MATSim simulation ---
		controler.run();

		// --- 6. Post-simulation: Test LLM interaction ---
		System.out.println("\n=== Testing LLM interaction post-simulation ===\n");

		DefaultToolManager toolManager = llmModule.getToolManager();
		ChatCompletionClientImpl chatClient = new ChatCompletionClientImpl(llmConfig);

		DefaultChatManager chatManager = new DefaultChatManager(
			Id.create("test-agent", IChatManager.class),
			chatClient,
			toolManager,
			null
		);
		chatManager.setSystemMessage(llmConfig.getSystemMessage());

		llmModule.getChatManagerContainer().add(chatManager);

		chatManager.submit(
			new SimpleRequestMessage(Role.USER,
				"The MATSim equil scenario simulation just completed with 10 iterations and 100 agents. "
				+ "What kind of analysis would you suggest for the output?")
		);

		System.out.println("\n=== LLM Response ===");
		System.out.println(chatManager.getLastMessage().getContent());
		System.out.println("====================\n");
	}
}
