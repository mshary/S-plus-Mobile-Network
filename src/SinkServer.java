/**
 * #### Sink Service ####
 * @author Muhammad Shahzad Shafi
 * date: Jan. 11, 2017
 * This class implements Sink Server for
 * S+ Mobile Network Architecture.
 */

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

public class SinkServer implements Runnable {
	private final String INITIATE_NETWORK_SERVICE_REQUEST = "22";
	private final String SINK_SERVICE_REQUEST = "23";
	private final String SEPARATOR = "@:##:@";
	private final int BUFFER_SIZE = 1024;

	public static enum SOCKET_TYPE { NONE, TCP, UDP, ALL };

	private Selector selector;	
	private SOCKET_TYPE type;
	
	public SinkServer(String host, int startPort, int max, SOCKET_TYPE type) throws IOException {
		this.type = type;
		this.selector = Selector.open();
		
		for (int x=0, y=startPort; x<max; x++, y++) {
			if (this.type == SOCKET_TYPE.TCP || type == SOCKET_TYPE.ALL) {
				ServerSocketChannel tcps = ServerSocketChannel.open();
				tcps.socket().setReuseAddress(true);
				tcps.configureBlocking(false);

				System.out.println("Starting TCP Server => " + host + ":" + y);
				tcps.socket().bind(new InetSocketAddress(host, y));
				tcps.register(selector, SelectionKey.OP_ACCEPT);					
			};
			
			if (this.type == SOCKET_TYPE.UDP || type == SOCKET_TYPE.ALL) {
				DatagramChannel udps = DatagramChannel.open();
				udps.socket().setReuseAddress(true);
				udps.configureBlocking(false);

				System.out.println("Starting UDP Server => " + host + ":" + y);
				
				udps.socket().bind(new InetSocketAddress(host, y));
				udps.register(selector, SelectionKey.OP_READ|SelectionKey.OP_WRITE);
			};
		};
		
		System.out.println();
		
		if (this.type == SOCKET_TYPE.TCP || this.type == SOCKET_TYPE.ALL) {
			System.out.println("Number of active TCP Servers: " + max);
		};
		
		if (this.type == SOCKET_TYPE.UDP || this.type == SOCKET_TYPE.ALL) {
			System.out.println("Number of active UDP Servers: " + max);
		};
		
		System.out.println();
	};

	protected void TCPProcessor(SelectionKey key) throws IOException {
		if (key.isAcceptable()) {			
			ServerSocketChannel tcps = (ServerSocketChannel)key.channel();
			SocketChannel client = tcps.accept();

			System.out.println("Accepted connection from TCP client at: " + client.getRemoteAddress());
			client.configureBlocking(false);

			// create and attach an empty data buffer
			ByteBuffer input = ByteBuffer.allocate(BUFFER_SIZE);

			// indicate that we wait till client send us something
			SelectionKey key2 = client.register(selector, SelectionKey.OP_READ);

			// client input must be saved in data buffer we created earlier
			key2.attach(input);
			
		} else if (key.isReadable()) {
			// retrieve data buffer
			SocketChannel client = (SocketChannel) key.channel();
			ByteBuffer data = (ByteBuffer) key.attachment();

			// read what remote client sent us into data buffer
			client.read(data);

			// process received data
			String input = this.processReceivedData(data);

			// process data to be sent
			ByteBuffer output = this.processSendData(input);

			// inform that we want to write socket now
			SelectionKey key2 = client.register(selector, SelectionKey.OP_WRITE);

			// attach data buffer containing our response data
			key2.attach(output);
			
		} else if (key.isWritable()) {
			// retrieve data buffer
			SocketChannel client = (SocketChannel) key.channel();
			ByteBuffer data = (ByteBuffer) key.attachment();

			// go to start of buffer if we are somewhere in the middle - precaution
			if (!data.hasRemaining()) { data.rewind(); };

			// write data back to client
			client.write(data);

			/*
			// clear and reset data buffer to empty
			ByteBuffer input = ByteBuffer.allocate(BUFFER_SIZE);

			// indicate that we will wait till more data arrives from client.
			SelectionKey key2 = client.register(selector, SelectionKey.OP_READ);

			// client input must be saved in data buffer
			key2.attach(input);
			 */

			// disconnect the client
			client.close();
		};
	};
	
	protected void UDPProcessor(SelectionKey key) throws IOException {
		DatagramChannel channel = (DatagramChannel) key.channel();
		ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

		SocketAddress client = channel.receive(buffer);
		key.attach(buffer);

		if (client != null) {
			ByteBuffer data = ByteBuffer.allocate(BUFFER_SIZE);
			data = (ByteBuffer) key.attachment();

			System.out.println("Accepted connection from UDP client at: " + client);

			// process received data
			String input = this.processReceivedData(data);

			// process data to be sent
			data = this.processSendData(input);

			// send it to remove client
			channel.send(data, client);
		};
	};
	
	public void run() {
		SinkServer.SOCKET_TYPE current_type = SOCKET_TYPE.NONE;
		
		while (true) {

			try {
				selector.select();
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			};

			Set<SelectionKey> readyKeys = selector.selectedKeys();
			Iterator<SelectionKey> iterator = readyKeys.iterator();

			while (iterator.hasNext()) {
				SelectionKey key = (SelectionKey) iterator.next();
				iterator.remove();

				try {
					if (this.type == SOCKET_TYPE.TCP || this.type == SOCKET_TYPE.ALL) {
						current_type = SOCKET_TYPE.TCP;
						this.TCPProcessor(key);

					} else if (this.type == SOCKET_TYPE.UDP || this.type == SOCKET_TYPE.ALL) {
						current_type = SOCKET_TYPE.UDP;
						this.UDPProcessor(key);
					};
				} catch (IOException e) {
					e.printStackTrace();
					System.exit(1);
				} catch (ClassCastException e) {
					try {
						if (current_type == SOCKET_TYPE.TCP) {
							this.UDPProcessor(key);
						} else if (current_type == SOCKET_TYPE.UDP) {
							this.TCPProcessor(key);;
						};
					} catch (IOException ex) {
						ex.printStackTrace();
						System.exit(1);
					};
				};
			};
		}
	};
	
	protected String processReceivedData(ByteBuffer data) {
		String retval = new String(data.array());
		System.out.println("Received: " + retval);		
		return retval;
	};

	protected ByteBuffer processSendData(String data) {
		// [segments[0] => INITIATE_NETWORK_SERVICE_REQUEST Code, segments[1] => UE Key]
		String[] segments = data.split(this.SEPARATOR);

		if (segments.length != 2) {
			System.out.println("Error: Bad Service Request: " + data);
			return ByteBuffer.wrap(data.getBytes());
		};
		
		if (segments[0] == this.INITIATE_NETWORK_SERVICE_REQUEST) {
			System.out.println("Error: Bad Service Request: " + segments[0]);
			return ByteBuffer.wrap(data.getBytes());
		};
		
		try { Thread.sleep((long)Math.random()*1000 + 1); } catch (Exception e) { System.out.println(e.getMessage()); };
		String out = this.SINK_SERVICE_REQUEST + this.SEPARATOR + segments[1];
		System.out.println("Sent: " + out);
		return ByteBuffer.wrap(out.getBytes());
	};

	public static void main(String[] args) {
		if (args.length != 3){
			System.out.println("Usage: <server-ip> <starting-port> <num-servers>");
			System.exit(1);
		};

		System.out.println("Args => Listen IP: " + args[0] + ", Start Port: " + args[1] + ", Active Servers: " + args[2]);
		System.out.println();
		
		String host = args[0];
		Integer port = new Integer(args[1]);
		Integer count = new Integer(args[2]);

		try {
			SinkServer server = new SinkServer(host, port, count, SOCKET_TYPE.ALL);
			server.run();
			
		} catch(IOException e) {
			e.printStackTrace();
		};
	};
}
