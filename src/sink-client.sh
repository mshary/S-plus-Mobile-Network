#!/bin/bash
# ---- SinkServer Test Client ----
# author: Muhammad Shahzad Shafi
# data: Jan. 13, 2017

proto="udp"
host="192.168.0.28"
port="8001"

input="22@:##:@0x1234567890abcdef"

# open the socket for both input and output
exec 3<>/dev/${proto}/${host}/${port} && echo "Connected to $proto:$host:$port successfully" || echo "Socket Open Failed: $?"

# write data to socket
echo -e $input >&3
echo "Sent: $input"

MESSAGE=$(dd bs=1024 count=1 <&3 2> /dev/null)
echo "Received: $MESSAGE"

# close socket input
exec 3<&-

# close socket output
exec 3>&-

exit
