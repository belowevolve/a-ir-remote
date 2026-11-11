package belowevolve.airremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Explicit, SDK-only settings/window check. Restores pairing and automatic keyboard preferences. */
public final class ModalRegressionRunner extends Instrumentation {
    private Bundle args;
    private Activity activity;
    private UiAutomation automation;
    private String pkg;
    private String phase = "opt_in";
    private final List<String> events = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); boolean passed = false; boolean authorized = false;
        try {
            require(args != null && "true".equals(args.getString("modalFixture")), "explicit_opt_in_required");
            authorized = true;
            require(Build.VERSION.SDK_INT >= 34, "requires_api_34");
            pkg = getTargetContext().getPackageName();
            if (!"true".equals(args.getString("restoreOnly"))) {
                backup();
                getTargetContext().getSharedPreferences("pc_remote", 0).edit().putBoolean("auto_keyboard", false).commit();
                if ("standard".equals(args.getString("editionChecks"))) runStandardEdition();
                else if (args.getString("voiceUi") != null) runVoiceUi();
                else if ("tv".equals(args.getString("screenPreview"))) runDisconnectedTvPreview(result);
                else if ("true".equals(args.getString("microphoneSettings"))) runMicrophoneSettings();
                else if ("true".equals(args.getString("bluetoothProgress"))) runBluetoothProgress();
                else if ("true".equals(args.getString("tvPairingFixture"))) runTvPairing();
                else if ("true".equals(args.getString("unifiedConnection"))) runUnifiedConnection();
                else if ("true".equals(args.getString("microphoneWithoutCompanion"))) runMicrophoneWithoutCompanion();
                else if ("true".equals(args.getString("interactions"))) runInteractions(); else runChecks();
            }
            passed = true;
        } catch (Throwable error) {
            events.add(phase + ": " + (error instanceof CheckFailure ? error.getMessage() : error.getClass().getSimpleName()));
            if (!(error instanceof CheckFailure)) for (StackTraceElement frame : error.getStackTrace()) if (frame.getClassName().equals(getClass().getName())) { events.add("runner_line: " + frame.getLineNumber()); break; }
            try {
                AccessibilityNodeInfo active = automation == null ? null : automation.getRootInActiveWindow();
                if (active != null) events.add("active_package: " + active.getPackageName());
                if (automation != null && root() != null) capture("failure");
            } catch (Throwable ignored) {}
        }
        finally {
            if (activity != null) {
                refreshActivity();
                runOnMainSync(() -> activity.finish());
                long deadline = SystemClock.elapsedRealtime() + 3_000;
                while (!activity.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            }
            try { if (authorized) restore(); } catch (Throwable error) { passed = false; events.add("restore: failed"); }
            if (authorized) new File(getTargetContext().getExternalFilesDir("modal-fixture"), "pairing-uri").delete();
        }
        result.putString("stream", String.join("\n", events) + "\n");
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private File backupFile() { return new File(getTargetContext().getExternalFilesDir("modal-fixture"), "preferences-backup"); }
    private void backup() throws Exception {
        File file = backupFile(); require(!file.exists(), "restore_previous_fixture_first");
        SharedPreferences link = getTargetContext().getSharedPreferences("companion", 0);
        SharedPreferences pc = getTargetContext().getSharedPreferences("pc_remote", 0);
        JSONObject json = new JSONObject().put("endpoint", link.getString("endpoint", ""))
                .put("hasAuto", pc.contains("auto_keyboard")).put("auto", pc.getBoolean("auto_keyboard", false))
                .put("hasBtHost", pc.contains("host")).put("btHost", pc.getString("host", ""))
                .put("hasBtAuto", pc.contains("auto_connect")).put("btAuto", pc.getBoolean("auto_connect", true));
        SharedPreferences tv = getTargetContext().getSharedPreferences("remote", 0);
        JSONObject remote = new JSONObject();
        for (String key : new String[]{"host", "name", "auto_connect", "auto_keyboard"})
            remote.put(key, tv.contains(key) ? tv.getAll().get(key) : JSONObject.NULL);
        json.put("remote", remote);
        if (args.getString("voiceUi") != null) {
            String key = "air_home:" + tv.getString("host", "");
            json.put("airHomeKey", key).put("airHomeValue", tv.contains(key) ? tv.getBoolean(key, false) : JSONObject.NULL);
        }
        SharedPreferences mic = getTargetContext().getSharedPreferences("microphone", 0);
        JSONObject microphone = new JSONObject();
        for (String key : new String[]{"waveform", "icon_meter"})
            microphone.put(key, mic.contains(key) ? mic.getBoolean(key, false) : JSONObject.NULL);
        json.put("microphone", microphone);
        if ("true".equals(args.getString("tvPairingFixture")) || "standard".equals(args.getString("editionChecks")))
            json.put("fixtureHost", fixtureHost()).put("cleanFixturePin", !getTargetContext().getSharedPreferences("tv_identity", 0).contains(fixtureHost()));
        Files.write(file.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void restore() throws Exception {
        File file = backupFile(); if (!file.exists()) return;
        JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        SharedPreferences.Editor link = getTargetContext().getSharedPreferences("companion", 0).edit();
        String endpoint = json.getString("endpoint");
        if (endpoint.isEmpty()) link.remove("endpoint"); else link.putString("endpoint", endpoint);
        require(link.commit(), "pairing_restore_failed");
        SharedPreferences.Editor pc = getTargetContext().getSharedPreferences("pc_remote", 0).edit();
        if (json.getBoolean("hasAuto")) pc.putBoolean("auto_keyboard", json.getBoolean("auto")); else pc.remove("auto_keyboard");
        if (json.has("hasBtHost")) {
            if (json.getBoolean("hasBtHost")) pc.putString("host", json.getString("btHost")); else pc.remove("host");
            if (json.getBoolean("hasBtAuto")) pc.putBoolean("auto_connect", json.getBoolean("btAuto")); else pc.remove("auto_connect");
        }
        require(pc.commit(), "automatic_keyboard_restore_failed");
        if (json.has("remote")) {
            SharedPreferences.Editor tv = getTargetContext().getSharedPreferences("remote", 0).edit();
            JSONObject remote = json.getJSONObject("remote");
            for (String key : new String[]{"host", "name", "auto_connect", "auto_keyboard"}) {
                if (remote.isNull(key)) tv.remove(key);
                else if (key.startsWith("auto_")) tv.putBoolean(key, remote.getBoolean(key));
                else tv.putString(key, remote.getString(key));
            }
            require(tv.commit(), "tv_preferences_restore_failed");
        }
        if (json.has("airHomeKey")) {
            SharedPreferences.Editor tv = getTargetContext().getSharedPreferences("remote", 0).edit();
            if (json.isNull("airHomeValue")) tv.remove(json.getString("airHomeKey"));
            else tv.putBoolean(json.getString("airHomeKey"), json.getBoolean("airHomeValue"));
            require(tv.commit(), "air_home_restore_failed");
        }
        if (json.has("microphone")) {
            SharedPreferences.Editor mic = getTargetContext().getSharedPreferences("microphone", 0).edit();
            JSONObject microphone = json.getJSONObject("microphone");
            for (String key : new String[]{"waveform", "icon_meter"}) {
                if (microphone.isNull(key)) mic.remove(key); else mic.putBoolean(key, microphone.getBoolean(key));
            }
            require(mic.commit(), "microphone_preferences_restore_failed");
        }
        if (json.optBoolean("cleanFixturePin"))
            require(getTargetContext().getSharedPreferences("tv_identity", 0).edit().remove(json.optString("fixtureHost", "127.0.0.1")).remove(json.optString("fixtureHost", "127.0.0.1") + ":identity").commit(), "fixture_pin_cleanup_failed");
        require(file.delete(), "backup_cleanup_failed");
        events.add("preferences_restored: PASS");
    }
    private void launch() {
        Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage(pkg);
        require(intent != null, "launch_unavailable");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = startActivitySync(intent);
        runOnMainSync(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        await("ПК");
    }
    private void runDisconnectedTvPreview(Bundle result) throws Exception {
        phase = "disconnected_tv_preview";
        require(getTargetContext().getSharedPreferences("remote", 0).edit()
                .remove("host").remove("name").putBoolean("auto_connect", false)
                .putBoolean("auto_keyboard", false).commit(), "tv_preview_setup_failed");
        launch();
        new ScreenPreviewCapture(this).capture("tv", result);
        events.add("disconnected_tv_preview: PASS");
    }

    private void runChecks() throws Exception {
        phase = "launch"; launch();
        phase = "pc_settings"; click(await("ПК")); click(await("Настройки ПК"));
        await("Автоклавиатура"); await("Чувствительность");
        capture("pc-settings"); click(await("Готово")); events.add(phase + ": PASS");
        phase = "mic_settings"; click(await("Микрофон")); click(await("Настройки микрофона"));
        await("Показывать волну"); require(find(root(), "Автоклавиатура") == null, "pc_option_in_microphone");
        require(find(root(), "Чувствительность") == null, "pc_slider_in_microphone");
        require(find(root(), "Забыть компаньон") == null, "separate_companion_forget_in_microphone_settings");
        await("Забыть ПК");
        capture("mic-settings"); click(await("Готово")); events.add(phase + ": PASS");
        phase = "tv_settings"; click(await("ТВ")); click(await("Настройки ТВ"));
        await("Автоклавиатура"); capture("tv-settings");
        click(await("Готово")); events.add(phase + ": PASS");

        // Only the Wi-Fi endpoint is temporarily hidden. Bluetooth selection/certificates stay intact.
        phase = "direct_qr";
        runOnMainSync(() -> activity.finish()); waitForIdleSync();
        require(getTargetContext().getSharedPreferences("companion", 0).edit().remove("endpoint").commit(), "fixture_setup_failed");
        launch(); click(await("ПК"));
        AccessibilityNodeInfo settings = find(root(), "Настройки ПК");
        click(settings != null ? settings : await("Подключить ПК"));
        await("Подключить компаньон");
        View parent = focusedWindow(); require(parent != null, "parent_window_missing");
        float parentDim = dim(parent);
        click(await("Подключить компаньон")); await("Сканировать QR"); await("Ввести код");
        View child = focusedWindow();
        requireDialogStack(child, 2, parent);
        require(Math.abs(dim(child) - parentDim) < 0.001f, "connection_changed_dim");
        require(animations(parent) != 0, "dialog_motion_missing");
        events.add(phase + ": PASS");
        phase = "qr_code_window_stack"; click(await("Ввести код")); await("Ввести код");
        requireDialogStack(focusedWindow(), 3, child);
        capture("manual-code"); click(await("Сканировать QR")); await("Ввести код");
        requireDialogStack(child, 2, parent);
        click(await("Назад")); await("Подключить компаньон");
        require(focusedWindow() == parent, "parent_window_was_recreated");
        click(await("Готово")); events.add(phase + ": PASS");

    }

    /** Opens settings only. Does not execute voice commands or control a real TV. */
    private void runStandardEdition() throws Exception {
        phase = "standard_edition";
        try {
            getTargetContext().getClassLoader().loadClass("belowevolve.airremote.VoiceControlService");
            throw new CheckFailure("voice_service_in_standard_edition");
        } catch (ClassNotFoundException expected) { }
        File model = new File(getTargetContext().getNoBackupFilesDir(), "voice");
        require(model.mkdirs() || model.isDirectory(), "model_fixture_directory");
        Files.write(new File(model, "previous-voice-edition").toPath(), new byte[1024]);
        require(getTargetContext().getSharedPreferences("remote", 0).edit()
            .putString("host", "127.0.0.1").putString("name", "Edition fixture")
            .putBoolean("auto_connect", false).commit(), "edition_fixture_setup_failed");
        SharedPreferences identity = getTargetContext().getSharedPreferences("tv_identity", 0);
        if (!identity.contains("127.0.0.1"))
            require(identity.edit().putString("127.0.0.1", "edition-fixture").commit(), "edition_fixture_identity");
        launch();
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (model.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        require(!model.exists(), "old_voice_model_not_removed");
        click(await("ТВ")); click(await("Настройки ТВ"));
        await("Забыть ТВ");
        require(find(root(), "Голосовое управление") == null, "voice_settings_in_standard_edition");
        require("127.0.0.1".equals(getTargetContext().getSharedPreferences("remote", 0).getString("host", "")),
            "edition_cleanup_removed_pairing_selection");
        capture("standard-edition"); click(await("Готово"));
        await("Микрофон");
        events.add("standard_edition: PASS (no assistant, old model removed, TV selection and PC microphone retained)");
    }

    /** Opens settings only. Does not execute voice commands or control a real TV. */
    private void runVoiceUi() throws Exception {
        phase = "voice_ui";
        require(getTargetContext().getSharedPreferences("remote", 0).edit()
                .putBoolean("auto_connect", false).commit(), "voice_ui_disable_tv_connection");
        boolean guide = "guide".equals(args.getString("voiceUi"));
        boolean stateButton = "state".equals(args.getString("voiceUi"));
        SharedPreferences tv = getTargetContext().getSharedPreferences("remote", 0);
        require(tv.edit().putBoolean("air_home:" + tv.getString("host", ""), !guide).commit(), "voice_ui_home_state_setup_failed");
        launch();
        click(await("ТВ")); click(await("Настройки ТВ"));
        await("Голосовое управление"); capture("voice-parent-" + args.getString("voiceUi"));
        click(await("Голосовое управление"));
        await("Голосовое управление"); SystemClock.sleep(500); capture("voice-top-" + args.getString("voiceUi"));
        if ("status".equals(args.getString("voiceUi"))) captureVoiceStatuses();
        if ("checks".equals(args.getString("voiceUi")) || guide || stateButton) {
            require(find(root(), "Прослушивание") == null, "obsolete_listening_heading");
            if (!guide && !stateButton) {
                click(await("Ассистент по умолчанию"));
                checkSettingsNavigation("assistant-settings");
                await("Голосовое управление");
                click(await("Уведомления"));
                checkSettingsNavigation("voice-notification-settings");
                await("Голосовое управление");
            }
            require(find(root(), "Быстрое питание по ИК") == null, "obsolete_ir_option");
            AccessibilityNodeInfo scroll = scrollable(root());
            require(scroll != null, "voice_commands_not_scrollable");
            require(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), "voice_scroll_failed");
            SystemClock.sleep(350); capture("voice-middle-checks");
            for (int i=0;i<8;i++) {
                scroll = scrollable(root());
                if (scroll == null || !scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break;
                SystemClock.sleep(150);
            }
            capture("voice-bottom-" + args.getString("voiceUi"));
            if (guide) {
                await("Гайд по установке");
                require(find(root(), "Перемотай \"15 минут\"") == null, "air_home_commands_without_companion");
            } else {
                require(find(root(), "Гайд по установке") == null, "guide_with_companion");
                await("Громкость \"20\""); await("Перемотай \"15 минут\""); await("Отмотай \"30 секунд\"");
                if (stateButton) {
                    captureHoveredButton("Air Home", "voice-home-present-hover");
                    require(headerSwitch().isChecked(), "home_switch_not_checked");
                    click(await("Air Home"));
                    await("Гайд по установке");
                    require(!headerSwitch().isChecked(), "home_switch_still_checked");
                    require(!tv.getBoolean("air_home:" + tv.getString("host", ""), true), "home_button_disable_not_saved");
                    capture("voice-home-absent");
                    captureHoveredButton("Air Home", "voice-home-absent-hover");
                    click(await("Air Home"));
                    waitForIdleSync();
                    require(headerSwitch().isChecked(), "home_switch_not_reenabled");
                    require(tv.getBoolean("air_home:" + tv.getString("host", ""), false), "home_button_enable_not_saved");
                    require(find(root(), "Гайд по установке") == null, "guide_after_enabling_home");
                    events.add("home_header_switch_toggle_and_persistence: PASS");
                }
            }
        }
        click(await("Готово"));
        events.add("voice_ui: PASS (settings only, no TV commands)");
    }

    /** Emulator-only previews of transient messages; never opens the microphone or sends commands. */
    private void captureVoiceStatuses() throws Exception {
        require("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "status_preview_requires_emulator");
        Class<?> service = Class.forName("belowevolve.airremote.VoiceControlService");
        java.lang.reflect.Field field = service.getDeclaredField("mutableStatus"); field.setAccessible(true);
        Object flow = field.get(null);
        Class<?> mutable = Class.forName("kotlinx.coroutines.flow.MutableStateFlow");
        java.lang.reflect.Method get = Class.forName("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        java.lang.reflect.Method set = mutable.getMethod("setValue", Object.class);
        java.lang.reflect.Constructor<?> status = Class.forName("belowevolve.airremote.VoiceControlStatus")
                .getDeclaredConstructor(boolean.class, boolean.class, String.class);
        Object original = get.invoke(flow);
        String[] messages = {"Подготовка распознавания…", "Микрофон временно занят или отключён Android. Жду восстановления…", "Команда переключения на HDMI отправлена", "Слушаю команды"};
        try {
            for (int i = 0; i < messages.length; i++) {
                set.invoke(flow, status.newInstance(true, i == 0, messages[i]));
                await(messages[i]); SystemClock.sleep(300); capture("voice-status-" + i);
            }
            events.add("full_width_voice_status_previews: PASS");
        } finally { set.invoke(flow, original); }
    }

    private void captureHoveredButton(String label, String name) throws Exception {
        AccessibilityNodeInfo node = await(label);
        while (node != null && !node.isClickable()) node = node.getParent();
        require(node != null, "hover_button_missing");
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        long at = SystemClock.uptimeMillis();
        MotionEvent hover = MotionEvent.obtain(at, at, MotionEvent.ACTION_HOVER_MOVE, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        hover.setSource(InputDevice.SOURCE_MOUSE);
        require(automation.injectInputEvent(hover, true), "hover_injection_failed"); hover.recycle();
        try { SystemClock.sleep(150); capture(name); }
        finally {
            MotionEvent exit = MotionEvent.obtain(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_EXIT, bounds.exactCenterX(), bounds.exactCenterY(), 0);
            exit.setSource(InputDevice.SOURCE_MOUSE); automation.injectInputEvent(exit, true); exit.recycle();
        }
    }

    private AccessibilityNodeInfo headerSwitch() {
        AccessibilityNodeInfo node = await("Air Home");
        while (node != null && !node.isCheckable()) node = node.getParent();
        require(node != null, "home_header_switch_missing");
        return node;
    }

    private void checkSettingsNavigation(String name) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 7_000;
        AccessibilityNodeInfo system = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            system = automation.getRootInActiveWindow();
            if (system != null && system.getPackageName() != null && !pkg.contentEquals(system.getPackageName())) break;
            SystemClock.sleep(50);
        }
        require(system != null && system.getPackageName() != null && !pkg.contentEquals(system.getPackageName()), name + "_not_opened");
        SystemClock.sleep(500);
        Bitmap screenshot = automation.takeScreenshot();
        require(screenshot != null, "system_screenshot_missing");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(getTargetContext().getExternalFilesDir("modal-fixture"), name + ".png"))) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        screenshot.recycle();
        events.add(name + ": PASS (" + system.getPackageName() + ")");
        require(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK), "settings_back_failed");
        SystemClock.sleep(350);
    }

    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.isScrollable()) return node;
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo found = scrollable(node.getChild(i)); if (found != null) return found;
        }
        return null;
    }

    private void runInteractions() throws Exception {
        phase = "launch"; launch(); click(await("ПК")); click(await("Настройки ПК"));
        await("Автоклавиатура");
        phase = "companion_ready";
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        while (find(root(), "Подключить компаньон") != null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        require(find(root(), "Подключить компаньон") == null, "live_companion_required");
        View pcWindow = focusedWindow();
        phase = "auto_keyboard_subscription";
        for (int i = 0; i < 4; i++) {
            click(await("Автоклавиатура"));
            observeWithoutAction("Подключить компаньон", i < 2 ? 2_000 : 600);
            require(focusedWindow() == pcWindow, "settings_window_changed");
        }
        events.add(phase + ": PASS");
        capture("pc-settings"); capturePressedRow("pc-settings-pressed"); click(await("Готово"));

        phase = "tv_settings_resume";
        click(await("ТВ")); click(await("Настройки ТВ")); await("Автоклавиатура");
        View tvWindow = focusedWindow();
        runOnMainSync(() -> activity.moveTaskToBack(true));
        SystemClock.sleep(300);
        Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage(pkg);
        require(intent != null, "resume_unavailable");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        runOnMainSync(() -> getTargetContext().startActivity(intent));
        await("Автоклавиатура");
        observeWithoutAction("Подключиться", 2_000);
        require(focusedWindow() == tvWindow, "resume_recreated_dialog");
        events.add(phase + ": PASS");
        capture("tv-settings"); click(await("Готово"));

        phase = "tv_keyboard_back";
        click(await("Клавиатура")); await("Очистить");
        View editor = focusedWindow(); awaitIme(editor);
        backGesture(false); await("Очистить");
        require(attached(editor), "cancelled_back_closed_editor");
        backGesture(true); await("ТВ");
        require(!attached(editor) && find(root(), "Очистить") == null, "one_back_did_not_leave_editor");
        events.add(phase + ": PASS");

        phase = "pc_keyboard_back";
        click(await("ПК")); click(await("Клавиатура Android")); await("Очистить");
        editor = focusedWindow(); awaitIme(editor);
        backGesture(true); await("ПК");
        require(!attached(editor) && find(root(), "Очистить") == null, "one_back_did_not_leave_editor");
        events.add(phase + ": PASS");
    }

    private void observeWithoutAction(String label, long duration) {
        long deadline = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo node = root(); require(node != null, "own_window_lost_focus");
            require(find(node, label) == null, "transient_connection_action_" + label);
            SystemClock.sleep(40);
        }
    }
    private void runUnifiedConnection() throws Exception {
        phase = "companion_first_bluetooth";
        SharedPreferences link = getTargetContext().getSharedPreferences("companion", 0);
        String endpoint = link.getString("endpoint", "");
        if (endpoint.isEmpty() && "true".equals(args.getString("fixtureCompanion"))) {
            File file = new File(getTargetContext().getExternalFilesDir("modal-fixture"), "pairing-uri");
            String value = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            require(value.matches("airremote://192\\.168\\.31\\.163:54346/[a-f0-9]{64}/[A-Za-z0-9_-]{43}"), "invalid_fixture_companion");
            require(link.edit().putString("endpoint", value).commit(), "fixture_pairing_failed");
            require(file.delete(), "fixture_pairing_file_cleanup_failed");
            endpoint = value;
        }
        require(!endpoint.isEmpty(), "paired_companion_required");
        // Exercise the reported order: companion is paired, Bluetooth is not selected yet.
        require(getTargetContext().getSharedPreferences("pc_remote", 0).edit().remove("host").putBoolean("auto_connect", false).commit(), "bluetooth_fixture_failed");
        launch(); click(await("ПК")); click(await("Настройки ПК")); await("Автоклавиатура");
        View window = focusedWindow();
        click(await("Подключить Bluetooth")); await("Bluetooth · клавиатура и мышь");
        await("Компаньон подключён");
        requireDialogStack(focusedWindow(), 2, window);
        require(endpoint.equals(link.getString("endpoint", "")), "bluetooth_setup_forgot_companion");
        events.add(phase + ": PASS");
        String name = args.getString("bluetoothName");
        if (name != null) {
            phase = "live_bluetooth_after_companion";
            click(await(name)); await("Автоклавиатура");
            require(find(root(), "Подключить Bluetooth") == null, "bluetooth_not_connected");
            requireSingleDialog(window);
            require(endpoint.equals(link.getString("endpoint", "")), "bluetooth_connect_forgot_companion");
            capture("pc-settings"); click(await("Готово"));
            events.add(phase + ": PASS");
            runOnMainSync(() -> activity.finish()); waitForIdleSync();
            require(link.edit().remove("endpoint").commit(), "endpoint_fixture_failed");
            phase = "microphone_bluetooth_direct_qr";
            launch(); click(await("ПК")); await(name);
            click(await("Микрофон")); click(await("Настройки микрофона"));
            await("Сканировать QR"); await("Ввести код");
            require(find(root(), "Bluetooth · клавиатура и мышь") == null, "redundant_connection_page");
            window = focusedWindow(); requireSingleDialog(window);
            click(await("Ввести код")); await("Ввести код"); requireDialogStack(focusedWindow(), 2, window);
            click(await("Сканировать QR")); await("Ввести код"); requireSingleDialog(window);
            click(await("Отмена")); await("ТВ");
            events.add(phase + ": PASS");
            runOnMainSync(() -> activity.finish()); waitForIdleSync();
            require(link.edit().putString("endpoint", endpoint).commit(), "endpoint_restore_failed");
            launch();
        } else { click(await("Назад")); await("Автоклавиатура"); click(await("Готово")); }
        phase = "microphone_unified_settings";
        click(await("Микрофон")); click(await("Настройки микрофона")); await("Показывать волну");
        require(find(root(), "Автоклавиатура") == null && find(root(), "Чувствительность") == null, "pc_controls_in_microphone");
        await("Забыть ПК"); require(find(root(), "Забыть компаньон") == null, "separate_forget_action");
        capture("mic-settings"); click(await("Готово")); events.add(phase + ": PASS");
        phase = "tv_settings_without_dividers";
        click(await("ТВ")); click(await("Настройки ТВ")); await("Автоклавиатура");
        capture("tv-settings"); capturePressedRow("tv-settings-pressed"); click(await("Готово"));
        events.add(phase + ": PASS");
    }
    private String fixtureHost() { return args.getString("tvFixtureHost", "127.0.0.1"); }
    private String fixtureName() { return args.getString("tvFixtureName", "Air Remote fixture TV"); }
    private void runTvPairing() throws Exception {
        phase = "tv_pairing_fixture";
        SharedPreferences identity = getTargetContext().getSharedPreferences("tv_identity", 0);
        java.util.Map<String, ?> pins = new java.util.HashMap<>(identity.getAll());
        require(!identity.contains(fixtureHost()), "fixture_host_already_pinned");
        require(getTargetContext().getSharedPreferences("remote", 0).edit().remove("host").remove("name")
                .putBoolean("auto_connect", false).putBoolean("auto_keyboard", false).commit(), "tv_fixture_failed");
        launch(); click(await("ТВ"));
        for (int attempt = 0; attempt < ("true".equals(args.getString("pairedRetryOnly")) ? 0 : 4); attempt++) {
            phase = "tv_pairing_attempt_" + (attempt + 1);
            click(await("Подключить ТВ")); await(fixtureName());
            require(find(root(), "IP-адрес ТВ") == null, "manual_tv_ip_present");
            View window = focusedWindow(); click(await(fixtureName()));
            if (attempt == 2) {
                click(await("Закрыть")); // Cancel while the fixture delays its acknowledgement.
                observeWithoutAction("Ввести код", 1_300);
            } else {
                await("Ввести код"); requireDialogStack(focusedWindow(), 2, window);
                if (attempt == 0) {
                    Bundle draft = new Bundle(); draft.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "A1B");
                    require(editable(root()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, draft), "pin_draft_rejected");
                    waitForIdleSync(); SystemClock.sleep(150);
                    recreateActivity(); await("Ввести код");
                    require("A1B".contentEquals(editable(root()).getText()), "pin_draft_lost_on_recreation");
                    window = focusedWindow(); requireDialogStack(window, 2, null);
                    events.add("tv_pairing_survives_recreation: PASS");
                }
                require(find(root(), "Автоклавиатура") == null && find(root(), "Забыть ТВ") == null, "settings_shown_before_pairing");
                if (attempt == 1) {
                    for (int back = 0; back < 2 && find(root(), "Ввести код") != null; back++) {
                        require(root() != null, "own_focus_required_for_back");
                        sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                        sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
                        waitForIdleSync(); SystemClock.sleep(200);
                    }
                } else click(await("Отмена"));
            }
            await("Подключить ТВ"); require(!attached(window), "orphan_tv_dialog_after_cancel");
            require(find(root(), "Автоклавиатура") == null && find(root(), "Подключиться") == null, "orphan_settings_after_cancel");
            events.add(phase + ": PASS");
        }
        require(pins.equals(identity.getAll()), "tv_certificate_pins_changed");
        events.add("tv_pins_preserved: PASS");
        phase = "tv_success_without_settings";
        click(await("Подключить ТВ")); await(fixtureName()); click(await(fixtureName())); await("Ввести код");
        View window = focusedWindow(); requireDialogStack(window, 2, null);
        if ("true".equals(args.getString("pinGeometry"))) checkPinGeometry(window);
        java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        java.security.cert.Certificate client = store.getCertificate("air-remote-client-tls-v2");
        require(client != null, "fixture_client_certificate_missing");
        File cert = new File(getTargetContext().getExternalFilesDir("modal-fixture"), "fixture-cert.pem");
        java.security.cert.Certificate server;
        try (java.io.FileInputStream input = new java.io.FileInputStream(cert)) {
            server = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
        for (java.security.cert.Certificate certificate : new java.security.cert.Certificate[]{client, server}) {
            java.security.interfaces.RSAPublicKey key = (java.security.interfaces.RSAPublicKey)certificate.getPublicKey();
            for (java.math.BigInteger number : new java.math.BigInteger[]{key.getModulus(), key.getPublicExponent()}) {
                byte[] bytes = number.toByteArray(); hash.update(bytes, bytes[0] == 0 ? 1 : 0, bytes.length - (bytes[0] == 0 ? 1 : 0));
            }
        }
        hash.update(new byte[]{(byte)0xa5, 0x5a});
        String code = String.format(java.util.Locale.ROOT, "%02XA55A", hash.digest()[0] & 255);
        Bundle value = new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code);
        require(editable(root()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value), "fixture_pin_input_rejected");
        waitForIdleSync(); SystemClock.sleep(100); click(await("Подключить"));
        await("Подключить ТВ"); require(!attached(window), "settings_window_after_success");
        require(find(root(), "Автоклавиатура") == null, "settings_opened_after_success");
        require(identity.contains(fixtureHost()), "fixture_pairing_not_confirmed");
        events.add(phase + ": PASS");
        phase = "paired_tv_connection_keeps_picker";
        runOnMainSync(() -> activity.finish()); waitForIdleSync();
        require(getTargetContext().getSharedPreferences("remote", 0).edit().remove("host").remove("name")
                .putBoolean("auto_connect", false).commit(), "paired_fixture_failed");
        launch(); click(await("ТВ")); click(await("Подключить ТВ")); await(fixtureName());
        View picker = focusedWindow(); click(await(fixtureName()));
        long pendingDeadline = SystemClock.elapsedRealtime() + 5_000;
        boolean pending = false;
        while (SystemClock.elapsedRealtime() < pendingDeadline) {
            AccessibilityNodeInfo row = find(root(), fixtureName());
            if (rowLoading(row)) { pending = true; break; }
            SystemClock.sleep(25);
        }
        require(pending, "tv_row_loader_not_visible");
        capture("paired-loading");
        requireSingleDialog(picker);
        require(find(root(), "Ввести код") == null && find(root(), "Автоклавиатура") == null, "wrong_paired_tv_page");
        click(await("Закрыть"));
        require(!attached(picker), "paired_cancel_kept_picker_open");
        await("Подключить ТВ");
        require(identity.contains(fixtureHost()), "paired_cancel_forgot_certificate");
        events.add(phase + ": PASS");
    }
    private Rect pinBounds(View window) {
        AccessibilityNodeInfo field = editable(root()); require(field != null, "pin_field_missing");
        Rect bounds = new Rect(); field.getBoundsInScreen(bounds);
        int[] origin = new int[2]; runOnMainSync(() -> window.getLocationOnScreen(origin));
        bounds.offset(-origin[0], -origin[1]);
        return bounds;
    }
    private void checkPinGeometry(View window) throws Exception {
        List<Rect> positions = new ArrayList<>();
        Bundle value = new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        require(editable(root()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value), "pin_clear_failed");
        waitForIdleSync(); SystemClock.sleep(220); positions.add(pinBounds(window));
        for (String text : new String[]{"A", "A1B2C3", ""}) {
            value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            require(editable(root()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value), "pin_typing_failed");
            waitForIdleSync(); SystemClock.sleep(220); positions.add(pinBounds(window));
        }
        events.add("pin_geometry: " + positions);
        if ("true".equals(args.getString("stablePin"))) {
            for (Rect bounds : positions) require(bounds.equals(positions.get(0)), "pin_field_moves_while_typing");
            events.add("pin_geometry_stable: PASS");
        }
        capture("pin-field");
    }
    private AccessibilityNodeInfo editable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo field = editable(node.getChild(i)); if (field != null) return field;
        }
        return null;
    }
    private void runMicrophoneWithoutCompanion() throws Exception {
        phase = "disconnected_routing";
        require(getTargetContext().getSharedPreferences("pc_remote", 0).edit()
                .putString("host", "02:00:00:00:00:01").putBoolean("auto_connect", false).commit(), "selection_fixture_failed");
        require(getTargetContext().getSharedPreferences("companion", 0).edit().remove("endpoint").commit(), "endpoint_fixture_failed");
        require(getTargetContext().getSharedPreferences("remote", 0).edit().remove("host").remove("name")
                .putBoolean("auto_connect", false).putBoolean("auto_keyboard", false).commit(), "tv_fixture_failed");
        launch();
        AccessibilityNodeInfo power = await("Питание");
        require(power.isEnabled(), "offline_tv_power_disabled");
        events.add("offline_tv_power_accessible: PASS"); // Do not transmit IR to the user's TV.
        tapMainContent(); await("Подключить ТВ");
        require(find(root(), "IP-адрес ТВ") == null, "manual_tv_ip_present");
        capture("tv-devices"); click(await("Закрыть")); assertFlowClosed();
        events.add("tv_any_tap_opens_picker_without_ip: PASS");
        phase = "pc_open_combined_picker";
        click(await("ПК")); tapMainContent();
        await("Bluetooth · клавиатура и мышь"); await("Подключить компаньон");
        View bluetoothWindow = focusedWindow(); requireSingleDialog(bluetoothWindow);
        capture("bluetooth-devices");
        click(await("Подключить компаньон")); await("Ввести код");
        View qrWindow = focusedWindow(); requireDialogStack(qrWindow, 2, bluetoothWindow);
        click(await("Ввести код")); await("Ввести код"); requireDialogStack(focusedWindow(), 3, qrWindow);
        Bundle draft = new Bundle(); draft.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "fixture draft");
        require(editable(root()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, draft), "code_draft_rejected");
        waitForIdleSync(); SystemClock.sleep(150);
        phase = "pc_recreate_code";
        recreateActivity(); await("Ввести код");
        require("fixture draft".contentEquals(editable(root()).getText()), "code_draft_lost_on_recreation");
        requireDialogStack(focusedWindow(), 3, null);
        click(await("Сканировать QR")); await("Ввести код"); requireDialogStack(focusedWindow(), 2, null);
        require(find(root(), "Не удалось открыть камеру") == null, "qr_camera_did_not_resume");
        phase = "pc_close_all_windows";
        pressBack(); assertFlowClosed();
        events.add("pc_combined_connection_and_close_without_reopen: PASS");
        click(await("Микрофон")); await("Подключить ПК"); tapMainContent();
        await("Bluetooth · клавиатура и мышь"); await("Подключить компаньон");
        click(await("Подключить компаньон")); await("Ввести код");
        click(await("Назад")); await("Bluetooth · клавиатура и мышь"); requireSingleDialog(focusedWindow());
        click(await("Закрыть")); assertFlowClosed();
        events.add("microphone_matches_pc_connection: PASS");
        runOnMainSync(() -> activity.finish()); waitForIdleSync();
        String dummy = "airremote://192.168.31.126:65333/" + "0".repeat(64) + "/" + "A".repeat(43);
        require(getTargetContext().getSharedPreferences("companion", 0).edit().putString("endpoint", dummy).commit(), "saved_endpoint_fixture_failed");
        launch(); click(await("Микрофон")); await("Подключить ПК"); tapMainContent();
        await("Bluetooth · клавиатура и мышь"); await("Подключить компаньон");
        click(await("Закрыть")); assertFlowClosed();
        events.add("saved_companion_does_not_bypass_bluetooth_picker: PASS");
    }
    private void tapMainContent() {
        require(root() != null && dialogWindowCount() == 0, "own_main_screen_required");
        int width = getTargetContext().getResources().getDisplayMetrics().widthPixels;
        int height = getTargetContext().getResources().getDisplayMetrics().heightPixels;
        long start = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, width * .5f, height * .45f, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            require(automation.injectInputEvent(event, true), "content_tap_failed"); event.recycle();
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(80);
        }
        waitForIdleSync(); SystemClock.sleep(260);
    }
    private void pressBack() {
        require(root() != null, "own_focus_required_for_back");
        sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        waitForIdleSync(); SystemClock.sleep(300);
    }
    private int dialogWindowCount() {
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        runOnMainSync(() -> {
            for (View view : WindowInspector.getGlobalWindowViews())
                if (view.isAttachedToWindow() && view.isShown() && pkg.equals(view.getContext().getPackageName())
                        && view.getLayoutParams() instanceof WindowManager.LayoutParams
                        && ((WindowManager.LayoutParams)view.getLayoutParams()).type == WindowManager.LayoutParams.TYPE_APPLICATION)
                    count.incrementAndGet();
        });
        return count.get();
    }
    private void assertFlowClosed() {
        long settle = SystemClock.elapsedRealtime() + 3_000;
        while ((root() == null || dialogWindowCount() != 0) && SystemClock.elapsedRealtime() < settle) SystemClock.sleep(50);
        require(root() != null, "own_screen_lost_focus_after_close");
        require(dialogWindowCount() == 0, "connection_flow_did_not_close");
        long deadline = SystemClock.elapsedRealtime() + 700;
        do {
            require(root() != null && dialogWindowCount() == 0, "connection_flow_reopened");
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
    }
    private void runMicrophoneSettings() throws Exception {
        phase = "microphone_visualization_settings";
        require(!getTargetContext().getSharedPreferences("companion", 0).getString("endpoint", "").isEmpty(), "paired_companion_required");
        launch(); click(await("Микрофон"));
        click(await("Настройки микрофона")); await("Показывать волну"); await("Анимация микрофона");
        View window = focusedWindow();
        for (String label : new String[]{"Анимация микрофона", "Показывать волну"}) {
            AccessibilityNodeInfo row = await(label);
            while (row != null && !row.isClickable()) row = row.getParent();
            require(row != null, "toggle_missing");
            Rect before = new Rect(); row.getBoundsInScreen(before);
            click(row); click(await(label));
            Rect after = new Rect(); await(label).getBoundsInScreen(after);
            require(focusedWindow() == window, "microphone_toggle_recreated_dialog");
        }
        capture("microphone-settings"); click(await("Готово")); assertFlowClosed();
        events.add("microphone_wave_and_icon_settings_independent: PASS");
    }
    private boolean rowLoading(AccessibilityNodeInfo node) {
        if (node == null) return false;
        Rect label = new Rect(); node.getBoundsInScreen(label);
        for (int depth = 0; node != null && depth < 5; depth++, node = node.getParent()) {
            if ((node.getStateDescription() != null && node.getStateDescription().toString().contains("Подключение")) || rowProgress(node, label)) return true;
        }
        return false;
    }
    private boolean rowProgress(AccessibilityNodeInfo node, Rect label) {
        if (node == null || !node.isVisibleToUser()) return false;
        if (android.text.TextUtils.equals("android.widget.ProgressBar", node.getClassName())) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (bounds.centerY() >= label.top && bounds.centerY() <= label.bottom) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) if (rowProgress(node.getChild(i), label)) return true;
        return false;
    }
    private void runBluetoothProgress() throws Exception {
        phase = "bluetooth_device_progress";
        String address = getTargetContext().getSharedPreferences("pc_remote", 0).getString("host", "");
        String requestedName = args.getString("bluetoothName");
        require(getTargetContext().getSharedPreferences("pc_remote", 0).edit().putBoolean("auto_connect", false).commit(), "bt_fixture_failed");
        require(getTargetContext().getSharedPreferences("remote", 0).edit().putBoolean("auto_connect", false).commit(), "tv_fixture_failed");
        require(getTargetContext().getSharedPreferences("companion", 0).edit().remove("endpoint").commit(), "endpoint_fixture_failed");
        phase = "bluetooth_profile_setup";
        launch(); click(await("ПК"));
        android.bluetooth.BluetoothManager manager = activity.getSystemService(android.bluetooth.BluetoothManager.class);
        require(manager != null && manager.getAdapter() != null, "bluetooth_adapter_unavailable");
        android.bluetooth.BluetoothAdapter adapter = manager.getAdapter();
        android.bluetooth.BluetoothDevice target = null;
        for (android.bluetooth.BluetoothDevice device : adapter.getBondedDevices())
            if (requestedName != null ? requestedName.equals(device.getName()) : device.getAddress().equals(address)) { target = device; break; }
        require(target != null, "paired_pc_required");
        final android.bluetooth.BluetoothDevice device = target;
        final String name = device.getName() != null ? device.getName() : device.getAddress();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<android.bluetooth.BluetoothHidDevice> profile = new AtomicReference<>();
        runOnMainSync(() -> adapter.getProfileProxy(getTargetContext(), new android.bluetooth.BluetoothProfile.ServiceListener() {
            public void onServiceConnected(int id, android.bluetooth.BluetoothProfile proxy) {
                profile.set((android.bluetooth.BluetoothHidDevice)proxy); latch.countDown();
            }
            public void onServiceDisconnected(int id) {}
        }, android.bluetooth.BluetoothProfile.HID_DEVICE));
        require(latch.await(5, TimeUnit.SECONDS), "hid_profile_unavailable");
        try {
            if (profile.get().getConnectionState(device) != android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                profile.get().disconnect(device);
                long deadline = SystemClock.elapsedRealtime() + 6_000;
                while (profile.get().getConnectionState(device) != android.bluetooth.BluetoothProfile.STATE_DISCONNECTED && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                require(profile.get().getConnectionState(device) == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED, "pc_did_not_disconnect");
            }
        } finally { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.HID_DEVICE, profile.get()); }
        phase = "bluetooth_device_click";
        tapMainContent();
        await("Bluetooth · клавиатура и мышь");
        AccessibilityNodeInfo row = await(name);
        while (row != null && !row.isClickable()) row = row.getParent();
        require(row != null && row.performAction(AccessibilityNodeInfo.ACTION_CLICK), "device_click_failed");
        long deadline = SystemClock.elapsedRealtime() + 6_000;
        boolean observed = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo pending = find(root(), name);
            if (rowLoading(pending)) { observed = true; break; }
            SystemClock.sleep(10);
        }
        require(observed, "bluetooth_row_loader_not_visible");
        events.add("bluetooth_row_loader_visible_on_real_pc: PASS");
        if (dialogWindowCount() != 0) click(await("Закрыть"));
        assertFlowClosed();

    }
    private Activity activityInView(View view) {
        android.content.Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper && !(context instanceof Activity))
            context = ((android.content.ContextWrapper)context).getBaseContext();
        if (context instanceof Activity && !((Activity)context).isDestroyed()) return (Activity)context;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Activity found = activityInView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private void refreshActivity() {
        runOnMainSync(() -> {
            for (View view : WindowInspector.getGlobalWindowViews()) {
                if (!view.isAttachedToWindow() || !view.isShown() || !pkg.equals(view.getContext().getPackageName())) continue;
                Activity found = activityInView(view);
                if (found != null) activity = found;
            }
        });
    }
    private void recreateActivity() {
        refreshActivity();
        Activity old = activity;
        runOnMainSync(old::recreate);
        long deadline = SystemClock.elapsedRealtime() + 7_000;
        do {
            waitForIdleSync(); refreshActivity();
            if (activity != old && !activity.isDestroyed()) break;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        require(activity != old && !activity.isDestroyed(), "activity_recreation_missing");
        runOnMainSync(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        waitForIdleSync(); SystemClock.sleep(300);
    }
    private void requireSingleDialog(View expected) { requireDialogStack(expected, 1, null); }
    private void requireDialogStack(View expected, int expectedCount, View retainedParent) {
        AtomicReference<Integer> count = new AtomicReference<>(0);
        runOnMainSync(() -> {
            for (View view : WindowInspector.getGlobalWindowViews())
                if (view.isAttachedToWindow() && view.isShown() && pkg.equals(view.getContext().getPackageName())
                        && view.getLayoutParams() instanceof WindowManager.LayoutParams
                        && ((WindowManager.LayoutParams)view.getLayoutParams()).type == WindowManager.LayoutParams.TYPE_APPLICATION)
                    count.set(count.get() + 1);
        });
        boolean same = focusedWindow() == expected;
        require(same && count.get() == expectedCount, "connection_dialogs_" + count.get() + "_expected_" + expectedCount + "_same_" + same);
        if (retainedParent != null) require(retainedParent != expected && attached(retainedParent), "parent_dialog_not_retained");
    }
    private void capturePressedRow(String name) throws Exception {
        AccessibilityNodeInfo node = await("Автоклавиатура");
        while (node != null && !node.isClickable()) node = node.getParent();
        require(node != null && focusedWindow() != null, "own_focus_required_for_press");
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        long start = SystemClock.uptimeMillis();
        float x = bounds.left + bounds.width() * 0.3f, y = bounds.exactCenterY();
        MotionEvent down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        require(automation.injectInputEvent(down, true), "press_injection_failed"); down.recycle();
        try { SystemClock.sleep(150); capture(name); }
        finally {
            MotionEvent cancel = MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0);
            cancel.setSource(InputDevice.SOURCE_TOUCHSCREEN); automation.injectInputEvent(cancel, true); cancel.recycle();
        }
    }
    private void awaitIme(View window) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        do {
            runOnMainSync(() -> visible.set(window.getRootWindowInsets() != null && window.getRootWindowInsets().isVisible(WindowInsets.Type.ime())));
            if (!visible.get()) SystemClock.sleep(50);
        } while (!visible.get() && SystemClock.elapsedRealtime() < deadline);
        require(visible.get(), "ime_not_visible");
        SystemClock.sleep(250);
    }
    private void backGesture(boolean complete) {
        View window = focusedWindow(); require(window != null && root() != null, "own_focus_required_for_gesture");
        int width = getTargetContext().getResources().getDisplayMetrics().widthPixels;
        int height = getTargetContext().getResources().getDisplayMetrics().heightPixels;
        float y = height * 0.55f;
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i <= 14; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 14 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            float progress = complete ? i / 14f : i <= 7 ? i / 14f : (14 - i) / 14f;
            MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1f + width * 0.38f * progress, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            require(automation.injectInputEvent(event, true), "gesture_injection_failed"); event.recycle();
            SystemClock.sleep(16);
        }
        waitForIdleSync(); SystemClock.sleep(350);
    }
    private View focusedWindow() {
        AtomicReference<View> result = new AtomicReference<>();
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            runOnMainSync(() -> {
                for (View view : WindowInspector.getGlobalWindowViews()) {
                    if (view.isAttachedToWindow() && view.isShown() && view.hasWindowFocus()
                            && pkg.equals(view.getContext().getPackageName())
                            && view.getLayoutParams() instanceof WindowManager.LayoutParams
                            && ((WindowManager.LayoutParams)view.getLayoutParams()).type == WindowManager.LayoutParams.TYPE_APPLICATION) result.set(view);
                }
            });
            if (result.get() == null) SystemClock.sleep(50);
        } while (result.get() == null && SystemClock.elapsedRealtime() < deadline);
        return result.get();
    }
    private boolean attached(View view) { AtomicReference<Boolean> result = new AtomicReference<>(false); runOnMainSync(() -> result.set(view.isAttachedToWindow())); return result.get(); }
    private float dim(View view) { AtomicReference<Float> result = new AtomicReference<>(0f); runOnMainSync(() -> result.set(((WindowManager.LayoutParams)view.getLayoutParams()).dimAmount)); return result.get(); }
    private int animations(View view) { AtomicReference<Integer> result = new AtomicReference<>(-1); runOnMainSync(() -> result.set(((WindowManager.LayoutParams)view.getLayoutParams()).windowAnimations)); return result.get(); }
    private void capture(String name) throws Exception {
        View window = focusedWindow(); require(window != null, "capture_window_missing");
        SystemClock.sleep(200);
        LinkedBlockingQueue<PixelCopy.Result> queue = new LinkedBlockingQueue<>(1);
        runOnMainSync(() -> PixelCopy.request(PixelCopy.Request.Builder.ofWindow(window).build(), getTargetContext().getMainExecutor(), queue::offer));
        PixelCopy.Result result = queue.poll(5, TimeUnit.SECONDS);
        require(result != null && result.getStatus() == PixelCopy.SUCCESS, "pixel_copy_failed");
        File image = new File(getTargetContext().getExternalFilesDir("modal-fixture"), name + ".png");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(image)) { result.getBitmap().compress(Bitmap.CompressFormat.PNG, 100, out); }
        result.getBitmap().recycle();
    }
    private AccessibilityNodeInfo root() { AccessibilityNodeInfo node = automation.getRootInActiveWindow(); return node != null && node.getPackageName() != null && pkg.contentEquals(node.getPackageName()) ? node : null; }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (node.isVisibleToUser() && (text.contentEquals(node.getText() == null ? "" : node.getText()) || text.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = find(node.getChild(i), text); if (found != null) return found; }
        return null;
    }
    private AccessibilityNodeInfo await(String text) {
        long deadline = SystemClock.elapsedRealtime() + 7_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            automation.clearCache();
            AccessibilityNodeInfo node = find(root(), text);
            if (node != null && node.isEnabled()) {
                AccessibilityNodeInfo action = node;
                while (action != null && !action.isClickable()) action = action.getParent();
                if (action == null || action.isEnabled()) return node;
            }
            SystemClock.sleep(50);
        }
        throw new CheckFailure("control_unavailable_" + text);
    }
    private void click(AccessibilityNodeInfo node) {
        while (node != null && !node.isClickable()) node = node.getParent();
        require(node != null && node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "click_rejected");
        waitForIdleSync(); SystemClock.sleep(260);
    }
    private static void require(boolean ok, String message) { if (!ok) throw new CheckFailure(message); }
    private static final class CheckFailure extends RuntimeException { CheckFailure(String message) { super(message); } }
}
