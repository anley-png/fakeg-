package com.example.mockroute

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Settings that are filled in when an area is chosen. Edit the numbers here to change an area's defaults. */
private class Area(
    val label: String, val asset: String,
    val accMin: String, val accMax: String,
    val interval: String, val hbMin: String, val hbMax: String
)

private val AREAS = listOf(
    Area("Area 1", "area1.gpx", "16", "22", "5", "1", "5"),
    Area("Area 2", "area2.gpx", "13", "19", "5", "1", "5"),
    Area("Area 3", "area3.gpx", "26", "32", "5", "1", "5"),
    Area("Area 4", "area4.gpx", "10", "16", "5", "1", "5")
)

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var accMin: EditText
    private lateinit var accMax: EditText
    private lateinit var interval: EditText
    private lateinit var hbMin: EditText
    private lateinit var hbMax: EditText
    private lateinit var loop: CheckBox
    private lateinit var areaGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "Choose Area"
            textSize = 18f
            setPadding(0, pad / 2, 0, 0)
        })
        areaGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        AREAS.forEachIndexed { i, a ->
            areaGroup.addView(RadioButton(this).apply {
                id = AREA_ID_BASE + i
                text = a.label
            })
        }
        root.addView(areaGroup)

        root.addView(button("Use a different GPX file (optional)") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, 1
            )
        })

        accMin = field(root, "Accuracy min (m)", "16")
        accMax = field(root, "Accuracy max (m)", "22")
        interval = field(root, "Seconds between points (only if the GPX has no time tags)", "5")
        hbMin = field(root, "Repeat the location: shortest wait (seconds, 0 = off)", "1")
        hbMax = field(root, "Repeat the location: longest wait (seconds)", "5")
        loop = CheckBox(this).apply {
            text = "Loop: after the last point, walk back to the first and start again"
            isChecked = true
        }
        root.addView(loop)

        root.addView(button("START") { startRoute() })
        root.addView(button("STOP") {
            startService(Intent(this, MockService::class.java).setAction("STOP"))
            status.text = "Stopped."
        })

        setContentView(ScrollView(this).apply { addView(root) })

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 2)

        areaGroup.setOnCheckedChangeListener { _, checkedId -> applyArea(checkedId - AREA_ID_BASE) }
        areaGroup.check(AREA_ID_BASE)      // Area 1 by default
    }

    private fun applyArea(index: Int) {
        val a = AREAS.getOrNull(index) ?: return
        accMin.setText(a.accMin)
        accMax.setText(a.accMax)
        interval.setText(a.interval)
        hbMin.setText(a.hbMin)
        hbMax.setText(a.hbMax)
        try {
            assets.open(a.asset).use { input ->
                File(filesDir, "route.gpx").outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            status.text = "Could not load the route for ${a.label}."
            return
        }
        refreshStatus()
    }

    private companion object { const val AREA_ID_BASE = 100 }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun field(root: LinearLayout, label: String, def: String): EditText {
        root.addView(TextView(this).apply { text = label })
        val e = EditText(this).apply {
            setText(def)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(e)
        return e
    }

    private fun num(e: EditText, def: Double): Double = e.text.toString().toDoubleOrNull() ?: def

    private fun refreshStatus() {
        val f = File(filesDir, "route.gpx")
        status.text = if (!f.exists()) {
            "No route yet. Choose a GPX file.\n\nFirst: Settings > Developer options > Select mock location app > Mock Route."
        } else {
            val pts = GpxRoute.parse(f.readText(), num(interval, 5.0))
            val secs = if (pts.isNotEmpty()) pts.last().offMs / 1000 else 0
            "Route loaded: ${pts.size} points, about ${secs / 60} min ${secs % 60} s.\n\n" +
                "Make sure Mock Route is selected as the mock location app in Developer options."
        }
    }

    private fun startRoute() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow the location permission first, then press START again."
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
            return
        }
        val i = Intent(this, MockService::class.java).apply {
            putExtra("accMin", num(accMin, 16.0))
            putExtra("accMax", num(accMax, 22.0))
            putExtra("interval", num(interval, 5.0))
            putExtra("hbMin", num(hbMin, 1.0))
            putExtra("hbMax", num(hbMax, 5.0))
            putExtra("loop", loop.isChecked)
        }
        startForegroundService(i)
        status.text = "Running. The hop counter is in the notification. Press STOP to end."
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 1 && resultCode == RESULT_OK && uri != null) {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            File(filesDir, "route.gpx").writeText(text)
            refreshStatus()
        }
    }
}
