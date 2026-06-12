package org.matsim.project.llm;

import org.matsim.core.controler.AbstractModule;

import tools.IToolManager;

/**
 * Guice module that registers the LLM replanning listener and the CreatePlanTool.
 */
public class LLMReplanningModule extends AbstractModule {

	private final CreatePlanTool createPlanTool;

	public LLMReplanningModule(CreatePlanTool createPlanTool) {
		this.createPlanTool = createPlanTool;
	}

	@Override
	public void install() {
		bind(CreatePlanTool.class).toInstance(createPlanTool);
		addControlerListenerBinding().to(LLMReplanningListener.class);
	}
}
