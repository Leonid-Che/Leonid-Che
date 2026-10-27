package dev.shield.voiceshield

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider

/**
 * Voice Shield — персональный генератор акустического белого шума для
 * маскирования конфиденциальных разговоров + блокировка перехвата записи
 * через микрофон самого смартфона.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var vm: ShieldViewModel
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var auditBtn: Button

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.activate(this) else vm.addLog("Разрешение RECORD_AUDIO не выдано — сентинел недоступен")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vm = ViewModelProvider(this)[ShieldViewModel::class.java]

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        toggleBtn = findViewById(R.id.toggleBtn)
        auditBtn = findViewById(R.id.auditBtn)

        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        modeGroup.setOnCheckedChangeListener { _, id ->
            vm.noiseMode = when (id) {
                R.id.modeWhite -> NoiseEngine.Mode.WHITE
                R.id.modePink -> NoiseEngine.Mode.PINK
                else -> NoiseEngine.Mode.SPEECHBAND
            }
        }

        findViewById<SeekBar>(R.id.gainBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    vm.gain = p / 100f
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            }
        )

        toggleBtn.setOnClickListener {
            if (vm.shieldActive.value == true) {
                vm.deactivate(this)
            } else {
                ensureMicPermissionThenActivate()
            }
        }

        auditBtn.setOnClickListener { vm.reaudit() }

        vm.shieldActive.observe(this) { active -> updateUi(active, vm.micStatus.value) }
        vm.micStatus.observe(this) { st -> updateUi(vm.shieldActive.value == true, st) }
        vm.log.observe(this) { lines -> logText.text = lines.joinToString("\n") }
    }

    private fun ensureMicPermissionThenActivate() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.activate(this) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun updateUi(active: Boolean, st: MicSentinel.MicStatus?) {
        toggleBtn.setText(if (active) R.string.btn_deactivate else R.string.btn_activate)
        statusText.text = when {
            !active -> getString(R.string.status_idle)
            st == MicSentinel.MicStatus.HIJACKED_POSSIBLY -> getString(R.string.status_hijack)
            else -> getString(R.string.status_ok)
        }
        // На Android 12+ система сама показывает индикатор использования микрофона —
        // напоминаем пользователю смотреть на зелёную точку в статус-баре.
        if (active && Build.VERSION.SDK_INT >= 31) {
            statusText.append("\n" + getString(R.string.status_indicator_hint))
        }
    }

    override fun onDestroy() {
        if (vm.shieldActive.value == true) vm.deactivate(this)
        super.onDestroy()
    }
}
