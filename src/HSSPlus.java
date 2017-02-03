/* #### Home Subscriber Service ####
 * This class establishes a secure db connection
 * to mysql database for UE validation.
 */

package net.floodlightcontroller.splus;

import java.sql.ResultSet;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import org.projectfloodlight.openflow.types.DatapathId;

public class HSSPlus {
	HSSPlus() {
		// load db driver
		try {
			Class.forName(Constants.DB_DRIVER);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		};
	};

	/* Validates the UE on the various parameters like IMSI. */
    public String validateUE(
		String IMSI,	// International Mobile Subscriber Identity 
		int SNID, 		// Serving Network ID
		String NT, 		// Network Type ID
		int SQN, 		// UE Sequence Number
		String TAI		// Tracking Area ID
	) {
		long key = 0, rand;
		ResultSet rs = null;
		Connection cn = null;;
		PreparedStatement ps = null;
		String sql = "SELECT key FROM ue_info WHERE imsi = ? AND tai = ? AND nt = ?";

		try {
			// connect to mysql db
			cn = DriverManager.getConnection(Constants.DB_CONNECTION, Constants.DB_USER, Constants.DB_PASSWORD);

			// prepare sql statement
			ps = cn.prepareStatement(sql);
			ps.setLong(1, Long.parseLong(IMSI));
			ps.setLong(2, Long.parseLong(TAI));
			ps.setString(3, NT);

			// execute query
			rs = ps.executeQuery();

			// check if we have found a matching record
			if (rs.next()) {
				key = rs.getLong("key");

			// if not, then just return null - validation failed
			} else {
				System.out.println("Error in selecting UE key - no matching record in HSSPlus");
				return null;
			};

			// get a random number from any range of numbers
			rand = Utils.randInt(16, 255);

			// Authentication & Key Agreement Algorithm
			long CK		= (rand + 1) * (key - 1) - (SQN + 1);	// Cipher key
			long IK		= (rand + 1) * (key + 1) - (SQN - 1);	// Integrity key

			long xres	= rand * key + SQN;						// Expected Response
			long autn	= (rand - 1) * (key + 1) - SQN;			// Authentication token
			long K_ASME	= SQN * CK + SNID * IK;					// Access Security Management

			// validation successful
			return xres + Constants.SEPARATOR + autn + Constants.SEPARATOR + rand + Constants.SEPARATOR + K_ASME;

		// if anything went wrong then return null - validation failure
		} catch (SQLException e) {
			System.out.println(e.getMessage());

		// make sure to close db connection and resultset etc. before exit
		} finally {
			try {
				if (ps != null) { ps.close(); };
				if (rs != null) { rs.close(); };
				if (cn != null) { cn.close(); };
			} catch (Exception e) {
				e.printStackTrace();
			};
		};

		// validation failure
		return null;
	};

	/* Featches the ID of PGW based on the APN (Access Point Name) specified by the UE */
	public DatapathId getPGW(String apn) {
		ResultSet rs = null;
		Connection cn = null;
		DatapathId dpid = null;
		PreparedStatement ps = null;
		String sql = "SELECT dispatch_id FROM pgw_info WHERE apn = ?";

		try {
			// connect to mysql db
			cn = DriverManager.getConnection(Constants.DB_CONNECTION, Constants.DB_USER, Constants.DB_PASSWORD);

			// prepare sql statement
			ps = dbConnection.prepareStatement(selectSQL);
			ps.setLong(1, Long.parseLong(apn));

			// execute query
			rs = ps.executeQuery();

			// check if we have found a matching record
			if (rs.next()) {
				dpid = DatapathId.of(rs.getLong("dispatch_id"));

			// if not, then just return null - validation failed
			} else {
				System.out.println("Error in selecting dispatch id - no matching record in HSSPlus");
				return null;
			};

			return dpid;

		} catch (SQLException e) {
			System.out.println(e.getMessage());

		} finally {
			// make sure to close db connection and resultset etc. before exit
			try {
				if (ps != null) { ps.close(); };
				if (rs != null) { rs.close(); };
				if (cn != null) { cn.close(); };
			} catch (Exception e) {
				e.printStackTrace();
			};
		};

		return null;
	};
}

