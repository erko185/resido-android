package sk.efabrica.resido

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.efabrica.resido.prefs.Prefs
import sk.efabrica.resido.prefs.PrinterConfig
import sk.efabrica.resido.print.BluetoothPrinterTransport
import sk.efabrica.resido.print.PrinterTransport
import sk.efabrica.resido.print.TestTicket
import sk.efabrica.resido.update.UpdateManager
import sk.efabrica.resido.web.UrlPolicy

/**
 * Native settings screen - the Android counterpart of the desktop client's
 * offline.html settings page: server URL, receipt printer + four bon
 * printers, test print, update check.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var updateManager: UpdateManager
    private lateinit var serverUrlField: EditText
    private lateinit var saveStatus: TextView
    private lateinit var updateStatus: TextView

    private val printerRows = mutableListOf<PrinterRow>()
    private var pendingBluetoothRow: PrinterRow? = null

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val row = pendingBluetoothRow
        pendingBluetoothRow = null

        if (granted) {
            // Row is set when the request came from the device picker button;
            // a plain "switched type to Bluetooth" request has nothing to open.
            row?.let(::showBluetoothDevicePicker)
        } else {
            Toast.makeText(this, R.string.printer_bt_permission_denied, Toast.LENGTH_LONG).show()

            // Permanently denied (no dialog will ever show again) - the only
            // way forward is the system app-permissions screen, so open it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null),
                    )
                )
            }
        }
    }

    /** Asks for BLUETOOTH_CONNECT as soon as a slot is switched to Bluetooth. */
    private fun ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BluetoothPrinterTransport.hasConnectPermission(this)
        ) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)
        updateManager = UpdateManager(this)
        serverUrlField = findViewById(R.id.server_url)
        saveStatus = findViewById(R.id.save_status)
        updateStatus = findViewById(R.id.update_status)

        serverUrlField.setText(prefs.serverUrl)

        val rowContainer = findViewById<LinearLayout>(R.id.printer_rows)
        val labels = listOf(
            R.string.printer1_label,
            R.string.printer2_label,
            R.string.printer3_label,
            R.string.printer4_label,
            R.string.printer5_label,
        )
        labels.forEachIndexed { index, labelRes ->
            val row = PrinterRow(index + 1, rowContainer, labelRes)
            row.bind(prefs.printer(index + 1))
            printerRows += row
            rowContainer.addView(row.view)
        }

        findViewById<TextView>(R.id.app_version).text =
            getString(R.string.app_version_label, BuildConfig.VERSION_NAME)

        if (BuildConfig.SELF_UPDATE_ENABLED) {
            findViewById<Button>(R.id.check_updates).setOnClickListener { checkForUpdates() }
        } else {
            // Play channel: the store updates the app, manual checks make no sense.
            findViewById<Button>(R.id.check_updates).visibility = View.GONE
        }
        findViewById<Button>(R.id.save_button).setOnClickListener { saveAndConnect() }
    }

    private fun saveAndConnect() {
        val url = serverUrlField.text.toString().trim()

        if (!UrlPolicy.isValidHttpUrl(url)) {
            saveStatus.text = getString(R.string.error_invalid_server_url)
            return
        }

        prefs.serverUrl = url
        printerRows.forEach { row ->
            prefs.setPrinter(row.slot, row.currentConfig())
        }

        saveStatus.text = getString(R.string.saved)
        setResult(RESULT_OK)
        finish()
    }

    private fun checkForUpdates() {
        val button = findViewById<Button>(R.id.check_updates)
        button.isEnabled = false
        updateStatus.text = getString(R.string.update_checking)

        lifecycleScope.launch {
            when (val result = updateManager.check()) {
                is UpdateManager.CheckResult.UpToDate ->
                    updateStatus.text = getString(R.string.update_none)

                is UpdateManager.CheckResult.Available -> {
                    updateStatus.text =
                        getString(R.string.update_available_status, result.remote.versionName)

                    // A manual check means the user wants the update now -
                    // offer download+install right away instead of making them
                    // restart the app and wait for the startup check.
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.update_dialog_title)
                        .setMessage(
                            getString(R.string.update_dialog_message, result.remote.versionName)
                        )
                        .setPositiveButton(R.string.update_dialog_download) { _, _ ->
                            lifecycleScope.launch {
                                updateManager.downloadAndPromptInstall(result.remote)
                            }
                        }
                        .setNegativeButton(R.string.update_dialog_later, null)
                        .show()
                }

                UpdateManager.CheckResult.Failed ->
                    updateStatus.text = getString(R.string.update_check_failed)
            }
            button.isEnabled = true
        }
    }

    private fun requestBluetoothPicker(row: PrinterRow) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BluetoothPrinterTransport.hasConnectPermission(this)
        ) {
            pendingBluetoothRow = row
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        showBluetoothDevicePicker(row)
    }

    // Permission is checked in requestBluetoothPicker before reaching here.
    @SuppressLint("MissingPermission")
    private fun showBluetoothDevicePicker(row: PrinterRow) {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val devices = manager?.adapter?.bondedDevices.orEmpty().toList()

        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.printer_bt_no_bonded, Toast.LENGTH_LONG).show()
            return
        }

        val names = devices.map { device ->
            val name = device.name.orEmpty().ifBlank { device.address }
            "$name (${device.address})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.printer_bt_pick)
            .setItems(names) { _, which ->
                val device = devices[which]
                row.setBluetoothDevice(device.address, device.name.orEmpty())
            }
            .show()
    }

    private fun testPrint(row: PrinterRow) {
        val config = row.currentConfig()

        if (config is PrinterConfig.None) {
            Toast.makeText(this, R.string.printer_test_not_configured, Toast.LENGTH_SHORT).show()
            return
        }

        val label = getString(row.labelRes)

        lifecycleScope.launch {
            row.setTestEnabled(false)

            val error = withContext(Dispatchers.IO) {
                try {
                    PrinterTransport.forConfig(this@SettingsActivity, config)
                        ?.send(TestTicket.bytes(label))
                    null
                } catch (e: Exception) {
                    android.util.Log.w("ResidoPrint", "Test print failed", e)
                    e.message ?: e.javaClass.simpleName
                }
            }

            row.setTestEnabled(true)

            if (error == null) {
                Toast.makeText(this@SettingsActivity, R.string.printer_test_ok, Toast.LENGTH_SHORT).show()
            } else {
                // Full error in a dialog - a toast truncates the message and
                // makes printer problems undiagnosable on site.
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.printer_test_print)
                    .setMessage(getString(R.string.printer_test_failed, error))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** One configurable printer slot in the settings UI. */
    private inner class PrinterRow(val slot: Int, parent: ViewGroup, val labelRes: Int) {

        val view: View = layoutInflater.inflate(R.layout.view_printer_row, parent, false)

        private val typeSpinner = view.findViewById<Spinner>(R.id.printer_type)
        private val networkFields = view.findViewById<View>(R.id.printer_network_fields)
        private val bluetoothFields = view.findViewById<View>(R.id.printer_bluetooth_fields)
        private val hostField = view.findViewById<EditText>(R.id.printer_host)
        private val portField = view.findViewById<EditText>(R.id.printer_port)
        private val bluetoothDeviceLabel = view.findViewById<TextView>(R.id.printer_bt_device)
        private val testButton = view.findViewById<Button>(R.id.printer_test)

        private var bluetoothMac = ""
        private var bluetoothName = ""

        init {
            view.findViewById<TextView>(R.id.printer_label).setText(labelRes)

            val adapter = ArrayAdapter(
                this@SettingsActivity,
                R.layout.item_spinner,
                listOf(
                    getString(R.string.printer_type_none),
                    getString(R.string.printer_type_network),
                    getString(R.string.printer_type_bluetooth),
                ),
            )
            adapter.setDropDownViewResource(R.layout.item_spinner)
            typeSpinner.adapter = adapter
            typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    updateFieldVisibility()

                    if (position == TYPE_BLUETOOTH) {
                        ensureBluetoothPermission()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

            view.findViewById<Button>(R.id.printer_bt_pick).setOnClickListener {
                requestBluetoothPicker(this)
            }
            testButton.setOnClickListener { testPrint(this) }
        }

        fun bind(config: PrinterConfig) {
            when (config) {
                is PrinterConfig.None -> typeSpinner.setSelection(TYPE_NONE)

                is PrinterConfig.Network -> {
                    typeSpinner.setSelection(TYPE_NETWORK)
                    hostField.setText(config.host)
                    portField.setText(config.port.toString())
                }

                is PrinterConfig.Bluetooth -> {
                    typeSpinner.setSelection(TYPE_BLUETOOTH)
                    setBluetoothDevice(config.mac, config.name)
                }
            }

            updateFieldVisibility()
        }

        fun setTestEnabled(enabled: Boolean) {
            testButton.isEnabled = enabled
        }

        fun setBluetoothDevice(mac: String, name: String) {
            bluetoothMac = mac
            bluetoothName = name
            bluetoothDeviceLabel.text = if (name.isBlank()) mac else "$name ($mac)"
        }

        fun currentConfig(): PrinterConfig = when (typeSpinner.selectedItemPosition) {
            TYPE_NETWORK -> {
                val host = hostField.text.toString().trim()
                val port = portField.text.toString().trim().toIntOrNull()
                    ?: PrinterConfig.DEFAULT_NETWORK_PORT

                if (host.isBlank()) PrinterConfig.None else PrinterConfig.Network(host, port)
            }

            TYPE_BLUETOOTH ->
                if (bluetoothMac.isBlank()) {
                    PrinterConfig.None
                } else {
                    PrinterConfig.Bluetooth(bluetoothMac, bluetoothName)
                }

            else -> PrinterConfig.None
        }

        private fun updateFieldVisibility() {
            val type = typeSpinner.selectedItemPosition
            networkFields.visibility = if (type == TYPE_NETWORK) View.VISIBLE else View.GONE
            bluetoothFields.visibility = if (type == TYPE_BLUETOOTH) View.VISIBLE else View.GONE
            testButton.visibility = if (type == TYPE_NONE) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val TYPE_NONE = 0
        const val TYPE_NETWORK = 1
        const val TYPE_BLUETOOTH = 2
    }
}
