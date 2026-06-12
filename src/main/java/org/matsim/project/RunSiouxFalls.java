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
 * Runs the Sioux Falls 2014 scenario from matsim-examples.
 *
 * Multimodal scenario with car, public transit (bus), and walk.
 * Dynamic and disaggregate demand with activity-based plans.
 * Full-scale (flowCapacityFactor=1.0), real PT schedule.
 *
 * Usage: ./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunSiouxFalls"
 *
 * Optional: pass number of iterations as first argument (default: 100)
 *   ./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunSiouxFalls" -Dexec.args="50"
 */
public class RunSiouxFalls {

	public static void main(String[] args) {

		URL context = org.matsim.examples.ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
		URL url = IOUtils.extendUrl(context, "config_default.xml");

		Config config = ConfigUtils.loadConfig(url);
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		// Reduce from 3000 to manageable number
		int iterations = 100;
		if (args != null && args.length > 0) {
			try {
				iterations = Integer.parseInt(args[0]);
			} catch (NumberFormatException ignored) {}
		}
		config.controller().setLastIteration(iterations);
		config.controller().setOutputDirectory("./output/siouxfalls");
		config.controller().setWriteEventsInterval(iterations);
		config.controller().setWritePlansInterval(iterations);

		Scenario scenario = ScenarioUtils.loadScenario(config);

		Controler controler = new Controler(scenario);

		// Enable SimWrapper for web-based dashboards
		controler.addOverridingModule(new SimWrapperModule());

		controler.run();
	}
}
