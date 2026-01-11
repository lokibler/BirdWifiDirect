// File: com/sheproduces/wifidirect/WifiDirectManager.java
package com.sheproduces.wifidirect;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.p2p.*;
import android.net.wifi.WpsInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wi-Fi Direct Manager for Android 15
 * Required Permissions:
 * - NEARBY_WIFI_DEVICES (runtime, Android 13+)
 * - ACCESS_FINE_LOCATION (runtime)
 * - CHANGE_NETWORK_STATE (may help with network binding on Android 15)
 * - ACCESS_WIFI_STATE (declarative)
 * - CHANGE_WIFI_STATE (declarative)
 */
public class WifiDirectManager {

    private static final String TAG = "WifiDirectManager";
    private static WifiDirectManager instance;

    // Connection states
    public enum ConnectionState {
        IDLE(0),
        DISCOVERING(1),
        CONNECTING(2),
        CONNECTED(3),
        ERROR(4);

        private final int value;
        ConnectionState(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    public interface ConnectionStateListener {
        void onStateChanged(ConnectionState state, String details);
        void onMessageReceived(String msg);
    }

    // Context and services
    private final Context appContext;
    private WeakReference<Activity> activityRef;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final ConnectivityManager connectivityManager;

    // State management
    private final AtomicReference<ConnectionState> connectionState = new AtomicReference<>(ConnectionState.IDLE);
    private ConnectionStateListener stateListener;
    
    // Device identity
    private volatile String frameId = null; // e.g., "FRAME_A", "FRAME_B"
    private volatile boolean isHost = false; // Whether this device acts as a host
    private volatile boolean multipleHostsExist = false; // Whether multiple hosts exist in the network
    
    // Network binding
    private volatile Network boundP2pNetwork = null;
    private ConnectivityManager.NetworkCallback p2pNetworkCallback = null;
    
    // Connection info (stored but not currently used - kept for potential future use)
    @SuppressWarnings("unused")
    private volatile WifiP2pInfo currentInfo = null;
    
    // Broadcast receiver
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;
    
    // Socket management
    private final ExecutorService socketExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WifiDirect-Socket");
        t.setDaemon(true);
        return t;
    });
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "WifiDirect-Scheduler");
        t.setDaemon(true);
        return t;
    });
    
    // Socket connections
    // For hosts: Map of client frame ID -> SocketConnection (supports up to 4 clients)
    // For clients: Single connection to host (stored with key "HOST")
    private final Map<String, SocketConnection> activeConnections = new ConcurrentHashMap<>();
    private final int MAX_CLIENTS = 4; // Maximum clients per host
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public static WifiDirectManager getInstance(Activity activity) {
        if (instance == null) {
            instance = new WifiDirectManager(activity);
        } else {
            instance.setActivity(activity);
        }
        return instance;
    }

    private WifiDirectManager(Activity activity) {
        this.appContext = activity.getApplicationContext();
        this.activityRef = new WeakReference<>(activity);
        this.manager = (WifiP2pManager) appContext.getSystemService(Context.WIFI_P2P_SERVICE);
        this.connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.channel = manager.initialize(activity, activity.getMainLooper(), null);
        
        setupIntentFilter();
        setupReceiver();
    }

    private void setActivity(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
    }

    public void setConnectionStateListener(ConnectionStateListener listener) {
        this.stateListener = listener;
    }

    /**
     * Set the frame ID for this device (e.g., "FRAME_A", "FRAME_B")
     * Note: The Wi-Fi Direct device name must be manually set to "BIRD_FRAME_X" on the tablet.
     */
    public void setFrameId(String frameId) {
        this.frameId = frameId;
        Log.d(TAG, "Frame ID set to: " + frameId + " (device name should be BIRD_FRAME_" + frameId + ")");
    }

    /**
     * Set whether this device should act as a host (accepts multiple client connections)
     */
    public void setIsHost(boolean isHost) {
        this.isHost = isHost;
        Log.d(TAG, "Host role set to: " + isHost);
    }

    /**
     * Set whether multiple hosts exist in the network (affects reconnection logic)
     */
    public void setMultipleHostsExist(boolean multiple) {
        this.multipleHostsExist = multiple;
        Log.d(TAG, "Multiple hosts exist: " + multiple);
    }

    private void setState(ConnectionState newState, String details) {
        ConnectionState oldState = connectionState.getAndSet(newState);
        if (oldState != newState) {
            Log.d(TAG, "State: " + oldState + " -> " + newState + (details != null ? " (" + details + ")" : ""));
            if (stateListener != null) {
                stateListener.onStateChanged(newState, details);
            }
        }
    }

    private ConnectionState getState() {
        return connectionState.get();
    }

    // ---------------- PERMISSIONS ----------------

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean hasP2pPermissions() {
        boolean nearbyOk = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearbyOk = ContextCompat.checkSelfPermission(appContext, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        boolean fineOk = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        return nearbyOk && fineOk;
    }

    public void ensurePermissions() {
        Activity a = activityRef != null ? activityRef.get() : null;
        if (a == null) {
            setState(ConnectionState.ERROR, "PERMS_NO_ACTIVITY");
            return;
        }

        List<String> toRequest = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(a, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(a, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(a, toRequest.toArray(new String[0]), 1234);
        }
    }

    // ---------------- ROLE SELECTION ----------------

    /**
     * Determines if this device should initiate connection based on lexicographic comparison
     * Higher-named device (e.g., "FRAME_B") becomes Group Owner (server)
     * Lower-named device (e.g., "FRAME_A") connects as client
     * 
     * @param myFrameId This device's frame ID
     * @param peerFrameId Peer device's frame ID (extracted from device name)
     * @return true if this device should initiate (becomes GO), false to wait for connection
     */
    private boolean shouldInitiateConnection(String myFrameId, String peerFrameId) {
        if (myFrameId == null || peerFrameId == null) {
            return false; // Can't determine, don't initiate
        }
        // Lexicographic comparison: higher name becomes Group Owner
        // e.g., "FRAME_B" > "FRAME_A", so B initiates and becomes GO
        return myFrameId.compareTo(peerFrameId) > 0;
    }

    /**
     * Extract frame ID from device name (assumes format "BIRD_FRAME_X")
     */
    private String extractFrameIdFromDeviceName(String deviceName) {
        if (deviceName == null || !deviceName.startsWith("BIRD_FRAME_")) {
            return null;
        }
        return deviceName.substring("BIRD_FRAME_".length());
    }

    // ---------------- DISCOVERY ----------------

    public void discoverPeers() {
        if (shutdown.get()) return;
        
        ensurePermissions();
        if (!hasP2pPermissions()) {
            setState(ConnectionState.ERROR, "DISCOVER_SKIP:NO_PERMS");
            return;
        }

        ConnectionState current = getState();
        if (current == ConnectionState.CONNECTED || current == ConnectionState.CONNECTING) {
            Log.d(TAG, "Skipping discovery - already connected or connecting");
            return;
        }

        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override 
                public void onSuccess() {
                    Log.d(TAG, "Discovery started");
                    setState(ConnectionState.DISCOVERING, null);
                }
                
                @Override 
                public void onFailure(int reason) {
                    Log.w(TAG, "Discovery failed, reason: " + reason);
                    setState(ConnectionState.ERROR, "DISCOVER_FAIL:" + reason);
                    // Auto-retry after delay
                    scheduler.schedule(() -> {
                        if (getState() != ConnectionState.CONNECTED && !shutdown.get()) {
                            discoverPeers();
                        }
                    }, 5, TimeUnit.SECONDS);
                }
            });
        } catch (SecurityException se) {
            Log.e(TAG, "Security exception starting discovery", se);
            setState(ConnectionState.ERROR, "DISCOVER_SECURITY_EXCEPTION");
        }
    }

    public void connectFirstPeer() {
        if (shutdown.get()) return;
        
        // Hosts don't initiate connections - they wait for clients to connect
        if (isHost) {
            Log.d(TAG, "Host device - skipping connectFirstPeer, waiting for clients to connect");
            return;
        }
        
        if (frameId == null) {
            setState(ConnectionState.ERROR, "CONNECT_NO_FRAME_ID");
            Log.w(TAG, "Cannot connect - frameId not set. Call setFrameId() first.");
            return;
        }

        ensurePermissions();
        if (!hasP2pPermissions()) {
            setState(ConnectionState.ERROR, "CONNECT_SKIP:NO_PERMS");
            return;
        }

        ConnectionState current = getState();
        if (current == ConnectionState.CONNECTED || current == ConnectionState.CONNECTING) {
            Log.d(TAG, "Skipping connect - already connected or connecting");
            return;
        }

        setState(ConnectionState.CONNECTING, null);

        try {
            manager.requestPeers(channel, peers -> {
                if (peers == null || peers.getDeviceList().isEmpty()) {
                    setState(ConnectionState.ERROR, "NO_PEERS_AVAILABLE");
                    return;
                }

                // Log all discovered peers for debugging
                StringBuilder allPeers = new StringBuilder();
                for (WifiP2pDevice d : peers.getDeviceList()) {
                    if (allPeers.length() > 0) allPeers.append(",");
                    allPeers.append(d.deviceName != null ? d.deviceName : "null");
                }
                Log.d(TAG, "All discovered peers: " + allPeers);

                // Find matching BIRD_FRAME device (client connects to any available peer)
                WifiP2pDevice target = null;
                
                for (WifiP2pDevice d : peers.getDeviceList()) {
                    Log.d(TAG, "Checking device: " + d.deviceName + " (looking for BIRD_FRAME_ prefix)");
                    if (d.deviceName != null && d.deviceName.startsWith("BIRD_FRAME_")) {
                        String extractedFrameId = extractFrameIdFromDeviceName(d.deviceName);
                        Log.d(TAG, "Found BIRD_FRAME device: " + d.deviceName + ", extracted frameId: " + extractedFrameId);
                        if (extractedFrameId != null && !extractedFrameId.equals(frameId)) {
                            target = d;
                            break;
                        } else {
                            Log.d(TAG, "Skipping device - same frameId as ours (" + frameId + ")");
                        }
                    }
                }

                if (target == null) {
                    Log.w(TAG, "No matching BIRD_FRAME peer found. Our frameId: " + frameId + ", All peers: " + allPeers);
                    setState(ConnectionState.ERROR, "NO_MATCHING_PEERS:found=" + allPeers);
                    return;
                }

                // Client always initiates connection
                Log.d(TAG, "Client initiating connection to " + target.deviceName);
                connect(target);
            });
        } catch (SecurityException se) {
            setState(ConnectionState.ERROR, "REQUEST_PEERS_SECURITY_EXCEPTION");
        }
    }

    private void connect(WifiP2pDevice device) {
        WifiP2pConfig cfg = new WifiP2pConfig();
        cfg.deviceAddress = device.deviceAddress;
        cfg.wps.setup = WpsInfo.PBC;

        setState(ConnectionState.CONNECTING, "CONNECTING_TO:" + device.deviceName);

        try {
            manager.connect(channel, cfg, new WifiP2pManager.ActionListener() {
                @Override 
                public void onSuccess() {
                    Log.d(TAG, "Connection request sent");
                }
                
                @Override 
                public void onFailure(int reason) {
                    setState(ConnectionState.ERROR, "CONNECT_FAIL:" + reason);
                    // Retry after delay
                    scheduler.schedule(() -> {
                        if (getState() != ConnectionState.CONNECTED && !shutdown.get()) {
                            connectFirstPeer();
                        }
                    }, 5, TimeUnit.SECONDS);
                }
            });
        } catch (SecurityException se) {
            setState(ConnectionState.ERROR, "CONNECT_SECURITY_EXCEPTION");
        }
    }

    // ---------------- RECEIVER ----------------

    private void setupIntentFilter() {
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    private void setupReceiver() {
        receiver = new BroadcastReceiver() {
            @Override 
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    boolean connected = (ni != null && ni.isConnected());

                    if (connected) {
                        Log.d(TAG, "Link up");
                        try {
                            manager.requestConnectionInfo(channel, connectionInfoListener);
                        } catch (SecurityException se) {
                            setState(ConnectionState.ERROR, "REQ_INFO_SECURITY_EXCEPTION");
                        }
                    } else {
                        Log.d(TAG, "Link down");
                        handleDisconnection();
                    }
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    // Peer discovery results
                    try {
                        manager.requestPeers(channel, peers -> {
                            if (peers == null) {
                                return;
                            }
                            
                            // Report peers to listener
                            StringBuilder peerList = new StringBuilder();
                            StringBuilder allPeersList = new StringBuilder();
                            for (WifiP2pDevice d : peers.getDeviceList()) {
                                String name = d.deviceName != null ? d.deviceName : "null";
                                if (allPeersList.length() > 0) allPeersList.append(",");
                                allPeersList.append(name);
                                
                                if (d.deviceName != null && d.deviceName.startsWith("BIRD_FRAME_")) {
                                    if (peerList.length() > 0) peerList.append(",");
                                    peerList.append(d.deviceName);
                                }
                            }
                            
                            Log.d(TAG, "Peer discovery update - All peers: " + allPeersList + ", BIRD_FRAME peers: " + peerList);
                            
                            // Always report peers, even if empty, so Unity knows discovery is working
                            setState(getState(), "PEERS:" + (peerList.length() > 0 ? peerList.toString() : "none") + ":all=" + allPeersList);
                        });
                    } catch (SecurityException se) {
                        Log.e(TAG, "Error requesting peers", se);
                    }
                }
            }
        };
    }

    public void registerReceiver() {
        Activity a = activityRef != null ? activityRef.get() : null;
        if (a == null) {
            Log.w(TAG, "Cannot register receiver - no activity");
            return;
        }
        try {
            a.registerReceiver(receiver, intentFilter);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receiver", e);
        }
    }

    public void unregisterReceiver() {
        Activity a = activityRef != null ? activityRef.get() : null;
        if (a == null) return;
        try {
            a.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered
        }
    }

    // ---------------- CONNECTION INFO ----------------

    private final WifiP2pManager.ConnectionInfoListener connectionInfoListener = info -> {
        currentInfo = info;

        if (info == null) {
            setState(ConnectionState.ERROR, "INFO_NULL");
            return;
        }

        Log.d(TAG, "Connection info: groupFormed=" + info.groupFormed + ", isGO=" + info.isGroupOwner);

        if (!info.groupFormed) {
            setState(ConnectionState.IDLE, "GROUP_NOT_FORMED");
            return;
        }

        // Bind to P2P network first, then start sockets
        bindToP2pNetwork(() -> {
            if (info.isGroupOwner) {
                // Only start server if we're configured as a host
                if (isHost) {
                    startServer();
                } else {
                    Log.w(TAG, "Became GO but not configured as host - this shouldn't happen with explicit host/client roles");
                    setState(ConnectionState.ERROR, "ROLE_MISMATCH:GO_but_not_host");
                }
            } else {
                // Client connects to host
                startClient(info.groupOwnerAddress);
            }
        });
    };

    // ---------------- NETWORK BINDING (Android 15) ----------------

    /**
     * Bind to Wi-Fi Direct network for reliable socket routing on Android 15
     * Required permissions: CHANGE_NETWORK_STATE (may help avoid security exceptions)
     */
    private void bindToP2pNetwork(Runnable onBound) {
        if (boundP2pNetwork != null) {
            Log.d(TAG, "Network already bound");
            onBound.run();
            return;
        }

        // Clean any previous callback
        if (p2pNetworkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(p2pNetworkCallback);
            } catch (Exception ignored) {}
            p2pNetworkCallback = null;
        }

        NetworkRequest.Builder b = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

        // Android 10+ capability for Wi-Fi P2P network selection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.addCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P);
        }

        NetworkRequest req = b.build();

        p2pNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                boundP2pNetwork = network;
                try {
                    connectivityManager.bindProcessToNetwork(network);
                    Log.d(TAG, "Network bound successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to bind process to network", e);
                }
                onBound.run();
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (network != null && network.equals(boundP2pNetwork)) {
                    Log.d(TAG, "Bound network lost");
                    boundP2pNetwork = null;
                    try {
                        connectivityManager.bindProcessToNetwork(null);
                    } catch (Exception ignored) {}
                }
            }
        };

        try {
            connectivityManager.requestNetwork(req, p2pNetworkCallback);
            Log.d(TAG, "Network request submitted");
        } catch (SecurityException se) {
            Log.w(TAG, "Network request security exception (may need CHANGE_NETWORK_STATE permission)", se);
            // Continue anyway - process binding might still work, or rely on default routing
            onBound.run();
        }
    }

    // ---------------- SOCKET MANAGEMENT ----------------

    private ServerSocket serverSocket = null;
    private final Set<String> connectedFrameIds = ConcurrentHashMap.newKeySet(); // Track connected client frame IDs

    private void startServer() {
        // Close any existing connections
        synchronized (activeConnections) {
            for (SocketConnection conn : activeConnections.values()) {
                if (conn != null) {
                    conn.close();
                }
            }
            activeConnections.clear();
            connectedFrameIds.clear();
        }

        CompletableFuture.runAsync(() -> {
            ServerSocket ss = null;

            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(8988));
                serverSocket = ss;
                Log.d(TAG, "Server listening on port 8988");

                // Update state once server is listening
                synchronized (activeConnections) {
                    if (activeConnections.isEmpty()) {
                        setState(ConnectionState.CONNECTED, "ROLE=HOST:CONNECTED=0/" + MAX_CLIENTS + ":PORT=8988");
                    }
                }

                // Accept multiple client connections (up to MAX_CLIENTS)
                while (!shutdown.get() && ss != null) {
                    try {
                        Socket clientSocket = ss.accept();
                        if (clientSocket == null) continue;

                        synchronized (activeConnections) {
                            if (activeConnections.size() >= MAX_CLIENTS) {
                                Log.w(TAG, "Max clients reached (" + MAX_CLIENTS + "), rejecting connection from " + clientSocket.getInetAddress());
                                try {
                                    clientSocket.close();
                                } catch (Exception ignored) {}
                                continue;
                            }
                        }

                        Log.d(TAG, "Server accepted connection from " + clientSocket.getInetAddress());
                        
                        // Handle each client connection in a separate thread
                        handleClientConnection(clientSocket);
                    } catch (java.net.SocketException e) {
                        // Socket closed or connection reset - expected when shutting down
                        if (!shutdown.get()) {
                            Log.d(TAG, "Server socket closed or connection reset");
                        }
                        break;
                    } catch (Exception e) {
                        if (!shutdown.get()) {
                            Log.e(TAG, "Error accepting client connection", e);
                        }
                    }
                }

                Log.d(TAG, "Server stopped accepting connections");
            } catch (Exception e) {
                if (!shutdown.get()) {
                    Log.e(TAG, "Server error", e);
                    setState(ConnectionState.ERROR, "SOCKET_ERROR:" + e.getClass().getSimpleName());
                    handleDisconnection();
                }
            } finally {
                if (ss != null) {
                    try {
                        ss.close();
                    } catch (Exception ignored) {}
                }
                serverSocket = null;
            }
        }, socketExecutor);
    }

    private void handleClientConnection(Socket clientSocket) {
        CompletableFuture.runAsync(() -> {
            BufferedReader in = null;
            PrintWriter out = null;
            String connectionKey = null; // Will be set to frame ID once we receive a message

            try {
                out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream())), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                // Use IP address as temporary key until we get frame ID from messages
                connectionKey = clientSocket.getInetAddress().toString();
                
                SocketConnection conn = new SocketConnection(clientSocket, in, out);
                synchronized (activeConnections) {
                    activeConnections.put(connectionKey, conn);
                }

                Log.d(TAG, "Client connection handler started for " + connectionKey);
                
                // Update connection state
                updateConnectionState();

                String line;
                while (!shutdown.get() && (line = in.readLine()) != null) {
                    // Extract frame ID from message if present
                    // Message format: {targetFrameId}:{messageType}:{sourceFrameId}:{data}
                    String sourceFrameId = extractSourceFrameId(line);
                    if (sourceFrameId != null && !connectionKey.equals(sourceFrameId)) {
                        // Update mapping from IP to frame ID
                        synchronized (activeConnections) {
                            if (activeConnections.containsKey(connectionKey)) {
                                SocketConnection oldConn = activeConnections.remove(connectionKey);
                                activeConnections.put(sourceFrameId, oldConn);
                                connectionKey = sourceFrameId;
                                connectedFrameIds.add(sourceFrameId);
                            }
                        }
                        updateConnectionState();
                    }

                    // Route message to Unity
                    if (stateListener != null) {
                        stateListener.onMessageReceived(line);
                    }
                }

                Log.d(TAG, "Client connection closed: " + connectionKey);
            } catch (Exception e) {
                if (!shutdown.get()) {
                    Log.e(TAG, "Client connection error for " + connectionKey, e);
                }
            } finally {
                synchronized (activeConnections) {
                    if (connectionKey != null) {
                        activeConnections.remove(connectionKey);
                        if (connectionKey.startsWith("BIRD_FRAME_") || !connectionKey.contains(".")) {
                            // It's a frame ID, not an IP
                            connectedFrameIds.remove(connectionKey);
                        } else {
                            // It's an IP, need to find and remove associated frame ID
                            connectedFrameIds.removeIf(id -> {
                                // Check if this IP connection might have been mapped to a frame ID
                                // This is approximate - ideally we'd track the mapping
                                return false; // Keep all frame IDs for now
                            });
                        }
                    }
                }
                
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (clientSocket != null) try { clientSocket.close(); } catch (Exception ignored) {}
                
                updateConnectionState();
            }
        }, socketExecutor);
    }

    private String extractSourceFrameId(String message) {
        // Message format: {targetFrameId}:{messageType}:{sourceFrameId}:{data}
        if (message == null) return null;
        String[] parts = message.split(":", 4);
        if (parts.length >= 3) {
            String sourceFrameId = parts[2];
            // Validate it looks like a frame ID (starts with BIRD_FRAME_ or is a simple frame ID)
            if (sourceFrameId.startsWith("BIRD_FRAME_")) {
                return sourceFrameId.substring("BIRD_FRAME_".length());
            } else if (!sourceFrameId.contains(".") && !sourceFrameId.isEmpty()) {
                // Simple frame ID like "FRAME_A"
                return sourceFrameId;
            }
        }
        return null;
    }

    private void updateConnectionState() {
        synchronized (activeConnections) {
            int clientCount = activeConnections.size();
            StringBuilder peerList = new StringBuilder();
            for (String frameId : connectedFrameIds) {
                if (peerList.length() > 0) peerList.append(",");
                peerList.append("BIRD_FRAME_").append(frameId);
            }
            String peersStr = peerList.length() > 0 ? peerList.toString() : "none";
            setState(ConnectionState.CONNECTED, "ROLE=HOST:CONNECTED=" + clientCount + "/" + MAX_CLIENTS + ":PEERS=" + peersStr);
        }
    }

    private void startClient(InetAddress host) {
        // Close any existing connection
        synchronized (activeConnections) {
            SocketConnection oldConn = activeConnections.remove("HOST");
            if (oldConn != null) {
                oldConn.close();
            }
        }

        CompletableFuture.runAsync(() -> {
            Socket s = null;
            BufferedReader in = null;
            PrintWriter out;

            try {
                Log.d(TAG, "Client connecting to " + host);
                s = new Socket();

                // Bind socket to P2P network if available (Android 6.0+)
                Network network = boundP2pNetwork;
                if (network != null) {
                    try {
                        network.bindSocket(s);
                        Log.d(TAG, "Socket bound to P2P network");
                    } catch (Exception e) {
                        Log.w(TAG, "Socket bind warning", e);
                    }
                }

                s.connect(new InetSocketAddress(host, 8988), 10000);
                Log.d(TAG, "Client connected to " + host);

                out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(s.getOutputStream())), true);
                in = new BufferedReader(new InputStreamReader(s.getInputStream()));

                SocketConnection conn = new SocketConnection(s, in, out);
                synchronized (activeConnections) {
                    activeConnections.put("HOST", conn);
                }
                
                // Extract host frame ID from connection info if available
                String hostFrameId = extractHostFrameId();
                setState(ConnectionState.CONNECTED, "ROLE=CLIENT:HOST=" + (hostFrameId != null ? hostFrameId : host.toString()) + ":SOCKET_CONNECTED");

                // Start heartbeat
                startHeartbeat(out);

                String line;
                while (!shutdown.get() && (line = in.readLine()) != null) {
                    if (stateListener != null) {
                        stateListener.onMessageReceived(line);
                    }
                }

                Log.d(TAG, "Client connection closed");
            } catch (Exception e) {
                if (!shutdown.get()) {
                    Log.e(TAG, "Client error", e);
                    setState(ConnectionState.ERROR, "SOCKET_ERROR:" + e.getClass().getSimpleName());
                    handleDisconnection();
                }
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (s != null) try { s.close(); } catch (Exception ignored) {}
                synchronized (activeConnections) {
                    activeConnections.remove("HOST");
                }
            }
        }, socketExecutor);
    }

    private String extractHostFrameId() {
        // Try to extract host frame ID from connection info
        // This is approximate - we might not know the host's frame ID until we see messages
        return null;
    }

    private ScheduledFuture<?> heartbeatTask = null;

    private void startHeartbeat(PrintWriter out) {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        // Use scheduleWithFixedDelay instead of scheduleAtFixedRate to avoid execution
        // when Android processes become cached
        heartbeatTask = scheduler.scheduleWithFixedDelay(() -> {
            if (out != null && !shutdown.get()) {
                try {
                    out.println("HEARTBEAT");
                    out.flush();
                } catch (Exception e) {
                    Log.w(TAG, "Heartbeat failed", e);
                    handleDisconnection();
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public void sendMessage(String msg) {
        if (isHost) {
            // Host routes messages to specific clients based on target frame ID
            // Message format: {targetFrameId}:{messageType}:{sourceFrameId}:{data}
            // If no target (e.g., HEARTBEAT), broadcast to all clients
            String targetFrameId = extractTargetFrameId(msg);
            
            synchronized (activeConnections) {
                if (targetFrameId != null) {
                    // Route to specific client
                    SocketConnection targetConn = activeConnections.get(targetFrameId);
                    if (targetConn != null && targetConn.out != null) {
                        try {
                            targetConn.out.println(msg);
                            targetConn.out.flush();
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to send message to " + targetFrameId, e);
                            // Remove failed connection
                            activeConnections.remove(targetFrameId);
                            connectedFrameIds.remove(targetFrameId);
                            updateConnectionState();
                        }
                    } else {
                        Log.w(TAG, "No connection found for target frame ID: " + targetFrameId);
                    }
                } else {
                    // Broadcast to all clients (e.g., HEARTBEAT)
                    List<String> toRemove = new ArrayList<>();
                    for (Map.Entry<String, SocketConnection> entry : activeConnections.entrySet()) {
                        SocketConnection conn = entry.getValue();
                        if (conn != null && conn.out != null) {
                            try {
                                conn.out.println(msg);
                                conn.out.flush();
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to broadcast message to " + entry.getKey(), e);
                                toRemove.add(entry.getKey());
                            }
                        }
                    }
                    // Remove failed connections
                    for (String key : toRemove) {
                        activeConnections.remove(key);
                        if (connectedFrameIds.contains(key)) {
                            connectedFrameIds.remove(key);
                        }
                    }
                    if (!toRemove.isEmpty()) {
                        updateConnectionState();
                    }
                }
            }
        } else {
            // Client sends message to host
            synchronized (activeConnections) {
                SocketConnection hostConn = activeConnections.get("HOST");
                if (hostConn != null && hostConn.out != null) {
                    try {
                        hostConn.out.println(msg);
                        hostConn.out.flush();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send message to host", e);
                        handleDisconnection();
                    }
                } else {
                    Log.w(TAG, "Cannot send message - no connection to host");
                }
            }
        }
    }

    private String extractTargetFrameId(String msg) {
        // Message format: {targetFrameId}:{messageType}:{sourceFrameId}:{data}
        if (msg == null) return null;
        String[] parts = msg.split(":", 4);
        if (parts.length >= 1 && !parts[0].isEmpty()) {
            String targetFrameId = parts[0];
            // Handle special messages (HEARTBEAT, etc.)
            if ("HEARTBEAT".equals(targetFrameId)) {
                return null; // Broadcast
            }
            // Validate it looks like a frame ID
            if (targetFrameId.startsWith("BIRD_FRAME_")) {
                return targetFrameId.substring("BIRD_FRAME_".length());
            } else if (!targetFrameId.contains(".") && !targetFrameId.isEmpty()) {
                // Simple frame ID like "FRAME_A"
                return targetFrameId;
            }
        }
        return null;
    }

    private void handleDisconnection() {
        Log.d(TAG, "Handling disconnection");
        
        // Close server socket if host
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
            serverSocket = null;
        }
        
        // Close all connections
        synchronized (activeConnections) {
            for (SocketConnection conn : activeConnections.values()) {
                if (conn != null) {
                    conn.close();
                }
            }
            activeConnections.clear();
            connectedFrameIds.clear();
        }
        
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }

        // Remove Wi-Fi Direct group to fully disconnect
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "Wi-Fi Direct group removed");
                }
                
                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "Failed to remove group (may not be in a group): " + reason);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Error removing group", e);
        }

        currentInfo = null;
        setState(ConnectionState.IDLE, null);

        // Unbind network
        try {
            connectivityManager.bindProcessToNetwork(null);
        } catch (Exception ignored) {}
        boundP2pNetwork = null;

        // Unregister network callback
        if (p2pNetworkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(p2pNetworkCallback);
            } catch (Exception ignored) {}
            p2pNetworkCallback = null;
        }
    }

    public void disconnect() {
        handleDisconnection();
        setState(ConnectionState.IDLE, "DISCONNECTED");
    }

    @SuppressWarnings("unused")
    public void shutdown() {
        shutdown.set(true);
        disconnect();
        
        socketExecutor.shutdown();
        scheduler.shutdown();
        
        try {
            if (!socketExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                socketExecutor.shutdownNow();
            }
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            socketExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    // Helper class for socket connection
    private static class SocketConnection {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        SocketConnection(Socket socket, BufferedReader in, PrintWriter out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        void close() {
            try {
                if (in != null) in.close();
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
        }
    }
}
