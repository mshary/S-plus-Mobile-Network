#!/bin/bash
# --- Network Service Request ---
# author: Muhammad Shahzad Shafi
# date: Jan. 13, 2017
# requires: JDK v7.x or better

# It starts specified number of sockets for both TCP & UDP to receieve
# and respond to Network Service Request from remote host. All sockets
# listen to given IP and multiple ports starting from given port number
# in ascending order.

debug=0

if [ $# != 3 ]; then
    echo "usage $0 <server-ip> <starting-port> <no-of-ports>"
    exit 1
elif [ $2 -le 0 ]; then
    echo "Starting port number should be greater than 0"
    exit 1
elif [ $3 -le 0 ]; then
    echo "Number of servers should be greater than 0"
    exit 1
else
	cmd=`which javac`
	if [ -e "$cmd" ] && [ -x "$cmd" ]; then
		if [ $debug -eq 1 ]; then
			`which javac` -verbose SinkServer.java
			`which java` -verbose:class SinkServer $1 $2 $3
		else
			`which javac` SinkServer.java
			`which java`  SinkServer $1 $2 $3
		fi
	else
		echo "JDK not installed. Please install Oracle JDK v8.x or better."
	fi
fi

echo
echo "Removing class files: "

# remove compiled classes
rm -fv *.class

exit
