
package com.crisismesh.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.crisismesh.app.ui.theme.CrisisMeshAndroidTheme
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {

        private const val SERVICE_ID = "com.crisismesh.app"

        private const val PREFS_NAME =
            "crisis_mesh_storage"

        private const val STORED_MESSAGES =
            "stored_messages"

        private const val SEEN_MESSAGES =
            "seen_messages"

        private const val MAX_HOPS = 10

        private const val MESSAGE_TTL =
            30 * 60 * 1000L

        private const val REQUEST_PERMISSIONS = 1001
    }

    private val nearbyClient by lazy {
        Nearby.getConnectionsClient(this)
    }

    private val connectedEndpoints =
        mutableStateMapOf<String, String>()

    private val discoveredEndpoints =
        mutableStateMapOf<String, String>()

    private val pendingConnections =
        mutableSetOf<String>()

    private val seenMessageIds =
        mutableSetOf<String>()

    private var isMeshRunning by mutableStateOf(false)

    private var networkStatus by mutableStateOf(
        "OFFLINE MESH STOPPED"
    )

    private var statusMessage by mutableStateOf(
        "Tap START MESH"
    )

    private var receivedEmergency by mutableStateOf(
        "No emergency received yet."
    )

    private var lastSentEmergency by mutableStateOf(
        "No SOS sent yet."
    )

    private var selectedEmergencyType by mutableStateOf(
        "Medical"
    )

    private var selectedRiskLevel by mutableStateOf(
        "HIGH"
    )

    private var customMessage by mutableStateOf(
        ""
    )

    private var currentLocation by mutableStateOf(
        "Location not available"
    )

    private val preferences by lazy {
        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        loadSeenMessageIds()

        setContent {

            CrisisMeshAndroidTheme {

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF4F7FB)
                ) {

                    CrisisMeshScreen()
                }
            }
        }

        requestRequiredPermissions()
    }

    // ============================================================
    // PERMISSIONS
    // ============================================================

    private fun requiredPermissions(): Array<String> {

        val permissions =
            mutableListOf<String>()

        permissions.add(
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        permissions.add(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            permissions.add(
                Manifest.permission.BLUETOOTH_SCAN
            )

            permissions.add(
                Manifest.permission.BLUETOOTH_CONNECT
            )

            permissions.add(
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            permissions.add(
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        }

        return permissions.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean {

        return requiredPermissions().all {

            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {

        if (!hasRequiredPermissions()) {

            ActivityCompat.requestPermissions(
                this,
                requiredPermissions(),
                REQUEST_PERMISSIONS
            )

        } else {

            startMesh()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_PERMISSIONS) {

            if (hasRequiredPermissions()) {

                startMesh()

            } else {

                networkStatus =
                    "PERMISSIONS REQUIRED"

                statusMessage =
                    "Allow Nearby Devices and Location permissions."
            }
        }
    }

    // ============================================================
    // MESH START
    // ============================================================

    private fun startMesh() {

        if (!hasRequiredPermissions()) {

            requestRequiredPermissions()

            return
        }

        stopMeshInternal()

        pendingConnections.clear()

        discoveredEndpoints.clear()

        connectedEndpoints.clear()

        networkStatus =
            "STARTING MESH..."

        statusMessage =
            "Advertising and discovering nearby CrisisMesh devices."

        val advertisingOptions =
            AdvertisingOptions.Builder()
                .setStrategy(
                    Strategy.P2P_CLUSTER
                )
                .build()

        val discoveryOptions =
            com.google.android.gms.nearby.connection.DiscoveryOptions.Builder()
                .setStrategy(
                    Strategy.P2P_CLUSTER
                )
                .build()

        nearbyClient
            .startAdvertising(
                getDeviceName(),
                SERVICE_ID,
                connectionLifecycleCallback,
                advertisingOptions
            )
            .addOnSuccessListener {

                runOnUiThread {

                    isMeshRunning = true

                    networkStatus =
                        "MESH ACTIVE"

                    statusMessage =
                        "Advertising is active."
                }
            }
            .addOnFailureListener { error ->

                runOnUiThread {

                    isMeshRunning = false

                    networkStatus =
                        "ADVERTISING ERROR"

                    statusMessage =
                        error.message ?: "Unable to advertise."
                }
            }

        nearbyClient
            .startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                discoveryOptions
            )
            .addOnSuccessListener {

                runOnUiThread {

                    isMeshRunning = true

                    networkStatus =
                        "MESH ACTIVE"

                    statusMessage =
                        "Searching for nearby CrisisMesh devices."
                }
            }
            .addOnFailureListener { error ->

                runOnUiThread {

                    networkStatus =
                        "DISCOVERY ERROR"

                    statusMessage =
                        error.message ?: "Unable to discover."
                }
            }

        updateLocation()
    }

    private fun stopMeshInternal() {

        try {
            nearbyClient.stopAdvertising()
        } catch (_: Exception) {
        }

        try {
            nearbyClient.stopDiscovery()
        } catch (_: Exception) {
        }
    }

    private fun stopMesh() {

        stopMeshInternal()

        try {
            nearbyClient.stopAllEndpoints()
        } catch (_: Exception) {
        }

        connectedEndpoints.clear()

        discoveredEndpoints.clear()

        pendingConnections.clear()

        isMeshRunning = false

        networkStatus =
            "MESH STOPPED"

        statusMessage =
            "Mesh is stopped."
    }

    // ============================================================
    // DEVICE NAME
    // ============================================================

    private fun getDeviceName(): String {

        val model =
            Build.MODEL ?: "Android"

        return "CrisisMesh-${model.take(18)}"
    }

    // ============================================================
    // CONNECTION CALLBACK
    // ============================================================

    private val connectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {

            override fun onConnectionInitiated(
                endpointId: String,
                connectionInfo: ConnectionInfo
            ) {

                runOnUiThread {

                    statusMessage =
                        "Connecting to ${connectionInfo.endpointName}"
                }

                nearbyClient.acceptConnection(
                    endpointId,
                    payloadCallback
                )
            }

            override fun onConnectionResult(
                endpointId: String,
                result: ConnectionResolution
            ) {

                pendingConnections.remove(
                    endpointId
                )

                val statusCode =
                    result.status.statusCode

                runOnUiThread {

                    when (statusCode) {

                        ConnectionsStatusCodes.STATUS_OK -> {

                            val name =
                                discoveredEndpoints[endpointId]
                                    ?: "CrisisMesh Device"

                            connectedEndpoints[
                                endpointId
                            ] = name

                            networkStatus =
                                "MESH CONNECTED"

                            statusMessage =
                                "Connected to $name"

                            sendStoredMessages(
                                endpointId
                            )
                        }

                        ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {

                            statusMessage =
                                "Connection rejected."
                        }

                        ConnectionsStatusCodes.STATUS_ERROR -> {

                            statusMessage =
                                "Connection error."
                        }

                        else -> {

                            statusMessage =
                                "Connection status: $statusCode"
                        }
                    }
                }
            }

            override fun onDisconnected(
                endpointId: String
            ) {

                pendingConnections.remove(
                    endpointId
                )

                runOnUiThread {

                    connectedEndpoints.remove(
                        endpointId
                    )

                    statusMessage =
                        "A mesh device disconnected."
                }
            }
        }

    // ============================================================
    // DISCOVERY CALLBACK
    // ============================================================

    private val endpointDiscoveryCallback =
        object : EndpointDiscoveryCallback() {

            override fun onEndpointFound(
                endpointId: String,
                info: DiscoveredEndpointInfo
            ) {

                val endpointName =
                    info.endpointName

                runOnUiThread {

                    discoveredEndpoints[
                        endpointId
                    ] = endpointName
                }

                if (
                    !connectedEndpoints.containsKey(
                        endpointId
                    ) &&
                    !pendingConnections.contains(
                        endpointId
                    )
                ) {

                    pendingConnections.add(
                        endpointId
                    )

                    nearbyClient
                        .requestConnection(
                            getDeviceName(),
                            endpointId,
                            connectionLifecycleCallback
                        )
                        .addOnFailureListener {

                            pendingConnections.remove(
                                endpointId
                            )
                        }
                }
            }

            override fun onEndpointLost(
                endpointId: String
            ) {

                runOnUiThread {

                    discoveredEndpoints.remove(
                        endpointId
                    )
                }
            }
        }

    // ============================================================
    // PAYLOAD CALLBACK
    // ============================================================

    private val payloadCallback =
        object : PayloadCallback() {

            override fun onPayloadReceived(
                endpointId: String,
                payload: Payload
            ) {

                if (
                    payload.type !=
                    Payload.Type.BYTES
                ) {
                    return
                }

                val bytes =
                    payload.asBytes()
                        ?: return

                val jsonString =
                    bytes.toString(
                        Charsets.UTF_8
                    )

                receiveMessage(
                    endpointId,
                    jsonString
                )
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate
            ) {
                // Byte payloads are handled automatically.
            }
        }

    // ============================================================
    // RECEIVE MESSAGE
    // ============================================================

    private fun receiveMessage(
        sourceEndpointId: String,
        jsonString: String
    ) {

        try {

            val json =
                JSONObject(jsonString)

            val messageId =
                json.optString(
                    "messageId",
                    ""
                )

            if (messageId.isBlank()) {
                return
            }

            // ----------------------------------------------------
            // DUPLICATE CHECK
            // ----------------------------------------------------

            if (
                seenMessageIds.contains(
                    messageId
                )
            ) {

                runOnUiThread {

                    statusMessage =
                        "Duplicate ignored: $messageId"
                }

                return
            }

            // ----------------------------------------------------
            // TTL CHECK
            // ----------------------------------------------------

            val timestamp =
                json.optLong(
                    "timestamp",
                    System.currentTimeMillis()
                )

            val age =
                System.currentTimeMillis() -
                        timestamp

            if (age > MESSAGE_TTL) {

                runOnUiThread {

                    statusMessage =
                        "Expired SOS ignored."
                }

                return
            }

            // ----------------------------------------------------
            // MARK AS SEEN
            // ----------------------------------------------------

            seenMessageIds.add(
                messageId
            )

            saveSeenMessageIds()

            // ----------------------------------------------------
            // STORE MESSAGE
            // ----------------------------------------------------

            storeMessage(
                jsonString
            )

            val type =
                json.optString(
                    "type",
                    "Other"
                )

            val risk =
                json.optString(
                    "riskLevel",
                    "HIGH"
                )

            val message =
                json.optString(
                    "message",
                    ""
                )

            val sender =
                json.optString(
                    "sender",
                    "Unknown"
                )

            val hopCount =
                json.optInt(
                    "hopCount",
                    0
                )

            val location =
                json.optString(
                    "location",
                    "Unknown"
                )

            runOnUiThread {

                receivedEmergency =
                    """
                    🚨 EMERGENCY RECEIVED
                    
                    Type: $type
                    Risk: $risk
                    
                    Message:
                    $message
                    
                    Sender:
                    $sender
                    
                    Location:
                    $location
                    
                    Message ID:
                    $messageId
                    
                    Hop Count:
                    $hopCount
                    """.trimIndent()

                statusMessage =
                    "New emergency received and stored."
            }

            // ----------------------------------------------------
            // AUTOMATIC RELAY
            // ----------------------------------------------------

            if (hopCount >= MAX_HOPS) {

                runOnUiThread {

                    statusMessage =
                        "Maximum hop count reached."
                }

                return
            }

            val forwardedJson =
                JSONObject(
                    jsonString
                )

            forwardedJson.put(
                "hopCount",
                hopCount + 1
            )

            forwardedJson.put(
                "lastRelayedBy",
                getDeviceName()
            )

            val relayData =
                forwardedJson.toString()

            relayToPeers(
                relayData,
                sourceEndpointId
            )

        } catch (error: Exception) {

            runOnUiThread {

                statusMessage =
                    "Invalid SOS received."
            }
        }
    }

    // ============================================================
    // RELAY
    // ============================================================

    private fun relayToPeers(
        jsonString: String,
        excludeEndpointId: String? = null
    ) {

        val payload =
            Payload.fromBytes(
                jsonString.toByteArray(
                    Charsets.UTF_8
                )
            )

        val targets =
            connectedEndpoints.keys.toList()

        var sentCount = 0

        for (endpointId in targets) {

            if (
                endpointId ==
                excludeEndpointId
            ) {
                continue
            }

            nearbyClient
                .sendPayload(
                    endpointId,
                    payload
                )
                .addOnSuccessListener {

                    sentCount++

                    runOnUiThread {

                        statusMessage =
                            "SOS relayed automatically."
                    }
                }
                .addOnFailureListener {
                    // Keep the message stored.
                    // It can be forwarded later.
                }
        }

        if (targets.isEmpty()) {

            runOnUiThread {

                statusMessage =
                    "No peer available. SOS stored for later."
            }
        }
    }

    // ============================================================
    // SEND SOS
    // ============================================================

    private fun sendEmergency() {

        if (
            customMessage.trim().isEmpty()
        ) {

            statusMessage =
                "Enter an emergency message first."

            return
        }

        if (!isMeshRunning) {

            statusMessage =
                "Start the mesh first."

            return
        }

        updateLocation()

        val messageId =
            "SOS-" +
                    UUID.randomUUID()
                        .toString()

        val timestamp =
            System.currentTimeMillis()

        val json =
            JSONObject()

        json.put(
            "messageId",
            messageId
        )

        json.put(
            "type",
            selectedEmergencyType
        )

        json.put(
            "riskLevel",
            selectedRiskLevel
        )

        json.put(
            "message",
            customMessage.trim()
        )

        json.put(
            "priority",
            selectedRiskLevel
        )

        json.put(
            "hopCount",
            0
        )

        json.put(
            "sender",
            getDeviceName()
        )

        json.put(
            "timestamp",
            timestamp
        )

        json.put(
            "location",
            currentLocation
        )

        json.put(
            "lastRelayedBy",
            getDeviceName()
        )

        val jsonString =
            json.toString()

        // --------------------------------------------------------
        // SAVE AS SEEN
        // --------------------------------------------------------

        seenMessageIds.add(
            messageId
        )

        saveSeenMessageIds()

        // --------------------------------------------------------
        // STORE LOCALLY
        // --------------------------------------------------------

        storeMessage(
            jsonString
        )

        // --------------------------------------------------------
        // SHOW LOCAL MESSAGE
        // --------------------------------------------------------

        lastSentEmergency =
            """
            🚨 SOS CREATED
            
            Type: $selectedEmergencyType
            Risk: $selectedRiskLevel
            
            Message:
            ${customMessage.trim()}
            
            Location:
            $currentLocation
            
            ID:
            $messageId
            
            Status:
            STORED → RELAYING
            """.trimIndent()

        // --------------------------------------------------------
        // BROADCAST TO ALL CONNECTED DEVICES
        // --------------------------------------------------------

        relayToPeers(
            jsonString
        )

        statusMessage =
            if (connectedEndpoints.isEmpty()) {

                "SOS stored. Waiting for a nearby peer."

            } else {

                "SOS sent to ${connectedEndpoints.size} mesh peer(s)."
            }

        customMessage = ""
    }

    // ============================================================
    // STORE AND FORWARD
    // ============================================================

    private fun storeMessage(
        jsonString: String
    ) {

        try {

            val messages =
                loadStoredMessages()
                    .toMutableList()

            val newId =
                JSONObject(
                    jsonString
                ).optString(
                    "messageId"
                )

            val alreadyStored =
                messages.any {

                    try {

                        JSONObject(it)
                            .optString(
                                "messageId"
                            ) == newId

                    } catch (_: Exception) {

                        false
                    }
                }

            if (!alreadyStored) {

                messages.add(
                    jsonString
                )
            }

            // Keep last 100 messages.
            val limited =
                messages.takeLast(100)

            saveStoredMessages(
                limited
            )

        } catch (_: Exception) {
        }
    }

    private fun loadStoredMessages(): List<String> {

        return try {

            val raw =
                preferences.getString(
                    STORED_MESSAGES,
                    "[]"
                ) ?: "[]"

            val array =
                JSONArray(raw)

            val result =
                mutableListOf<String>()

            for (i in 0 until array.length()) {

                result.add(
                    array.getString(i)
                )
            }

            result

        } catch (_: Exception) {

            emptyList()
        }
    }

    private fun saveStoredMessages(
        messages: List<String>
    ) {

        val array =
            JSONArray()

        for (message in messages) {

            array.put(
                message
            )
        }

        preferences
            .edit()
            .putString(
                STORED_MESSAGES,
                array.toString()
            )
            .apply()
    }

    // ============================================================
    // SEND STORED MESSAGES TO NEW PEER
    // ============================================================

    private fun sendStoredMessages(
        endpointId: String
    ) {

        val messages =
            loadStoredMessages()

        if (messages.isEmpty()) {
            return
        }

        var sent = 0

        for (message in messages) {

            val json = try {

                JSONObject(message)

            } catch (_: Exception) {

                continue
            }

            val timestamp =
                json.optLong(
                    "timestamp",
                    System.currentTimeMillis()
                )

            val age =
                System.currentTimeMillis() -
                        timestamp

            if (age > MESSAGE_TTL) {
                continue
            }

            nearbyClient
                .sendPayload(
                    endpointId,
                    Payload.fromBytes(
                        message.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                )
                .addOnSuccessListener {

                    sent++

                    runOnUiThread {

                        statusMessage =
                            "Synced stored SOS messages."
                    }
                }
        }
    }

    // ============================================================
    // SYNC STORED MESSAGES TO EVERY PEER
    // ============================================================

    private fun syncStoredMessages() {

        if (
            connectedEndpoints.isEmpty()
        ) {

            statusMessage =
                "No connected peers."

            return
        }

        val endpoints =
            connectedEndpoints.keys.toList()

        for (endpointId in endpoints) {

            sendStoredMessages(
                endpointId
            )
        }

        statusMessage =
            "Syncing stored SOS messages..."
    }

    // ============================================================
    // DUPLICATE DATABASE
    // ============================================================

    private fun loadSeenMessageIds() {

        try {

            val raw =
                preferences.getString(
                    SEEN_MESSAGES,
                    "[]"
                ) ?: "[]"

            val array =
                JSONArray(raw)

            seenMessageIds.clear()

            for (i in 0 until array.length()) {

                seenMessageIds.add(
                    array.getString(i)
                )
            }

        } catch (_: Exception) {
        }
    }

    private fun saveSeenMessageIds() {

        try {

            // Keep the latest 1000 IDs.
            val ids =
                seenMessageIds
                    .toList()
                    .takeLast(1000)

            val array =
                JSONArray()

            for (id in ids) {

                array.put(id)
            }

            preferences
                .edit()
                .putString(
                    SEEN_MESSAGES,
                    array.toString()
                )
                .apply()

        } catch (_: Exception) {
        }
    }

    // ============================================================
    // GPS
    // ============================================================

    private fun updateLocation() {

        try {

            val hasFine =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            val hasCoarse =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {

                currentLocation =
                    "Permission unavailable"

                return
            }

            val locationManager =
                getSystemService(
                    LOCATION_SERVICE
                ) as LocationManager

            val providers =
                locationManager
                    .getProviders(true)

            var bestLocation: Location? =
                null

            for (provider in providers) {

                val location =
                    locationManager.getLastKnownLocation(
                        provider
                    )

                if (location != null) {

                    if (
                        bestLocation == null ||
                        location.accuracy <
                        bestLocation!!.accuracy
                    ) {

                        bestLocation =
                            location
                    }
                }
            }

            if (bestLocation != null) {

                currentLocation =
                    String.format(
                        "%.6f, %.6f",
                        bestLocation!!.latitude,
                        bestLocation!!.longitude
                    )

            } else {

                currentLocation =
                    "GPS location unavailable"
            }

        } catch (_: Exception) {

            currentLocation =
                "GPS unavailable"
        }
    }

    // ============================================================
    // UI
    // ============================================================

    @Composable
    private fun CrisisMeshScreen() {

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF4F7FB))
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),

            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ----------------------------------------------------
            // HEADER
            // ----------------------------------------------------

            Text(
                text = "CRISIS MESH",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF172033)
            )

            Text(
                text = "Offline Emergency Communication Network",
                fontSize = 14.sp,
                color = Color(0xFF607086)
            )

            // ----------------------------------------------------
            // MESH STATUS
            // ----------------------------------------------------

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(
                    1.dp,
                    if (isMeshRunning)
                        Color(0xFFB9E7CF)
                    else
                        Color(0xFFE0E5EC)
                )
            ) {

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {

                            Text(
                                text = networkStatus,
                                color =
                                    if (isMeshRunning)
                                        Color(0xFF168A55)
                                    else
                                        Color(0xFFD32F2F),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )

                            Text(
                                text = statusMessage,
                                color = Color(0xFF607086),
                                fontSize = 13.sp
                            )
                        }

                        Text(
                            text = if (isMeshRunning) "●" else "●",
                            color =
                                if (isMeshRunning)
                                    Color(0xFF20A464)
                                else
                                    Color(0xFFD32F2F),
                            fontSize = 28.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {

                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { startMesh() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1565C0),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "START MESH",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { stopMesh() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "STOP",
                                color = Color(0xFF344054),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // NETWORK SUMMARY
            // ----------------------------------------------------

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(18.dp)
            ) {

                Column(
                    modifier = Modifier.padding(16.dp)
                ) {

                    Text(
                        text = "NETWORK",
                        color = Color(0xFF172033),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        StatBox(
                            modifier = Modifier.weight(1f),
                            value = discoveredEndpoints.size.toString(),
                            label = "Nearby"
                        )

                        StatBox(
                            modifier = Modifier.weight(1f),
                            value = connectedEndpoints.size.toString(),
                            label = "Connected"
                        )

                        StatBox(
                            modifier = Modifier.weight(1f),
                            value = loadStoredMessages().size.toString(),
                            label = "Stored SOS"
                        )
                    }
                }
            }

            // ----------------------------------------------------
            // EMERGENCY TYPE
            // ----------------------------------------------------

            SectionTitle(
                "1. EMERGENCY TYPE"
            )

            val emergencyTypes = listOf(
                "Medical",
                "Fire",
                "Accident",
                "Trapped",
                "Flood",
                "Food / Water",
                "Other"
            )

            for (index in emergencyTypes.indices step 2) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    EmergencyTypeCard(
                        modifier = Modifier.weight(1f),
                        label = emergencyTypes[index],
                        icon = when (emergencyTypes[index]) {
                            "Medical" -> "🏥"
                            "Fire" -> "🔥"
                            "Accident" -> "🚗"
                            "Trapped" -> "🆘"
                            "Flood" -> "🌊"
                            "Food / Water" -> "💧"
                            else -> "⚠️"
                        },
                        selected =
                            selectedEmergencyType ==
                                    emergencyTypes[index],
                        onClick = {
                            selectedEmergencyType =
                                emergencyTypes[index]
                        }
                    )

                    if (index + 1 < emergencyTypes.size) {

                        EmergencyTypeCard(
                            modifier = Modifier.weight(1f),
                            label = emergencyTypes[index + 1],
                            icon = when (emergencyTypes[index + 1]) {
                                "Medical" -> "🏥"
                                "Fire" -> "🔥"
                                "Accident" -> "🚗"
                                "Trapped" -> "🆘"
                                "Flood" -> "🌊"
                                "Food / Water" -> "💧"
                                else -> "⚠️"
                            },
                            selected =
                                selectedEmergencyType ==
                                        emergencyTypes[index + 1],
                            onClick = {
                                selectedEmergencyType =
                                    emergencyTypes[index + 1]
                            }
                        )
                    }
                }
            }

            // ----------------------------------------------------
            // RISK LEVEL
            // ----------------------------------------------------

            SectionTitle(
                "2. RISK LEVEL"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                RiskChip(
                    modifier = Modifier.weight(1f),
                    label = "LOW",
                    selected = selectedRiskLevel == "LOW",
                    onClick = {
                        selectedRiskLevel = "LOW"
                    }
                )

                RiskChip(
                    modifier = Modifier.weight(1f),
                    label = "MEDIUM",
                    selected = selectedRiskLevel == "MEDIUM",
                    onClick = {
                        selectedRiskLevel = "MEDIUM"
                    }
                )

                RiskChip(
                    modifier = Modifier.weight(1f),
                    label = "HIGH",
                    selected = selectedRiskLevel == "HIGH",
                    onClick = {
                        selectedRiskLevel = "HIGH"
                    }
                )

                RiskChip(
                    modifier = Modifier.weight(1f),
                    label = "CRITICAL",
                    selected = selectedRiskLevel == "CRITICAL",
                    onClick = {
                        selectedRiskLevel = "CRITICAL"
                    }
                )
            }

            // ----------------------------------------------------
            // MESSAGE
            // ----------------------------------------------------

            SectionTitle(
                "3. EMERGENCY MESSAGE"
            )

            OutlinedTextField(
                value = customMessage,

                onValueChange = {
                    customMessage = it
                },

                modifier = Modifier.fillMaxWidth(),

                label = {
                    Text("What is happening?")
                },

                placeholder = {
                    Text(
                        "Example: Two people are trapped inside a building."
                    )
                },

                minLines = 4,
                maxLines = 6,

                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color(0xFF172033),
                    fontSize = 16.sp
                ),

                colors =
                    androidx.compose.material3
                        .OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF172033),
                            unfocusedTextColor = Color(0xFF172033),
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = Color(0xFF1565C0),
                            unfocusedBorderColor = Color(0xFFB8C2D1),
                            focusedLabelColor = Color(0xFF1565C0),
                            unfocusedLabelColor = Color(0xFF607086),
                            cursorColor = Color(0xFF1565C0),
                            focusedPlaceholderColor = Color(0xFF8793A5),
                            unfocusedPlaceholderColor = Color(0xFF8793A5)
                        ),

                shape = RoundedCornerShape(14.dp)
            )

            // ----------------------------------------------------
            // LOCATION
            // ----------------------------------------------------

            SectionTitle(
                "4. LOCATION"
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        text = "📍",
                        fontSize = 24.sp
                    )

                    Spacer(Modifier.width(10.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {

                        Text(
                            text = "GPS LOCATION",
                            color = Color(0xFF172033),
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = currentLocation,
                            color = Color(0xFF607086),
                            fontSize = 13.sp
                        )
                    }

                    TextButton(
                        onClick = { updateLocation() }
                    ) {
                        Text(
                            text = "UPDATE",
                            color = Color(0xFF1565C0),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ----------------------------------------------------
            // SEND SOS
            // ----------------------------------------------------

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),

                enabled =
                    customMessage.trim().isNotEmpty() &&
                            isMeshRunning,

                onClick = {
                    sendEmergency()
                },

                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFDDE2E9),
                    disabledContentColor = Color(0xFF8994A5)
                ),

                shape = RoundedCornerShape(16.dp)
            ) {

                Text(
                    text = "🚨  SEND SOS",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            if (!isMeshRunning) {

                Text(
                    text = "Start the mesh before sending an SOS.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = Color(0xFFD32F2F),
                    fontSize = 12.sp
                )
            }

            // ----------------------------------------------------
            // LAST SENT
            // ----------------------------------------------------

            SectionTitle(
                "LAST SENT SOS"
            )

            MessageCard(
                text = lastSentEmergency
            )

            // ----------------------------------------------------
            // RECEIVED EMERGENCY
            // ----------------------------------------------------

            SectionTitle(
                "RECEIVED EMERGENCY"
            )

            MessageCard(
                text = receivedEmergency
            )

            // ----------------------------------------------------
            // CONNECTED PEERS
            // ----------------------------------------------------

            SectionTitle(
                "CONNECTED MESH PEERS"
            )

            if (connectedEndpoints.isEmpty()) {

                EmptyStateCard(
                    text = "No connected peers yet."
                )

            } else {

                connectedEndpoints.forEach { (_, name) ->

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFEAF8F1)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {

                        Text(
                            text = "🟢  $name",
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFF168A55),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ----------------------------------------------------
            // NEARBY DEVICES
            // ----------------------------------------------------

            SectionTitle(
                "NEARBY DEVICES"
            )

            if (discoveredEndpoints.isEmpty()) {

                EmptyStateCard(
                    text = "Searching for nearby CrisisMesh devices..."
                )

            } else {

                discoveredEndpoints.forEach { (id, name) ->

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            Color(0xFFE0E5EC)
                        )
                    ) {

                        Column(
                            modifier = Modifier.padding(14.dp)
                        ) {

                            Text(
                                text = "📡  $name",
                                color = Color(0xFF172033),
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "ID: $id",
                                color = Color(0xFF8793A5),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // STORE AND FORWARD
            // ----------------------------------------------------

            SectionTitle(
                "STORE & FORWARD"
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {

                    Text(
                        text = "Offline message storage",
                        color = Color(0xFF172033),
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text =
                            "SOS messages stay on this phone when no peer is available. When a new peer connects, stored messages are automatically synchronized.",
                        color = Color(0xFF607086),
                        fontSize = 13.sp
                    )

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            syncStoredMessages()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {

                        Text(
                            text = "SYNC STORED MESSAGES",
                            color = Color(0xFF1565C0),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ----------------------------------------------------
            // HOW IT WORKS
            // ----------------------------------------------------

            SectionTitle(
                "HOW CRISIS MESH WORKS"
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {

                    HowItWorksRow(
                        number = "1",
                        text = "Create an emergency SOS."
                    )

                    HowItWorksRow(
                        number = "2",
                        text = "Nearby CrisisMesh phones automatically connect."
                    )

                    HowItWorksRow(
                        number = "3",
                        text = "The SOS is stored locally."
                    )

                    HowItWorksRow(
                        number = "4",
                        text = "Connected phones automatically relay it."
                    )

                    HowItWorksRow(
                        number = "5",
                        text = "Duplicate message IDs are ignored."
                    )

                    HowItWorksRow(
                        number = "6",
                        text = "Stored SOS messages sync when a new peer appears."
                    )

                    HowItWorksRow(
                        number = "7",
                        text = "Hop count prevents endless forwarding."
                    )

                    HowItWorksRow(
                        number = "8",
                        text = "Expired messages are ignored."
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(10.dp)
            )

            Text(
                text = "CRISISMESH • OFFLINE-FIRST EMERGENCY NETWORK",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Color(0xFF8793A5),
                fontSize = 11.sp
            )

            Spacer(
                modifier = Modifier.height(20.dp)
            )
        }
    }

    // ============================================================
    // COMPOSABLE HELPERS
    // ============================================================

    @Composable
    private fun SectionTitle(
        title: String
    ) {

        Column {

            Text(
                text = title,
                color = Color(0xFF1565C0),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp
            )

            Spacer(
                modifier = Modifier.height(5.dp)
            )

            HorizontalDivider(
                color = Color(0xFFDCE3EC)
            )
        }
    }

    @Composable
    private fun StatBox(
        modifier: Modifier,
        value: String,
        label: String
    ) {

        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF4F7FB)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = value,
                    color = Color(0xFF1565C0),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Text(
                    text = label,
                    color = Color(0xFF607086),
                    fontSize = 11.sp
                )
            }
        }
    }

    @Composable
    private fun EmergencyTypeCard(
        modifier: Modifier,
        label: String,
        icon: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {

        Card(
            modifier = modifier.height(72.dp),
            onClick = onClick,
            colors = CardDefaults.cardColors(
                containerColor =
                    if (selected)
                        Color(0xFFEAF3FF)
                    else
                        Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                1.5.dp,
                if (selected)
                    Color(0xFF1565C0)
                else
                    Color(0xFFE0E5EC)
            )
        ) {

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = icon,
                    fontSize = 22.sp
                )

                Spacer(
                    modifier = Modifier.width(9.dp)
                )

                Text(
                    text = label,
                    color = Color(0xFF172033),
                    fontSize = 14.sp,
                    fontWeight =
                        if (selected)
                            FontWeight.Bold
                        else
                            FontWeight.Medium
                )
            }
        }
    }

    @Composable
    private fun RiskChip(
        modifier: Modifier,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {

        Card(
            modifier = modifier.height(52.dp),
            onClick = onClick,
            colors = CardDefaults.cardColors(
                containerColor =
                    if (selected)
                        when (label) {
                            "LOW" -> Color(0xFFE8F5E9)
                            "MEDIUM" -> Color(0xFFFFF8E1)
                            "HIGH" -> Color(0xFFFFF0E6)
                            else -> Color(0xFFFFE9E9)
                        }
                    else
                        Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(
                1.5.dp,
                if (selected)
                    when (label) {
                        "LOW" -> Color(0xFF2E7D32)
                        "MEDIUM" -> Color(0xFFF9A825)
                        "HIGH" -> Color(0xFFEF6C00)
                        else -> Color(0xFFD32F2F)
                    }
                else
                    Color(0xFFE0E5EC)
            )
        ) {

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {

                Text(
                    text = label,
                    color =
                        if (selected)
                            when (label) {
                                "LOW" -> Color(0xFF2E7D32)
                                "MEDIUM" -> Color(0xFF9A6B00)
                                "HIGH" -> Color(0xFFBF5600)
                                else -> Color(0xFFD32F2F)
                            }
                        else
                            Color(0xFF526071),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun EmptyStateCard(
        text: String
    ) {

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {

            Text(
                text = text,
                modifier = Modifier.padding(14.dp),
                color = Color(0xFF7B8798),
                fontSize = 13.sp
            )
        }
    }

    @Composable
    private fun HowItWorksRow(
        number: String,
        text: String
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(28.dp)
                    .background(
                        Color(0xFFEAF3FF),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {

                Text(
                    text = number,
                    color = Color(0xFF1565C0),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(
                modifier = Modifier.width(10.dp)
            )

            Text(
                text = text,
                color = Color(0xFF526071),
                fontSize = 13.sp
            )
        }
    }

    @Composable
    private fun MessageCard(
        text: String
    ) {

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                1.dp,
                Color(0xFFE1E7EF)
            )
        ) {

            Text(
                text = text,
                modifier = Modifier.padding(16.dp),
                color = Color(0xFF172033),
                lineHeight = 20.sp,
                fontSize = 13.sp
            )
        }
    }

    // ============================================================
    // CLEANUP
    // ============================================================

    override fun onDestroy() {

        stopMeshInternal()

        try {
            nearbyClient.stopAllEndpoints()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
