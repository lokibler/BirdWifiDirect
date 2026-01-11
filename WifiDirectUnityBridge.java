package com.sheproduces.wifidirect;

import com.unity3d.player.UnityPlayer;

public class WifiDirectUnityBridge {

    private static WifiDirectManager getMgr() {
        return WifiDirectManager.getInstance(UnityPlayer.currentActivity);
    }

    public static void Init() {
        WifiDirectManager mgr = getMgr();
        // Disconnect any existing connection first to start fresh
        mgr.disconnect();
        mgr.ensurePermissions();
        mgr.registerReceiver();
    }

    public static void SetFrameId(String frameId) {
        getMgr().setFrameId(frameId);
    }

    public static void SetIsHost(boolean isHost) {
        getMgr().setIsHost(isHost);
    }

    public static void SetMultipleHostsExist(boolean multiple) {
        getMgr().setMultipleHostsExist(multiple);
    }

    public static void disconnect() {
        getMgr().disconnect();
    }

    public static void DiscoverPeers() {
        getMgr().discoverPeers();
    }

    public static void ConnectFirstPeer() {
        getMgr().connectFirstPeer();
    }

    public static void SendMessage(String msg) {
        getMgr().sendMessage(msg);
    }

    public static void SetUnityTarget(
            final String gameObjectName,
            final String msgMethod,
            final String stateMethod) {

        WifiDirectManager mgr = getMgr();
        mgr.setConnectionStateListener(new WifiDirectManager.ConnectionStateListener() {
            @Override
            public void onStateChanged(WifiDirectManager.ConnectionState state, String details) {
                // Pass state enum value (int) and details to Unity
                // Format: "STATE_INT:DETAILS" for backwards compatibility with string parsing
                String stateStr = state.getValue() + (details != null ? ":" + details : "");
                UnityPlayer.UnitySendMessage(gameObjectName, stateMethod, stateStr);
            }

            @Override
            public void onMessageReceived(String msg) {
                // This calls BirdNetworkManager.OnWifiMessage(msg) on the C# side
                UnityPlayer.UnitySendMessage(gameObjectName, msgMethod, msg);
            }
        });
    }

    public static void OnUnityPause(boolean paused) {
        if (paused) {
            getMgr().unregisterReceiver();
        } else {
            getMgr().registerReceiver();
        }
    }
}
