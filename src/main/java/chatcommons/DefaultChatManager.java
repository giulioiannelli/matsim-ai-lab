package chatcommons;



import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import chatrequest.IRequestMessage;
import chatrequest.SimpleRequestMessage;
import chatresponse.ChatResult;
import chatresponse.ChatStats;
import chatresponse.IChatCompletionResponse;
import chatresponse.IResponseMessage;
import chatresponse.IUsage;
import matsimBinding.LLMConfigGroup;
import rag.IVectorDB;
import rag.IVectorDB.RetrievedDocument;
import tools.DefaultToolResponse;
import tools.ErrorMessages;
import tools.ExternalValidator;
import tools.IToolManager;
import tools.IToolResponse;
import tools.ToolFilter;

import java.util.Set;

public class DefaultChatManager implements IChatManager {
	
	private IChatMessage systemMessage;
    private final IChatCompletionClient llmClient;
    private final IToolManager toolManager;
    private final IVectorDB vectorDB; // Direct ChromaDB client
    private final Id<IChatManager> id;
    private Map<String,Object> context = new HashMap<>();
    private Id<Person> personId;
    private boolean retrievalFlag = true;

    private LLMConfigGroup config;

    /** Optional per-round tool filter. null = expose all tools (baseline behavior). */
    private ToolFilter toolFilter = null;

    /** Tracks which tools have been called so far in the current conversation. */
    private final List<String> toolsCalledHistory = new ArrayList<>();

    /** Current round number (1-indexed). Set by submit() before each submitInternal(). */
    private int currentRound = 0;

    private final List<IChatMessage> history = new ArrayList<>();

    public DefaultChatManager(Id<IChatManager>id , IChatCompletionClient llmClient, IToolManager toolManager, IVectorDB vectorDB, LLMConfigGroup config) {
        this.id = id;
    	this.llmClient = llmClient;
        this.toolManager = toolManager;
        this.vectorDB = vectorDB;
        this.config = config;
    }
    
    private Map<String, String> buildMetadataFilter() {
        Map<String, String> filter = new HashMap<>();

        if (this.personId != null) {
            filter.put("personId", this.personId.toString());
        }

//        // Optional: add more filters if you stored them
//        if (this.context != null) {
//            Object iteration = context.get("iteration");
//            if (iteration != null) {
//                filter.put("iteration", iteration.toString());
//            }
//        }

        return filter;
    }

    /**
     * Returns the reasoning-token count for this round. Prefers {@code usage.reasoning_tokens}
     * from the backend; when that is zero but a reasoning string is present on the response
     * (e.g. Ollama's OpenAI-compatible endpoint returns {@code message.reasoning} without
     * splitting the token counts), estimates via a char/4 heuristic.
     */
    private static int resolveReasoningTokens(IUsage usage, IChatCompletionResponse response) {
        int reported = usage != null ? usage.getReasoningTokens() : 0;
        if (reported > 0) return reported;
        if (response == null) return 0;
        String reasoning = response.getReasoning();
        if (reasoning == null) {
            IResponseMessage msg = response.getMessage();
            if (msg != null) reasoning = msg.getReasoning();
        }
        if (reasoning == null || reasoning.isBlank()) return 0;
        return Math.max(1, reasoning.length() / 4);
    }

    @Override
    public ChatResult submit(IRequestMessage message) {
//            history.add(message);
        Map<String, IToolResponse<?>> toolResponses = new HashMap<>();
        ChatStats stats = new ChatStats();

        long startTime = System.currentTimeMillis();

        int noToolCallRetryCount = 0;
        final int maxNoToolCallRetries = 3;

        final int maxIteration = this.config.getMaxToolIterations();
        int i = 0;

        stats.success = false;
        stats.failureType = "UNKNOWN";

        while (true) {
            stats.llmRounds++;
            currentRound = stats.llmRounds;
            long roundStart = System.currentTimeMillis();

            IChatCompletionResponse completionResponse = submitInternal(message);

            long roundDuration = System.currentTimeMillis() - roundStart;
            stats.durationMs += roundDuration;

            IUsage usage = completionResponse.getUsage();
            if (usage != null) {
                stats.promptTokens += usage.getPromptTokens();
                stats.completionTokens += usage.getCompletionTokens();
                stats.totalTokens += usage.getTotalTokens();
            }
            int roundReasoningTokens = resolveReasoningTokens(usage, completionResponse);
            stats.reasoningTokens += roundReasoningTokens;
            int cap = config != null ? config.getThinkingTokenCap() : 0;
            if (cap > 0 && roundReasoningTokens > cap) {
                stats.thinkingTokenCapHit++;
            }
            IResponseMessage response = completionResponse.getMessage();
            history.add(response);

            if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                noToolCallRetryCount++;
                stats.noToolCallRetries = noToolCallRetryCount;

                if (noToolCallRetryCount >= maxNoToolCallRetries) {
                    System.out.println("Warning: model failed to produce a valid tool call after "
                            + maxNoToolCallRetries + " attempts.");
                    retrievalFlag = true;

                    stats.success = false;
                    stats.failureType = "NO_TOOL_CALL_AFTER_RETRIES";
                    break;
                }

                IRequestMessage noToolMessage = new SimpleRequestMessage(
                        Role.USER,
                        "Your previous response was invalid because it did not contain any valid tool call. "
                                + "You must respond with a valid tool call only.",
                        null,
                        false
                );
                message = noToolMessage;
                retrievalFlag = false;

            } else {
                noToolCallRetryCount = 0;
                retrievalFlag = true;

                List<IToolResponse<?>> newResponses = new ArrayList<>();
                boolean ifNonDummy = false;

                for (var call : response.getToolCalls()) {
                    stats.totalToolCalls++;
                    if (isComparisonToolName(call.getName())) stats.comparisonToolInvocations++;
                    toolsCalledHistory.add(call.getName());

                    IToolResponse<?> toolResult = toolManager.runToolCall(call, this.vectorDB, this.context);
                    newResponses.add(toolResult);
                    toolResponses.put(call.getId(), toolResult);

                    if (toolResult != null && toolResult.getResponseJson() != null) {
                        String responseJson = toolResult.getResponseJson().toLowerCase();

                        if (responseJson.contains("\"status\":\"error\"") || responseJson.contains("\"status\": \"error\"")) {
                            if (responseJson.contains("parse")) {
                                stats.toolParsingFailures++;
                            } else if (responseJson.contains("verification")) {
                                stats.toolVerificationFailures++;
                            } else {
                                stats.toolExecutionFailures++;
                            }
                        }
                    }

                    if (toolResult.isForLLM()) {
                        ifNonDummy = true;
                    }
                }

                IRequestMessage toolMessage = new SimpleRequestMessage(Role.TOOL, "", newResponses, false);
//                history.add(toolMessage);
                message = toolMessage;

                if (!ifNonDummy) {
                    stats.success = true;
                    stats.failureType = "NONE";
                    break;
                }
            }

            i++;
            if (i > maxIteration) {
                stats.hitMaxIterations = true;
                stats.success = false;
                stats.failureType = "MAX_ITERATION_REACHED";
                break;
            }
        }

        stats.durationMs = System.currentTimeMillis() - startTime;

        return new ChatResult(toolResponses, stats);
    }
    
    
    @Override
    public ChatResult submit(IRequestMessage message, Map<String,ExternalValidator<?>> externalToolResultValidator) {
    	
    	List<IRequestMessage> messagesToSend = new ArrayList<>();
    	
    	if (externalToolResultValidator == null) {
            externalToolResultValidator = new HashMap<>();
        }

        for (Map.Entry<String, ExternalValidator<?>> entry : externalToolResultValidator.entrySet()) {
            String toolName = entry.getKey();
            ExternalValidator<?> validator = entry.getValue();

            if (validator == null) {
                throw new IllegalArgumentException("Null external validator provided for tool name " + toolName);
            }

            if (this.toolManager.getByName(toolName) == null) {
                throw new IllegalArgumentException("Unrecognized tool result validator for tool name " + toolName);
            }

            Class<?> toolOutputClass = this.toolManager.getByName(toolName).getOutputClass();
            Class<?> validatorTargetClass = validator.getTargetType();

            if (validatorTargetClass == null) {
                throw new IllegalArgumentException("Validator target type is null for tool name " + toolName);
            }

            if (!toolOutputClass.equals(validatorTargetClass)) {
                throw new IllegalArgumentException(
                    "Tool output class and validator target type do not match for tool " + toolName
                    + ". Tool output class = " + toolOutputClass.getName()
                    + ", validator target class = " + validatorTargetClass.getName()
                );
            }

            if (!toolName.equals(validator.getTargetToolName())) {
                throw new IllegalArgumentException(
                    "Validator target tool name mismatch. Map key = " + toolName
                    + ", validator reports = " + validator.getTargetToolName()
                );
            }
        }
//            history.add(message);
        Map<String, IToolResponse<?>> toolResponses = new HashMap<>();
        ChatStats stats = new ChatStats();

        long startTime = System.currentTimeMillis();

        int noToolCallRetryCount = 0;
        final int maxNoToolCallRetries = 3;

        final int maxIteration = this.config.getMaxToolIterations();
        int i = 0;

        stats.success = false;
        stats.failureType = "UNKNOWN";
        messagesToSend.add(message);
        while (true) {
            stats.llmRounds++;
            currentRound = stats.llmRounds;
            long roundStart = System.currentTimeMillis();

            IChatCompletionResponse completionResponse = submitInternal(messagesToSend);

            long roundDuration = System.currentTimeMillis() - roundStart;
            stats.durationMs += roundDuration;

            IUsage usage = completionResponse.getUsage();
            if (usage != null) {
                stats.promptTokens += usage.getPromptTokens();
                stats.completionTokens += usage.getCompletionTokens();
                stats.totalTokens += usage.getTotalTokens();
            }
            int roundReasoningTokens = resolveReasoningTokens(usage, completionResponse);
            stats.reasoningTokens += roundReasoningTokens;
            int cap = config != null ? config.getThinkingTokenCap() : 0;
            if (cap > 0 && roundReasoningTokens > cap) {
                stats.thinkingTokenCapHit++;
            }
            IResponseMessage response = completionResponse.getMessage();
            history.add(response);

            if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                noToolCallRetryCount++;
                stats.noToolCallRetries = noToolCallRetryCount;

                if (noToolCallRetryCount >= maxNoToolCallRetries) {
                    System.out.println("Warning: model failed to produce a valid tool call after "
                            + maxNoToolCallRetries + " attempts.");
                    retrievalFlag = true;

                    stats.success = false;
                    stats.failureType = "NO_TOOL_CALL_AFTER_RETRIES";
                    break;
                }

                IRequestMessage noToolMessage = new SimpleRequestMessage(
                        Role.USER,
                        "Your previous response was invalid because it did not contain any valid tool call. "
                                + "You must respond with a valid tool call only.",
                        null,
                        false
                );
                messagesToSend.clear();
                messagesToSend.add(noToolMessage);
                retrievalFlag = false;

            }else {
                noToolCallRetryCount = 0;
                retrievalFlag = true;

                List<IToolResponse<?>> newResponses = new ArrayList<>();
                boolean ifNonDummy = false;

                for (var call : response.getToolCalls()) {
                    stats.totalToolCalls++;
                    if (isComparisonToolName(call.getName())) stats.comparisonToolInvocations++;
                    toolsCalledHistory.add(call.getName());
                    ErrorMessages em = new ErrorMessages();
                    IToolResponse<?> toolResult = toolManager.runToolCall(call, this.vectorDB, this.context);

                    

                    if (toolResult != null && toolResult.getResponseJson() != null) {
                        String responseJson = toolResult.getResponseJson().toLowerCase();

                        if (responseJson.contains("\"status\":\"error\"") || responseJson.contains("\"status\": \"error\"")) {
                            if (responseJson.contains("parse")) {
                                stats.toolParsingFailures++;
                            } else if (responseJson.contains("verification")) {
                                stats.toolVerificationFailures++;
                            } else {
                                stats.toolExecutionFailures++;
                            }
                        }
                    }

                    ExternalValidator<?> validator = externalToolResultValidator.get(call.getName());
                    
                    boolean valid = true;
                    
                    if (validator != null && toolResult != null && toolResult.getToolCallOutputContainer()!=null) {
                        Object rawOutput = toolResult.getToolCallOutputContainer();

                        
                        
                        valid = runExternalValidationUnchecked(validator, rawOutput, this.context, em);
                        
                        if(!valid) {
                        	
                        	
                        	toolResult = new DefaultToolResponse<>(
                                    call.getId(),
                                    call.getName(),
                                    em.getCombinedErrorMessages(), // for logging/LLM tracing if needed
                                    null,
                                    false // always sent to LLM
                                );
                        	
                        	stats.externalValidationFailures++;
                        }

                    }
                    if (!valid || toolResult.isForLLM()) {
                        ifNonDummy = true;
                    }

                    newResponses.add(toolResult);

                    if(toolResult.getToolCallOutputContainer()!=null && valid) {
                    	toolResponses.put(call.getId(), toolResult);// Should this not be only the successful ones? 
                    }

                }

                
                IRequestMessage toolMessage = new SimpleRequestMessage(Role.TOOL, "", newResponses, false);
                
                
               
                messagesToSend.clear();
                messagesToSend.add(toolMessage);


                if (!ifNonDummy) {
                    stats.success = true;
                    stats.failureType = "NONE";
                    break;
                }
            }

            i++;
            if (i > maxIteration) {
                stats.hitMaxIterations = true;
                stats.success = false;
                stats.failureType = "MAX_ITERATION_REACHED";
                break;
            }
        }

        stats.durationMs = System.currentTimeMillis() - startTime;

        return new ChatResult(toolResponses, stats);
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean runExternalValidationUnchecked(
            ExternalValidator<?> validator,
            Object rawOutput,
            Map<String, Object> context,
            ErrorMessages em) {

        ExternalValidator<T> typedValidator = (ExternalValidator<T>) validator;
        T candidate = typedValidator.getTargetType().cast(rawOutput);
        return typedValidator.validate(candidate, context, em);
    }

//    @Override
//    public ChatResult submit(IRequestMessage message) {
////        history.add(message);
//    	Map<String, IToolResponse<?>> toolResponses = new HashMap<>();
//    	ChatStats stats = new ChatStats();
//
//    	long startTime = System.currentTimeMillis();
//
//    	int noToolCallRetryCount = 0;
//    	final int maxNoToolCallRetries = 3;
//
//    	final int maxIteration = 12;
//    	int i = 0;
//
//    	stats.success = false;
//    	stats.failureType = "UNKNOWN";
//        
//        while (true) {
//            IResponseMessage response = submitInternal(message);
//            history.add(response);
//
//            if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
//            	noToolCallRetryCount++;
//
//                if (noToolCallRetryCount >= maxNoToolCallRetries) {
//                    System.out.println("Warning: model failed to produce a valid tool call after "
//                            + maxNoToolCallRetries + " attempts.");
//                    retrievalFlag = true;
//                    break;
//                }
//                IRequestMessage noToolMessage = new SimpleRequestMessage(Role.USER,"Your previous response was invalid because it did not contain any valid tool call. "
//                		+ "You must respond with a valid tool call only.",null,false);
//                message = noToolMessage;
//                retrievalFlag = false;
//            }else {
//            	noToolCallRetryCount = 0;
//            	retrievalFlag = true;
//	            List<IToolResponse<?>> newResponses = new ArrayList<>();
//	            boolean ifNonDummy = false;
//	            for (var call : response.getToolCalls()) {
//	                
//	                IToolResponse<?> toolResult = toolManager.runToolCall(call, this.vectorDB, this.context);
//	                newResponses.add(toolResult);
//	                toolResponses.put(call.getId(), toolResult);
//	                if(toolResult.isForLLM()) {
//	                	ifNonDummy = true;
//	                }
//	                
//	            }
//	
//	            IRequestMessage toolMessage = new SimpleRequestMessage(Role.TOOL,"",newResponses,false);
//	//            history.add(toolMessage);
//	            message = toolMessage;
//	            
//	            if(!ifNonDummy) {
//	            	break;
//	            }
//            }
//            i++;
//            if(i>maxIteration)break;
//        }
//
//        return new ChatResult(toolResponses,stats);
//    }


    @Override
    public IChatCompletionResponse submitInternal(IRequestMessage message) {
        IRequestMessage enrichedMessage = message;
        Map<String, String> metaDataFilter = this.buildMetadataFilter();
        // Inject context only for the initial USER message
        if (this.retrievalFlag && vectorDB != null && message.getRole() == Role.USER && message.getContent() != null && !message.getContent().isBlank()) {
            List<RetrievedDocument> docs = vectorDB.query(message.getContent(), 3, metaDataFilter);

            if (!docs.isEmpty()) {
                StringBuilder contextBlock = new StringBuilder("[Retrieved Context]\n");
                for (RetrievedDocument doc : docs) {
                    contextBlock.append("- ").append(doc.content()).append("\n");
                }

                contextBlock.append("\n[User Message]\n").append(message.getContent());

                // Wrap new content in a new SimpleRequestMessage
                enrichedMessage = new SimpleRequestMessage(Role.USER, contextBlock.toString());
            }
        }

        IChatCompletionResponse completion = llmClient.query(history, enrichedMessage, resolveToolSchemas(), toolManager.getIfToolDummy());
        return completion;
    }
    
    @Override
    public IChatCompletionResponse submitInternal(List<IRequestMessage> message) {
        List<IRequestMessage> enrichedMessages = new ArrayList<>(message);
        Map<String, String> metaDataFilter = this.buildMetadataFilter();
        // Inject context only for the initial USER message
        for(IRequestMessage enrichedMessage:enrichedMessages) {
	        if (this.retrievalFlag && vectorDB != null && enrichedMessage.getRole() == Role.USER && enrichedMessage.getContent() != null && !enrichedMessage.getContent().isBlank()) {
	            List<RetrievedDocument> docs = vectorDB.query(enrichedMessage.getContent(), 3, metaDataFilter);
	
	            if (!docs.isEmpty()) {
	                StringBuilder contextBlock = new StringBuilder("[Retrieved Context]\n");
	                for (RetrievedDocument doc : docs) {
	                    contextBlock.append("- ").append(doc.content()).append("\n");
	                }
	
	                contextBlock.append("\n[User Message]\n").append(enrichedMessage.getContent());
	
	                // Wrap new content in a new SimpleRequestMessage
	                enrichedMessage = new SimpleRequestMessage(Role.USER, contextBlock.toString());
	            }
	        }
        }

        IChatCompletionResponse completion = llmClient.query(history, enrichedMessages, resolveToolSchemas(), toolManager.getIfToolDummy());
        return completion;
    }

    /** Enable via -Dmatsim.llm.toolFilter.debug=true */
    private static final boolean DEBUG_FILTER = Boolean.getBoolean("matsim.llm.toolFilter.debug");

    private java.util.List<com.google.gson.JsonObject> resolveToolSchemas() {
        if (toolFilter == null) {
            return toolManager.getAllToolSchemas();
        }
        ToolFilter.ChatState state = new ToolFilter.ChatState() {
            @Override public int round() { return currentRound; }
            @Override public java.util.List<String> toolsCalledSoFar() {
                return java.util.Collections.unmodifiableList(toolsCalledHistory);
            }
        };
        Set<String> visible = toolFilter.visibleTools(state);
        if (visible == null || visible.isEmpty()) {
            return toolManager.getAllToolSchemas();
        }
        if (DEBUG_FILTER) logFilter(visible);
        return toolManager.getSchemasFor(visible);
    }

    private void logFilter(Set<String> visible) {
        System.out.println("[ToolFilter] round=" + currentRound
            + " called=" + toolsCalledHistory + " visible=" + visible);
    }



    @Override
    public void append(IChatMessage... messages) {
        history.addAll(Arrays.asList(messages));
    }

    public List<IChatMessage> getHistory() {
        return history;
    }

    public IVectorDB getChromaClient() {
        return vectorDB;
    }



	@Override
	public String getSystemMessage() {
		return this.systemMessage.getContent();
	}

	@Override
	public void setSystemMessage(String systemMessage) {
		this.systemMessage = new SimpleRequestMessage(Role.SYSTEM,systemMessage);
		this.history.add(this.systemMessage);
	}

	@Override
	public IChatMessage getLastMessage() {
		
		return this.history.get(this.history.size()-1);
	}

	@Override
	public void clear() {
		this.history.clear();
		this.history.add(systemMessage);
		this.toolsCalledHistory.clear();
		this.currentRound = 0;
	}

	/**
	 * Sets an optional tool filter to restrict which tools are visible to the
	 * LLM on a per-round basis. When null (default), all registered tools are
	 * exposed — this is the baseline behavior.
	 */
	public void setToolFilter(ToolFilter filter) {
		this.toolFilter = filter;
	}

	public ToolFilter getToolFilter() {
		return this.toolFilter;
	}

	private static boolean isComparisonToolName(String name) {
		return "compare_routes".equals(name) || "evaluate_plan".equals(name);
	}

	@Override
	public Id<IChatManager> getId() {
		// TODO Auto-generated method stub
		return this.id;
	}

	@Override
	public Map<String, Object> getContextObject() {
		// TODO Auto-generated method stub
		return this.context;
	}

	@Override
	public void setContextObject(Map<String, Object> context) {
		this.context = context;
	}

	@Override
	public Id<Person> getPersonId() {
		// TODO Auto-generated method stub
		return this.personId;
	}

	@Override
	public void setPersonId(Id<Person> person) {
		this.personId = person;
	}
}

