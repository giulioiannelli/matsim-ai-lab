package gsonprocessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.StageActivityHandling;
import org.matsim.core.router.TripStructureUtils.Trip;

public class PlanGson {
	public List<PlanElementGson> activitiesAndLegs = new ArrayList<>();
	public String personId;

	public static PlanGson createPlanGson(Plan pl) {
		Plan plan = PopulationUtils.createPlan();
		PopulationUtils.copyFromTo(pl, plan);
		List<Trip> trips = TripStructureUtils.getTrips(plan);
		List<Activity> activities = TripStructureUtils.getActivities(plan,
				StageActivityHandling.ExcludeStageActivities);
		PlanGson p = new PlanGson();
		double previousActEndTime = 0;
		boolean ifCar = false;
		String carLocation = "unknown";
		if (!trips.isEmpty()
				&& TripStructureUtils.getRoutingModeIdentifier().identifyMainMode(trips.get(0).getTripElements())
						.equals("car")) {
			ifCar = true;
		}
		Map<String, Integer> actOccurance = new HashMap<>();

		for (int i = 0; i < activities.size(); i++) {
			Activity a = activities.get(i);
			if (actOccurance.containsKey(a.getType()))
				actOccurance.put(a.getType(), actOccurance.get(a.getType()) + 1);
			else
				actOccurance.put(a.getType(), 0);
			ActivityGson aa = new ActivityGson();
			aa.id = a.getType() + "___" + actOccurance.get(a.getType());
			aa.activityType = a.getType();
			aa.coord = a.getCoord();
			if (a.getEndTime().isDefined()) {
				aa.endTime = a.getEndTime().seconds();
			} else {
				aa.endTime = 27 * 3600.;
			}
			if (a.getFacilityId() != null)
				aa.facilityId = a.getFacilityId().toString();
			if (a.getLinkId() != null)
				aa.linkId = a.getLinkId().toString();
			aa.maximumDuration = aa.endTime - previousActEndTime;
			if (ifCar)
				carLocation = aa.activityType + "_" + aa.facilityId;
			aa.carLocation = carLocation;

			p.activitiesAndLegs.add(aa);
			previousActEndTime = aa.endTime;

			if (i < trips.size()) {
				LegGson l = new LegGson();
				String mode = TripStructureUtils.getRoutingModeIdentifier()
						.identifyMainMode(trips.get(i).getTripElements());
				l.mode = mode;
				if (activities.size() > i + 1 && a.getCoord() != null && activities.get(i + 1).getCoord() != null) {
					l.distance = org.matsim.core.utils.geometry.CoordUtils
							.calcEuclideanDistance(activities.get(i + 1).getCoord(), a.getCoord());
				}
				p.activitiesAndLegs.add(l);
				if (mode.equals("car")) {
					ifCar = true;
				}
			}
		}
		if (pl.getPerson() != null)
			p.personId = pl.getPerson().getId().toString();
		return p;
	}

	public Plan getPlan() {
		Plan plan = PopulationUtils.createPlan();
		for (PlanElementGson pe : this.activitiesAndLegs) {
			if (pe instanceof ActivityGson) {
				plan.addActivity(((ActivityGson) pe).getActivity());
			} else if (pe instanceof LegGson) {
				plan.addLeg(((LegGson) pe).getLeg());
			}
		}
		return plan;
	}
}
