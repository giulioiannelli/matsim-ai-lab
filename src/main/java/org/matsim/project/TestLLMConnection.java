package org.matsim.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import chatcommons.ChatCompletionClientImpl;
import chatcommons.IChatMessage;
import chatcommons.Role;
import chatrequest.SimpleRequestMessage;
import chatresponse.IChatCompletionResponse;
import matsimBinding.LLMConfigGroup;

/**
 * Standalone test to verify LLM backend connectivity.
 * Run this BEFORE attempting the full MATSim+LLM integration.
 *
 * Usage: ./mvnw compile exec:java -Dexec.mainClass="org.matsim.project.TestLLMConnection"
 */
public class TestLLMConnection {

	public static void main(String[] args) {
		System.out.println("Testing LLM connection...\n");

		LLMConfigGroup llmConfig = new LLMConfigGroup();
		llmConfig.setBackend(LLMConfigGroup.BackendType.OPENAI_COMPAT);
		llmConfig.setLlmHost("localhost");
		llmConfig.setLlmPort(11434);
		llmConfig.setLlmPath("/v1/chat/completions");
		llmConfig.setModelName("qwen2.5:7b");
		llmConfig.setUseHttps(false);
		llmConfig.setTemperature(0.7);
		llmConfig.setMaxTokens(256);
		llmConfig.setAuthorization("ollama");

		ChatCompletionClientImpl client = new ChatCompletionClientImpl(llmConfig);

		List<IChatMessage> history = new ArrayList<>();
		SimpleRequestMessage systemMsg = new SimpleRequestMessage(
			Role.SYSTEM,
			"You are a helpful assistant. Respond briefly."
		);
		history.add(systemMsg);

		SimpleRequestMessage userMsg = new SimpleRequestMessage(
			Role.USER,
			"Hello! Please confirm you are connected. Reply with exactly: CONNECTION_OK"
		);

		try {
			IChatCompletionResponse response = client.query(
				history, userMsg, null, new HashMap<>()
			);
			System.out.println("LLM Response: " + response.getMessage().getContent());
			System.out.println("\nConnection test PASSED.");
		} catch (Exception e) {
			System.err.println("Connection test FAILED: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
