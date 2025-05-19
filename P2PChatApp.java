import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class P2PChatApp extends Frame implements ActionListener {
    private TextArea chatArea;
    private TextField inputField, nameField, ipField;
    private Button sendButton, connectButton;
    private String uniqueName;
    private ServerSocket serverSocket;
    private Set<String> knownPeers = new HashSet<>(); // Stores peer addresses (host:port)
    private Map<String, String> nameToAddress = new HashMap<>(); // Maps names to addresses
    private Set<String> seenMessageIds = new HashSet<>(); // Tracks seen message IDs
    private ExecutorService executor = Executors.newCachedThreadPool();

    public P2PChatApp() {
        setTitle("P2P Chat Application");
        setSize(600, 500);
        setLayout(new BorderLayout());

        // UI Components
        chatArea = new TextArea("", 10, 40, TextArea.SCROLLBARS_VERTICAL_ONLY);
        chatArea.setEditable(false);
        add(chatArea, BorderLayout.CENTER);

        Panel inputPanel = new Panel(new BorderLayout());
        inputField = new TextField(30);
        sendButton = new Button("Send");
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        add(inputPanel, BorderLayout.SOUTH);

        Panel connectPanel = new Panel(new FlowLayout());
        nameField = new TextField(10);
        ipField = new TextField(15);
        ipField.setText("localhost:0"); // Default value for standalone start
        connectButton = new Button("Connect");
        connectPanel.add(new Label("Unique Name:"));
        connectPanel.add(nameField);
        connectPanel.add(new Label("Peer IP:Port:"));
        connectPanel.add(ipField);
        connectPanel.add(connectButton);
        add(connectPanel, BorderLayout.NORTH);

        // Event Listeners
        sendButton.addActionListener(this);
        connectButton.addActionListener(this);
        inputField.addActionListener(this);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent we) {
                shutdown();
                System.exit(0);
            }
        });
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == connectButton) {
            String name = nameField.getText().trim();
            String peerAddress = ipField.getText().trim();
            if (name.isEmpty()) {
                chatArea.append("Please enter a unique name.\n");
                return;
            }
            if (!peerAddress.matches("^[a-zA-Z0-9.]+:[0-9]+$")) {
                chatArea.append("Please enter a valid IP:Port (e.g., localhost:5000).\n");
                return;
            }
            uniqueName = name;
            nameField.setEnabled(false);
            ipField.setEnabled(false);
            connectButton.setEnabled(false);
            startServer(peerAddress);
        } else if (e.getSource() == sendButton || e.getSource() == inputField) {
            String message = inputField.getText().trim();
            if (!message.isEmpty() && uniqueName != null) {
                String messageId = UUID.randomUUID().toString();
                String fullMessage = messageId + "|" + uniqueName + ": " + message;
                seenMessageIds.add(messageId); // Mark as seen locally
                broadcastMessage(fullMessage);
                chatArea.append(uniqueName + ": " + message + "\n"); // Display locally once
                inputField.setText("");
            }
        }
    }

    private void startServer(String bootstrapPeer) {
        try {
            serverSocket = new ServerSocket(0); // Dynamic port assignment
            String address = InetAddress.getLocalHost().getHostAddress() + ":" + serverSocket.getLocalPort();
            knownPeers.add(address);
            nameToAddress.put(uniqueName, address);
            chatArea.append("Started server on " + address + " as " + uniqueName + "\n");
            executor.submit(this::acceptConnections);

            // Connect to the specified peer if not localhost:0
            if (!bootstrapPeer.equals("localhost:0")) {
                connectToPeer(bootstrapPeer);
            }
        } catch (IOException e) {
            chatArea.append("Error starting server: " + e.getMessage() + "\n");
        }
    }

    private void acceptConnections() {
        try {
            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            if (!serverSocket.isClosed()) {
                chatArea.append("Server error: " + e.getMessage() + "\n");
            }
        }
    }

    private void handleClient(Socket socket) {
        try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
            while (true) {
                Object data = in.readObject();
                if (data instanceof String) {
                    String message = (String) data;
                    if (message.startsWith("JOIN:")) {
                        handleJoinMessage(message, socket.getInetAddress().getHostAddress() + ":" + socket.getPort());
                    } else if (message.startsWith("PEERS:")) {
                        updateKnownPeers(message);
                    } else {
                        processMessage(message);
                    }
                }
            }
        } catch (EOFException | SocketException e) {
            // Client disconnected
        } catch (IOException | ClassNotFoundException e) {
            chatArea.append("Client error: " + e.getMessage() + "\n");
        }
    }

    private void processMessage(String message) {
        String[] parts = message.split("\\|", 2);
        if (parts.length != 2) {
            return; // Invalid message format
        }
        String messageId = parts[0];
        String content = parts[1];

        synchronized (seenMessageIds) {
            if (!seenMessageIds.contains(messageId)) {
                seenMessageIds.add(messageId);
                chatArea.append(content + "\n");
                broadcastMessage(message); // Forward to other peers
            }
        }
    }

    private void handleJoinMessage(String message, String peerAddress) {
        String[] parts = message.split(":", 3);
        if (parts.length == 3) {
            String name = parts[1];
            String address = parts[2];
            if (!name.equals(uniqueName) && !nameToAddress.containsKey(name)) {
                knownPeers.add(address);
                nameToAddress.put(name, address);
                chatArea.append(name + " joined the network.\n");
                // Share known peers with the new peer
                sendPeerList(address);
                // Broadcast join message to others
                broadcastMessage(message);
            }
        }
    }

    private void updateKnownPeers(String message) {
        String[] parts = message.split(":", 2);
        if (parts.length == 2) {
            String[] peers = parts[1].split(",");
            for (String peer : peers) {
                if (!peer.equals(getOwnAddress()) && !knownPeers.contains(peer)) {
                    knownPeers.add(peer);
                    connectToPeer(peer);
                }
            }
        }
    }

    private void sendPeerList(String peerAddress) {
        try {
            String[] parts = peerAddress.split(":");
            Socket socket = new Socket(parts[0], Integer.parseInt(parts[1]));
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            String peerList = "PEERS:" + String.join(",", knownPeers);
            out.writeObject(peerList);
            out.flush();
            socket.close();
        } catch (IOException e) {
            chatArea.append("Error sending peer list to " + peerAddress + ": " + e.getMessage() + "\n");
        }
    }

    private void connectToPeer(String peerAddress) {
        if (knownPeers.contains(peerAddress) || peerAddress.equals(getOwnAddress())) {
            return;
        }
        try {
            String[] parts = peerAddress.split(":");
            Socket socket = new Socket(parts[0], Integer.parseInt(parts[1]));
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            String joinMessage = "JOIN:" + uniqueName + ":" + getOwnAddress();
            out.writeObject(joinMessage);
            out.flush();
            socket.close();
            knownPeers.add(peerAddress);
            chatArea.append("Connected to peer " + peerAddress + "\n");
        } catch (IOException e) {
            chatArea.append("Error connecting to " + peerAddress + ": " + e.getMessage() + "\n");
        }
    }

    private void broadcastMessage(String message) {
        for (String peer : knownPeers) {
            if (!peer.equals(getOwnAddress())) {
                try {
                    String[] parts = peer.split(":");
                    Socket socket = new Socket(parts[0], Integer.parseInt(parts[1]));
                    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                    out.writeObject(message);
                    out.flush();
                    socket.close();
                } catch (IOException e) {
                    chatArea.append("Error sending to " + peer + ": " + e.getMessage() + "\n");
                }
            }
        }
    }

    private String getOwnAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress() + ":" + serverSocket.getLocalPort();
        } catch (UnknownHostException e) {
            return "";
        }
    }

    private void shutdown() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        executor.shutdownNow();
    }

    public static void main(String[] args) {
        P2PChatApp app = new P2PChatApp();
        app.setVisible(true);
    }
}