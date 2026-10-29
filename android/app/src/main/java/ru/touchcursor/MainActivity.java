package ru.touchcursor;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Главный экран приложения:
 *  - верхняя панель: поле IP-адреса компьютера (для Wi-Fi) и статус подключения;
 *  - центр: тачпад (зона TouchPadView), курсор ПК следует за пальцем;
 *  - низ: кнопки канала подключения (USB / Bluetooth), кнопки скорости
 *    перемещения курсора (−/+), служебная кнопка вкл/выкл управления.
 *
 * Кнопки громкости смартфона перехватываются в dispatchKeyEvent:
 *  - VolumeUp   (одиночное/двойное нажатие) = ЛКМ (клик / двойной клик);
 *  - VolumeDown (нажатие или удержание)     = ПКМ (клик / нажатие с удержанием);
 * Пока управление включено, стандартная регулировка громкости заблокирована.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "touchcursor";
    private static final String KEY_IP = "pc_ip";
    private static final String KEY_PORT = "pc_port";
    private static final String KEY_MODE = "conn_mode";       // 0=wifi, 1=usb, 2=bt
    private static final String KEY_BT_MAC = "bt_mac";        // адрес адаптера ПК ("" = авто)

    /** Экстра: сервис не смог запустить FGS из-за отсутствующих прав. */
    public static final String EXTRA_FGS_PERMISSION_DENIED = "fgs_permission_denied";

    /** Интервал, в пределах которого второе нажатие VolumeUp считается двойным кликом. */
    private static final long DOUBLE_CLICK_TIMEOUT_MS = 400;
    /** Время удержания VolumeDown, после которого начинается "удержание ПКМ". */
    private static final long VOLDOWN_HOLD_THRESHOLD_MS = 350;

    private TouchPadView touchPad;
    private Button toggleButton;
    private TextView statusText;
    private EditText ipEdit;
    private Button btnUsb, btnBt;
    private TextView speedText;
    private android.os.Handler uiHandler = null;

    private int connMode = PcClient.MODE_WIFI;

    private ControlService svc;
    private boolean bound = false;

    // --- состояние обработки кнопок громкости ---
    private long lastVolUpTime = 0;          // время последнего отпущенного VolumeUp
    private boolean volUpDownSeen = false;   // текущее нажатие VolumeUp уже обработано на DOWN
    private long volDownPressTime = 0;       // время нажатия VolumeDown
    private boolean volDownHeld = false;     // VolumeDown перешло в режим удержания (ПКМ down отправлен)

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((ControlService.LocalBinder) binder).getService();
            bound = true;
            updateToggleUi(svc.isControlling());
            updateStatusText();
            updateSpeedUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            bound = false;
            updateToggleUi(false);
            updateStatusText();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        uiHandler = new android.os.Handler(getMainLooper());

        touchPad = findViewById(R.id.touch_pad);
        toggleButton = findViewById(R.id.btn_toggle);
        statusText = findViewById(R.id.tv_status);
        ipEdit = findViewById(R.id.et_ip);
        btnUsb = findViewById(R.id.btn_conn_usb);
        btnBt = findViewById(R.id.btn_conn_bt);
        speedText = findViewById(R.id.tv_speed);

        // Восстановить сохранённые настройки
        var prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ipEdit.setText(prefs.getString(KEY_IP, ""));
        ipEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(KEY_IP, s.toString().trim()).apply();
            }
        });
        connMode = prefs.getInt(KEY_MODE, PcClient.MODE_WIFI);
        updateConnButtons();

        // --- Кнопки выбора канала подключения внизу экрана ---
        findViewById(R.id.btn_conn_wifi).setOnClickListener(v -> selectConnMode(PcClient.MODE_WIFI));
        btnUsb.setOnClickListener(v -> selectConnMode(PcClient.MODE_USB));
        btnBt.setOnClickListener(v -> selectConnMode(PcClient.MODE_BT));

        // --- Кнопки увеличения/уменьшения скорости перемещения курсора ---
        findViewById(R.id.btn_speed_up).setOnClickListener(v -> {
            if (svc != null) {
                svc.increaseSpeed();
                updateSpeedUi();
            }
        });
        findViewById(R.id.btn_speed_down).setOnClickListener(v -> {
            if (svc != null) {
                svc.decreaseSpeed();
                updateSpeedUi();
            }
        });

        // Служебная кнопка включения/выключения управления
        toggleButton.setOnClickListener(v -> {
            if (!bound || svc == null) return;
            boolean nowOn = !svc.isControlling();
            if (nowOn) {
                if (connMode == PcClient.MODE_WIFI) {
                    String ip = ipEdit.getText().toString().trim();
                    if (ip.isEmpty()) {
                        Toast.makeText(this, R.string.enter_ip, Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else if (connMode == PcClient.MODE_USB) {
                    Toast.makeText(this, R.string.usb_need_cable, Toast.LENGTH_LONG).show();
                } else if (connMode == PcClient.MODE_BT) {
                    ensureBluetoothPermissions();
                    Toast.makeText(this, R.string.bt_need_pair, Toast.LENGTH_LONG).show();
                }
                int port = prefs.getInt(KEY_PORT, ControlService.DEFAULT_PORT);
                String btMac = prefs.getString(KEY_BT_MAC, "");
                svc.startControlling(connMode, ipEdit.getText().toString().trim(),
                        port, btMac, Build.MODEL);
            } else {
                svc.stopControlling();
            }
            updateToggleUi(nowOn);
        });

        // Тачпад: палец -> абсолютная позиция курсора ПК.
        // Зона тачпада занимает всю площадь экрана телефона, поэтому касание
        // напрямую (с учётом ориентации) отображается на экран компьютера.
        // Кнопки −/+ меняют множитель скорости: при speed < 1 курсор проходит
        // меньшую часть экрана тем же расстоянием пальца (точнее), при > 1 — большую.
        touchPad.setOnTouchFraction((fx, fy) -> {
            if (bound && svc != null && svc.isControlling()) {
                double px = fx;
                double py = fy;
                int rot = getResources().getConfiguration().orientation;
                if (rot == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                    // телефон вертикально, а приложение в landscape: разворачиваем маппинг
                    px = fy;
                    py = 1.0 - fx;
                }
                double k = svc.getSpeedFactor();
                px = clamp01(0.5 + (px - 0.5) * k);
                py = clamp01(0.5 + (py - 0.5) * k);
                svc.sendMove(px, py);
            }
        });

        // Вспомогательные экранные кнопки (дублируют кнопки громкости)
        findViewById(R.id.btn_left_click).setOnClickListener(v -> {
            if (bound && svc != null) svc.sendClick("left", false);
        });
        findViewById(R.id.btn_left_dblclick).setOnClickListener(v -> {
            if (bound && svc != null) svc.sendClick("left", true);
        });
        findViewById(R.id.btn_right_click).setOnClickListener(v -> {
            if (bound && svc != null) svc.sendClick("right", false);
        });
        findViewById(R.id.btn_scroll_up).setOnClickListener(v -> {
            if (bound && svc != null) svc.sendScroll(-1);
        });
        findViewById(R.id.btn_scroll_down).setOnClickListener(v -> {
            if (bound && svc != null) svc.sendScroll(1);
        });
        findViewById(R.id.btn_help).setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.help_title)
                        .setMessage(R.string.help_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());

        bindService(new Intent(this, ControlService.class), conn, Context.BIND_AUTO_CREATE);
        startForegroundServiceCompat(new Intent(this, ControlService.class));

        // Динамические права, необходимые для foreground-сервиса типа connectedDevice
        // (Android 12+) и уведомления сервиса (Android 13+). Запрашиваем сразу при старте.
        requestRuntimePermissionsIfNeeded();

        // Если сервис уже сообщил об отказе startForeground() (EXTRA_FGS_PERMISSION_DENIED)
        handleFgsDeniedIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleFgsDeniedIntent(intent);
    }

    private void handleFgsDeniedIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_FGS_PERMISSION_DENIED, false)) {
            intent.removeExtra(EXTRA_FGS_PERMISSION_DENIED);
            onFgsPermissionDenied();
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Переключение канала связи; если управление активно — переподключаемся по новому каналу. */
    private void selectConnMode(int mode) {
        if (mode == connMode) return;
        connMode = mode;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply();
        updateConnButtons();
        String label = mode == PcClient.MODE_USB ? "USB"
                : mode == PcClient.MODE_BT ? "Bluetooth" : "Wi-Fi";
        Toast.makeText(this, getString(R.string.conn_mode_changed, label), Toast.LENGTH_SHORT).show();
        if (mode == PcClient.MODE_BT) ensureBluetoothPermissions();
        if (bound && svc != null && svc.isControlling()) {
            // перезапуск соединения уже выбранным транспортом
            svc.stopControlling();
            var prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            svc.startControlling(mode, prefs.getString(KEY_IP, ""),
                    prefs.getInt(KEY_PORT, ControlService.DEFAULT_PORT),
                    prefs.getString(KEY_BT_MAC, ""), Build.MODEL);
            updateToggleUi(true);
        }
        updateStatusText();
    }

    private void updateConnButtons() {
        highlight(btnUsb, connMode == PcClient.MODE_USB);
        highlight(btnBt, connMode == PcClient.MODE_BT);
        // Подпись Wi-Fi-кнопки задаётся в XML через @string/btn_conn_wifi
        Button wifi = findViewById(R.id.btn_conn_wifi);
        highlight(wifi, connMode == PcClient.MODE_WIFI);
    }

    private void highlight(Button b, boolean selected) {
        b.setBackgroundColor(getColor(selected ? R.color.accent_green : R.color.gray_button));
    }

    /**
     * Вызывается из ControlService, если startForeground() отклонён системой
     * (SecurityException: не выдано динамическое право для типа connectedDevice).
     * Запрашивает недостающие права и обновляет интерфейс.
     */
    public void onFgsPermissionDenied() {
        runOnUiThread(() -> {
            Toast.makeText(this, R.string.fgs_permission_denied, Toast.LENGTH_LONG).show();
            requestRuntimePermissionsIfNeeded();
            updateToggleUi(false);
            updateStatusText();
        });
    }

    /** Период обновления состояния после выдачи прав. */
    private static final int REQ_RUNTIME_PERMISSIONS = 101;

    /**
     * С Android 12 (API 31) foreground-сервис типа connectedDevice требует, чтобы у
     * приложения было выдано хотя бы одно динамическое право этого типа
     * (BLUETOOTH_CONNECT и т.п.). С Android 13 (API 33) уведомление сервиса требует
     * POST_NOTIFICATIONS. Запрашиваем их при старте — иначе startForeground() может
     * бросить SecurityException.
     */
    private void requestRuntimePermissionsIfNeeded() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            needed.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), REQ_RUNTIME_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // После выдачи (или отказа) прав переподключаемся, если управление активно,
        // и обновляем статус.
        if (requestCode == REQ_RUNTIME_PERMISSIONS) {
            if (bound && svc != null && svc.isControlling()) {
                var prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                svc.stopControlling();
                svc.startControlling(connMode, prefs.getString(KEY_IP, ""),
                        prefs.getInt(KEY_PORT, ControlService.DEFAULT_PORT),
                        prefs.getString(KEY_BT_MAC, ""), Build.MODEL);
            }
            updateStatusText();
        }
    }

    /** Динамическое право BLUETOOTH_CONNECT нужно на Android 12+ перед BT-подключением. */
    private void ensureBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 100);
            }
        }
    }

    private void updateSpeedUi() {
        int level = svc != null ? svc.getSpeedLevel() : ControlService.SPEED_DEFAULT;
        speedText.setText(getString(R.string.speed_level, level));
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        stopService(new Intent(this, ControlService.class));
        super.onDestroy();
    }

    // =====================================================================
    //  Перехват аппаратных кнопок громкости
    // =====================================================================

    @Override
    public void onBackPressed() {
        // При включённом управлении Back не закрывает приложение -
        // иначе случайное нажатие сбросит режим. Выключаем управление кнопкой внизу.
        if (isControlling()) {
            Toast.makeText(this, R.string.back_blocked, Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    /**
     * Дополнительная гарантия перехвата кнопок громкости: даже если событие
     * до dispatchKeyEvent не дошло (сторонняя ROM), здесь оно тоже
     * конвертируется в клики мыши и не уходит в систему при активном управлении.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isControlling() && (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            handleVolumeKey(keyCode, KeyEvent.ACTION_DOWN, event.getRepeatCount());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isControlling() && (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            handleVolumeKey(keyCode, KeyEvent.ACTION_UP, 0);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean isControlling() {
        return bound && svc != null && svc.isControlling();
    }

    /** Вызывается из TouchPadView при прокрутке двумя пальцами. */
    public void onScrollFromPad(int notches) {
        if (isControlling()) {
            svc.sendScrollSteps(notches);
        }
    }

    /**
     * Полностью перехватываем события кнопок громкости до того, как система
     * начнёт регулировать громкость: если управление активно - меняем их смысл
     * на клики мыши и НЕ передаём событие дальше (volume-панель не появляется,
     * громкость не меняется). Если управление выключено - всё работает штатно.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        final boolean active = isControlling();

        if (active && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            handleVolumeKey(code, event.getAction(), event.getRepeatCount());
            return true; // consume: громкость смартфона не меняется
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleVolumeKey(int code, int action, int repeatCount) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (repeatCount > 0) return; // игнорируем автоповтор при долгом удержании
                if (volUpDownSeen) return;
                volUpDownSeen = true;
                long now = System.currentTimeMillis();
                if (now - lastVolUpTime <= DOUBLE_CLICK_TIMEOUT_MS) {
                    // Второе нажатие в пределах окна двойного клика -> double click
                    svc.sendClick("left", true);
                    lastVolUpTime = 0;      // сброс, чтобы тройное нажатие не стало снова "double"
                } else {
                    // Одиночное: отправляем сразу (отзывчивее, чем ждать таймаут).
                    // Если следом придёт второе - сервер получит ещё и double.
                    svc.sendClick("left", false);
                    lastVolUpTime = now;
                }
            } else if (action == KeyEvent.ACTION_UP) {
                volUpDownSeen = false;
            }
        } else if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (repeatCount > 0) return;
                volDownPressTime = System.currentTimeMillis();
                volDownHeld = false;
                checkHold(); // запланировать переход в "удержание ПКМ"
            } else if (action == KeyEvent.ACTION_UP) {
                if (uiHandler != null) uiHandler.removeCallbacks(holdChecker);
                long held = System.currentTimeMillis() - volDownPressTime;
                volDownPressTime = 0;
                if (volDownHeld) {
                    // было долгое удержание -> завершаем его отпусканием ПКМ
                    svc.sendButtonUp("right");
                    volDownHeld = false;
                } else if (held >= 0) {
                    // короткое нажатие -> одиночный клик ПКМ
                    svc.sendClick("right", false);
                }
            }
        }
    }

    /**
     * Проверяет, не перешло ли удержание VolumeDown в порог "зажатой ПКМ".
     * Вызывается по таймеру, пока кнопка физически удерживается.
     */
    private final Runnable holdChecker = new Runnable() {
        @Override
        public void run() {
            if (!isControlling()) return;
            if (volDownPressTime > 0 && !volDownHeld
                    && System.currentTimeMillis() - volDownPressTime >= VOLDOWN_HOLD_THRESHOLD_MS) {
                volDownHeld = true;
                svc.sendButtonDown("right");
            }
        }
    };

    private void checkHold() {
        if (uiHandler == null) return;
        uiHandler.removeCallbacks(holdChecker);
        if (!volDownHeld && volDownPressTime > 0) {
            long remain = VOLDOWN_HOLD_THRESHOLD_MS
                    - (System.currentTimeMillis() - volDownPressTime);
            uiHandler.postDelayed(holdChecker, Math.max(16, remain));
        }
    }

    // =====================================================================
    //  UI статуса
    // =====================================================================

    private void updateToggleUi(boolean on) {
        toggleButton.setText(on ? R.string.control_on : R.string.control_off);
        toggleButton.setBackgroundColor(getColor(on ? R.color.accent_green : R.color.gray_button));
        touchPad.setActive(on);
        updateStatusText();
    }

    private void updateStatusText() {
        if (!bound || svc == null) {
            statusText.setText(R.string.status_service_stopped);
        } else if (!svc.isControlling()) {
            statusText.setText(R.string.status_idle);
        } else if (svc.isConnected()) {
            statusText.setText(getString(R.string.status_connected, svc.getServerAddress()));
        } else {
            statusText.setText(getString(R.string.status_connecting, svc.getServerAddress()));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        touchPad.post(statusRefresher);
    }

    @Override
    protected void onPause() {
        touchPad.removeCallbacks(statusRefresher);
        super.onPause();
    }

    /** Периодическое обновление строки статуса (подключение может появиться позже). */
    private final Runnable statusRefresher = new Runnable() {
        @Override
        public void run() {
            updateStatusText();
            touchPad.postDelayed(this, 1000);
        }
    };

    private void startForegroundServiceCompat(Intent intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
