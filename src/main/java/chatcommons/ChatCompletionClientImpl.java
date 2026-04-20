package chatcommons;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.matsim.core.controler.MatsimServices;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Inject;

import chatrequest.IChatCompletionRequest;
import chatrequest.IRequestMessage;
import chatrequest.OllamaNativeChatRequest;
import chatrequest.OpenAiCompatChatRequest;
import chatrequest.grammar.GrammarMode;
import chatresponse.IChatCompletionResponse;
import chatresponse.OllamaNativeChatResponse;
import chatresponse.OpenAiCompatChatResponse;
import matsimBinding.LLMConfigGroup;
import matsimBinding.LLMConfigGroup.AuthScheme;
import matsimBinding.LLMConfigGroup.BackendType;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatCompletionClientImpl implements IChatCompletionClient {

	private final LLMConfigGroup config;
	  // Raw request/response JSONL log
    private final String rawLogFilePath;
    
    private MatsimServices services;


	private final OkHttpClient httpClient = new OkHttpClient.Builder()
			.connectTimeout(30, TimeUnit.SECONDS)
			.readTimeout(10, TimeUnit.MINUTES)
			.writeTimeout(30, TimeUnit.SECONDS)
			.callTimeout(0, TimeUnit.SECONDS) // or omit this line
			.build();
	private final Gson gson = new Gson();

	@Inject
	public ChatCompletionClientImpl(LLMConfigGroup configGroup , MatsimServices services) {
		this.config = configGroup;
		this.rawLogFilePath = (config.getLogFilePath() != null && !config.getLogFilePath().isBlank())
                ? config.getLogFilePath()
                : "llm_raw.jsonl";

		this.services = services;

	}

	/** Build a request serializer matched to the backend's wire format. */
	private static IChatCompletionRequest requestBuilderFor(BackendType backend) {
		return switch (backend) {
			case OPENAI_COMPAT -> new OpenAiCompatChatRequest();
			case OLLAMA_NATIVE -> new OllamaNativeChatRequest();
		};
	}

	/** Parse the raw response JSON into the shape expected by the backend. */
	private IChatCompletionResponse parseResponseFor(BackendType backend, String body) {
		return switch (backend) {
			case OPENAI_COMPAT -> gson.fromJson(body, OpenAiCompatChatResponse.class);
			case OLLAMA_NATIVE -> gson.fromJson(body, OllamaNativeChatResponse.class);
		};
	}

	/**
	 * Attaches the correct authorization header (if any) to the request builder
	 * for the current backend. A blank credential suppresses the header entirely,
	 * which is the usual case for local Ollama.
	 */
	private Request.Builder addAuthHeader(Request.Builder builder, BackendType backend) {
		AuthScheme scheme = backend.getAuthScheme();
		String cred = config.getAuthorization();
		if (scheme == AuthScheme.BEARER && cred != null && !cred.isBlank()) {
			builder.addHeader("Authorization", "Bearer " + cred);
		}
		return builder;
	}

	@Override
	public IChatCompletionResponse query(List<IChatMessage> history, IRequestMessage userMessage, List<JsonObject> tools, Map<String,Boolean> ifToolDummy) {
		String endpoint = config.getFullLlmUrl();
		BackendType backend = config.getBackendEnum();
		history.add(userMessage);
		
		long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();

		IChatCompletionRequest builder = requestBuilderFor(backend);

		// Thinking is authoritatively set by the resolved model profile
		// (LLMConfigGroup.enableThinking). A message-level override still wins if
		// it is explicitly set to true, so callers can force-enable per round.
		boolean thinkingOn = config.isEnableThinking() || userMessage.ifEnableThinking();
		String body = builder.serializeToHttpBody(
				history, tools, ifToolDummy, "auto",
				config.getTemperature(), config.getMaxTokens(),
				config.getContextWindowTokens(),
				config.getModelName(), false, thinkingOn
				);
		body = GrammarMode.rewriteRequestBody(body, tools);

		Request.Builder reqBuilder = new Request.Builder()
				.url(endpoint)
				.addHeader("Content-Type", "application/json")
				.post(RequestBody.create(body, MediaType.parse("application/json")));
		Request request = addAuthHeader(reqBuilder, backend).build();

		Integer httpStatus = null;
	    String responseBody = null;
	    String error = null;

		try (Response response = httpClient.newCall(request).execute()) {
			responseBody  = response.body() != null ? response.body().string() : "(no body)";

			httpStatus = response.code();


			if (!response.isSuccessful()) {
				throw new IOException("HTTP " + response.code() + " error from OpenAI: " + responseBody);
			}

			IChatCompletionResponse parsed = parseResponseFor(backend, responseBody);

			if (parsed != null) {
				parsed.postBuildCleanup();
			}

			return GrammarMode.adaptResponse(parsed);

		} catch (IOException e) {
			error = e.toString();
			throw new RuntimeException("Failed to call LLM endpoint: " + e.getMessage(), e);
		}finally {
            long durationMs = System.currentTimeMillis() - startTime;

            RawLlmLogEntry logEntry = new RawLlmLogEntry();
            logEntry.traceId = traceId;
            logEntry.timestamp = LocalDateTime.now().toString();
            logEntry.backend = backend.name();
            logEntry.endpoint = endpoint;
            logEntry.modelName = config.getModelName();
            logEntry.temperature = config.getTemperature();
            logEntry.maxTokens = config.getMaxTokens();
            logEntry.thinkingEnabled = thinkingOn;
            logEntry.httpStatus = httpStatus;
            logEntry.durationMs = durationMs;
            logEntry.requestBody = body;
            logEntry.responseBody = responseBody;
            logEntry.error = error;
            
            logEntry.iteration = (services != null) ? services.getIterationNumber() : null;
            
            String iterFilePath;
            String combinedFilePath;

            if (services != null) {
                iterFilePath = services.getControlerIO()
                        .getIterationFilename(services.getIterationNumber(), rawLogFilePath + "_ChatLog.jsonl");

                combinedFilePath = services.getControlerIO()
                        .getOutputFilename(rawLogFilePath + "_ChatLog_combined.jsonl");
            } else {
                iterFilePath = rawLogFilePath + "_ChatLog.json";
                combinedFilePath = rawLogFilePath + "_ChatLog_combined.jsonl";
            }

            appendJsonLine(iterFilePath, logEntry);
            appendJsonLine(combinedFilePath, logEntry);
            
            
//            String filePath = null;
//            if(services!=null) {
//            	filePath = services.getControlerIO().getIterationFilename(services.getIterationNumber(), rawLogFilePath+"_ChatLog.json");
//            	
//            }else {
//            	filePath = rawLogFilePath+"_ChatLog.json";
//            }
//            
//            appendJsonLine(filePath, logEntry);
        }

	}
	
	@Override
	public IChatCompletionResponse query(List<IChatMessage> history, List<IRequestMessage> userMessage, List<JsonObject> tools, Map<String,Boolean> ifToolDummy) {
		String endpoint = config.getFullLlmUrl();
		BackendType backend = config.getBackendEnum();
		history.addAll(userMessage);
		
		long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();

		IChatCompletionRequest builder = requestBuilderFor(backend);

		boolean thinkingOn = config.isEnableThinking()
				|| userMessage.get(userMessage.size()-1).ifEnableThinking();
		String body = builder.serializeToHttpBody(
				history, tools, ifToolDummy, "auto",
				config.getTemperature(), config.getMaxTokens(),
				config.getContextWindowTokens(),
				config.getModelName(), false, thinkingOn
				);
		body = GrammarMode.rewriteRequestBody(body, tools);

		Request.Builder reqBuilder = new Request.Builder()
				.url(endpoint)
				.addHeader("Content-Type", "application/json")
				.post(RequestBody.create(body, MediaType.parse("application/json")));
		Request request = addAuthHeader(reqBuilder, backend).build();

		Integer httpStatus = null;
	    String responseBody = null;
	    String error = null;

		try (Response response = httpClient.newCall(request).execute()) {
			responseBody  = response.body() != null ? response.body().string() : "(no body)";

			httpStatus = response.code();


			if (!response.isSuccessful()) {
				throw new IOException("HTTP " + response.code() + " error from OpenAI: " + responseBody);
			}

			IChatCompletionResponse parsed = parseResponseFor(backend, responseBody);

			if (parsed != null) {
				parsed.postBuildCleanup();
			}

			return GrammarMode.adaptResponse(parsed);

		} catch (IOException e) {
			error = e.toString();
			throw new RuntimeException("Failed to call LLM endpoint: " + e.getMessage(), e);
		}finally {
            long durationMs = System.currentTimeMillis() - startTime;

            RawLlmLogEntry logEntry = new RawLlmLogEntry();
            logEntry.traceId = traceId;
            logEntry.timestamp = LocalDateTime.now().toString();
            logEntry.backend = backend.name();
            logEntry.endpoint = endpoint;
            logEntry.modelName = config.getModelName();
            logEntry.temperature = config.getTemperature();
            logEntry.maxTokens = config.getMaxTokens();
            logEntry.thinkingEnabled = thinkingOn;
            logEntry.httpStatus = httpStatus;
            logEntry.durationMs = durationMs;
            logEntry.requestBody = body;
            logEntry.responseBody = responseBody;
            logEntry.error = error;
            
            logEntry.iteration = (services != null) ? services.getIterationNumber() : null;
            
            String iterFilePath;
            String combinedFilePath;

            if (services != null) {
                iterFilePath = services.getControlerIO()
                        .getIterationFilename(services.getIterationNumber(), rawLogFilePath + "_ChatLog.jsonl");

                combinedFilePath = services.getControlerIO()
                        .getOutputFilename(rawLogFilePath + "_ChatLog_combined.jsonl");
            } else {
                iterFilePath = rawLogFilePath + "_ChatLog.json";
                combinedFilePath = rawLogFilePath + "_ChatLog_combined.jsonl";
            }

            appendJsonLine(iterFilePath, logEntry);
            appendJsonLine(combinedFilePath, logEntry);
            
            
//            String filePath = null;
//            if(services!=null) {
//            	filePath = services.getControlerIO().getIterationFilename(services.getIterationNumber(), rawLogFilePath+"_ChatLog.json");
//            	
//            }else {
//            	filePath = rawLogFilePath+"_ChatLog.json";
//            }
//            
//            appendJsonLine(filePath, logEntry);
        }

	}

	

	@Override
	public LLMConfigGroup getLLMConfig() {
		return config;
	}
	
	private void appendJsonLine(String filePath, Object obj) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, true))) {
            writer.write(gson.toJson(obj));
            writer.newLine();
        } catch (IOException e) {
            System.err.println("Failed to write LLM log to " + filePath + ": " + e.getMessage());
        }
    }

    private static class RawLlmLogEntry {
        String traceId;
        String timestamp;
        
        Integer iteration;

        String backend;
        String endpoint;
        String modelName;

        Double temperature;
        Integer maxTokens;
        Boolean thinkingEnabled;

        Integer httpStatus;
        Long durationMs;

        String requestBody;
        String responseBody;
        String error;
    }
}



