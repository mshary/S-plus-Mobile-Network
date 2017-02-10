/* #### Proxy Gateway Controller #### 
 * This class defines proxy gateway controller
 * and adds / removes up-link and down-link flow
 * rules of proxy gateway switch as well as
 * allocates IP for UE.
 */
package net.floodlightcontroller.splus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.VlanVid;

public class PGWC implements IFloodlightModule {
	protected static IOFSwitchService switchService;

	int numIPs;
	int tunnel_id;
	int pgw_pdn_port;
	volatile String ip;
	
	Queue<String> reuseable_ip_pool;
	Queue<Integer> reuseable_te_id_pool;

	public PGWC() {
		numIPs = 0;
		tunnel_id = 4000;

		// proxy gateway port which is connected to PDN
		pgw_pdn_port = Constants.PGW_PDN_PORT;
		ip = Constants.STARTING_UE_IP;
		
		reuseable_te_id_pool = new LinkedList<Integer>();
		reuseable_ip_pool = new LinkedList<String>();
	};

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		System.out.println("--- Initializing Proxy Gateway Controller Service ---");
		switchService = context.getServiceImpl(IOFSwitchService.class);
	};

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		// TODO Auto-generated method stub
		System.out.println("--- Starting up Proxy Gateway Controller Service ---");
	};
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	};

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	};

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// TODO Auto-generated method stub
		return null;
	};

	public String[] getIPFragments(String ipAddr) {
		return ipAddr.split("\\.");
	};
	
	public String getPGWStartIP() {
		return this.ip;
	};

	/* This method generates the next IP address in sequence given the current IP address. */
	public String getNextIP(String ipAddr) throws IllegalArgumentException {
		String[] octets = getIPFragments(ipAddr);
		
		if (octets != null && octets.length == 4) {
			Integer octet1 = Integer.parseInt(octets[0]);
			Integer octet2 = Integer.parseInt(octets[1]);
			Integer octet3 = Integer.parseInt(octets[2]);
			Integer octet4 = Integer.parseInt(octets[3]);
		
			if (octet4 < 255) {
				return octet1 + "." + octet2 + "." + octet3 + "." + (++octet4);
			} else if (octet4 == 255) {
				if (octet3 < 255) {
					return octet1 + "." + octet2 + "." + (++octet3) + ".0";
				} else if (octet3 == 255) {
					if (octet2 < 255) {
						return octet1 + "." + (++octet2) + ".0.0";
					} else if (octet2 == 255) {
						if (octet1 < 255) {
							return (++octet1) + ".0.0.0";
						} else if (octet1 == 255) {
							throw new IllegalArgumentException("IP Range Exceeded: '" + ipAddr + "'");
						};
					};
				};
			};
		};

		throw new IllegalArgumentException("Bad or Invalid IP Address: '" + ipAddr + "'");
	};

	/* This method generates and allocates the UE IP along with PGW Tunnel ID for specific UE session */
	String allocateIPForUE(IOFSwitch pgw_switch, int sgw_tunnel_id, DatapathId sgw_dispatch_id, DatapathId pgw_dispatch_id, String apn) {
		int pgw_te_id;

		// check is reusing old tunnel id possible
		if(reuseable_te_id_pool.isEmpty()) {
			if(tunnel_id < 0 || tunnel_id > 4000) { tunnel_id = 4000; };
			pgw_te_id = tunnel_id;
			tunnel_id--;
			
		} else {
			pgw_te_id = reuseable_te_id_pool.remove();		
			if(Constants.DEBUG) {
				System.out.println("PGW-C Reusing Tunnel Endpoint ID: '" + pgw_te_id + "'");
			};
		};
		
		try {
			if(reuseable_ip_pool.isEmpty()) {
				this.ip = getNextIP(ip);
				numIPs++;
	
			} else {
				this.ip = reuseable_ip_pool.remove();
				if(Constants.DEBUG) {
					System.out.println("PGW-C Reusing IP: '" + ip + "', IP Count: '" + numIPs + "'");
				};
			};
			
		} catch (Exception e) {
			e.printStackTrace();
		};
		
		if(Constants.DEBUG) {
			System.out.println("PGW-D Tunnel ID of UE '" + apn + "' is '" + pgw_te_id + "'");
		};
		
		// install up-link and down-link rules
		installPGWRules(pgw_switch, pgw_dispatch_id, Constants.PGW_SGW_PORT_MAP.get(pgw_dispatch_id.getLong() + Constants.SEPARATOR + sgw_dispatch_id.getLong()), pgw_te_id, sgw_tunnel_id, pgw_pdn_port, apn, ip);
		return ip + Constants.SEPARATOR + pgw_te_id;
	};

	/* This method deletes up-link and down-link flow rules from Proxy Gateway Switch (PGW-D) */
	public boolean detachUEFromPGW(IOFSwitch pgw_switch, DatapathId sgw_dispatch_id, DatapathId pgw_dispatch_id, int pgw_te_id, String ue_ip) {
		int pgw_port = Constants.PGW_SGW_PORT_MAP.get(pgw_dispatch_id.getLong() + Constants.SEPARATOR + sgw_dispatch_id.getLong());

		// delete up-link rule
		deleteFlowRuleWithTEID(pgw_switch, pgw_port, pgw_te_id, ue_ip);

		if(Constants.DEBUG) {
			System.out.println("PGW-C deleting up-link rule with PGW-D Tunnel Endpoint ID: '" + pgw_te_id + "' and Port: '" + pgw_port + "' for UE with IP: '" + ue_ip + "'");
		};

		// delete down-link rule
		deleteFlowRuleWithIP(pgw_switch, pgw_pdn_port, ue_ip);
		
		if(Constants.DEBUG) {
			System.out.println("PGW-C deleting down-link rule with PGW-D Tunnel Endpoint ID: '" + pgw_te_id + "' and Port: '" + pgw_pdn_port + "' for UE with IP: '" + ue_ip + "'");
		};
		
		reuseable_ip_pool.add(ue_ip);
		return true;
	};
	
	/* This method deletes down-link rule */
	private void deleteFlowRuleWithIP(IOFSwitch switch_id, int switch_port, String ue_ip) {
		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowDelete();
		Match.Builder mb = switch_id.getOFFactory().buildMatch();

		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
		.setExact(MatchField.IN_PORT, OFPort.of(switch_port))
		.setExact(MatchField.IPV4_DST, IPv4Address.of(ue_ip));

		fmb.setMatch(mb.build());
		switch_id.write(fmb.build());
	};
	
	/* This method deletes up-link rule */
	private void deleteFlowRuleWithTEID(IOFSwitch switch_id, int switch_port, int pgw_te_id, String src_ip){
		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowDelete();
		Match.Builder mb = switch_id.getOFFactory().buildMatch();

		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
		.setExact(MatchField.IN_PORT, OFPort.of(switch_port))
		.setExact(MatchField.IPV4_SRC, IPv4Address.of(src_ip))
		.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(VlanVid.ofVlan(pgw_te_id)));

		fmb.setMatch(mb.build());
		switch_id.write(fmb.build());
	};

	/* This method installs up-link and down-link flow rules on Proxy Gateway Switch (PGW-D) */
	private void installPGWRules(IOFSwitch pgw, DatapathId pgw_dispatch_id, int in_port, int pgw_tunnel_id, int sgw_tunnel_id, int out_port, String apn, String ue_ip) {
		// up-link rule (PGW -> PDN)
		if(Constants.DEBUG) {
			System.out.println("PGW-C installing up-link rule on PGW-D Dispatch ID: '" + pgw_dispatch_id.getLong() + "' with In-Port: '" + in_port + "' and In-Tunnel Endpoint ID: '" + pgw_tunnel_id + 
					"' to Out-Port: '" + out_port + "' and Out-Tunnel Endpoint ID: '" + pgw_tunnel_id + "' of UE: '" + apn + "'");
		};
		
		installFlowRule(pgw, pgw_dispatch_id, in_port, pgw_tunnel_id, out_port, pgw_tunnel_id, ue_ip, Constants.PDN_IP);

		// down-link rule (PDN to PGW)
		if(Constants.DEBUG) {
			System.out.println("PGW-C installing down-link rule on PGW-D Dispatch ID: '" + pgw_dispatch_id.getLong() + "' with In-Port: '" + out_port + "' and Source IP: '" + ue_ip + 
					"' to Out-Port: '" + in_port + "' and Out-Tunnel Endpoint ID: '" + sgw_tunnel_id + " with Source IP: '" + Constants.PDN_IP + "' of UE: '" + apn + "'");
		};
		
		installFlowRuleWithIP(pgw, pgw_dispatch_id, out_port, in_port, sgw_tunnel_id, ue_ip, Constants.PDN_IP, Constants.SGWD_IP_DOWNLINK);
	};

	/* This method installs up-link rule */
	private void installFlowRule(IOFSwitch switch_id, DatapathId dispatch_id, int in_port, int in_tunnel_id, int out_port, int out_tunnel_id, String src_ip, String dst_ip) {
		if(Constants.DEBUG) {
			System.out.println("Installing up-link rule at Switch Service: '" + switchService + "' and Dispatch ID '" + dispatch_id + "'");
		};

		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowAdd();
		Match.Builder mb = switch_id.getOFFactory().buildMatch();

		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
		.setExact(MatchField.IN_PORT, OFPort.of(in_port))
		.setExact(MatchField.IPV4_SRC, IPv4Address.of(src_ip))
		.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(VlanVid.ofVlan(in_tunnel_id)));

		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(switch_id.getOFFactory().actions().stripVlan()); // Out-Tunnel-ID
		
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

	/* This method installs down-link rule */
	private void installFlowRuleWithIP(IOFSwitch switch_id, DatapathId distpach_id, int in_port, int out_port, int out_tunnel_id, String ue_ip, String pdn_ip, String sgw_ip) {
		if(Constants.DEBUG) {
			System.out.println("Installing down-link rule at Switch Service: '"+ switchService + "' and  Dispatch ID: '" + distpach_id + "'");
		};

		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowAdd();
		Match.Builder mb = switch_id.getOFFactory().buildMatch();

		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
		.setExact(MatchField.IN_PORT, OFPort.of(in_port))
		.setExact(MatchField.IPV4_DST, IPv4Address.of(ue_ip));

		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(switch_id.getOFFactory().actions().setVlanVid(VlanVid.ofVlan(out_tunnel_id)));
		actions.add(switch_id.getOFFactory().actions().setNwDst(IPv4Address.of(sgw_ip)));
		actions.add(switch_id.getOFFactory().actions().output(OFPort.of(out_port), Integer.MAX_VALUE));
		fmb.setActions(actions);

		fmb.setHardTimeout(0)
		.setIdleTimeout(0)
		.setPriority(1)
		.setBufferId(OFBufferId.NO_BUFFER)
		.setMatch(mb.build());

		switch_id.write(fmb.build());
	};
}
