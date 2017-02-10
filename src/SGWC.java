/* #### Serving Gateway Controller ####
 * This class defines serving gateway controller service.
 * It adds / removes up-link and down-link flow rules for
 * serving gateway switch as well as connects with PGW-C
 * on behalf of MME to allocate IP and flow rules on PGW-D
 */
package net.floodlightcontroller.splus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.module.ModuleLoaderResource;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.VlanVid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SGWC implements IFloodlightModule, IOFMessageListener {
	protected static Logger log = LoggerFactory.getLogger(SGWC.class);

	private IFloodlightProviderService floodlightProvider;

	int tunnel_id;
	PGWC pgw_controller;
	ModuleLoaderResource module_resource;
	
	Map<Integer, Integer> SGW_PGW_TE_ID_MAP;
	Queue<Integer> reuseable_te_pool;

	public SGWC() {
		tunnel_id = 1;
		pgw_controller = new PGWC();
		module_resource = new ModuleLoaderResource();
		
		SGW_PGW_TE_ID_MAP = new HashMap<Integer, Integer>();
		reuseable_te_pool = new LinkedList<Integer>();
	};

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		System.out.println("--- Initializing Serving Gateway Controller Service ---");
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
	};

	@Override
	public void startUp(FloodlightModuleContext context) {
		System.out.println("--- Starting up Serving Gateway Controller Service ---");
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
	};

	@Override
	public String getName() {
		return SGWC.class.getPackage().getName();
	};

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// We don't provide any services, return null
		return null;
	};

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// We don't provide any services, return null
		return null;
	};

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> ifls = new ArrayList<Class<? extends IFloodlightService>>();
		ifls.add(IFloodlightProviderService.class);
		return ifls;
	};
	
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	};

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	};

	public void setFloodlightProvider(IFloodlightProviderService floodlightProvider) {
		this.floodlightProvider = floodlightProvider;
	}

	/*
	 * This method is invoked by MME to establish the data tunnel between SGW-D and PGW-D
	 * by installing flow rules specific to the UE on SGW-D (for up-link data traffic)
	 * Here PGW-C also allocated an IP address for the UE. This IP address will be passed on to UE via SGW-C,
	 * MME and eNodeB in sequence.
	 */
	String contactPGW(IOFSwitch sgw_id, IOFSwitch pgw_id, DatapathId sgw_dispatch_id, DatapathId pgw_dispatch_id, String apn) {
		String[] data_segments = null;
		int pgw_te_id, sgw_te_id;
		
		if(reuseable_te_pool.isEmpty()) {
			if(tunnel_id > 4000) { tunnel_id = 1; };
			sgw_te_id = tunnel_id;
			tunnel_id++;
			
		} else {
			sgw_te_id = reuseable_te_pool.remove();
			if(Constants.DEBUG) {
				System.out.println("SGW-C reusing Tunnel Endpoing ID: '" + sgw_te_id + "'");
			};
		};

		if(Constants.DEBUG){
			System.out.println("Using PGW-D: '" + pgw_dispatch_id.getLong() + "' and SGW Tunnel Endpoint ID: '" + sgw_te_id + "' for UE: '" + apn + "'");
		};

		/* Request PGW-C to allocate an IP address for UE and tunnel end-point id for SGW-C
		 * The return format will be "UE_IP_ADDRESS + SEPARATOR + PGW tunnel ID for this UE" 
		 */
		String ip_pgw = pgw_controller.allocateIPForUE(pgw_id, sgw_te_id, sgw_dispatch_id, pgw_dispatch_id,  apn);
		data_segments = ip_pgw.split(Constants.SEPARATOR);

		// up-link rule (SGW-D to PGW-D)
		if(Constants.DEBUG){
			System.out.println("SGW-C installing up-link rule on SGW-D Dispatch ID: '" + sgw_dispatch_id.getLong() + "' and In-Port: '" + Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[0] + "' in Tunnel Endpoint ID: '" + sgw_te_id + 
				"' to Out-Port: '" + Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[1] + "' and Out-Tunnel Endpoint ID: '" + data_segments[1] + "' of UE: '" + apn + "'");
		};
		
		pgw_te_id = Integer.parseInt(data_segments[1]);
		SGW_PGW_TE_ID_MAP.put(sgw_te_id, pgw_te_id);

		// up-link rule (SGW to PGW)
		installFlowRule(sgw_id, sgw_dispatch_id, Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[0], sgw_te_id, Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[1], pgw_te_id, data_segments[0], Constants.PGWD_IP_UPLINK);
		return data_segments[0] + Constants.SEPARATOR + sgw_te_id;
	};

	/* This method installs down-link flow rule between SGW-D and eNodeB after knowing the UE generated tunnel for eNodeB */
	public void modifyBearerRequest(IOFSwitch sgw_id, DatapathId sgw_dispatch_id, int sgw_te_id, int ue_te_id, String key) {
		if(Constants.DEBUG) {
			System.out.println("SGW-C installing downlink rule on SGW-D Dispatch ID: '" + sgw_dispatch_id.getLong() + "' and In-Port: '" + Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[1] + "' in Tunnel Endpoint ID: '" + sgw_te_id + 
				"' to Out-Port: '" + Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[0] + "' and  Out-Tunnel Endpoing ID: '" + ue_te_id + "' of UE with Key: '" + key + "'");
		};

		// down-link rule (SGW to ENodeB)
		installFlowRule(sgw_id, sgw_dispatch_id, Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[1], sgw_te_id, Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[0], ue_te_id, Constants.PDN_IP, Constants.ENODEB_SW_IP_DOWNLINK);
	};

	/* This is a utility method requiring to know the starting IP address PGW-C will be using */
	public String getStartingIPAddress() {
		return pgw_controller.getPGWStartIP();
	};

	/* This method detaches the tunnel between SGW-D and eNodeB, by deleting the up-link and down-link rules on SGW-D */
	public boolean detachUEFromSGW(IOFSwitch sgw_id, IOFSwitch pgw_id, DatapathId sgw_dispatch_id, DatapathId pgw_dispatch_id, int sgw_te_id, String ue_ip){
		int pgw_te_id = SGW_PGW_TE_ID_MAP.get(sgw_te_id);
		SGW_PGW_TE_ID_MAP.remove(sgw_te_id);

		// delete up-link rule
		deleteFlowRuleWithTEID(sgw_id, Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[0], sgw_te_id, ue_ip);
		if(Constants.DEBUG) {
			System.out.println("SGW-C deleting uplink rule with PGW-D Tunnel Endpoint ID: '" + pgw_te_id + "' for UE with IP: '" + ue_ip + "'");
		};

		// delete down-link rule
		deleteFlowRuleWithTEID(sgw_id, Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[1], sgw_te_id, Constants.PDN_IP);
		if(Constants.DEBUG) {
			System.out.println("SGW-C deleting downlink rule with SGW-D Tunnel Eendpoint ID: '" + sgw_te_id + "' for UE with IP: '" + ue_ip + "'");
		};

		return pgw_controller.detachUEFromPGW(pgw_id, sgw_dispatch_id, pgw_dispatch_id, pgw_te_id, ue_ip);
	};

	/* This method is used to simulate UE idle timeout after which we delete the down-link rule between SGW-D and eNodeB */
	public void releaseAccessBearersRequest(IOFSwitch sgw_id, DatapathId sgw_dispatch_id, int sgw_te_id, String ue_ip){
		// delete down-link rule
		deleteFlowRuleWithTEID(sgw_id, Constants.SGW_PORT_MAP.get(sgw_dispatch_id)[1], sgw_te_id, Constants.PDN_IP);
		if(Constants.DEBUG){
			System.out.println("SGW-C deleting downlink rule with SGW-D Tunnel Endpoint ID: '" + sgw_te_id + "' for UE with IP: '" + ue_ip + "'");
		};
	};

	/* This method helps to delete the flow rule on SGW-D with matching TEID */
	private void deleteFlowRuleWithTEID(IOFSwitch switch_id, int in_port, int ue_te_id, String src_ip) {
		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowDelete();
		Match.Builder mb = switch_id.getOFFactory().buildMatch();

		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
		.setExact(MatchField.IN_PORT, OFPort.of(in_port))
		.setExact(MatchField.IPV4_SRC, IPv4Address.of(src_ip))
		.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(VlanVid.ofVlan(ue_te_id)));
		fmb.setMatch(mb.build());

		// delete the rule from the switch
		switch_id.write(fmb.build());
	};

	/* This method helps to install the flow rule on SGW-D with matching TEID */
	private void installFlowRule(IOFSwitch switch_id, DatapathId dispatch_id, int in_port, int in_tunnel_id, int out_port, int out_tunnel_id, String src_ip, String dst_ip) {
		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowAdd();
		Match.Builder mb = switch_id.getOFFactory().buildMatch();

		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
		.setExact(MatchField.IN_PORT, OFPort.of(in_port))
		.setExact(MatchField.IPV4_SRC, IPv4Address.of(src_ip))
		.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(VlanVid.ofVlan(in_tunnel_id)));

		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(switch_id.getOFFactory().actions().setVlanVid(VlanVid.ofVlan(out_tunnel_id)));

		if(!dst_ip.isEmpty()) {
			actions.add(switch_id.getOFFactory().actions().setNwDst(IPv4Address.of(dst_ip)));
		};

		actions.add(switch_id.getOFFactory().actions().output(OFPort.of(out_port), Integer.MAX_VALUE));
		fmb.setActions(actions);

		fmb.setHardTimeout(0)
		.setIdleTimeout(0)
		.setPriority(1)
		.setBufferId(OFBufferId.NO_BUFFER)
		.setMatch(mb.build());

		switch_id.write(fmb.build());
	};

	public Command receive(IOFSwitch switch_id, OFMessage message, FloodlightContext context) {
		switch (message.getType()) {
			case PACKET_IN:
				if(DatapathId.of(Constants.SGW_DISPATCH_ID).equals(switch_id.getId())) {
					log.info("--- SGW-C received PACKET_IN request from switch {} ---", switch_id);
					return this.processPacketInMessage(switch_id, (OFPacketIn) message, context);
				};
				return Command.CONTINUE;
				
			case ERROR:
				log.info("SGW-C received an error {} from switch {}", message, switch_id);
				return Command.CONTINUE;
				
			default:
				log.error("received an unexpected message {} from switch {}", message, switch_id);
				return Command.CONTINUE;
		}
	};

	private Command processPacketInMessage(IOFSwitch switch_id, OFPacketIn packet, FloodlightContext context) {
		OFPort in_port = (packet.getVersion().compareTo(OFVersion.OF_12) < 0 ? packet.getInPort() : packet.getMatch().get(MatchField.IN_PORT));

		/* Read packet header attributes into Match */
		Match m = createMatchFromPacket(switch_id, in_port, context);
		MacAddress sourceMac = m.get(MatchField.ETH_SRC);
		MacAddress destMac = m.get(MatchField.ETH_DST);
		VlanVid vlan = m.get(MatchField.VLAN_VID) == null ? VlanVid.ZERO : m.get(MatchField.VLAN_VID).getVlanVid();
		IPv4Address srcIP, dstIP;

		if (sourceMac == null) { sourceMac = MacAddress.NONE; };
		if (destMac == null) { destMac = MacAddress.NONE; };
		if (vlan == null) { vlan = VlanVid.ZERO; };

		Ethernet eth = IFloodlightProviderService.bcStore.get(context, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		if(eth.getEtherType() == EthType.IPv4) {
			IPv4 ipPkt = (IPv4)eth.getPayload();
	
			srcIP = ipPkt.getSourceAddress();
			dstIP = ipPkt.getDestinationAddress();

			System.out.println("Processing Packet from Source IP: '"+ srcIP+"' to Dst IP: '" + dstIP + "'");
			//mme.downlinkDataNotification(srcIP.toString());
		};
		return Command.CONTINUE;
	};

	protected Match createMatchFromPacket(IOFSwitch switch_id, OFPort in_port, FloodlightContext context) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(context, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		VlanVid vlan = VlanVid.ofVlan(eth.getVlanID());
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();

		Match.Builder mb = switch_id.getOFFactory().buildMatch();
		mb.setExact(MatchField.IN_PORT, in_port)
		.setExact(MatchField.ETH_SRC, srcMac)
		.setExact(MatchField.ETH_DST, dstMac);

		if (!vlan.equals(VlanVid.ZERO)) { mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(vlan)); };
		return mb.build();
	};
}
