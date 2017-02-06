/***********************************************************************
 * This file contains all the constants used in the controller's code  *
 ***********************************************************************/
package net.floodlightcontroller.splus;

import java.util.HashMap;

import org.projectfloodlight.openflow.types.DatapathId;

public class Constants {

    /* Enable / Disable Debug */
    final static boolean DEBUG = true;

	/* #### HSS DB Connection Parameters #### */
	final static String DB_DRIVER = "com.mysql.jdbc.Driver";
	final static String DB_CONNECTION = "jdbc:mysql://localhost:3306/HSS";
	final static String DB_USER = "root";
	final static String DB_PASSWORD = "root";

	/* #### Configurable Parameters #### */
	final static String UE_MAC = "00:00:01:aa:bb:ca";			// MAC Address of UE/eNodeB Node	*
	final static String ENODEB_SW_MAC = "00:00:01:aa:bb:cb";	// MAC Address of eNodeB Switch		*
	final static String SINK_MAC = "00:00:01:aa:bb:cc";			// MAC Address of SINK Node			*
																//									*
	// #### IP address of various component interfaces			//									*
	final static String RAN_IP = "192.168.127.2";				// Radio Access Network IP			*
	final static String ENODEB_SW_IP_UPLINK = "192.168.127.3";	// eNodeB Switch Up Link IP			*
	final static String ENODEB_SW_IP_DOWNLINK = "192.168.126.3";// eNodeB Switch Down Link IP		*
	final static String SGWD_IP_UPLINK = "192.168.126.4";		// SGW-D UP Link IP					*
	final static String SGWD_IP_DOWNLINK = "192.168.125.4";		// SGW-D Down Link IP				*
	final static String PGWD_IP_UPLINK = "192.168.125.5";		// PGW-D Up Link IP					*
	final static String PGWD_IP_DOWNLINK = "192.168.127.5";		// PGW-D Down Link IP				*
	final static String SINK_IP = "192.168.127.6";				// SINK IP							*
																//									*
	// The starting IP address which is allocated to the first UE connecting to our					*
	// network. After this addresses are assigned in monotonically increasing order.				*
	final static String STARTING_UE_IP = "192.168.127.7";		// UE Starting IP					*
	/* #### End of Configurable Parameters #### */

	
	/* #### Authentication and UE Tunnel Setup Codes ####  */
	final static String AUTHENTICATION_FAILURE = "-1";
	final static String AUTHENTICATION_STEP_ONE = "1";
	final static String AUTHENTICATION_STEP_TWO = "2";
	final static String AUTHENTICATION_STEP_THREE = "3";
	final static String NAS_STEP_ONE = "4";
	final static String SEND_APN = "5";
	final static String SEND_IP_SGW_TE_ID = "6";
	final static String SEND_UE_TE_ID = "7";
	final static String ATTACH_ACCEPT = "8";
	final static String SEPARATOR = "@:##:@";
	final static String DETACH_REQUEST = "9";
	final static String DETACH_ACCEPT = "10";
	final static String DETACH_FAILURE = "11";
	final static String REQUEST_STARTING_IP = "12";
	final static String SEND_STARTING_IP = "13";
	final static String UE_CONTEXT_RELEASE_REQUEST = "14";
	final static String UE_CONTEXT_RELEASE_COMMAND = "15";
	final static String UE_CONTEXT_RELEASE_COMPLETE = "16";
	final static String UE_SERVICE_REQUEST = "17";
	final static String INITIAL_CONTEXT_SETUP_REQUEST = "18";
	final static String INITIAL_CONTEXT_SETUP_RESPONSE = "19";
	final static String NAS_STEP_TWO = "20";
	final static String PAGING_REQUEST = "21";
	final static String INITIATE_NETWORK_SERVICE_REQUEST = "22";
	final static String SINK_SERVICE_REQUEST = "23";

	// Serving Network ID of the MME
	final static int SN_ID = 1;

	// Network type identifier
	final static String NW_TYPE = "UMTS";

	// Key length for AES encryption / decryption algorithm
	final static int ENC_KEY_LENGTH = 32;

	// Sample AES encryption / decryption key
	final static String SAMPLE_ENC_KEY = "lsC1gdMnsMlylg22s5ys1sN6u9FYSIeD";

	// Range of Tunnel IDs from 1 to 4095 depending upon the length of VLAN field of ethernet header
	final static int MIN_TE_ID = 1;
	final static int MAX_TE_ID = 4095;

	// Dispatch ID or unique ID of SGW-D switch (assuming only one SGW-D in the network)
	final static int SGW_DISPATCH_ID = 2;

	// boolean flags which control whether encryption and integrity checks needs to be performed or not.
	static boolean DO_ENCRYPTION = true;
	static boolean CHECK_INTEGRITY = true;

	// Dispatch ID or unique ID of eNodeB switch 
	final static int ENODEB_SW_ID = 1;
	
	// Dispatch ID of SGW-D
	final static int SGW_ID = 2;
	
	// Dispatch ID of PGW-D
	final static int PGW_ID = 4;
	
	// its the source port used by MME while sending UDP packets to UE
	final static int DEFAULT_CONTROL_TRAFFIC_UDP_PORT = 9876;

	// Port of eNodeB switch with which UE is connected
	final static int ENODEB_SW_UE_PORT = 3;

	// Port with which eNodeB switch is connected to UE
	final static int UE_PORT = 3; 

	// Port of PGW-D which is connected with sink
	final static int PGW_SINK_PORT = 4;

	/* We assumed here that there are two SGW-D namely SGW-D1 and SGW-D2 connected
	 * in series between eNodeB switch and PGW-D.
	 * 
	 * Note: one SGW-D is also good as long as its port numbers that are connected to
	 * both ends are correct.
	 */
	@SuppressWarnings("serial")
	final static HashMap<String, Integer> PGW_SGW_PORT_MAP = new HashMap<String, Integer>()
	{{
		put("4" + SEPARATOR + "2", 3); // for switch S2(SGW-D1) connected to S4(PGW-D) via port 4 of PGW
		put("4" + SEPARATOR + "3", 1); // for switch S3(SGW-D2) connected to S4(PGW-D) via port 1 of PGW
	}};

	@SuppressWarnings("serial")
	final static HashMap<DatapathId, int[]> SGW_PORT_MAP = new HashMap<DatapathId, int[]>()
	{{
		// new int[]{SGW-INPORT, SGW-OUTPORT}
		put(DatapathId.of(2), new int[]{3,4}); 

		// new int[]{SGW-INPORT, SGW-OUTPORT}
		put(DatapathId.of(3), new int[]{3,4}); 
	}};

	@SuppressWarnings("serial")
	final static HashMap<String, Integer> ENODEB_SGW_PORT_MAP = new HashMap<String, Integer>()
	{{
		put(ENODEB_SW_ID + SEPARATOR + "2", 4);// , 3// for switch S2(SGW-D1) connected to S1(eNodeB) via port 3 of eNodeB Switch
		put(ENODEB_SW_ID + SEPARATOR + "3", 1);// , 4// for switch S3(SGW-D2) connected to S1(ENodeB) via port 4 of eNodeB Switch
	}};

	/* Stores Algorithm ID for encryption / integrity algorithms */
	@SuppressWarnings("serial")
	final static HashMap<String, Integer> CIPHER_ALGO_MAP = new HashMap<String, Integer>()	
	{{
		put("AES", 1);
		put("HmacSHA1", 2);
	}};
}