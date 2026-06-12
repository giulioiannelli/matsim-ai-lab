package org.matsim.project;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.simwrapper.SimWrapperModule;

import java.net.URL;

/**
 * Runs the Kelheim scenario from matsim-examples.
 *
 * Kelheim is a real German town with multimodal transport:
 * car, public transit, bike, walk, ride, freight.
 * 1% population sample, real network with PT.
 *
 * Usage: ./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunKelheim"
 */
public class RunKelheim {

	public static void main(String[] args) {

		URL context = org.matsim.examples.ExamplesUtils.getTestScenarioURL("kelheim");
		URL url = IOUtils.extendUrl(context, "config.xml");

		Config config = ConfigUtils.loadConfig(url);
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		// Reduce iterations for a quick test run (original: 1000)
		// Use 50 for a meaningful result, 10 for a quick smoke test
		int iterations = 50;
		if (args != null && args.length > 0) {
			try {
				iterations = Integer.parseInt(args[0]);
			} catch (NumberFormatException ignored) {}
		}
		config.controller().setLastIteration(iterations);
		config.controller().setOutputDirectory("./output/kelheim");
		config.controller().setWriteEventsInterval(iterations); // write events only for last iteration
		config.controller().setWritePlansInterval(iterations);

		Scenario scenario = ScenarioUtils.loadScenario(config);

		Controler controler = new Controler(scenario);

		// Enable SimWrapper for web-based dashboards
		controler.addOverridingModule(new SimWrapperModule());

		controler.run();
	}
}
