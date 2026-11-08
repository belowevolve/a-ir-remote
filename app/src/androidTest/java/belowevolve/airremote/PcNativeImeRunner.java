package belowevolve.airremote;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** SDK-only opt-in driver. Requires a separately armed, owned Windows input fixture. */
public final class PcNativeImeRunner extends Instrumentation {
    private static final String PREFIX = "PC prefix | ";
    private static final String SUFFIX = " | suffix";
    private static final long STEP_SETTLE_MS = 700;
    private Bundle arguments;
    private String packageName;
    private UiAutomation automation;
    private EditText editor;
    private InputConnection connection;
    private String phase = "opt_in";
    private boolean openedDialog;
    private final List<String> events = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean passed = false;
        try {
            require("true".equals(arguments.getString("nativeImeFixture")), "explicit_opt_in_required");
            for (String key : new String[] {"screenPreview", "clipboardFixture", "tvHost", "tvSubmit", "tvRawKey", "tvRawLaunchURI", "tvRawDelayMs"}) {
                require(!arguments.containsKey(key), "incompatible_probe");
            }
            require(Build.VERSION.SDK_INT >= 29, "requires_api_29");
            packageName = getTargetContext().getPackageName();
            if ("state".equals(arguments.getString("typingProbe"))) {
                result.putBoolean("savedTv", !getTargetContext().getSharedPreferences("remote", 0).getString("host", "").isEmpty());
                result.putBoolean("savedCompanion", getTargetContext().getSharedPreferences("companion", 0).contains("endpoint"));
                result.putBoolean("autoKeyboard", getTargetContext().getSharedPreferences("pc_remote", 0).getBoolean("auto_keyboard", false));
            } else runFixture();
            passed = true;
            event("complete", "local_sequence_pass_pc_fixture_must_verify");
        } catch (Throwable error) {
            event(phase, error instanceof FixtureFailure ? error.getMessage() : "unexpected_fixture_exception");
        } finally {
            // Only close the production dialog this runner opened; no global Back,
            // injected coordinates, or operations on PiP/keyboard/other applications.
            if (openedDialog && automation != null) {
                try {
                    main(() -> { }); // Do not bring an obscured dialog back over another app.
                    AccessibilityNodeInfo close = find(ownRoot(), "Закрыть");
                    if (close != null) click(close);
                } catch (Throwable ignored) { event("cleanup", "dialog_close_unavailable"); }
            }
        }
        result.putString("stream", String.join("\n", events) + "\n");
        if (packageName != null) {
            try {
                File directory = getTargetContext().getExternalFilesDir("native-ime-fixture");
                require(directory != null && (directory.isDirectory() || directory.mkdirs()), "report_directory_unavailable");
                File report = new File(directory, "native-ime-result.txt");
                try (FileOutputStream output = new FileOutputStream(report)) {
                    output.write((String.join("\n", events) + "\n").getBytes(StandardCharsets.UTF_8));
                }
                result.putString("reportPath", report.getAbsolutePath());
            } catch (Exception ignored) { result.putString("reportError", "report_write_failed"); }
        }
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void runFixture() {
        phase = "open_pc";
        Intent launch = getTargetContext().getPackageManager().getLaunchIntentForPackage(packageName);
        require(launch != null, "launch_unavailable");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity activity = startActivitySync(launch);
        require(packageName.equals(activity.getPackageName()), "wrong_activity");
        automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(service);
        long routeDeadline = SystemClock.elapsedRealtime() + 5_000;
        boolean routed = false;
        while (!routed && SystemClock.elapsedRealtime() < routeDeadline) {
            if (find(ownRoot(), "Очистить") != null) routed = true;
            else {
                AccessibilityNodeInfo pc = find(ownRoot(), "ПК");
                if (pc != null) { click(pc); routed = true; }
                else SystemClock.sleep(50);
            }
        }
        require(routed, "pc_route_unavailable");
        SystemClock.sleep(STEP_SETTLE_MS);
        phase = "open_native_keyboard";
        if (find(ownRoot(), "Очистить") == null) click(awaitNode("Клавиатура Android", 5_000));
        if ("pair".equals(arguments.getString("typingProbe"))) {
            phase = "pair_existing_companion";
            require(!getTargetContext().getSharedPreferences("companion", 0).contains("endpoint"), "existing_pair_must_not_be_replaced");
            File codeFile = new File(getTargetContext().getExternalFilesDir("native-ime-fixture"), "pairing-uri");
            try {
                String code = new String(java.nio.file.Files.readAllBytes(codeFile.toPath()), StandardCharsets.UTF_8).trim();
                require(code.startsWith("airremote://192.168.31.163:54346/"), "fixture_companion_host_mismatch");
                click(awaitNode("Ввести код", 3_000));
                AccessibilityNodeInfo input = null;
                long deadline = SystemClock.elapsedRealtime() + 5_000;
                while (input == null && SystemClock.elapsedRealtime() < deadline) {
                    input = findEditable(ownRoot());
                    if (input == null) SystemClock.sleep(50);
                }
                require(input != null, "pairing_editor_unavailable");
                Bundle value = new Bundle();
                value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code);
                require(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value), "pairing_input_rejected");
                click(awaitNode("Подключить", 3_000));
                require(getTargetContext().getSharedPreferences("companion", 0).contains("endpoint"), "pairing_not_saved");
                require(getTargetContext().getSharedPreferences("companion", 0).edit().commit(), "pairing_flush_failed");
                SystemClock.sleep(800);
            } catch (java.io.IOException error) { throw new FixtureFailure("fixture_pair_file_unavailable"); }
            finally { codeFile.delete(); }
            event(phase, "manual_pairing_saved_without_logging_code");
            return;
        }
        if ("offline".equals(arguments.getString("typingProbe"))) {
            phase = "offline_companion_gate";
            awaitConnectionFlow();
            require(find(ownRoot(), "Очистить") == null, "offline_editor_opened");
            click(awaitNode("Готово", 3_000));
            click(awaitNode("Вставить на ПК", 3_000));
            awaitConnectionFlow();
            require(find(ownRoot(), "Очистить") == null, "offline_paste_opened_editor");
            click(awaitNode("Готово", 3_000));
            click(awaitNode("Микрофон", 3_000));
            AccessibilityNodeInfo microphone = find(ownRoot(), "Включить микрофон");
            click(microphone != null ? microphone : awaitNode("Подключить ПК", 3_000));
            awaitConnectionFlow();
            click(awaitNode("Готово", 3_000));
            event(phase, "keyboard_paste_and_microphone_open_connection_pass");
            return;
        }
        openedDialog = true;
        awaitActiveEditor(10_000);
        awaitSoftKeyboard();
        main(() -> {
            boolean steam = emptyInitialField();
            require(steam ? editor.length() == 0 : (PREFIX + SUFFIX).contentEquals(editor.getText()), "requires_known_initial_pc_field");
            require(editor.getSelectionStart() == (steam ? 0 : PREFIX.length()) && editor.getSelectionEnd() == (steam ? 0 : PREFIX.length()), "initial_selection_mismatch");
            EditorInfo info = new EditorInfo();
            connection = editor.onCreateInputConnection(info);
            require(connection != null, "input_connection_unavailable");
            require((info.inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0, "pc_editor_not_multiline");
            require((info.imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_GO
                    && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0, "pc_ime_action_is_not_native_enter");
        });
        event("initial_field_and_keyboard", "known_field_selection_and_visible_ime_pass");

        if ("empty".equals(arguments.getString("typingProbe"))) {
            phase = "empty_field_no_phantom_text";
            verify("", 0, 0);
            SystemClock.sleep(1_000);
            verify("", 0, 0);
            return;
        }
        if ("notepad".equals(arguments.getString("typingProbe"))) {
            phase = "notepad_first_line";
            batch(() -> require(connection.commitText("first", 1), "commit_rejected"));
            verify("first", 5, 5);
            java.util.concurrent.atomic.AtomicBoolean clearedDuringEnter = new java.util.concurrent.atomic.AtomicBoolean();
            android.text.TextWatcher watcher = new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) { }
                public void afterTextChanged(android.text.Editable s) { if (s.length() == 0) clearedDuringEnter.set(true); }
            };
            main(() -> editor.addTextChangedListener(watcher));
            main(() -> require(connection.performEditorAction(EditorInfo.IME_ACTION_GO), "ime_action_rejected"));
            verify("first\n", 6, 6);
            phase = "notepad_second_line";
            batch(() -> require(connection.commitText("second", 1), "commit_rejected"));
            verify("first\nsecond", 12, 12);
            phase = "notepad_fast_typing_after_enter";
            main(() -> require(connection.performEditorAction(EditorInfo.IME_ACTION_GO), "ime_action_rejected"));
            main(() -> {
                connection.beginBatchEdit();
                require(connection.commitText("third", 1), "commit_rejected");
            });
            SystemClock.sleep(300); // Let the PC's Enter ACK arrive during the newer IME batch.
            main(() -> {
                require("first\nsecond\nthird".contentEquals(editor.getText()), "ack_overwrote_active_ime_batch");
                connection.endBatchEdit();
            });
            verify("first\nsecond\nthird", 18, 18);
            main(() -> editor.removeTextChangedListener(watcher));
            require(!clearedDuringEnter.get(), "notepad_enter_temporarily_cleared_text");
            event("notepad_no_flash", "no_empty_editor_state_during_enter_pass");
            phase = "clear_whole_input_button";
            click(awaitNode("Очистить", 3_000));
            verify("", 0, 0);
            return;
        }
        if ("steam".equals(arguments.getString("typingProbe"))) {
            phase = "steam_text_only";
            batch(() -> require(connection.commitText("airremote_test", 1), "commit_rejected"));
            verify("airremote_test", 14, 14);
            SystemClock.sleep(15_000); // Root captures only Steam's prepared search field.
            phase = "steam_clear_probe";
            batch(() -> {
                require(connection.setSelection(0, 14), "selection_rejected");
                require(connection.commitText("", 1), "delete_rejected");
            });
            verify("", 0, 0);
            return;
        }
        phase = "compose_typo";
        batch(() -> require(connection.setComposingText("превет", 1), "composition_rejected"));
        verifyMiddle("превет", 6, 6);
        main(() -> require(BaseInputConnection.getComposingSpanStart(editor.getText()) == PREFIX.length()
                && BaseInputConnection.getComposingSpanEnd(editor.getText()) == PREFIX.length() + 6, "composition_span_missing"));

        phase = "autocorrect_commit";
        batch(() -> require(connection.commitText("привет", 1), "commit_rejected"));
        verifyMiddle("привет", 6, 6);
        main(() -> require(BaseInputConnection.getComposingSpanStart(editor.getText()) == -1, "composition_not_committed"));

        phase = "append_word";
        batch(() -> require(connection.commitText(" мир ", 1), "commit_rejected"));
        verifyMiddle("привет мир ", 11, 11);
        phase = "select_middle_word";
        batch(() -> require(connection.setSelection(PREFIX.length() + 7, PREFIX.length() + 10), "selection_rejected"));
        verifyMiddle("привет мир ", 7, 10);
        phase = "replace_middle_word";
        batch(() -> require(connection.commitText("друг", 1), "commit_rejected"));
        verifyMiddle("привет друг ", 11, 11);
        phase = "move_to_end";
        batch(() -> require(connection.setSelection(PREFIX.length() + 12, PREFIX.length() + 12), "selection_rejected"));
        verifyMiddle("привет друг ", 12, 12);

        phase = "native_enter_keyevent";
        main(() -> {
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        });
        verify("", 0, 0);
        phase = "type_after_native_enter";
        batch(() -> require(connection.commitText("после Enter", 1), "commit_rejected"));
        verify("после Enter", 11, 11);
        phase = "footer_enter";
        click(awaitNode("Ввод", 3_000));
        verify("", 0, 0);
        phase = "nested_native_enter_deferred";
        main(() -> {
            connection.beginBatchEdit();
            connection.beginBatchEdit();
            require(connection.commitText("batch", 1), "commit_rejected");
            require(connection.performEditorAction(EditorInfo.IME_ACTION_GO), "ime_action_rejected");
            connection.endBatchEdit();
        });
        verify("batch", 5, 5);
        main(() -> connection.endBatchEdit());
        verify("", 0, 0);
        phase = "type_after_third_enter";
        batch(() -> require(connection.commitText("final", 1), "commit_rejected"));
        verify("final", 5, 5);
    }

    private boolean emptyInitialField() {
        String probe = arguments.getString("typingProbe");
        return "steam".equals(probe) || "notepad".equals(probe) || "empty".equals(probe);
    }

    private void verifyMiddle(String middle, int start, int end) {
        verify(PREFIX + middle + SUFFIX, PREFIX.length() + start, PREFIX.length() + end);
    }

    private void awaitSoftKeyboard() {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Boolean> visible = new AtomicReference<>(false);
            main(() -> visible.set(editor.hasFocus() && (Build.VERSION.SDK_INT < 30
                    || (editor.getRootWindowInsets() != null && editor.getRootWindowInsets().isVisible(WindowInsets.Type.ime())))));
            if (visible.get()) return;
            SystemClock.sleep(50);
        }
        AtomicReference<String> state = new AtomicReference<>();
        main(() -> state.set("view_focus=" + editor.hasFocus() + ",window_focus=" + editor.hasWindowFocus()
                + ",ime_insets_visible=" + (Build.VERSION.SDK_INT >= 30 && editor.getRootWindowInsets() != null
                    && editor.getRootWindowInsets().isVisible(WindowInsets.Type.ime()))
                + ",ime_active=" + editor.getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).isActive(editor)));
        boolean imeWindowVisible = false;
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && root.isVisibleToUser()) imeWindowVisible = true;
            }
        }
        event("keyboard_visibility", state.get() + ",ime_window_visible=" + imeWindowVisible);
        throw new FixtureFailure("android_soft_keyboard_not_visible");
    }

    private void batch(Runnable edits) {
        main(() -> {
            require(editor.isEnabled(), "editor_stopped");
            connection.beginBatchEdit();
            try { edits.run(); } finally { connection.endBatchEdit(); }
        });
    }

    private void verify(String expected, int start, int end) {
        waitForIdleSync();
        SystemClock.sleep(STEP_SETTLE_MS);
        failOnUiError();
        main(() -> {
            require(editor.isAttachedToWindow() && editor.isEnabled(), "editor_stopped");
            require(expected.contentEquals(editor.getText()), "synthetic_phone_fragment_mismatch");
            require(editor.getSelectionStart() == start && editor.getSelectionEnd() == end, "synthetic_phone_selection_mismatch");
        });
        event(phase, "local_value_ok"); // Not a claim that the separate PC fixture passed.
    }

    private void awaitActiveEditor(long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            failOnUiError();
            AtomicReference<EditText> candidate = new AtomicReference<>();
            main(() -> {
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    if (root.isShown() && root.hasWindowFocus() && packageName.equals(root.getContext().getPackageName())) {
                        EditText found = editText(root);
                        if (found != null) { candidate.set(found); break; }
                    }
                }
            });
            AtomicReference<Boolean> initialized = new AtomicReference<>(false);
            if (candidate.get() != null) main(() -> initialized.set(emptyInitialField()
                    ? candidate.get().length() == 0
                    : (PREFIX + SUFFIX).contentEquals(candidate.get().getText())
                    && candidate.get().getSelectionStart() == PREFIX.length() && candidate.get().getSelectionEnd() == PREFIX.length()));
            if (initialized.get()) {
                editor = candidate.get();
                return;
            }
            SystemClock.sleep(50);
        }
        throw new FixtureFailure("active_native_editor_unavailable");
    }

    private EditText editText(View view) {
        if (!view.isShown()) return null;
        if (view instanceof EditText && view.isEnabled() && ((EditText) view).getHint() != null
                && "Введите текст".contentEquals(((EditText) view).getHint())) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = editText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void failOnUiError() {
        String[][] knownErrors = {
                {"Поле на ПК сменилось.", "focus_changed"},
                {"Отпусти клавиши ПК", "input_busy"},
                {"Выбери поле текста на ПК", "unsupported_target"},
                {"Это поле не поддерживается", "unsupported_edit"},
                {"Неподдерживаемый текст", "invalid_text"},
                {"Текст слишком длинный", "invalid_text"},
                {"Ввод остановлен.", "input_failed"},
                {"Нет ответа от ПК.", "ack_timeout"},
                {"Связь потеряна.", "connection_lost"},
                {"Обнови Air Companion", "capability_unavailable"}
        };
        AccessibilityNodeInfo root = ownRoot();
        for (String[] error : knownErrors) if (hasTextPrefix(root, error[0])) throw new FixtureFailure(error[1]);
    }

    private boolean hasTextPrefix(AccessibilityNodeInfo node, String prefix) {
        if (node == null) return false;
        if (node.isVisibleToUser() && node.getText() != null && node.getText().toString().startsWith(prefix)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasTextPrefix(node.getChild(i), prefix)) return true;
        return false;
    }

    private AccessibilityNodeInfo ownRoot() {
        if (automation == null) return null;
        AccessibilityNodeInfo active = automation.getRootInActiveWindow();
        if (isOwn(active)) return active;
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (isOwn(root)) return root;
        }
        return null;
    }

    private boolean isOwn(AccessibilityNodeInfo node) {
        return node != null && node.getPackageName() != null && packageName.contentEquals(node.getPackageName());
    }

    private void awaitConnectionFlow() {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = ownRoot();
            if (find(root, "Ввести код") != null || find(root, "Подключиться") != null) return;
            SystemClock.sleep(50);
        }
        throw new FixtureFailure("companion_connection_unavailable");
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (node.isVisibleToUser() && (label.contentEquals(node.getText() == null ? "" : node.getText())
                || label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo awaitNode(String label, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo found = find(ownRoot(), label);
            if (found != null) return found;
            SystemClock.sleep(50);
        }
        throw new FixtureFailure("production_control_unavailable");
    }

    private void click(AccessibilityNodeInfo node) {
        while (node != null && !node.isClickable()) node = node.getParent();
        require(isOwn(node) && node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "production_control_rejected");
        waitForIdleSync();
    }

    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                if (editor != null) require(editor.getRootView().hasWindowFocus(), "phone_focus_changed");
                action.run();
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() instanceof FixtureFailure) throw (FixtureFailure) failure.get();
        require(failure.get() == null, "native_ime_operation_failed");
    }

    private void event(String label, String outcome) {
        String value = "NATIVE_IME " + label + ": " + outcome;
        events.add(value);
        Bundle status = new Bundle();
        status.putString("stream", value + "\n");
        sendStatus(0, status);
    }

    private static void require(boolean condition, String code) { if (!condition) throw new FixtureFailure(code); }
    private static final class FixtureFailure extends RuntimeException { FixtureFailure(String code) { super(code); } }
}
