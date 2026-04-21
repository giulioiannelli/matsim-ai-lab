package matsimBinding;



import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import chatcommons.ChatCompletionClientImpl;
import chatcommons.ChatManagerContainer;
import chatcommons.IChatCompletionClient;
import rag.IVectorDB;
import rag.VectorDBImplement;
import tools.DefaultToolManager;
import tools.IToolManager;
import tools.Implement.ActivityChainSummaryTool;
import tools.Implement.AvailableModesTool;
import tools.Implement.ExtractPlanTool;
import tools.Implement.PullAdditionalContextTool;
import tools.Implement.RouterTool;
import tools.Implement.ValidateTimingTool;

public class LLMIntegrationModule extends AbstractModule {

	ConnectionType type = ConnectionType.replanning;
	private final boolean comparisonToolsEnabled;

	public static enum ConnectionType{
		replanning,
		withinday,
		controllerlistener
	}

	public LLMIntegrationModule(ConnectionType type) {
		this(type, false);
	}

	public LLMIntegrationModule(ConnectionType type, boolean comparisonToolsEnabled) {
		this.type = type;
		this.comparisonToolsEnabled = comparisonToolsEnabled;
	}

    private final DefaultToolManager toolManager = new DefaultToolManager();
    private final ChatManagerContainer container = new ChatManagerContainer();

    public DefaultToolManager getToolManager() {
        return toolManager;
    }

    public ChatManagerContainer getChatManagerContainer() {
        return container;
    }

    @Override
    public void install() {
        // These are pre-created so tools can be registered before controler.run()
        bind(IToolManager.class).toInstance(toolManager);
        toolManager.registerTool(new ExtractPlanTool());
        toolManager.registerTool(new RouterTool());
        toolManager.registerTool(new PullAdditionalContextTool());
        toolManager.registerTool(new ActivityChainSummaryTool());
        toolManager.registerTool(new AvailableModesTool());
        toolManager.registerTool(new ValidateTimingTool());
        if (comparisonToolsEnabled) {
            toolManager.registerTool(new tools.Implement.comparison.CompareRoutesTool());
            toolManager.registerTool(new tools.Implement.comparison.EvaluatePlanTool());
        }
        bind(ChatManagerContainer.class).toInstance(container);
        
        
        
        // These are config-based and created at runtime by Guice
        bind(IVectorDB.class).toProvider(VectorDbProvider.class).in(Singleton.class);
        bind(IChatCompletionClient.class).toProvider(ChatClientProvider.class).in(Singleton.class);
        //this.bind(AgentExperienceEventHandler.class).asEagerSingleton();
        this.addEventHandlerBinding().to(AgentExperienceEventHandlerV2.class).asEagerSingleton();
        //this.addControlerListenerBinding().to(AgentExperienceEventHandler.class);
        if(this.type.equals(ConnectionType.controllerlistener)) {
        	this.addControlerListenerBinding().to(LLMControllerListener.class).asEagerSingleton();
        }
        
        if(this.type.equals(ConnectionType.replanning)) {
	        this.addPlanStrategyBinding(LLMReplanningStrategyModule.StrategyName).toProvider(LLMReplanningStrategyProvider.class);
	        bind(LLMReplanningStrategyModule.class).asEagerSingleton();
	        this.addControlerListenerBinding().to(LLMReplanningStrategyModule.class).asEagerSingleton();
        }
        if(this.type.equals(ConnectionType.withinday)) {
	        this.addControlerListenerBinding().to(LLMWithinDayListener.class).asEagerSingleton();
	        
	        installQSimModule(new AbstractQSimModule() {
	
				@Override
				protected void configureQSim() {
					bind(LLMWithinDayListener.class).asEagerSingleton();
	                addMobsimListenerBinding().to(LLMWithinDayListener.class);
				}
	        	
	        });
        }
    }
}


class VectorDbProvider implements Provider<IVectorDB> {

    @Inject
    private Config config;

    @Override
    public IVectorDB get() {
        VectorDBImplement vectorDb = new VectorDBImplement((LLMConfigGroup)config.getModules().get(LLMConfigGroup.GROUP_NAME));
        return vectorDb;
    }
}


class ChatClientProvider implements Provider<IChatCompletionClient> {

    @Inject
    private Config config;
    
    @Inject
    MatsimServices services;

    @Override
    public IChatCompletionClient get() {
        return new ChatCompletionClientImpl((LLMConfigGroup)config.getModules().get(LLMConfigGroup.GROUP_NAME), services);
    }
}

