package net.floodlightcontroller.fastfailoverdemo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Set;

import org.projectfloodlight.openflow.protocol.OFBarrierReply;
import org.projectfloodlight.openflow.protocol.OFBarrierRequest;
import org.projectfloodlight.openflow.protocol.OFBucket;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.OFGroupAdd;
import org.projectfloodlight.openflow.protocol.OFGroupDelete;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.protocol.OFPortConfig;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFPortMod;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.ver10.OFPortConfigSerializerVer10;
import org.projectfloodlight.openflow.protocol.ver11.OFPortConfigSerializerVer11;
import org.projectfloodlight.openflow.protocol.ver12.OFPortConfigSerializerVer12;
import org.projectfloodlight.openflow.protocol.ver13.OFPortConfigSerializerVer13;
import org.projectfloodlight.openflow.protocol.ver14.OFPortConfigSerializerVer14;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.fastfailoverdemo.web.FastFailoverDemoRoutable;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.util.FlowModUtils;

/**
 * 
 * @author Ryan Izard, rizard@g.clemson.edu, ryan.izard@bigswitch.com
 *
 */
public class FastFailoverDemo implements IFloodlightModule, IOFSwitchListener, IFastFailoverDemoService {
	/*
	 * The pre-defined services that we use.
	 */
	private static IOFSwitchService switchService;
	private static IRestApiService restApiService;
	private static ILinkDiscoveryService linkDiscoveryService;

	/*
	 * The Logger that we'll use for debug output.
	 */
	private static final Logger log = LoggerFactory.getLogger(FastFailoverDemo.class);

	/*
	 * To more easily identify our flows, we will use a cookie.
	 */
	private static final U64 cookie = U64.ofRaw(0x11223344);

	/*
	 * The path we're currently using. This will be used to determine
	 * which set of ports to take up/down upon a toggle.
	 */
	private static boolean usingPath1 = true;

	/*
	 * We could come up with a complex way to detect the various paths and switches
	 * involved using the ITopologyService, but for simplicity, we will do everything
	 * here so that there isn't any hand-waving and "magic" involved.
	 */
	private static final DatapathId dpid1 = DatapathId.of("00:00:00:00:00:00:00:01");
	private static final DatapathId dpid2a = DatapathId.of("00:00:00:00:00:00:00:2a");
	private static final DatapathId dpid2b = DatapathId.of("00:00:00:00:00:00:00:2b");
	private static final DatapathId dpid3 = DatapathId.of("00:00:00:00:00:00:00:03");

	/*
	 * Once we learn the links, which include the port numbers we need, these will
	 * be set appropriately. A REST API call will trigger asking for the links if they
	 * haven't been determined already. The assumption here is that all the ports are
	 * initially up so that the numbers can be determined dynamically. Otherwise, if a
	 * port is set down, LLDP will not be broadcast out that port, and we will never
	 * learn who that port is connected to.
	 */
	private static Link link_dpid1_to_dpid2a;
	private static Link link_dpid1_to_dpid2b;
	private static Link link_dpid2a_to_dpid3;
	private static Link link_dpid2b_to_dpid3;

	/*
	 * Keep track of who has flows and who doesn't.
	 */
	private static boolean dpid1_has_flows = false;
	private static boolean dpid2a_has_flows = false;
	private static boolean dpid2b_has_flows = false;
	private static boolean dpid3_has_flows = false;

	/*
	 * Maintain an active Map of all the switches we care about and whether or not they
	 * are connected (i.e. ready-to-go). If they aren't connected, then we either just
	 * booted and need to wait, or there's a problem.
	 */
	private static Map<DatapathId, Boolean> switchConnected;
	private static boolean allSwitchesConnected;

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		/*
		 * Our module implements the IFastFailoverDemoService.
		 */
		Collection<Class<? extends IFloodlightService>> services = new ArrayList<Class<? extends IFloodlightService>>();
		services.add(IFastFailoverDemoService.class);
		return services;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		/*
		 * We are the object that implements the IFastFailoverDemoService. Give our reference
		 * to the module loader so that any other modules can know where we are.
		 * 
		 * This will be used by the IRestApiService in its internal Map of Floodlight
		 * services. In this way, we will be able to call our service's functions
		 * (exposed through the interface) when a REST query is received by the IRestApiService.
		 */
		Map<Class<? extends IFloodlightService>, IFloodlightService> services = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		services.put(IFastFailoverDemoService.class, this);
		return services;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		/* 
		 * We require the use of the IOFSwitchService to listen for switch events.
		 * We also have a REST API, so we need to have the IRestApiService loaded 
		 * before us as well. Lastly, we look at the discovered links in order to
		 * learn the ports for use in our flows. Thus, we depend on information
		 * from the ILinkDiscoveryService.
		 */
		Collection<Class<? extends IFloodlightService>> deps = new ArrayList<Class<? extends IFloodlightService>>();
		deps.add(IOFSwitchService.class);
		deps.add(IRestApiService.class);
		deps.add(ILinkDiscoveryService.class);
		return deps;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		/*
		 * Setup our internal data structures.
		 */
		switchConnected = new HashMap<DatapathId, Boolean>(4);
		switchConnected.put(dpid1, false);
		switchConnected.put(dpid2a, false);
		switchConnected.put(dpid2b, false);
		switchConnected.put(dpid3, false);
		allSwitchesConnected = false;

		/*
		 * Since we list the IOFSwitchService, IRestApiService, and ILinkDiscoveryService as 
		 * dependencies in getModuleDependencies(), they will be loaded before us in the module
		 * loading chain. So, it's safe to ask the context map for a reference to them.
		 */
		switchService = context.getServiceImpl(IOFSwitchService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
		linkDiscoveryService = context.getServiceImpl(ILinkDiscoveryService.class);

		/*
		 * Note, at this point, it still is not safe to call any functions defined
		 * by these services. We must wait until our startUp() function is called.
		 * The Floodlight module loader will have called their startUp() functions,
		 * by the time ours is called (since we listed them as dependencies). Thus,
		 * they will be ready-to-rock in our startUp().
		 */
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		/*
		 * We are a module that wants to register for switch events. So, we tell the
		 * IOFSwitchService that we want to be added to the callback chain. Note that
		 * we implement IOFSwitchListener, so we have the functions defined that the
		 * IOFSwitchService will call when a switch event occurs (e.g. switchAdded).
		 */
		switchService.addOFSwitchListener(this);

		/*
		 * Similarly, tell the IRestApiService that we are implementing an API. Thus,
		 * when an HTTP request comes in, the IRestApiService will have our URIs registered
		 * and can match the request to one of them.
		 */
		restApiService.addRestletRoutable(new FastFailoverDemoRoutable());

		/*
		 * And lastly, we also use the ILinkDiscoveryService; however, we don't register
		 * with it for anything. We will ask it for links when a REST API call is made.
		 * This will allow us to determine the ports used in our flows.
		 */
		log.info("Fast failover demo module has successfully started.");
	}

	@Override
	public void switchAdded(DatapathId switchId) {
		/*
		 * Set the switch as connected.
		 */
		if (switchConnected.keySet().contains(switchId)) {
			/*
			 * Add to Map.
			 */
			switchConnected.put(switchId, true);
			
			/*
			 * Set ports up. This resets the switches from the "last run" if need be.
			 */
			setAllPortsUp(switchService.getSwitch(switchId));
			
			/*
			 * Determine if all are connected.
			 */
			boolean allConnected = true;
			for (Boolean value : switchConnected.values()) {
				if (!value.booleanValue()) {
					allConnected = false;
				}
			}
			if (allConnected) {
				/*
				 * We're ready-to-rock!
				 */
				allSwitchesConnected = true;
				log.info("All switched connected. Ready to rock!");
			}
		}
	}

	@Override
	public void switchRemoved(DatapathId switchId) {
		/*
		 * Set the switch as disconnected and remove all
		 * associated links. The port might change if/when
		 * the switch comes back up.
		 */
		if (switchConnected.keySet().contains(switchId)) {
			switchConnected.put(switchId, false);
			allSwitchesConnected = false;
			if (dpid1.equals(switchId)) {
				dpid1_has_flows = false;
				link_dpid1_to_dpid2a = null;
				link_dpid1_to_dpid2b = null;
			} else if (dpid2a.equals(switchId)) {
				dpid2a_has_flows = false;
				link_dpid2a_to_dpid3 = null;
				link_dpid1_to_dpid2a = null;
			} else if (dpid2b.equals(switchId)) {
				dpid2b_has_flows = false;
				link_dpid2b_to_dpid3 = null;
				link_dpid1_to_dpid2b = null;
			} else if (dpid3.equals(switchId)) {
				dpid3_has_flows = false;
				link_dpid2a_to_dpid3 = null;
				link_dpid2b_to_dpid3 = null;
			}
			log.error("Switch {} disconnected! Check control network.", switchId.toString());
		}	
	}

	@Override
	public void switchActivated(DatapathId switchId) {
		/*
		 * "Activated" is for transitions from slave to master.
		 * We don't require that in this module, and we assume
		 * we are always master to all switches (the default).
		 */
	}

	@Override
	public void switchPortChanged(DatapathId switchId, OFPortDesc port,
			PortChangeType type) {
		/*
		 * We won't react to switch port change events. We will be the
		 * cause of switch port changes by bringing them up and down
		 * administratively.
		 */
	}

	@Override
	public void switchChanged(DatapathId switchId) {
		/*
		 * Ditto (minus the port part).
		 */
	}

	@Override
	public synchronized Map<String, String> handleToggleRequest(String json) {
		/*
		 * We don't care about the String as input, since we
		 * randomly toggle between the paths, but this is how you
		 * would provide your module with the HTTP payload of a 
		 * POST or PUT. (Again, we will ignore the argument though
		 * in this demonstration.)
		 */

		/*
		 * Our return Map, which is readily converted to JSON.
		 */
		Map<String, String> message = new HashMap<String, String>();

		/*
		 * First, let's make sure everyone's connected.
		 */
		if (!allSwitchesConnected) {
			log.error("Not all switches are connected. Status: {}", switchConnected.toString());
			message.put("STATUS", "ERROR");
			message.put("DETAILS", "Not all switches are connected. Switch status: " + switchConnected.toString());
			return message;
		}

		/*
		 * Next, we need to make sure we have learned all the links.
		 */
		if (!learnLinks()) {
			log.error("Have not learned all links yet.");
			message.put("STATUS", "ERROR");
			message.put("DETAILS", "Have not learned all links in topology. Try again after a few moments. "
					+ "Make sure all ports are set up to enable LLDP to discover missing links.");
			return message;
		}

		/*
		 * Next, insert flows if they haven't been already.
		 * TODO We assume this was successful, which it might 
		 * not be if the switches do not support groups or if
		 * they are not OF1.3 or some other odd situation.
		 */
		boolean didInsertFlow = insertFlows();		

		/*
		 * Now, toggle the path.
		 */
		if (usingPath1) {
			usePathA();
			usingPath1 = !usingPath1;
			message.put("STATUS", "SUCCESS");
			message.put("DETAILS", (didInsertFlow ? "Inserted groups and flows. " : "") +
					"Administratively set ports along path A up and path B down. " +
					"You should observe path A being chosen by the FAST-FAILOVER groups, " +
					"which can be verified by observing packets on s2a on path A" );
		} else {
			usePathB();
			usingPath1 = !usingPath1;
			message.put("STATUS", "Success");
			message.put("DETAILS", (didInsertFlow ? "Inserted groups and flows. " : "") +
					"Administratively set ports along path B up and path A down. " +
					"You should observe path B being chosen by the FAST-FAILOVER groups, " +
					"which can be verified by observing packets on s2b on path B" );
		}

		return message;
	}
	
	@Override
	public Map<String, String> handleResetRequest(String json) {
		/*
		 * Our return Map, which is readily converted to JSON.
		 */
		Map<String, String> message = new HashMap<String, String>();
		
		IOFSwitch sw = switchService.getSwitch(dpid1);
		
		if (sw == null) {
			log.error("Could not reset s1 ports. Switch service does not see it connected. Check the control plane to verify s1 is in fact connected to the controller.");
			message.put("S1 STATUS", "ERROR");
			message.put("S1 DETAILS", "Could not reset s1. Switch service does not see it connected. " + 
			"Check the control plane to verify s1 is in fact connected to the controller.");
		} else {
			setAllPortsUp(sw);
			message.put("S1 STATUS", "SUCCESS");
			message.put("S1 DETAILS", "Reset s1 ports to enabled/up.");
		}
		
		sw = switchService.getSwitch(dpid3);
		
		if (sw == null) {
			log.error("Could not reset s3 ports. Switch service does not see it connected. Check the control plane to verify s3 is in fact connected to the controller.");
			message.put("S3 STATUS", "ERROR");
			message.put("S3 DETAILS", "Could not reset s3. Switch service does not see it connected. " + 
			"Check the control plane to verify s3 is in fact connected to the controller.");
		} else {
			setAllPortsUp(sw);
			message.put("S3 STATUS", "SUCCESS");
			message.put("S3 DETAILS", "Reset s3 ports to enabled/up.");
		}
		
		return message;
	}

	private boolean learnLinks() {
		/*
		 * Try to learn 1 to 2a and 1 to 2b if not known.
		 */
		if (link_dpid1_to_dpid2a == null || link_dpid1_to_dpid2b == null) {
			Map<DatapathId, Set<Link>> linksByDpid = linkDiscoveryService.getSwitchLinks();
			Set<Link> links = linksByDpid.get(dpid1);
			if (links != null) {
				for (Link link : links) {
					if (link_dpid1_to_dpid2a == null && link.getSrc().equals(dpid1) && link.getDst().equals(dpid2a)) {
						log.info("Learned Link: {}", link.toString());
						link_dpid1_to_dpid2a = link;
					} else if (link_dpid1_to_dpid2b == null && link.getSrc().equals(dpid1) && link.getDst().equals(dpid2b)) {
						log.info("Learned Link: {}", link.toString());
						link_dpid1_to_dpid2b = link;
					}	
				}
			}
		}
		/*
		 * Try to learn 2a to 3 if not known.
		 */
		if (link_dpid2a_to_dpid3 == null) {
			Map<DatapathId, Set<Link>> linksByDpid = linkDiscoveryService.getSwitchLinks();
			Set<Link> links = linksByDpid.get(dpid2a);
			if (links != null) {
				for (Link link : links) {
					if (link.getSrc().equals(dpid2a) && link.getDst().equals(dpid3)) {
						log.info("Learned Link: {}", link.toString());
						link_dpid2a_to_dpid3 = link;
					}
				}
			}
		}
		/*
		 * Try to learn 2b to 3 if not known.
		 */
		if (link_dpid2b_to_dpid3 == null) {
			Map<DatapathId, Set<Link>> linksByDpid = linkDiscoveryService.getSwitchLinks();
			Set<Link> links = linksByDpid.get(dpid2b);
			if (links != null) {
				for (Link link : links) {
					if (link.getSrc().equals(dpid2b) && link.getDst().equals(dpid3)) {
						log.info("Learned Link: {}", link.toString());
						link_dpid2b_to_dpid3 = link;
					}
				}
			}
		}

		/*
		 * Only if all links are known, return true.
		 */
		if (link_dpid1_to_dpid2a == null || link_dpid1_to_dpid2b == null
				|| link_dpid2a_to_dpid3 == null || link_dpid2b_to_dpid3 == null) {
			return false;
		} else {
			return true;
		}
	}

	private void sendBarrier(IOFSwitch sw) {
		OFBarrierRequest barrierRequest = sw.getOFFactory().buildBarrierRequest()
				.build();
		ListenableFuture<OFBarrierReply> future = sw.writeRequest(barrierRequest);
		try {
			future.get(10, TimeUnit.SECONDS); /* If successful, we can discard the reply. */
		} catch (InterruptedException | ExecutionException
				| TimeoutException e) {
			log.error("Switch {} doesn't support barrier messages? OVS should.", sw.toString());
		}
	}

	private boolean insertFlows() {
		boolean pushed = false;

		if (!dpid2a_has_flows) {
			IOFSwitch sw2a = switchService.getSwitch(dpid2a);
			OFFlowDelete flowDelete = sw2a.getOFFactory().buildFlowDelete()
					.setCookie(cookie)
					.setCookieMask(U64.NO_MASK)
					.build();
			sw2a.write(flowDelete);

			sendBarrier(sw2a);

			/* ARP and IPv4 from sw2a to sw3 */
			OFFlowAdd flowAdd = sw2a.getOFFactory().buildFlowAdd()
					.setCookie(cookie)
					.setHardTimeout(0)
					.setIdleTimeout(0)
					.setPriority(FlowModUtils.PRIORITY_MAX)
					.setMatch(sw2a.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, link_dpid1_to_dpid2a.getDstPort())
							.build())
							.setActions(Collections.singletonList((OFAction) sw2a.getOFFactory().actions().buildOutput()
									.setMaxLen(0xffFFffFF)
									.setPort(link_dpid2a_to_dpid3.getSrcPort())
									.build()))
									.build();
			sw2a.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw2a.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, link_dpid1_to_dpid2a.getDstPort())
							.build())
							.build();
			sw2a.write(flowAdd);

			/* ARP and IPv4 from sw3 to sw2a */
			flowAdd = flowAdd.createBuilder()
					.setMatch(sw2a.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, link_dpid2a_to_dpid3.getSrcPort())
							.build())
							.setActions(Collections.singletonList((OFAction) sw2a.getOFFactory().actions().buildOutput()
									.setMaxLen(0xffFFffFF)
									.setPort(link_dpid1_to_dpid2a.getDstPort())
									.build()))
									.build();
			sw2a.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw2a.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, link_dpid2a_to_dpid3.getSrcPort())
							.build())
							.build();
			sw2a.write(flowAdd);

			log.info("Inserted flows for switch {}", dpid2a.toString());
			dpid2a_has_flows = true;
		}

		if (!dpid2b_has_flows) {
			IOFSwitch sw2b = switchService.getSwitch(dpid2b);
			OFFlowDelete flowDelete = sw2b.getOFFactory().buildFlowDelete()
					.setCookie(cookie)
					.setCookieMask(U64.NO_MASK)
					.build();
			sw2b.write(flowDelete);

			sendBarrier(sw2b);

			/* ARP and IPv4 from sw2a to sw3 */
			OFFlowAdd flowAdd = sw2b.getOFFactory().buildFlowAdd()
					.setCookie(cookie)
					.setHardTimeout(0)
					.setIdleTimeout(0)
					.setPriority(FlowModUtils.PRIORITY_MAX)
					.setMatch(sw2b.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, link_dpid1_to_dpid2b.getDstPort())
							.build())
							.setActions(Collections.singletonList((OFAction) sw2b.getOFFactory().actions().buildOutput()
									.setMaxLen(0xffFFffFF)
									.setPort(link_dpid2b_to_dpid3.getSrcPort())
									.build()))
									.build();
			sw2b.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw2b.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, link_dpid1_to_dpid2b.getDstPort())
							.build())
							.build();
			sw2b.write(flowAdd);

			/* ARP and IPv4 from sw3 to sw2a */
			flowAdd = flowAdd.createBuilder()
					.setMatch(sw2b.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, link_dpid2b_to_dpid3.getSrcPort())
							.build())
							.setActions(Collections.singletonList((OFAction) sw2b.getOFFactory().actions().buildOutput()
									.setMaxLen(0xffFFffFF)
									.setPort(link_dpid1_to_dpid2b.getDstPort())
									.build()))
									.build();
			sw2b.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw2b.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, link_dpid2b_to_dpid3.getSrcPort())
							.build())
							.build();
			sw2b.write(flowAdd);

			log.info("Inserted flows for switch {}", dpid2b.toString());
			dpid2b_has_flows = true;
			pushed = true;
		}

		if (!dpid1_has_flows) {
			IOFSwitch sw1 = switchService.getSwitch(dpid1);
			OFFlowDelete flowDelete = sw1.getOFFactory().buildFlowDelete()
					.setCookie(cookie)
					.setCookieMask(U64.NO_MASK)
					.build();
			sw1.write(flowDelete);

			OFGroupDelete groupDelete = sw1.getOFFactory().buildGroupDelete()
					.setGroup(OFGroup.ANY)
					.setGroupType(OFGroupType.FF)
					.build();
			sw1.write(groupDelete);

			sendBarrier(sw1);

			/* Add the group: fast-failover watching ports leading to dpid2a and dpid2b */
			ArrayList<OFBucket> buckets = new ArrayList<OFBucket>(2);
			buckets.add(sw1.getOFFactory().buildBucket()
					.setWatchPort(link_dpid1_to_dpid2a.getSrcPort())
					.setWatchGroup(OFGroup.ZERO)
					.setActions(Collections.singletonList((OFAction) sw1.getOFFactory().actions().buildOutput()
							.setMaxLen(0xffFFffFF)
							.setPort(link_dpid1_to_dpid2a.getSrcPort())
							.build()))
							.build());
			buckets.add(sw1.getOFFactory().buildBucket()
					.setWatchPort(link_dpid1_to_dpid2b.getSrcPort())
					.setWatchGroup(OFGroup.ZERO)
					.setActions(Collections.singletonList((OFAction) sw1.getOFFactory().actions().buildOutput()
							.setMaxLen(0xffFFffFF)
							.setPort(link_dpid1_to_dpid2b.getSrcPort())
							.build()))
							.build());
			OFGroupAdd groupAdd = sw1.getOFFactory().buildGroupAdd()
					.setGroup(OFGroup.of(1))
					.setGroupType(OFGroupType.FF)
					.setBuckets(buckets)
					.build();
			sw1.write(groupAdd);

			/* ARP and IPv4 from sw1 to group1 */
			OFFlowAdd flowAdd = sw1.getOFFactory().buildFlowAdd()
					.setCookie(cookie)
					.setHardTimeout(0)
					.setIdleTimeout(0)
					.setPriority(FlowModUtils.PRIORITY_MAX)
					.setMatch(sw1.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, getHostPort(sw1))
							.build())
							.setActions(Collections.singletonList((OFAction) sw1.getOFFactory().actions().buildGroup()
									.setGroup(OFGroup.of(1))
									.build()))
									.build();

			sw1.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw1.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, getHostPort(sw1))
							.build())
							.build();
			sw1.write(flowAdd);

			/* ARP and IPv4 from sw2a to host */
			flowAdd = flowAdd.createBuilder()
					.setMatch(sw1.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, link_dpid1_to_dpid2a.getSrcPort())
							.build())
							.setActions(Collections.singletonList((OFAction) sw1.getOFFactory().actions().buildOutput()
									.setMaxLen(0xffFFffFF)
									.setPort(getHostPort(sw1))
									.build()))
									.build();
			sw1.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw1.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, link_dpid1_to_dpid2a.getSrcPort())
							.build())
							.build();
			sw1.write(flowAdd);

			/* ARP and IPv4 from sw2b to host */
			flowAdd = flowAdd.createBuilder()
					.setMatch(sw1.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, link_dpid1_to_dpid2b.getSrcPort())
							.build())
							.build();
			sw1.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw1.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, link_dpid1_to_dpid2b.getSrcPort())
							.build())
							.build();
			sw1.write(flowAdd);

			log.info("Inserted flows for switch {}", dpid1.toString());
			dpid1_has_flows = true;
			pushed = true;
		}

		if (!dpid3_has_flows) {
			IOFSwitch sw3 = switchService.getSwitch(dpid3);
			OFFlowDelete flowDelete = sw3.getOFFactory().buildFlowDelete()
					.setCookie(cookie)
					.setCookieMask(U64.NO_MASK)
					.build();
			sw3.write(flowDelete);

			OFGroupDelete groupDelete = sw3.getOFFactory().buildGroupDelete()
					.setGroup(OFGroup.ANY)
					.setGroupType(OFGroupType.FF)
					.build();
			sw3.write(groupDelete);

			sendBarrier(sw3);

			/* Add the group: fast-failover watching ports leading to dpid2a and dpid2b */
			ArrayList<OFBucket> buckets = new ArrayList<OFBucket>(2);
			buckets.add(sw3.getOFFactory().buildBucket()
					.setWatchPort(link_dpid2a_to_dpid3.getDstPort())
					.setWatchGroup(OFGroup.ZERO)
					.setActions(Collections.singletonList((OFAction) sw3.getOFFactory().actions().buildOutput()
							.setMaxLen(0xffFFffFF)
							.setPort(link_dpid2a_to_dpid3.getDstPort())
							.build()))
							.build());
			buckets.add(sw3.getOFFactory().buildBucket()
					.setWatchPort(link_dpid2b_to_dpid3.getDstPort())
					.setWatchGroup(OFGroup.ZERO)
					.setActions(Collections.singletonList((OFAction) sw3.getOFFactory().actions().buildOutput()
							.setMaxLen(0xffFFffFF)
							.setPort(link_dpid2b_to_dpid3.getDstPort())
							.build()))
							.build());
			OFGroupAdd groupAdd = sw3.getOFFactory().buildGroupAdd()
					.setGroup(OFGroup.of(1))
					.setGroupType(OFGroupType.FF)
					.setBuckets(buckets)
					.build();
			sw3.write(groupAdd);

			/* ARP and IPv4 from s3 to group1 */
			OFFlowAdd flowAdd = sw3.getOFFactory().buildFlowAdd()
					.setCookie(cookie)
					.setHardTimeout(0)
					.setIdleTimeout(0)
					.setPriority(FlowModUtils.PRIORITY_MAX)
					.setMatch(sw3.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, getHostPort(sw3))
							.build())
							.setActions(Collections.singletonList((OFAction) sw3.getOFFactory().actions().buildGroup()
									.setGroup(OFGroup.of(1))
									.build()))
									.build();

			sw3.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw3.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, getHostPort(sw3))
							.build())
							.build();
			sw3.write(flowAdd);

			/* ARP and IPv4 from s2a to host */
			flowAdd = flowAdd.createBuilder()
					.setMatch(sw3.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, link_dpid2a_to_dpid3.getDstPort())
							.build())
							.setActions(Collections.singletonList((OFAction) sw3.getOFFactory().actions().buildOutput()
									.setMaxLen(0xffFFffFF)
									.setPort(getHostPort(sw3))
									.build()))
									.build();
			sw3.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw3.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, link_dpid2a_to_dpid3.getDstPort())
							.build())
							.build();
			sw3.write(flowAdd);

			/* ARP and IPv4 from s2b to host */
			flowAdd = flowAdd.createBuilder()
					.setMatch(sw3.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.ARP)
							.setExact(MatchField.IN_PORT, link_dpid2b_to_dpid3.getDstPort())
							.build())
							.build();
			sw3.write(flowAdd);

			flowAdd = flowAdd.createBuilder()
					.setMatch(sw3.getOFFactory().buildMatch()
							.setExact(MatchField.ETH_TYPE, EthType.IPv4)
							.setExact(MatchField.IN_PORT, link_dpid2b_to_dpid3.getDstPort())
							.build())
							.build();
			sw3.write(flowAdd);

			log.info("Inserted flows for switch {}", dpid3.toString());
			dpid3_has_flows = true;
			pushed = true;
		}

		return pushed;
	}

	private OFPort getHostPort(IOFSwitch sw) {
		if (sw.getId().equals(dpid1)) {
			OFPort port = null;
			Iterator<OFPortDesc> itr = sw.getPorts().iterator();
			while (itr.hasNext()) {
				OFPortDesc portDesc = itr.next();
				if (!portDesc.getPortNo().equals(link_dpid1_to_dpid2a.getSrcPort())
						&& !portDesc.getPortNo().equals(link_dpid1_to_dpid2b.getSrcPort())
						&& !portDesc.getPortNo().equals(OFPort.LOCAL)) {
					port = portDesc.getPortNo();
					break;
				}
			}

			if (port != null) {
				return port;
			} else {
				log.error("Error locating port on switch {}. Possible ports: {}", sw.getId().toString(), sw.getPorts().toString());
				throw new IllegalArgumentException("Could not find host port on switch. Switch=" + sw.getId().toString());
			}
		} else if (sw.getId().equals(dpid3)) {
			OFPort port = null;
			Iterator<OFPortDesc> itr = sw.getPorts().iterator();
			while (itr.hasNext()) {
				OFPortDesc portDesc = itr.next();
				if (!portDesc.getPortNo().equals(link_dpid2a_to_dpid3.getDstPort())
						&& !portDesc.getPortNo().equals(link_dpid2b_to_dpid3.getDstPort())
						&& !portDesc.getPortNo().equals(OFPort.LOCAL)) {
					port = portDesc.getPortNo();
					break;
				}
			}

			if (port != null) {
				return port;
			} else {
				log.error("Error locating port on switch {}. Possible ports: {}", sw.getId().toString(), sw.getPorts().toString());
				throw new IllegalArgumentException("Could not find host port on switch. Switch=" + sw.getId().toString());
			}
		} else {
			log.error("We don't have a host on switch {}.", sw.getId().toString());
			throw new IllegalArgumentException("Invalid switch for host. Switch=" + sw.getId().toString());
		}
	}

	private void setAllPortsUp(IOFSwitch sw) {
		if (sw == null) {
			log.error("Switch was null while setting ports up. That's weird.");
			return;
		} else if (sw.getId().equals(dpid1) || sw.getId().equals(dpid3)) {
			Collection<OFPortDesc> ports = sw.getPorts();
			for (OFPortDesc port : ports) {
				if (!port.getPortNo().equals(OFPort.LOCAL)) {
					/* Set up */
					OFPortMod portMod = sw.getOFFactory().buildPortMod()
							.setPortNo(port.getPortNo())
							.setConfig(0) /* All zeros is the default config w/no flags set */
							.setMask(portDown(sw)) /* Use port down mask to set it to value above --> UP */
							.setHwAddr(port.getHwAddr())
							.build();
					sw.write(portMod);
				}
			}
		}
	}

	private long portDown(IOFSwitch sw) {
		long config = 0;
		switch (sw.getOFFactory().getVersion()) {
		case OF_10:
			config = OFPortConfigSerializerVer10.toWireValue(Collections.singleton(OFPortConfig.PORT_DOWN));
			break;
		case OF_11:
			config = OFPortConfigSerializerVer11.toWireValue(Collections.singleton(OFPortConfig.PORT_DOWN));
			break;
		case OF_12:
			config = OFPortConfigSerializerVer12.toWireValue(Collections.singleton(OFPortConfig.PORT_DOWN));
			break;
		case OF_13:
			config = OFPortConfigSerializerVer13.toWireValue(Collections.singleton(OFPortConfig.PORT_DOWN));
			break;
		case OF_14:
			config = OFPortConfigSerializerVer14.toWireValue(Collections.singleton(OFPortConfig.PORT_DOWN));
			break;
		default:
			log.error("Bad OFVersion {}", sw.getOFFactory().getVersion().toString());
			break;
		}
		return config;
	}

	private void usePathA() {
		/*
		 * Take down path B ports at s1 and s3
		 * Bring up path A ports at s1 and s3
		 */
		IOFSwitch sw = switchService.getSwitch(dpid1);
		/* Set down */
		OFPortMod portMod = sw.getOFFactory().buildPortMod()
				.setPortNo(link_dpid1_to_dpid2b.getSrcPort())
				.setConfig(portDown(sw))
				.setMask(portDown(sw))
				.setHwAddr(sw.getPort(link_dpid1_to_dpid2b.getSrcPort()).getHwAddr())
				.build();
		sw.write(portMod);
		/* Set up */
		portMod = sw.getOFFactory().buildPortMod()
				.setPortNo(link_dpid1_to_dpid2a.getSrcPort())
				.setConfig(0)
				.setMask(portDown(sw))
				.setHwAddr(sw.getPort(link_dpid1_to_dpid2a.getSrcPort()).getHwAddr())
				.build();
		sw.write(portMod);

		sw = switchService.getSwitch(dpid3);
		/* Set down */
		portMod = sw.getOFFactory().buildPortMod()
				.setPortNo(link_dpid2b_to_dpid3.getDstPort())
				.setConfig(portDown(sw))
				.setMask(portDown(sw))
				.setHwAddr(sw.getPort(link_dpid2b_to_dpid3.getDstPort()).getHwAddr())
				.build();
		sw.write(portMod);
		/* Set up */
		portMod = sw.getOFFactory().buildPortMod()
				.setPortNo(link_dpid2a_to_dpid3.getDstPort())
				.setConfig(0)
				.setMask(portDown(sw))
				.setHwAddr(sw.getPort(link_dpid2a_to_dpid3.getDstPort()).getHwAddr())
				.build();
		sw.write(portMod);

		log.info("Took down ports on path sw1--sw2b--sw3 and brought up ports on sw1--sw2a--sw3");
	}

	private void usePathB() {
		/*
		 * Take down path A ports at s1 and s3
		 * Bring up path B ports at s1 and s3
		 */
		IOFSwitch sw = switchService.getSwitch(dpid1);
		/* Set down */
		OFPortMod portMod = sw.getOFFactory().buildPortMod()
				.setPortNo(link_dpid1_to_dpid2a.getSrcPort())
				.setConfig(portDown(sw))
				.setMask(portDown(sw))
				.setHwAddr(sw.getPort(link_dpid1_to_dpid2a.getSrcPort()).getHwAddr())
				.build();
		sw.write(portMod);
		/* Set up */
		portMod = sw.getOFFactory().buildPortMod()
				.setPortNo(link_dpid1_to_dpid2b.getSrcPort())
				.setConfig(0)
				.setMask(portDown(sw))
				.setHwAddr(sw.getPort(link_dpid1_to_dpid2b.getSrcPort()).getHwAddr())
				.build();
		sw.write(portMod);

		sw = switchService.getSwitch(dpid3);
		/* Set down */
		portMod = sw.getOFFactory().buildPortMod()
				.setPortNo(link_dpid2a_to_dpid3.getDstPort())
				.setConfig(portDown(sw))
				.setMask(portDown(sw))
				.setHwAddr(sw.getPort(link_dpid2a_to_dpid3.getDstPort()).getHwAddr())
				.build();
		sw.write(portMod);
		/* Set up */
		portMod = sw.getOFFactory().buildPortMod()
				.setPortNo(link_dpid2b_to_dpid3.getDstPort())
				.setConfig(0)
				.setMask(portDown(sw))
				.setHwAddr(sw.getPort(link_dpid2b_to_dpid3.getDstPort()).getHwAddr())
				.build();
		sw.write(portMod);

		log.info("Took down ports on path sw1--sw2a--sw3 and brought up ports on sw1--sw2b--sw3");
	}
}
