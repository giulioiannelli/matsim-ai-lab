package org.matsim.project;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.simwrapper.SimWrapperModule;

import chatcommons.ChatCompletionClientImpl;
import chatcommons.DefaultChatManager;
import chatcommons.IChatManager;
import chatcommons.Role;
import chatrequest.SimpleRequestMessage;
import matsimBinding.LLMConfigGroup;
import matsimBinding.LLMIntegrationModule;
import tools.DefaultToolManager;

import java.net.URL;

/**
 * Runs the Sioux Falls 2014 scenario with LLM plugin integration.
 *
 * Usage: ./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunSiouxFallsWithLLM"
 *
 * Optional: pass number of iterations as first argument (default: 100)
 */
public class RunSiouxFallsWithLLM {

	public static void main(String[] args) {

		URL context = org.matsim.examples.ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
		URL url = IOUtils.extendUrl(context, "config_default.xml");

		Config config = ConfigUtils.loadConfig(url);
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		int iterations = 100;
		if (args != null && args.length > 0) {
			try {
				iterations = Integer.parseInt(args[0]);
			} catch (NumberFormatException ignored) {}
		}
		config.controller().setLastIteration(iterations);
		config.controller().setOutputDirectory("./output/siouxfalls-llm");
		config.controller().setWriteEventsInterval(iterations);
		config.controller().setWritePlansInterval(iterations);

		// --- Configure LLM ---
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
		llmConfig.setSystemMessage("You are an AI assistant integrated with MATSim transport simulation. "
			+ "You are analyzing the Sioux Falls multimodal scenario with car, public transit, and walking.");
		llmConfig.setEnableContextRetrieval(false);
		config.addModule(llmConfig);

		// --- Load and run ---
		Scenario scenario = ScenarioUtils.loadScenario(config);

		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new SimWrapperModule());

		LLMIntegrationModule llmModule = new LLMIntegrationModule();
		controler.addOverridingModule(llmModule);

		controler.run();

		// --- Post-simulation LLM query ---
		System.out.println("\n=== LLM Post-Simulation Analysis ===\n");

		DefaultToolManager toolManager = llmModule.getToolManager();
		ChatCompletionClientImpl chatClient = new ChatCompletionClientImpl(llmConfig);

		DefaultChatManager chatManager = new DefaultChatManager(
			Id.create("analyst", IChatManager.class),
			chatClient,
			toolManager,
			null
		);
		chatManager.setSystemMessage(llmConfig.getSystemMessage());
		llmModule.getChatManagerContainer().add(chatManager);

		chatManager.submit(
			new SimpleRequestMessage(Role.USER,
				"The Sioux Falls multimodal simulation completed with " + iterations + " iterations. "
				+ "Mode share evolved from 78% car / 19% PT / 3% walk to roughly 48% car / 26% PT / 26% walk. "
				+ "Average scores improved from -34.7 to +19.6. "
				+ "What does this convergence pattern tell us about the transport system?")
		);

		System.out.println(chatManager.getLastMessage().getContent());
		System.out.println("\n====================================\n");
	}
}
