/***********************************************************************
 * This file contains all the constants used in the controller's code  *
 ***********************************************************************/
package net.floodlightcontroller.splus;

import java.util.HashMap;

import org.projectfloodlight.openflow.types.DatapathId;

public class Constants {

    /* Enable / Disable Debug */
    static boolean DEBUG = true;

	/* #### HSS DB Connection Parameters #### */
	static final String DB_DRIVER = "com.mysql.jdbc.Driver";
	static final String DB_CONNECTION = "jdbc:mysql://localhost:3306/HSS";
	static final String DB_USER = "root";
	static final String DB_PASSWORD = "root";

}
