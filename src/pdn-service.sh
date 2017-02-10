#!/bin/bash
# --- Public Data Network Service ---
# author: Muhammad Shahzad Shafi
# date: Jan. 13, 2017
# requires: iperf3

# It starts specified number of iperf3 instances in daemon mode.
# All instances listen on given IP and multiple ports starting from
# given port number in ascending order.

if [ $# != 3 ]; then
    echo "Usage: sh $0 <server-ip> <starting-port> <no-of-ports>"
    exit 1
elif [ $2 -le 0 ]; then
    echo "Starting port number should be greater than 0"
    exit 1
elif [ $3 -le 0 ]; then
    echo "Number of servers should be greater than 0"
    exit 1
else
	cmd=`which iperf3`
	if [ -e "$cmd" ] && [ -x "$cmd" ]; then
    	for i in $(seq 0 `expr "$3" - 1`);
		do
        	port=`expr "$2" + "$i"`
        	$cmd -s -B $1 -p $port -D
    	done
	else
		echo "iperf3: command not found"
	fi
fi

exit
