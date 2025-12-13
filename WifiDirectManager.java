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
    
    private volatile SocketConnection activeConnection = null;
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
     * Used for lexicographic role determination.
     * Note: The Wi-Fi Direct device name must be manually set to "BIRD_FRAME_X" on the tablet.
     */
    public void setFrameId(String frameId) {
        this.frameId = frameId;
        Log.d(TAG, "Frame ID set to: " + frameId + " (device name should be BIRD_FRAME_" + frameId + ")");
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

                // Find matching BIRD_FRAME device and determine role
                WifiP2pDevice target = null;
                String peerFrameId = null;
                
                for (WifiP2pDevice d : peers.getDeviceList()) {
                    Log.d(TAG, "Checking device: " + d.deviceName + " (looking for BIRD_FRAME_ prefix)");
                    if (d.deviceName != null && d.deviceName.startsWith("BIRD_FRAME_")) {
                        String extractedFrameId = extractFrameIdFromDeviceName(d.deviceName);
                        Log.d(TAG, "Found BIRD_FRAME device: " + d.deviceName + ", extracted frameId: " + extractedFrameId);
                        if (extractedFrameId != null && !extractedFrameId.equals(frameId)) {
                            target = d;
                            peerFrameId = extractedFrameId;
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

                // Determine if we should initiate based on lexicographic comparison
                boolean shouldInitiate = shouldInitiateConnection(frameId, peerFrameId);
                
                if (shouldInitiate) {
                    Log.d(TAG, "Initiating connection to " + target.deviceName + " (we become GO)");
                    connect(target);
                } else {
                    Log.d(TAG, "Waiting for " + target.deviceName + " to initiate (they become GO)");
                    // Don't call connect() - wait for them to connect to us
                    setState(ConnectionState.IDLE, "WAITING_FOR_PEER_INITIATE");
                }
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
                setState(ConnectionState.CONNECTED, "ROLE=GO:PORT=8988");
                startServer();
            } else {
                setState(ConnectionState.CONNECTED, "ROLE=CLIENT:GO=" + info.groupOwnerAddress);
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

    private void startServer() {
        if (activeConnection != null) {
            activeConnection.close();
        }

        CompletableFuture.runAsync(() -> {
            ServerSocket ss = null;
            Socket s = null;
            BufferedReader in = null;
            PrintWriter out;

            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(8988));
                Log.d(TAG, "Server listening on port 8988");

                s = ss.accept();
                Log.d(TAG, "Server accepted connection from " + s.getInetAddress());

                out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(s.getOutputStream())), true);
                in = new BufferedReader(new InputStreamReader(s.getInputStream()));

                activeConnection = new SocketConnection(s, in, out);
                setState(ConnectionState.CONNECTED, "SOCKET_CONNECTED");

                // Start heartbeat
                startHeartbeat(out);

                String line;
                while (!shutdown.get() && (line = in.readLine()) != null) {
                    if (stateListener != null) {
                        stateListener.onMessageReceived(line);
                    }
                }

                Log.d(TAG, "Server connection closed");
            } catch (Exception e) {
                if (!shutdown.get()) {
                    Log.e(TAG, "Server error", e);
                    setState(ConnectionState.ERROR, "SOCKET_ERROR:" + e.getClass().getSimpleName());
                    handleDisconnection();
                }
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (s != null) try { s.close(); } catch (Exception ignored) {}
                if (ss != null) try { ss.close(); } catch (Exception ignored) {}
                activeConnection = null;
            }
        }, socketExecutor);
    }

    private void startClient(InetAddress host) {
        if (activeConnection != null) {
            activeConnection.close();
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

                activeConnection = new SocketConnection(s, in, out);
                setState(ConnectionState.CONNECTED, "SOCKET_CONNECTED");

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
                activeConnection = null;
            }
        }, socketExecutor);
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
        if (activeConnection != null && activeConnection.out != null) {
            try {
                activeConnection.out.println(msg);
                activeConnection.out.flush();
            } catch (Exception e) {
                Log.e(TAG, "Failed to send message", e);
                handleDisconnection();
            }
        } else {
            Log.w(TAG, "Cannot send message - no active connection");
        }
    }

    private void handleDisconnection() {
        Log.d(TAG, "Handling disconnection");
        if (activeConnection != null) {
            activeConnection.close();
            activeConnection = null;
        }
        
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
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
