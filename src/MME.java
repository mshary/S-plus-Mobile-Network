/* #### Mobility Management Entity ####
 * This class defines a MME and adds / deletes
 * flow rules for OVSwitch running at eNodeB.
 */
package net.floodlightcontroller.splus;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.SingletonTask;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.PacketParsingException;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.threadpool.IThreadPoolService;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.VlanVid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MME implements IFloodlightModule, IOFMessageListener, IOFSwitchListener {
	protected static Logger log = LoggerFactory.getLogger(MME.class);

	protected SingletonTask discoveryTask;
	protected IOFSwitchService switchService;
	protected IThreadPoolService threadPoolService;

	private HashMap<String, Boolean> ue_state;
	private HashMap<String, String> uekey_ueip_map;
	private HashMap<String, String> uekey_guti_map;
	private HashMap<String, String> imsi_xres_mapping;
	private HashMap<String, String> uekey_sgw_te_id_map;
	private HashMap<String, String[]> uekey_nas_keys_map;
	private HashMap<DatapathId, IOFSwitch> switch_mapping;
	private HashMap<String, TransportPort> uekey_udp_src_port_map;

	private Hashtable<String, String> sgw_teid_uekey_map;
	private IFloodlightProviderService floodlightProvider;

	int ue_port;

	SGWC sgw;
	HSSPlus hss;
	IOFSwitch switch_id;
	Set<DatapathId> switches;
	DatapathId eNodeB; 
	HashMap<DatapathId, Long> switchStats;

	public MME() {
		sgw = new SGWC();
		hss = new HSSPlus();
		ue_port = Constants.UE_PORT;
		
		imsi_xres_mapping = new HashMap<String, String>();
		uekey_sgw_te_id_map = new HashMap<String, String>();
		sgw_teid_uekey_map = new Hashtable<String, String>();
		uekey_ueip_map = new HashMap<String, String>();
		switch_mapping = new HashMap<DatapathId, IOFSwitch>();
		
		/* [Key => UE-Key, Value => State [TRUE => Active, FALSE => Idle] ] */
		ue_state = new HashMap<String, Boolean>();	
		
		/* [Key => UE-Key, Value => GUTI] */
		uekey_guti_map = new HashMap<String, String>();
		
		/* [Key => IMSI, Value => [ [0] => K_ASME, [1] => NAS Integrity Key, [3] => NAS Encryption Key] ] */
		uekey_nas_keys_map = new HashMap<String, String[]>();
		
		/* [Key => UE-Key, Value => UE UDP Port] */
		uekey_udp_src_port_map = new HashMap<String, TransportPort>();

		switchStats =  new HashMap<DatapathId, Long>();
		eNodeB = DatapathId.of(Constants.ENODEB_SW_ID);
	};

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		System.out.println("--- Initializing MME Controller Service ---");
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		threadPoolService = context.getServiceImpl(IThreadPoolService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
	};

	@Override
	public void startUp(FloodlightModuleContext context) {
		System.out.println("--- Starting up MME Controller Service ---");
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		switchService.addOFSwitchListener(this);
	};

	@Override
	public String getName() {
		return MME.class.getPackage().getName();
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
		Collection<Class<? extends IFloodlightService>> deps = new ArrayList<Class<? extends IFloodlightService>>();
		deps.add(IFloodlightProviderService.class);
		return deps;
	};

	public void setFloodlightProvider(IFloodlightProviderService floodlightProvider) {
		this.floodlightProvider = floodlightProvider;
	};

	public Command receive(IOFSwitch switch_id, OFMessage message, FloodlightContext context) {
		switch (message.getType()) {
 			case PACKET_IN:
				log.info("--- MME received PACKET_IN request from switch {} ---", switch_id);
 				if(DatapathId.of(Constants.ENODEB_SW_ID).equals(switch_id.getId())) {
 					return this.processPacketInMessage(switch_id, (OFPacketIn) message, context);
 				} else if(DatapathId.of(Constants.SGW_DISPATCH_ID).equals(switch_id.getId())) {
 					return this.processPacketInMessageFromSGW(switch_id, (OFPacketIn) message, context);
 				};
 				return Command.CONTINUE;
 			case ERROR:
 				log.info("received an error {} from switch {}", message, switch_id);
 				return Command.CONTINUE;
 			default:
 				log.error("received an unexpected message {} from switch {}", message, switch_id);
 				return Command.CONTINUE;
		}
	};

	private Command processPacketInMessageFromSGW(IOFSwitch switch_id, OFPacketIn packet, FloodlightContext context) {
		String payload_segments[];
		OFPort inPort = (packet.getVersion().compareTo(OFVersion.OF_12) < 0 ? packet.getInPort() : packet.getMatch().get(MatchField.IN_PORT));
		
		/* Read packet header attributes into Match */
		Match match = createMatchFromPacket(switch_id, inPort, context);
		VlanVid vlan = match.get(MatchField.VLAN_VID) == null ? VlanVid.ZERO : match.get(MatchField.VLAN_VID).getVlanVid();
		Ethernet eth = IFloodlightProviderService.bcStore.get(context, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		if(eth.getEtherType() == EthType.IPv4) {
			IPv4 ipPkt = (IPv4)eth.getPayload();

			if(ipPkt.getProtocol().equals(IpProtocol.UDP)) {
				UDP udpPkt = (UDP)ipPkt.getPayload();
				Data dataPkt = null;

				if(Data.class.isInstance(udpPkt.getPayload())) {
					dataPkt = (Data)udpPkt.getPayload();
					byte[] data = dataPkt.getData();
					String payload = "";

					try {
						payload = new String(data, "ISO-8859-1");
					} catch (UnsupportedEncodingException e) {
						e.printStackTrace();
					};

					if(payload.contains(Constants.SEPARATOR)){
						payload_segments = payload.split(Constants.SEPARATOR);

						/* [payload_segments[0] => PDN_SERVICE_REQUEST code, payload_segments[1] => UE Key] */
						if(payload_segments[0].equals(Constants.PDN_SERVICE_REQUEST)){
							downlinkDataNotification(payload_segments[1], vlan);
						} else {
							System.out.println("ERROR: Unknown message code received from PDN, received: '" + payload + "' expected: '" + Constants.SEPARATOR + "'");
							System.exit(1);
						};

					} else {
						System.out.println("ERROR: Unknown packet received from PDN, received: '" + payload + "'");
						System.exit(1);
					};
				};
			};
		}
		return Command.CONTINUE;
	};

	public String byteArrayToHex(byte[] a) {
		StringBuilder sb = new StringBuilder(a.length * 2);
		for(byte b: a) { sb.append(String.format("%02x", b & 0xff)); };
		return sb.toString();
	};

	private Command processPacketInMessage(IOFSwitch switch_id, OFPacketIn packet, FloodlightContext context) {
		OFPort in_port = (packet.getVersion().compareTo(OFVersion.OF_12) < 0 ? packet.getInPort() : packet.getMatch().get(MatchField.IN_PORT));

		/* Read packet header attributes into Match */
		Match m = createMatchFromPacket(switch_id, in_port, context);
		MacAddress src_mac = m.get(MatchField.ETH_SRC);
		MacAddress dst_mac = m.get(MatchField.ETH_DST);
		VlanVid vlan = m.get(MatchField.VLAN_VID) == null ? VlanVid.ZERO : m.get(MatchField.VLAN_VID).getVlanVid();
		TransportPort src_port=TransportPort.of(67), dst_port=TransportPort.of(67);
		IPv4Address src_ip, dst_ip;

		if (src_mac == null) { src_mac = MacAddress.NONE; };
		if (dst_mac == null) { dst_mac = MacAddress.NONE; };
		if (vlan == null) { vlan = VlanVid.ZERO; };

		Ethernet eth = IFloodlightProviderService.bcStore.get(context, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		if(eth.getEtherType() == EthType.IPv4) {
			IPv4 ipPkt = (IPv4)eth.getPayload();
			src_ip = ipPkt.getSourceAddress();
			dst_ip = ipPkt.getDestinationAddress();
			
			if(ipPkt.getProtocol().equals(IpProtocol.UDP)) {
				UDP udpPkt = (UDP)ipPkt.getPayload();
				src_port = udpPkt.getSourcePort();
				dst_port = udpPkt.getDestinationPort();
				Data dataPkt = null;
				
				if(Data.class.isInstance(udpPkt.getPayload())) {
					dataPkt = (Data)udpPkt.getPayload();
					byte[] data = dataPkt.getData();
					String payload = "";
					
					try {
						payload = new String(data, "ISO-8859-1");
					} catch (UnsupportedEncodingException e) {
						e.printStackTrace();
					};

					if(Constants.DEBUG) {
						if(!payload.startsWith("{") && !payload.startsWith("_ipps") && !payload.startsWith("�") && !payload.contains("arpa")) {
							System.out.println("RECEIVED: '" + payload + "'");
						};
					};

					String NAS_MAC = null;
					StringBuilder response;

					@SuppressWarnings("unused")
					String payload_segments[], payload_ext_segments[], decArray[], NAS_Keys[];
					String res, xres, autn, rand, K_ASME, imsi, ue_nw_capability, KSI_ASME, SQN, tai;
	
					if(payload.contains(Constants.SEPARATOR)) {
						payload_segments = payload.split(Constants.SEPARATOR);
						DatapathId sgw_dispatch_id, pgw_dispatch_id;
						Date d1 = null, d2 = null;
						int step = 0;
						
						switch(payload_segments[0]) {
							case Constants.AUTHENTICATION_STEP_ONE:
								if(Constants.DEBUG) {
									System.out.println("--- Case => AUTHENTICATION_STEP_ONE ---");
									step = 1;
									d1 = d2 = null;
									d1 = new Date();
								};
								
								//validating user in HSS
								imsi = payload_segments[1];
								ue_nw_capability = payload_segments[2];
								KSI_ASME = payload_segments[3];
								SQN = payload_segments[4];	// UE sequence number
								tai = payload_segments[5];	// Tracking area ID
								Date d3 = new Date();
								payload = hss.validateUE(imsi, Constants.SN_ID, Constants.NW_TYPE, Integer.parseInt(SQN), tai);
							
								if(Constants.DEBUG) {
									Date d4 = new Date();
									timeDiff(d3, d4, 11);
								};

								if(payload != null && payload.contains(Constants.SEPARATOR)) {
									payload_ext_segments = payload.split(Constants.SEPARATOR);
									/* [payload_ext_segments[0] => xres, payload_ext_segments[1] => autn, payload_ext_segments[2] => rand, payload_ext_segments[3] => K_ASME] */
									xres = payload_ext_segments[0];
									autn = payload_ext_segments[1];
									rand = payload_ext_segments[2];
									K_ASME = payload_ext_segments[3];
									
									if(Constants.DEBUG) {
										System.out.println("INITIAL IMSI: '" + imsi + "', MSISDN: '" + ue_nw_capability + "'");
									};
									
									imsi_xres_mapping.put(imsi, xres);
									uekey_nas_keys_map.put(imsi, new String[]{K_ASME, "", ""});
									KSI_ASME = "1";
									
									response = new StringBuilder();
									response.append(Constants.AUTHENTICATION_STEP_TWO).append(Constants.SEPARATOR).append(rand).append(Constants.SEPARATOR);
									response.append(autn);
									response.append(Constants.SEPARATOR).append(KSI_ASME);
									sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip, IpProtocol.UDP, dst_port, src_port, response.toString());
									response = null;
				
								} else {
									System.out.println("ERROR:: STEP ONE AUTHENTICATION failure with IMSI '" + imsi + "' and MSISDN: '" + ue_nw_capability + "', payload: '"+payload + "'");
									sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, Constants.AUTHENTICATION_FAILURE);
									System.exit(1);
								};
								
								if(Constants.DEBUG) { d2 = new Date(); };
								break;
							
							case Constants.AUTHENTICATION_STEP_THREE:
								if(Constants.DEBUG) {
									System.out.println("--- Case => AUTHENTICATION_STEP_THREE ---");
									step = 2;
									d1 = d2 = null;
									d1 = new Date();
								};
								
								imsi = payload_segments[1];
								res = payload_segments[2];	// RES from UE
								
								if(Constants.DEBUG) {
									System.out.println("imsi="+imsi + " res="+res);
								};
								
								if(imsi_xres_mapping.containsKey(imsi)) {

									// UE Authentication (RES == XRES)
									if(imsi_xres_mapping.get(imsi).equals(res)) { 
										imsi_xres_mapping.remove(imsi);
										
										//UE is authenticated
										KSI_ASME = "1";

										// Replayed UE Network Capability decided by MME
										int replayed_nw_capability = Utils.randInt(0, 10);

										int NAS_integrity_algo_id = Constants.CIPHER_ALGO_MAP.get("HmacSHA1");
										int NAS_cipher_algo_id = Constants.CIPHER_ALGO_MAP.get("AES");

										if(uekey_nas_keys_map.containsKey(imsi)) {
											K_ASME = uekey_nas_keys_map.get(imsi)[0];
										} else {
											System.out.println("ERROR in AUTHENTICATION_STEP_THREE: IMSI '" + imsi + "' not found");
											System.exit(1);
										};
										
										K_ASME = uekey_nas_keys_map.get(imsi)[0];

										// [NAS_keys[0] => K_NAS_int, NAS_keys[1] => K_NAS_enc]
										String NAS_keys[] = KDF_NAS(Integer.parseInt(K_ASME), NAS_integrity_algo_id, NAS_cipher_algo_id);

										if(Constants.DEBUG) {
											System.out.println("AUTHENTICATION_STEP_THREE: INT_KEY: '" + NAS_keys[0] + "', ENC_KEY: '" + NAS_keys[1] + "'");
										};
										
										uekey_nas_keys_map.put(imsi, new String[]{K_ASME, NAS_keys[0], NAS_keys[1]});
										response = new StringBuilder();
										response.append(Constants.NAS_STEP_ONE).append(Constants.SEPARATOR).append(KSI_ASME).append(Constants.SEPARATOR).append(replayed_nw_capability).append(Constants.SEPARATOR).append(NAS_cipher_algo_id).append(Constants.SEPARATOR).append(NAS_integrity_algo_id);

										// Generate Message Authentication Code using the hash function
										NAS_MAC = Utils.hmacDigest(response.toString(), NAS_keys[0] + "");	
			
										if(Constants.DEBUG) {
											System.out.println("AUTHENTICATION_STEP_THREE: Generated NAS MAC: '" + NAS_MAC + "'");
										};
										
										response.append(Constants.SEPARATOR).append(NAS_MAC);
										sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, response.toString());
										response = null;
								
									} else {
										System.out.println(imsi_xres_mapping.get(imsi).equals(res) +" ### "+ imsi_xres_mapping.get(imsi) +" ue_res= "+ res+" ### imsi "+ imsi);
										sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, Constants.AUTHENTICATION_FAILURE);
										System.exit(1);
									};
	
								} else {
									System.out.println("ERROR: AUTHENTICATION_STEP_THREE failure");
									sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, Constants.AUTHENTICATION_FAILURE);
									System.exit(1);
								};
					
								if(Constants.DEBUG) { d2 = new Date(); };
								break;
								
							case Constants.SEND_APN:
								if(Constants.DEBUG) {
									System.out.println("--- Case => SEND_APN ---");
									step = 3;
									d1 = d2 = null;
									d1 = new Date();
								};
								
								if(Constants.DO_ENCRYPTION) {
									decArray = getDecryptedArray(payload_segments);
								};

								/* [payload_segments[1] => UE APN, payload_segments[2] => UE Key] */
								if(Constants.DEBUG) {
									System.out.println("Received APN: '" + payload_segments[1] + "'");
								};
								
								/* storing source port of UDP packet to identify the specific UE, 
								 * when MME wants to initiate connection with this UE.
								 */
								uekey_udp_src_port_map.put(payload_segments[2], src_port);

								pgw_dispatch_id = hss.getPGW(payload_segments[1]);
								sgw_dispatch_id = DatapathId.of(Constants.SGW_DISPATCH_ID);

								String sgw_ip = sgw.contactPGW(switch_mapping.get(sgw_dispatch_id),switch_mapping.get(pgw_dispatch_id), sgw_dispatch_id, pgw_dispatch_id, payload_segments[1]);

								response = new StringBuilder();
								response.append(Constants.SEND_IP_SGW_TE_ID).append(Constants.SEPARATOR).append(sgw_ip);

								if(Constants.DO_ENCRYPTION) {
									Utils.aesEncrypt(response.toString(), Constants.SAMPLE_ENC_KEY);
									Utils.hmacDigest(response.toString(), Constants.SAMPLE_ENC_KEY);
								};

								payload_ext_segments = sgw_ip.split(Constants.SEPARATOR);
								int sgw_te_id = Integer.parseInt(payload_ext_segments[1]);

								// install up-link rule on default switch
								if(Constants.DEBUG) {
									System.out.println("eNodeB controller installing uplink rule on eNodeB switch Dispatch ID: '" + eNodeB.getLong() + "' In-Port: '" + ue_port + "' and Source IP: '" + payload_ext_segments[0] + 
										"' Out-Port: '" + Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + sgw_dispatch_id.getLong()) + "' and Out Source IP: '" + Constants.RAN_IP + "' Out-Tunnel Endpoint ID: '" + sgw_te_id + "' of UE Key: '" + payload_segments[2] + "'");
								};

								installFlowRuleWithIP(eNodeB, ue_port, Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + sgw_dispatch_id.getLong()), sgw_te_id, payload_ext_segments[0], Constants.RAN_IP, Constants.SGWD_IP_UPLINK, Constants.PDN_MAC);
								uekey_ueip_map.put(payload_segments[2], payload_ext_segments[0]); 

								uekey_sgw_te_id_map.put(payload_segments[2], sgw_dispatch_id.toString() + Constants.SEPARATOR + payload_ext_segments[1]);
								sgw_teid_uekey_map.put(payload_ext_segments[1], payload_segments[2]);

								sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, response.toString());
								response = null;
								
								if(Constants.DEBUG) { d2 = new Date(); };
								break;

							case Constants.SEND_UE_TE_ID:
								if(Constants.DEBUG) {
									System.out.println("--- Case => SEND_UE_TE_ID ---");
									step = 4;
									d1 = d2 = null;
									d1 = new Date();
								};
								
								if(Constants.DO_ENCRYPTION) {
									decArray = getDecryptedArray(payload_segments);
								};

								/* [payload_segments[1] => ue_te_id, payload_segments[2] => UE Key] */
								if(Constants.DEBUG) {
									System.out.println("received teid="+payload_segments[1]);
								};
								
								String str = uekey_sgw_te_id_map.get(payload_segments[2]);
								payload_ext_segments = str.split(Constants.SEPARATOR);

								sgw.modifyBearerRequest(switch_mapping.get(DatapathId.of(payload_ext_segments[0])), DatapathId.of(payload_ext_segments[0]), Integer.parseInt(payload_ext_segments[1]), Integer.parseInt(payload_segments[1]), payload_segments[2]);

								String ue_ip = uekey_ueip_map.get(payload_segments[2]);
								uekey_ueip_map.remove(payload_segments[2]);

								if(Constants.DEBUG) {
									// install down-link rule on default switch
									System.out.println("eNodeB controller installing downlink rule on eNodeB switch Dispatch ID: '" + eNodeB.getLong() + "' In-Port: '" + Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + DatapathId.of(payload_ext_segments[0]).getLong()) + 
										"' In-Tunnel Endpoint ID: '" + Integer.parseInt(payload_segments[1]) + "' Out-Port: '" + ue_port + "' Out-Tunnel Endpoint ID: '" + Integer.parseInt(payload_segments[1]) + "' of UE Key: '" + payload_segments[2] + "'");
								};

								installFlowRule(eNodeB, Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + DatapathId.of(payload_ext_segments[0]).getLong()), Integer.parseInt(payload_segments[1]), ue_port, Integer.parseInt(payload_segments[1]), Constants.PDN_IP, ue_ip, Constants.UE_MAC);

								response = new StringBuilder();
								uekey_guti_map.put(payload_segments[2], (Integer.parseInt(payload_segments[2]) + 1000) + "");
								ue_state.put(payload_segments[2], true);
								response.append(Constants.ATTACH_ACCEPT).append(Constants.SEPARATOR).append(Integer.parseInt(payload_segments[2])+1000);

								if(Constants.DO_ENCRYPTION) {
									decArray = getDecryptedArray(payload_segments);
								};

								sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, response.toString());
								response = null;
								
								if(Constants.DEBUG) { d2 = new Date(); };
								break;
								
							case Constants.DETACH_REQUEST:
								if(Constants.DEBUG) {
									System.out.println("--- Case => DETACH_REQUEST ---");
									step = 5;
									d1 = d2 = null;
									d1 = new Date();
								};
								
								if(Constants.DO_ENCRYPTION) {
									decArray = getDecryptedArray(payload_segments);
								};

								// [payload_segments[1] => UE-IP, payload_segments[2] => UE-TE-ID, payload_segments[3] => SGW-TE-ID, payload_segments[4] => UE-KEY]
								if(Constants.DEBUG) {
									System.out.println("RECEIVED DETACH REQUEST from UE with IP: '" + payload_segments[1] + "', Tunnel Endpoint ID: '" + payload_segments[2] + "', corresponding SGW Tunnel Endpoint ID: '" + payload_segments[3] + "' and UE-KEY: '" + payload_segments[4] + "'");
								};
								
								// removing the port mapping between UE key and its source port (UDP) used for control traffic
								uekey_udp_src_port_map.remove(payload_segments[4]);

								pgw_dispatch_id = DatapathId.of(Constants.PGW_ID);
								sgw_dispatch_id = DatapathId.of(Constants.SGW_ID);

								// newly added, we can't remove it in SEND_UE_TE_ID step due to tunnel re-establishment
								uekey_sgw_te_id_map.remove(payload_segments[4]);
								sgw_teid_uekey_map.remove(payload_segments[3]);

								// delete up-link rule
								deleteFlowRuleWithIP(eNodeB, ue_port, payload_segments[1]);
								
								if(Constants.DEBUG) {
									System.out.println("eNodeB controller deleting uplink rule for UE with IP: " + payload_segments[1] + "'");
								};
								
								int eNodeB_SGW_PORT = Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + sgw_dispatch_id.getLong());
						
								//delete down-link rule
								deleteFlowRuleWithTEID(eNodeB, eNodeB_SGW_PORT, Integer.parseInt(payload_segments[2]), Constants.PDN_IP);
					
								if(Constants.DEBUG) {
									System.out.println("DEFAULT SWITCH deleting downlink rule for UE with IP: '" + payload_segments[1] + "' and UE Tunnel Endpoint ID: '" + payload_segments[2] + "'");
								};

								boolean status = sgw.detachUEFromSGW(switch_mapping.get(sgw_dispatch_id), switch_mapping.get(pgw_dispatch_id), sgw_dispatch_id, pgw_dispatch_id, Integer.parseInt(payload_segments[3]), payload_segments[1]);
								response = new StringBuilder();
					
								if(status) {
									response.append(Constants.DETACH_ACCEPT).append(Constants.SEPARATOR).append("");
						
									if(Constants.DO_ENCRYPTION) {
										Utils.aesEncrypt(response.toString(), Constants.SAMPLE_ENC_KEY);
										Utils.hmacDigest(response.toString(), Constants.SAMPLE_ENC_KEY);
									};
									
									sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, response.toString());
						
								} else {
									response.append(Constants.DETACH_FAILURE).append(Constants.SEPARATOR).append("");
									
									if(Constants.DO_ENCRYPTION) {
										Utils.aesEncrypt(response.toString(), Constants.SAMPLE_ENC_KEY);
										Utils.hmacDigest(response.toString(), Constants.SAMPLE_ENC_KEY);
									};
									
									sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, response.toString());
									System.out.println("ERROR: DETACH_FAILURE - aborting");
									System.exit(1);
								};
								
								response = null;
								
								if(Constants.DEBUG) { d2 = new Date(); };
								break;

							case Constants.REQUEST_STARTING_IP:
								if(Constants.DEBUG) {
									System.out.println("--- Case => REQUEST_STARTING_IP ---");

									step = 6;
									d1 = d2 = null;
									d1 = new Date();
								};
								
								String ip = sgw.getStartingIPAddress();
						
								if(Constants.DEBUG) {
									System.out.println("Current Starting IP: '" + ip + "'");
								};
								
								response = new StringBuilder();
								response.append(Constants.SEND_STARTING_IP).append(Constants.SEPARATOR).append(ip);
								sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, response.toString());
								response = null;
							
								if(Constants.DEBUG) { d2 = new Date(); };
								break;

							case Constants.UE_CONTEXT_RELEASE_REQUEST:
								if(Constants.DEBUG) {
									System.out.println("--- Case => UE_CONTEXT_RELEASE_REQUEST ---");
									/* [payload_segments[1] => UE-IP, payload_segments[2] => UE-TE-ID,  payload_segments[3] => SGW-TE-ID, payload_segments[4] => UE-KEY] */
									System.out.println("RECEIVED UE CONTEXT RELEASE REQUEST from UE with IP: '" + payload_segments[1] + "' Tunnel Endpoint ID: '" + payload_segments[2] + "', corresponding SGW Tunnel Endpoint ID: '" + payload_segments[3] + "' and UE KEY: '" + payload_segments[4] + "'");

									step = 7;
									d1 = d2 = null;
									d1 = new Date();
								};
								
								sgw_dispatch_id = DatapathId.of(Constants.SGW_ID);

								// delete up-link rule
								deleteFlowRuleWithIP(eNodeB, ue_port, payload_segments[1]);
								
								if(Constants.DEBUG) {
									System.out.println("DEFAULT SWITCH deleting uplink rule for UE with IP: '"+payload_segments[1] + "'");
								};
								
								eNodeB_SGW_PORT = Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + sgw_dispatch_id.getLong());
								
								//delete down-link rule
								deleteFlowRuleWithTEID(eNodeB, eNodeB_SGW_PORT, Integer.parseInt(payload_segments[2]), Constants.PDN_IP);

								if(Constants.DEBUG) {
									System.out.println("DEFAULT SWITCH deleting downlink rule for UE with IP: '" + payload_segments[1] + "' and UE Tunnel Endpoint ID: '" + payload_segments[2] + "'");
								};

								sgw.releaseAccessBearersRequest(switch_mapping.get(sgw_dispatch_id), sgw_dispatch_id, Integer.parseInt(payload_segments[3]), payload_segments[1]);

								response = new StringBuilder();
								ue_state.put(payload_segments[4], false);
								response.append(Constants.UE_CONTEXT_RELEASE_COMMAND).append(Constants.SEPARATOR).append("");
								sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, response.toString());
								response = null;
				
								if(Constants.DEBUG) { d2 = new Date(); };
								break;

							case Constants.UE_CONTEXT_RELEASE_COMPLETE:
								if(Constants.DEBUG) {
									System.out.println("--- Case => UE_CONTEXT_RELEASE_COMPLETE ---");
									System.out.println("RECEIVED UE_CONTEXT_RELEASE_COMPLETE from UE with UE-Key: '" + payload_segments[1] + "', UE-IP: '" + payload_segments[2] +"', Network_Service_Request_Boolean: '" + payload_segments[3] + "' and PDN UDP server port: '" + payload_segments[4] + "'");
								};	
				
								/* [payload_segments[1] => UE-Key, payload_segments[2] => UE-IP, payload_segments[3] => Network-Service-Request-Boolean, payload_segments[4] => PDN-UDP-Server-Port] */
								if(payload_segments[3].equals("1")) {
									int serverPort = Integer.parseInt(payload_segments[4]);
									
									src_ip = IPv4Address.of(payload_segments[2]);
									src_port = TransportPort.of(serverPort);

									dst_ip = IPv4Address.of(Constants.PDN_IP);
									dst_port = TransportPort.of(serverPort);

									MacAddress srcMac = MacAddress.of(Constants.UE_MAC), dstMac =  MacAddress.of(Constants.PDN_MAC);
									switch_id = switchService.getSwitch(DatapathId.of(Constants.PGW_ID));

									response = new StringBuilder();
									response.append(Constants.INITIATE_NETWORK_SERVICE_REQUEST).append(Constants.SEPARATOR).append(payload_segments[1]);
						
									if(Constants.DEBUG) {
										System.out.println("Sending Network Service Request to PDN for UE-Key: '" + payload_segments[1] + "', UE-IP: '" + payload_segments[2] + "'");
									};

									// Inform PDN to initiate network service request
									sendPacket(switch_id, OFPort.of(Constants.PGW_PDN_PORT), srcMac, dstMac, src_ip, dst_ip,  IpProtocol.UDP, src_port, dst_port, response.toString());
								};

								step = 8;
								d1 = d2 = null;
								d1 = new Date();

								if(Constants.DEBUG) { d2 = new Date(); };
								break;

							case Constants.UE_SERVICE_REQUEST:
								if(Constants.DEBUG) {
									System.out.println("--- Case => UE_SERVICE_REQUEST ---");
									
									step = 9;
									d1 = d2 = null;
									d1 = new Date();
								};
								
								/* [payload_segments[1] => UE-KEY, payload_segments[2] => KSI_ASME, payload_segments[3] => UE-IP] */
								if(Constants.DEBUG) {
									System.out.println("RECEIVED UE_SERVICE_REQUEST from UE with UE-Key: '" + payload_segments[1] + "', KSI_ASME: '" + payload_segments[2] + "'");
								};
								
								if(Constants.DO_ENCRYPTION) {
									decArray = getDecryptedArray(payload_segments);
								};
								
								sgw_dispatch_id = DatapathId.of(Constants.SGW_DISPATCH_ID);
								ue_ip = payload_segments[3];

								String sgw_dispatch_id_sgw_te_id = uekey_sgw_te_id_map.get(payload_segments[1]);
								payload_ext_segments = sgw_dispatch_id_sgw_te_id.split(Constants.SEPARATOR);
								sgw_te_id = Integer.parseInt(payload_ext_segments[1]);

								// install up-link rule on eNodeB switch
								if(Constants.DEBUG) {
									System.out.println("eNodeB controller installing uplink rule on eNodeB Switch Dispatch ID: '" + eNodeB.getLong() + "', In-Port: '" + ue_port + "' and Source IP: '" + ue_ip + 
										"', Out-Port: '" + Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + sgw_dispatch_id.getLong())+"' and Out Source IP: '" + Constants.RAN_IP + "', Out-Tunnel Endpoint ID: '" + sgw_te_id + "' of UE Key: '" + payload_segments[1] + "'");
								};
								
								installFlowRuleWithIP(eNodeB, ue_port, Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + sgw_dispatch_id.getLong()), sgw_te_id, ue_ip, Constants.RAN_IP, Constants.SGWD_IP_UPLINK, Constants.PDN_MAC);

								response = new StringBuilder();
								response.append(Constants.INITIAL_CONTEXT_SETUP_REQUEST).append(Constants.SEPARATOR).append(sgw_te_id);

								if(Constants.DO_ENCRYPTION) {
									decArray = getDecryptedArray(payload_segments);
								};

								sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, response.toString());
								response = null;
							
								if(Constants.DEBUG) { d2 = new Date(); };
								break;

							case Constants.INITIAL_CONTEXT_SETUP_RESPONSE:
								if(Constants.DEBUG) {
									System.out.println("--- Case => INITIAL_CONTEXT_SETUP_RESPONSE ---");
									
									step = 10;
									d1 = d2 = null;
									d1 = new Date();
								};
								
								if(Constants.DO_ENCRYPTION){
									decArray = getDecryptedArray(payload_segments);
								};

								/* [payload_segments[1] => UE-Tunnel-ID, payload_segments[2] => UE Key, payload_segments[3] => UE IP] */
								if(Constants.DEBUG) {
									System.out.println("received teid="+payload_segments[1]);
								};

								// find matching UE-Key to SGW-TE-ID map
								while(uekey_sgw_te_id_map.get(payload_segments[2]) == null);

								str = uekey_sgw_te_id_map.get(payload_segments[2]);
								payload_ext_segments = str.split(Constants.SEPARATOR);

								sgw.modifyBearerRequest(switch_mapping.get(DatapathId.of(payload_ext_segments[0])), DatapathId.of(payload_ext_segments[0]), Integer.parseInt(payload_ext_segments[1]), Integer.parseInt(payload_segments[1]), payload_segments[2]);
								ue_ip = payload_segments[3];
								
								if(Constants.DEBUG) {
									// install down-link rule on default switch
									System.out.println("eNodeB controller installing downlink rule on eNodeB Switch Dispatch ID: '" + eNodeB.getLong() + "', In-Port: '" + Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + DatapathId.of(payload_ext_segments[0]).getLong()) + 
										"' in Tunnel Endpoint ID: '" + Integer.parseInt(payload_segments[1]) + "' Out-Port: '" + ue_port + "', Out-Tunnel Endpoint ID: '" + Integer.parseInt(payload_segments[1]) + "' of UE Key: '" + payload_segments[2] + "'");
								};
								
								installFlowRule( eNodeB, Constants.ENODEB_SGW_PORT_MAP.get(Constants.ENODEB_SW_ID + Constants.SEPARATOR + DatapathId.of(payload_ext_segments[0]).getLong()), Integer.parseInt(payload_segments[1]), ue_port, Integer.parseInt(payload_segments[1]), Constants.PDN_IP, ue_ip, Constants.UE_MAC);

								response = new StringBuilder();
								uekey_guti_map.put(payload_segments[2], (Integer.parseInt(payload_segments[2]) + 1000) + "");
								
								ue_state.put(payload_segments[2], true);
								response.append(Constants.ATTACH_ACCEPT).append(Constants.SEPARATOR).append(payload_segments[2]);

								if(Constants.DO_ENCRYPTION) {
									decArray = getDecryptedArray(payload_segments);
								};

								sendPacket(switch_id, in_port, dst_mac, src_mac, dst_ip, src_ip,  IpProtocol.UDP, dst_port, src_port, response.toString());
								response = null;

								if(Constants.DEBUG) { d2 = new Date(); };
								break;

							case Constants.PDN_SERVICE_REQUEST:
								if(Constants.DEBUG) {
									System.out.println("--- Case => PDN_SERVICE_REQUEST ---");
								};

								if(payload_segments[0].equals(Constants.PDN_SERVICE_REQUEST)) {
									downlinkDataNotification(payload_segments[1], vlan);
								} else {
									System.out.println("** ERROR: Unknown message code received from PDN, received: '" + payload + "', expected: '" + Constants.SEPARATOR + "' - aborting ");
									System.exit(1);
								};
				
								break;

							default:
								if(Constants.DEBUG) {
									System.out.println("--- Case => default ---");
									
									step = 11;
									d1 = d2 = null;
									d1 = new Date();
								};

								if(payload_segments.length == 3) {
									if(Constants.DEBUG) {
										System.out.println("NAS STEP TWO: data length: '" + data.length + "', received encrypted text: '" + payload_segments[0] + "', NAS-MAC: '" + payload_segments[1] + "', IMSI: '" + payload_segments[2] + "'");
										System.out.println("Encrypted length: '" + payload_segments[0].length() + "', NAS-MAC length: '" + payload_segments[1].length() + "', IMSI length: '" + payload_segments[2].length() + "'");
									};
									
									if(!Constants.CHECK_INTEGRITY) {
										d2 = new Date();
										break;
									};

									NAS_Keys = uekey_nas_keys_map.get(payload_segments[2]);
									NAS_MAC = Utils.hmacDigest(payload_segments[0], NAS_Keys[1]);
								
									if(Constants.DEBUG) {
										System.out.println("NAS_STEP_TWO: Generated NAS MAC: '" + NAS_MAC + "'");
									};
									
								} else {
									System.out.println("ERROR: NAS STEP TWO: unknown command. payload length: '" + payload_segments.length + "', payload: '" + payload + "' - aborting");
									System.exit(1);
								};
								
								d2 = new Date();
							};
							
							if(Constants.DEBUG) { timeDiff(d1, d2, step); };
						};
					};

			} else if(ipPkt.getProtocol().equals(IpProtocol.IPIP)) { //IP within IP tunnel
				if(Constants.DEBUG) {
					System.out.println("src-ip: '" + src_ip.toString() + "', dst-ip: '" + dst_ip.toString() + "'");
					System.out.println("proto: '" + ipPkt.getProtocol().toString() + "', version: '" + EthType.IPv4 + "', encaps: '" + IpProtocol.IPX_IN_IP + "'");
				};
				
				Data dataPkt1 = (Data) ipPkt.getPayload();
				byte[] data1 = dataPkt1.getData();
				
				try {
					IPv4 ipPkt1 = (IPv4) ipPkt.deserialize(data1, 0, data1.length);
					src_ip = ipPkt1.getSourceAddress();
					dst_ip = ipPkt1.getDestinationAddress();

					UDP udpPkt1 = (UDP)ipPkt1.getPayload();
					src_port = udpPkt1.getSourcePort();
					dst_port = udpPkt1.getDestinationPort();

					if(Constants.DEBUG){
						System.out.println("src-ip: '" + src_ip.toString() + "', dst-ip: '" + dst_ip.toString() + "'");
					};
					
				} catch (PacketParsingException e) {
					e.printStackTrace();
				};
			};
		};
		
		return Command.CONTINUE;
	};

	long case_sum[] = new long[20];
	long case_cnt[] = new long[20];
	long MOD = 10;

	/* This method is used for measuring execution time of procedures */ 
	private void timeDiff(Date d1, Date d2, int step) {
		if(d1 == null || d2 == null) {
			System.out.println("Step " + step + ": start => " + d1 + ",  end => " + d2);
			System.exit(1);
		};
		
		long diff = d2.getTime() - d1.getTime();
		case_cnt[step-1]++;
		case_sum[step-1] += diff;

		if (case_cnt[step-1] % MOD == 0) {
			System.out.println(getStepName(step) + " Average over "+ MOD + " no. of steps is " + (case_sum[step-1]*1.0/case_cnt[step-1]) +" ms");
			case_cnt[step-1] = case_sum[step-1] = 0;
			if(step == 5) { System.out.println(); };
		};
	};

	protected boolean downlinkDataNotification(String ue_key, VlanVid vlan) {
		TransportPort src_port = TransportPort.of(Constants.DEFAULT_CONTROL_TRAFFIC_UDP_PORT), dstPort = TransportPort.of(67);
		IPv4Address src_ip = IPv4Address.of(Constants.ENODEB_SW_IP_UPLINK), dstIp = IPv4Address.of(Constants.RAN_IP);
		MacAddress src_mac = MacAddress.of(Constants.ENODEB_SW_MAC), dstMac =  MacAddress.of(Constants.UE_MAC);
		IOFSwitch switch_id = switchService.getSwitch(DatapathId.of(Constants.ENODEB_SW_ID));
		StringBuilder response = new StringBuilder();

		if(uekey_udp_src_port_map.containsKey(ue_key)) {
			dstPort = uekey_udp_src_port_map.get(ue_key);
		} else {
			System.out.println("ERROR: UE UDP port not found for the UE key: '" + ue_key + "' - aborting ");
			System.exit(1);
		};

		response.append(Constants.PAGING_REQUEST).append(Constants.SEPARATOR).append(vlan.getVlan());
	
		if(Constants.DEBUG) {
			System.out.println("Sending paging request to UE with UE Key: '" + ue_key + "'");
		};
		
		sendPacket(switch_id, OFPort.of(Constants.ENODEB_SW_UE_PORT), src_mac, dstMac, src_ip, dstIp,  IpProtocol.UDP, src_port, dstPort, response.toString());
		return true;
	};

	private String getStepName(int step) {
		switch(step) {
			case 1: return "AUTHENTICATION_STEP_ONE: ";
			case 2: return "AUTHENTICATION_STEP_THREE: ";
			case 3: return "SEND_APN: ";
			case 4: return "SEND_UE_TEID: ";
			case 5: return "DETACH_REQUEST: ";
			case 6: return "REQUEST_STARTING_IP: ";
			case 7: return "UE_CONTEXT_RELEASE_REQUEST: ";
			case 8: return "UE_CONTEXT_RELEASE_COMPLETE";
			case 9: return "UE_SERVICE_REQUEST: ";
			case 10: return "TUNNEL_SETUP_ACCEPT: ";
			case 11: return "DEFAULT: ";
			case 12: return "HSS call: ";
			default: return "Invalid";
		}
	};

	private String[] getDecryptedArray(String tmpArray[]) {
		/* [tmpArray[1] => Encrypted text, tmpArray[2] => HMAC, tmpArray[3] => IMSI] */
		Utils.hmacDigest(tmpArray[1], Constants.SAMPLE_ENC_KEY);

		String decText = "";
		Utils.aesEncrypt(tmpArray[1], Constants.SAMPLE_ENC_KEY);
		return decText.split(Constants.SEPARATOR);
	};

	private String[] KDF_NAS(int K_ASME, int NAS_integrity_algo_id, int NAS_cipher_algo_id) {
		String NAS_keys[] = new String[2];	
		long K_NAS_int = K_ASME * 2 + NAS_integrity_algo_id;
		long K_NAS_enc = K_ASME * 4 + NAS_cipher_algo_id;

		NAS_keys[0] = K_NAS_int + "";	// K_NAS_int
		NAS_keys[1] = K_NAS_enc + "";	// K_NAS_enc

		if(NAS_keys[1].length() > Constants.ENC_KEY_LENGTH) {
			NAS_keys[1].substring(0, Constants.ENC_KEY_LENGTH);
		} else if(NAS_keys[1].length() < Constants.ENC_KEY_LENGTH) {
			NAS_keys[1] += Constants.SAMPLE_ENC_KEY.substring(0, Constants.ENC_KEY_LENGTH - NAS_keys[1].length());
		};
		
		return NAS_keys;
	};

	/* This method deletes up-link rule */
	private void deleteFlowRuleWithIP(DatapathId dispatch_id, int inPort, String ue_ip) {
		if(switch_id == null) {
			switch_id = switchService.getSwitch(dispatch_id);
		};
		
		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowDelete();
		
		Match.Builder mb = switch_id.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4).setExact(MatchField.IN_PORT, OFPort.of(inPort)).setExact(MatchField.IPV4_SRC, IPv4Address.of(ue_ip));

		fmb.setMatch(mb.build());
		switch_id.write(fmb.build());
	};

	/* This method delete down-link rule */
	private void deleteFlowRuleWithTEID(DatapathId dispatch_id, int in_port, int ue_te_id, String src_ip) {
		if(switch_id == null) {
			switch_id = switchService.getSwitch(dispatch_id);
		};
		
		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowDelete();
		
		Match.Builder mb = switch_id.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
		.setExact(MatchField.IN_PORT, OFPort.of(in_port))
		.setExact(MatchField.IPV4_SRC, IPv4Address.of(src_ip))
		.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(VlanVid.ofVlan(ue_te_id)));

		fmb.setMatch(mb.build());
		switch_id.write(fmb.build());
	};

	/* This method installs up-link rule */
	private void installFlowRuleWithIP(DatapathId dispatch_id, int in_port, int out_port, int out_tunnel_id, String ue_ip, String src_ip, String dst_ip, String dst_mac) {
		if(switch_id == null) {
			switch_id = switchService.getSwitch(dispatch_id);
		};
		
		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowAdd();
		Match.Builder mb = switch_id.getOFFactory().buildMatch();

		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
		mb.setExact(MatchField.IN_PORT, OFPort.of(in_port));
		mb.setExact(MatchField.IPV4_SRC, IPv4Address.of(ue_ip));

		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(switch_id.getOFFactory().actions().setVlanVid(VlanVid.ofVlan(out_tunnel_id)));

		if(!dst_ip.isEmpty()) {
			actions.add(switch_id.getOFFactory().actions().setNwDst(IPv4Address.of(dst_ip)));
		};
		
		actions.add(switch_id.getOFFactory().actions().setDlDst(MacAddress.of(dst_mac)));
		actions.add(switch_id.getOFFactory().actions().output(OFPort.of(out_port), Integer.MAX_VALUE));
		fmb.setActions(actions);

		fmb.setHardTimeout(0).setIdleTimeout(0).setPriority(1).setBufferId(OFBufferId.NO_BUFFER).setMatch(mb.build());
		switch_id.write(fmb.build());
	};

	//* This method installs down-link rule */
	private void installFlowRule(DatapathId dispatch_id, int in_port, int in_tunnel_id, int out_port, int out_tunnel_id, String src_ip, String dst_ip, String dst_mac) {
		if(switch_id == null) {
			switch_id = switchService.getSwitch(dispatch_id);
		};
		
		OFFlowMod.Builder fmb = switch_id.getOFFactory().buildFlowAdd();
		
		Match.Builder mb = switch_id.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
		.setExact(MatchField.IN_PORT, OFPort.of(in_port))
		.setExact(MatchField.IPV4_SRC, IPv4Address.of(src_ip))
		.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(VlanVid.ofVlan(in_tunnel_id)));

		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(switch_id.getOFFactory().actions().setVlanVid(VlanVid.ZERO));
		actions.add(switch_id.getOFFactory().actions().setNwDst(IPv4Address.of(dst_ip)));
		actions.add(switch_id.getOFFactory().actions().setDlDst(MacAddress.of(dst_mac)));
		actions.add(switch_id.getOFFactory().actions().output(OFPort.of(out_port), Integer.MAX_VALUE));
		fmb.setActions(actions);

		fmb.setHardTimeout(0).setIdleTimeout(0).setPriority(1).setBufferId(OFBufferId.NO_BUFFER).setMatch(mb.build());
		switch_id.write(fmb.build());
	};

	public DatapathId selectSGWD() {
		DatapathId minLoadedSwitch = null;
		Long minBytes = 0l;
		boolean first_time = true;

		// find the minimum loaded sgw switch
		for (Map.Entry<DatapathId, Long> entry : switchStats.entrySet()) {
			if(first_time) {
				minLoadedSwitch = entry.getKey();
				minBytes = entry.getValue();
				first_time = false;
			} else {
				if(entry.getValue() < minBytes) {
					minBytes = entry.getValue();
					minLoadedSwitch = entry.getKey();
				};
			};
		};
		
		return minLoadedSwitch;
	};

	/* This method creates and sends packet to eNodeB switch on port in which it arrived */
	public boolean sendPacket(IOFSwitch switch_id, OFPort out_port, MacAddress src_mac, MacAddress dst_mac, 
			IPv4Address src_ip, IPv4Address dst_ip, IpProtocol proto, 
			TransportPort src_port, TransportPort dst_port, String data) {

		try{
			// sending packet in response
			OFPacketOut.Builder pktNew = switch_id.getOFFactory().buildPacketOut();
			pktNew.setBufferId(OFBufferId.NO_BUFFER);

			Ethernet ethNew = new Ethernet();
			ethNew.setSourceMACAddress(src_mac);
			ethNew.setDestinationMACAddress(dst_mac);
			ethNew.setEtherType(EthType.IPv4);

			IPv4 ipNew = new IPv4();
			ipNew.setSourceAddress(src_ip);
			ipNew.setDestinationAddress(dst_ip);

			ipNew.setProtocol(proto);
			ipNew.setTtl((byte) 64);

			UDP updNew = new UDP();
			updNew.setSourcePort(src_port);
			updNew.setDestinationPort(dst_port);

			Data dataNew = new Data();
			dataNew.setData(data.getBytes());

			// putting it all together
			ethNew.setPayload(ipNew.setPayload(updNew.setPayload(dataNew)));

			// set in-port to OFPP_NONE
			pktNew.setInPort(OFPort.ZERO);
			List<OFAction> actions = new ArrayList<OFAction>();
			actions.add(switch_id.getOFFactory().actions().output(out_port, 0xffFFffFF));

			pktNew.setActions(actions);
			pktNew.setData(ethNew.serialize());

			switch_id.write(pktNew.build());
			return true;
	
		} catch(Exception e) {
			e.printStackTrace();
		};
		
		return false;
	};

	protected Match createMatchFromPacket(IOFSwitch switch_id, OFPort in_port, FloodlightContext context) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(context, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		VlanVid vlan = VlanVid.ofVlan(eth.getVlanID());
		MacAddress src_mac = eth.getSourceMACAddress();
		MacAddress dst_mac = eth.getDestinationMACAddress();

		// The packet in match will only contain port number.
		Match.Builder mb = switch_id.getOFFactory().buildMatch();
		mb.setExact(MatchField.IN_PORT, in_port).setExact(MatchField.ETH_SRC, src_mac).setExact(MatchField.ETH_DST, dst_mac);

		if (!vlan.equals(VlanVid.ZERO)) {
			mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(vlan));
		};

		return mb.build();
	};

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	};

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	};

	@Override
	public void switchAdded(DatapathId switch_id) {
		System.out.println("--- SWITCH Added: '" + switch_id + "' ---");
		switch_mapping.put(switch_id, switchService.getSwitch(switch_id));

		// install ARP rule
		IOFSwitch iof_switch = switchService.getSwitch(switch_id);
		OFFlowMod.Builder fmb = iof_switch.getOFFactory().buildFlowAdd();
		
		Match.Builder mb = iof_switch.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.ARP);

		OFActionOutput.Builder actionBuilder = iof_switch.getOFFactory().actions().buildOutput();
		actionBuilder.setPort(OFPort.NORMAL);
		
		fmb.setActions(Collections.singletonList((OFAction) actionBuilder.build()));
		fmb.setHardTimeout(0).setIdleTimeout(0).setPriority(1).setBufferId(OFBufferId.NO_BUFFER).setMatch(mb.build());

		iof_switch.write(fmb.build());
	};

	@Override
	public void switchRemoved(DatapathId switch_id) {
		System.out.println("--- SWITCH Removed: '" + switch_id + "' ---");
		switchStats.remove(switch_id);
		switch_mapping.remove(switch_id);
	};

	@Override
	public void switchActivated(DatapathId switchId) {
		// TODO Auto-generated method stub
	};

	@Override
	public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {
		// TODO Auto-generated method stub
	};

	@Override
	public void switchChanged(DatapathId switchId) {
		// TODO Auto-generated method stub
	};

	@Override
	public void switchDeactivated(DatapathId switchId) {
		// TODO Auto-generated method stub
	};
}
