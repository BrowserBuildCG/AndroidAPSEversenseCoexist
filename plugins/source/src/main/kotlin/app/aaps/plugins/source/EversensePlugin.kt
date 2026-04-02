package app.aaps.plugins.source

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewNotification
import com.nightscout.eversense.models.ActiveAlarm
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.keys.interfaces.Preferences
import com.nightscout.eversense.EversenseCGMPlugin
import com.nightscout.eversense.callbacks.EversenseScanCallback
import com.nightscout.eversense.callbacks.EversenseWatcher
import com.nightscout.eversense.enums.CalibrationReadiness
import com.nightscout.eversense.enums.EversenseType
import com.nightscout.eversense.models.EversenseCGMResult
import com.nightscout.eversense.models.EversenseScanResult
import com.nightscout.eversense.models.EversenseSecureState
import com.nightscout.eversense.models.EversenseState
import com.nightscout.eversense.util.StorageKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class EversensePlugin @Inject constructor(
    rh: ResourceHelper,
    private val context: Context,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    private val rxBus: RxBus
) : AbstractBgSourcePlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_blooddrop_48)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.source_eversense)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_eversense),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences
), BgSource, EversenseWatcher {

    @Inject lateinit var persistenceLayer: PersistenceLayer

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val json = Json { ignoreUnknownKeys = true }

    private val securePrefs by lazy {
        context.getSharedPreferences("EversenseCGMManager", Context.MODE_PRIVATE)
    }

    private var connectedPreference: Preference? = null
    private var batteryPreference: Preference? = null
    private var placementSignalPreference: Preference? = null
    private var insertionPreference: Preference? = null
    private var lastSyncPreference: Preference? = null
    private var currentPhasePreference: Preference? = null
    private var lastCalibrationPreference: Preference? = null
    private var nextCalibrationPreference: Preference? = null
    private var calibrationActionPreference: Preference? = null
    private var lastNotifiedFirmwareVersion: String = ""
    private var consecutiveNoSignalReadings: Int = 0
    private val NO_SIGNAL_WARNING_THRESHOLD = 3
    private var releaseForOfficialApp: Boolean = false
    private var releasePreference: Preference? = null

    init {
        eversense.setContext(context, true)
        eversense.addWatcher(this)
    }

    override fun advancedFilteringSupported(): Boolean = true

    override fun onStart() {
        super.onStart()
        eversense.addWatcher(this)
        if (hasBluetoothPermissions()) {
            aapsLogger.debug(LTag.BGSOURCE, "onStart — permissions granted, attempting auto-reconnect")
            ioScope.launch {
                eversense.connect(null)
            }
        } else {
            aapsLogger.warn(LTag.BGSOURCE, "Bluetooth permissions not granted — requesting permissions")
            requestBluetoothPermissions()
        }
        mainHandler.post {
            connectedPreference?.summary = if (eversense.isConnected()) "✅" else "❌"
        }
    }

    override fun onStop() {
        super.onStop()
    }

    private fun requestBluetoothPermissions() {
        val intent = Intent(context, app.aaps.plugins.source.activities.RequestEversensePermissionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getSecureState(): EversenseSecureState {
        val stateJson = securePrefs.getString(StorageKeys.SECURE_STATE, null) ?: "{}"
        return json.decodeFromString(stateJson)
    }

    private fun saveSecureState(state: EversenseSecureState) {
        securePrefs.edit(commit = true) {
            putString(StorageKeys.SECURE_STATE, json.encodeToString(EversenseSecureState.serializer(), state))
        }
    }

    override fun addPreferenceScreen(
        preferenceManager: PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?
    ) {
        val state = eversense.getCurrentState()
        val notConnected = rh.gs(R.string.eversense_not_connected)
        val secureState = getSecureState()

        super.addPreferenceScreen(preferenceManager, parent, context, requiredKey)

        val bgSourceCategory = parent.findPreference<PreferenceCategory>("bg_source_upload_settings")
        bgSourceCategory?.let { category ->
            val eselSmoothing = SwitchPreference(context)
            eselSmoothing.key = "eversense_use_smoothing"
            eselSmoothing.title = rh.gs(R.string.eversense_use_smoothing)
            eselSmoothing.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                eversense.setSmoothing(newValue as Boolean)
                true
            }
            category.addPreference(eselSmoothing)

            val coexistence = SwitchPreference(context)
            coexistence.key = "eversense_coexistence_mode"
            coexistence.title = rh.gs(R.string.eversense_coexistence_mode)
            coexistence.summary = rh.gs(R.string.eversense_coexistence_summary)
            coexistence.isChecked = false
            coexistence.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                eversense.setCoexistenceMode(enabled)
                aapsLogger.info(LTag.BGSOURCE, "Coexistence mode set to: $enabled")
                true
            }
            category.addPreference(coexistence)
        }

        // Credentials section
        val credentials = PreferenceCategory(context)
        parent.addPreference(credentials)
        credentials.apply {
            title = rh.gs(R.string.eversense_credentials_title)
            initialExpandedChildrenCount = 0

            val username = EditTextPreference(context)
            username.key = "eversense_credentials_username"
            username.title = rh.gs(R.string.eversense_credentials_username)
            username.summary = if (secureState.username.isNotEmpty()) secureState.username
            else rh.gs(R.string.eversense_credentials_not_set)
            username.text = secureState.username
            username.dialogTitle = rh.gs(R.string.eversense_credentials_username)
            username.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                val value = newValue as String
                val updated = getSecureState().also { it.username = value }
                saveSecureState(updated)
                pref.summary = if (value.isNotEmpty()) value
                else rh.gs(R.string.eversense_credentials_not_set)
                aapsLogger.info(LTag.BGSOURCE, "Eversense username updated")
                true
            }
            addPreference(username)

            val password = EditTextPreference(context)
            password.key = "eversense_credentials_password"
            password.title = rh.gs(R.string.eversense_credentials_password)
            password.summary = if (secureState.password.isNotEmpty()) rh.gs(R.string.eversense_credentials_password_set)
            else rh.gs(R.string.eversense_credentials_not_set)
            password.text = secureState.password
            password.dialogTitle = rh.gs(R.string.eversense_credentials_password)
            password.setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                val toggleButton = android.widget.Button(editText.context)
                toggleButton.text = "Show"
                toggleButton.textSize = 12f
                toggleButton.setOnClickListener {
                    if (editText.inputType == (android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
                        editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        toggleButton.text = "Hide"
                    } else {
                        editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        toggleButton.text = "Show"
                    }
                    editText.setSelection(editText.text.length)
                }
                val parentLayout = editText.parent as? android.widget.LinearLayout
                parentLayout?.addView(toggleButton)
            }
            password.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                val value = newValue as String
                val updated = getSecureState().also { it.password = value }
                saveSecureState(updated)
                pref.summary = if (value.isNotEmpty()) rh.gs(R.string.eversense_credentials_password_set)
                else rh.gs(R.string.eversense_credentials_not_set)
                aapsLogger.info(LTag.BGSOURCE, "Eversense password updated")
                true
            }
            addPreference(password)

            // Sign Out button
            val signOut = Preference(context)
            signOut.key = "eversense_credentials_sign_out"
            signOut.title = "Sign Out"
            signOut.summary = "Clear saved username and password"
            signOut.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                AlertDialog.Builder(preferenceManager.context)
                    .setTitle("Sign Out")
                    .setMessage("Are you sure you want to clear your Eversense credentials?")
                    .setPositiveButton("Sign Out") { _, _ ->
                        val cleared = getSecureState().also {
                            it.username = ""
                            it.password = ""
                        }
                        saveSecureState(cleared)
                        username.summary = rh.gs(R.string.eversense_credentials_not_set)
                        username.text = ""
                        password.summary = rh.gs(R.string.eversense_credentials_not_set)
                        password.text = ""
                        aapsLogger.info(LTag.BGSOURCE, "Eversense credentials cleared by user")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            addPreference(signOut)
        }

        // Calibration section
        val calibration = PreferenceCategory(context)
        parent.addPreference(calibration)
        calibration.apply {
            title = rh.gs(R.string.eversense_calibration_title)
            initialExpandedChildrenCount = 0

            val currentPhase = Preference(context)
            currentPhase.key = "eversense_calibration_phase"
            currentPhase.title = rh.gs(R.string.eversense_calibration_phase)
            currentPhase.summary = state?.calibrationPhase?.name ?: notConnected
            addPreference(currentPhase)
            currentPhasePreference = currentPhase

            val lastCalibration = Preference(context)
            lastCalibration.key = "eversense_calibration_last"
            lastCalibration.title = rh.gs(R.string.eversense_calibration_last)
            lastCalibration.summary = state?.let { dateFormatter.format(Date(it.lastCalibrationDate)) } ?: notConnected
            addPreference(lastCalibration)
            lastCalibrationPreference = lastCalibration

            val nextCalibration = Preference(context)
            nextCalibration.key = "eversense_calibration_next"
            nextCalibration.title = rh.gs(R.string.eversense_calibration_next)
            nextCalibration.summary = state?.let { dateFormatter.format(Date(it.nextCalibrationDate)) } ?: notConnected
            addPreference(nextCalibration)
            nextCalibrationPreference = nextCalibration

            val calibrationAction = Preference(context)
            calibrationAction.key = "eversense_calibration_action"
            calibrationAction.title = rh.gs(R.string.eversense_calibration_action)
            calibrationAction.summary = when {
                state == null -> notConnected
                state.calibrationReadiness != CalibrationReadiness.READY -> state.calibrationReadiness.name
                else -> ""
            }
            calibrationAction.isEnabled = state?.calibrationReadiness == CalibrationReadiness.READY
            calibrationAction.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val latestState = eversense.getCurrentState()
                if (latestState == null) {
                    aapsLogger.warn(LTag.BGSOURCE, "Calibration tapped but state is null")
                    return@OnPreferenceClickListener false
                }
                if (latestState.calibrationReadiness != CalibrationReadiness.READY) {
                    aapsLogger.warn(LTag.BGSOURCE, "Calibration tapped but readiness is ${latestState.calibrationReadiness}")
                    return@OnPreferenceClickListener false
                }
                val intent = Intent(context, app.aaps.plugins.source.activities.EversenseCalibrationActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return@OnPreferenceClickListener true
            }
            addPreference(calibrationAction)
            calibrationActionPreference = calibrationAction
        }

        // Information section
        val information = PreferenceCategory(context)
        parent.addPreference(information)
        information.apply {
            title = rh.gs(R.string.eversense_information_title)
            initialExpandedChildrenCount = 0

            val connected = Preference(context)
            connected.key = "eversense_information_connected"
            connected.title = rh.gs(R.string.eversense_information_connected)
            connected.summary = if (eversense.isConnected()) "✅" else "❌"
            connected.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val activityContext = preferenceManager.context
                if (eversense.isConnected()) {
                    AlertDialog.Builder(activityContext)
                        .setTitle(rh.gs(R.string.eversense_scan_title))
                        .setMessage("Disconnect from transmitter?")
                        .setPositiveButton("Disconnect") { _, _ ->
                            eversense.clearStoredDevice()
                            eversense.disconnect()
                            aapsLogger.info(LTag.BGSOURCE, "User disconnected transmitter")
                            connectedPreference?.summary = "❌"
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@OnPreferenceClickListener true
                }
                if (!hasBluetoothPermissions()) {
                    aapsLogger.warn(LTag.BGSOURCE, "Cannot start scan — requesting Bluetooth permissions")
                    requestBluetoothPermissions()
                    return@OnPreferenceClickListener false
                }
                aapsLogger.debug(LTag.BGSOURCE, "User tapped connect — starting BLE scan")
                showDeviceSelectionDialog(activityContext)
                return@OnPreferenceClickListener true
            }
            addPreference(connected)
            connectedPreference = connected

            val battery = Preference(context)
            battery.key = "eversense_information_battery"
            battery.title = rh.gs(R.string.eversense_information_battery)
            battery.summary = state?.let { "${it.batteryPercentage}%" } ?: notConnected
            addPreference(battery)
            batteryPreference = battery

            val placementSignal = Preference(context)
            placementSignal.key = "eversense_information_placement_signal"
            placementSignal.title = rh.gs(R.string.eversense_placement_signal)
            placementSignal.summary = state?.let { signalToLabel(it.sensorSignalStrength) } ?: notConnected
            placementSignal.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val intent = Intent(context, app.aaps.plugins.source.activities.EversensePlacementActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return@OnPreferenceClickListener true
            }
            addPreference(placementSignal)
            placementSignalPreference = placementSignal

            val insertion = Preference(context)
            insertion.key = "eversense_information_insertion_date"
            insertion.title = rh.gs(R.string.eversense_information_insertion_date)
            insertion.summary = state?.let { dateFormatter.format(Date(it.insertionDate)) } ?: notConnected
            addPreference(insertion)
            insertionPreference = insertion

            val lastSync = Preference(context)
            lastSync.key = "eversense_information_last_sync"
            lastSync.title = rh.gs(R.string.eversense_information_last_sync)
            lastSync.summary = state?.let { dateFormatter.format(Date(it.lastSync)) } ?: notConnected
            lastSync.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                aapsLogger.debug(LTag.BGSOURCE, "User tapped Last Sync — triggering full sync and glucose read")
                if (!eversense.isConnected()) {
                    aapsLogger.warn(LTag.BGSOURCE, "Cannot sync — not connected")
                    return@OnPreferenceClickListener false
                }
                ioScope.launch {
                    eversense.triggerFullSync()
                }
                return@OnPreferenceClickListener true
            }
            addPreference(lastSync)
            lastSyncPreference = lastSync

            val releaseForApp = Preference(context)
            releaseForApp.key = "eversense_release_for_official_app"
            releaseForApp.title = rh.gs(R.string.eversense_release_for_official_app)
            releaseForApp.summary = rh.gs(R.string.eversense_release_summary)
            releaseForApp.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (releaseForOfficialApp) return@OnPreferenceClickListener false
                aapsLogger.info(LTag.BGSOURCE, "User releasing transmitter for official app")
                releaseForOfficialApp = true
                eversense.disconnect()
                releaseForApp.summary = rh.gs(R.string.eversense_release_active)
                rxBus.send(EventNewNotification(Notification(97, "Eversense transmitter released — open the official Eversense app now. AAPS will reconnect automatically every 5 minutes.", Notification.INFO)))
                mainHandler.postDelayed({ startOfficialAppReleaseReconnectLoop() }, 300000L)
                true
            }
            addPreference(releaseForApp)
            releasePreference = releaseForApp
        }
    }

    private fun startOfficialAppReleaseReconnectLoop() {
        if (!releaseForOfficialApp) return
        aapsLogger.info(LTag.BGSOURCE, "Release mode — attempting reconnect")
        ioScope.launch {
            eversense.connect(null)
            mainHandler.postDelayed({
                if (eversense.isConnected()) {
                    aapsLogger.info(LTag.BGSOURCE, "Reconnected after official app release")
                    releaseForOfficialApp = false
                    mainHandler.post {
                        releasePreference?.summary = rh.gs(R.string.eversense_release_summary)
                        rxBus.send(EventDismissNotification(97))
                    }
                } else {
                    aapsLogger.info(LTag.BGSOURCE, "Reconnect failed — retrying in 5 minutes")
                    mainHandler.postDelayed({ startOfficialAppReleaseReconnectLoop() }, 300000L)
                }
            }, 10000L)
        }
    }

    private fun signalToLabel(strength: Int): String = when {
        strength >= 75 -> "Excellent"
        strength >= 48 -> "Good"
        strength >= 30 -> "Low"
        strength >= 25 -> "Poor"
        strength > 0   -> "Very Poor"
        else           -> rh.gs(R.string.eversense_not_connected)
    }

    override fun onStateChanged(state: EversenseState) {
        aapsLogger.info(LTag.BGSOURCE, "New state received: ${Json.encodeToString(state)}")

        // Check for persistent no-signal — indicates transmitter not placed over sensor
        if (state.sensorSignalStrength == 0) {
            consecutiveNoSignalReadings++
            aapsLogger.warn(LTag.BGSOURCE, "No signal reading $consecutiveNoSignalReadings of $NO_SIGNAL_WARNING_THRESHOLD")
            if (consecutiveNoSignalReadings >= NO_SIGNAL_WARNING_THRESHOLD) {
                onTransmitterNotPlaced()
                consecutiveNoSignalReadings = 0
            }
        } else {
            consecutiveNoSignalReadings = 0
            rxBus.send(EventDismissNotification(98))
        }

        // Show firmware notification only once per unique firmware version
        if (state.firmwareVersion.isNotEmpty() && state.firmwareVersion != lastNotifiedFirmwareVersion) {
            lastNotifiedFirmwareVersion = state.firmwareVersion
            aapsLogger.info(LTag.BGSOURCE, "Transmitter firmware: ${state.firmwareVersion}")
            rxBus.send(EventNewNotification(
                Notification(96, "Eversense firmware: ${state.firmwareVersion} — open the official Eversense app to check for updates", Notification.INFO, 1440)
                    .also { n -> n.action = Runnable { rxBus.send(EventDismissNotification(96)) }; n.buttonText = app.aaps.core.ui.R.string.snooze }
            ))
        }
    }

    override fun onTransmitterNotPlaced() {
        aapsLogger.warn(LTag.BGSOURCE, "Transmitter not placed — firing placement warning notification")
        mainHandler.post {
            val notification = Notification(98, rh.gs(R.string.eversense_transmitter_not_placed), Notification.URGENT)
            notification.action = Runnable {
                rxBus.send(EventDismissNotification(98))
                val intent = Intent(context, app.aaps.plugins.source.activities.EversensePlacementActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            notification.buttonText = app.aaps.core.ui.R.string.ok
            rxBus.send(EventNewNotification(notification))
        }
    }

    override fun onConnectionChanged(connected: Boolean) {
        aapsLogger.info(LTag.BGSOURCE, "Connection changed — connected: $connected")
        mainHandler.post {
            connectedPreference?.summary = if (connected) "✅" else "❌"
        }
    }

    override fun onAlarmReceived(alarm: ActiveAlarm) {
        aapsLogger.info(LTag.BGSOURCE, "Eversense alarm received: ${alarm.code.title}")
        val level = when {
            alarm.code.isCritical -> Notification.URGENT
            alarm.code.isWarning  -> Notification.NORMAL
            else                  -> Notification.INFO
        }
        val notificationId = 95 + (alarm.codeRaw % 50)
        mainHandler.post {
            rxBus.send(EventNewNotification(Notification(notificationId, alarm.code.title, level, 60)))
        }
    }

    override fun onCGMRead(type: EversenseType, readings: List<EversenseCGMResult>) {
        val glucoseValues = readings.map { reading ->
            GV(
                timestamp = reading.datetime,
                value = reading.glucoseInMgDl.toDouble(),
                noise = null,
                raw = null,
                trendArrow = TrendArrow.fromString(reading.trend.type),
                sourceSensor = when (type) {
                    EversenseType.EVERSENSE_365 -> SourceSensor.EVERSENSE_365
                    EversenseType.EVERSENSE_E3  -> SourceSensor.EVERSENSE_E3
                }
            )
        }

        ioScope.launch {
            val result = persistenceLayer.insertCgmSourceData(
                Sources.Eversense,
                glucoseValues,
                listOf(),
                null
            ).blockingGet()
            aapsLogger.info(LTag.BGSOURCE, "CGM insert complete — inserted: ${result.inserted}, updated: ${result.updated}")
        }
    }

    private fun showDeviceSelectionDialog(context: Context) {
        val foundDevices = mutableListOf<EversenseScanResult>()
        var isCancelled = false
        var dialog: AlertDialog? = null

        val scanCallback = object : EversenseScanCallback {
            override fun onResult(item: EversenseScanResult) {
                if (!isCancelled && foundDevices.none { it.name == item.name }) {
                    foundDevices.add(item)
                    aapsLogger.info(LTag.BGSOURCE, "Scan found device: ${item.name}")
                }
            }
        }

        eversense.startScan(scanCallback)

        mainHandler.postDelayed({
            if (isCancelled) return@postDelayed
            eversense.stopScan()
            dialog?.dismiss()

            if (foundDevices.isEmpty()) {
                AlertDialog.Builder(context)
                    .setTitle(rh.gs(R.string.eversense_scan_title))
                    .setMessage("No Eversense transmitters found. Make sure the transmitter is nearby and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                val items = foundDevices.map { it.name }.toTypedArray()
                AlertDialog.Builder(context)
                    .setTitle(rh.gs(R.string.eversense_scan_title))
                    .setItems(items) { _, position ->
                        val selected = foundDevices[position]
                        aapsLogger.info(LTag.BGSOURCE, "User selected device: ${selected.name}")
                        eversense.connect(selected.device)
                    }
                    .setNegativeButton(rh.gs(R.string.eversense_scan_cancel), null)
                    .show()
            }
        }, 3000)

        dialog = AlertDialog.Builder(context)
            .setTitle(rh.gs(R.string.eversense_scan_title))
            .setMessage("Scanning for Eversense devices...")
            .setNegativeButton(rh.gs(R.string.eversense_scan_cancel)) { _, _ ->
                isCancelled = true
                eversense.stopScan()
            }
            .setCancelable(false)
            .show()
    }

    companion object {
        private val eversense get() = EversenseCGMPlugin.instance
    }
}
