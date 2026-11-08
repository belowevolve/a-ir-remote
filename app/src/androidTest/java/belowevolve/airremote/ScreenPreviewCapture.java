package belowevolve.airremote;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Diagnostic copy of the real app Window. No Kotlin, AndroidX, or production class dependencies. */
final class ScreenPreviewCapture {
    private final Instrumentation instrumentation;
    private final String packageName;
    private UiAutomation automation;

    ScreenPreviewCapture(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        packageName = instrumentation.getTargetContext().getPackageName();
    }

    void capture(String mode, Bundle result) throws Exception {
        String label;
        if ("pc".equals(mode) || "pc-collapsed".equals(mode)) label = "ПК";
        else if ("tv".equals(mode)) label = "ТВ";
        else if ("mic".equals(mode)) label = "Микрофон";
        else throw new IllegalArgumentException("screenPreview must be pc, pc-collapsed, tv or mic");

        Intent launch = instrumentation.getTargetContext().getPackageManager()
                .getLaunchIntentForPackage(packageName);
        require(launch != null, "App launch activity unavailable");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity activity = instrumentation.startActivitySync(launch);
        require(packageName.equals(activity.getPackageName()), "Unexpected launch activity");
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(service);
        click(awaitNode(5_000, label));

        if ("pc".equals(mode) || "pc-collapsed".equals(mode)) {
            String show = "Показать клавиатуру";
            String hide = "Скрыть клавиатуру";
            AccessibilityNodeInfo toggle = awaitNode(5_000, show, hide);
            String action = "pc-collapsed".equals(mode) ? hide : show;
            if (hasLabel(toggle, action)) click(toggle);
            awaitNode(5_000, "pc-collapsed".equals(mode) ? show : hide);
        }
        // A live meter or PiP may continue producing accessibility events. The bounded
        // idle timeout does not prevent copying our own window's current frame.
        try { automation.waitForIdle(300, 3_000); } catch (TimeoutException ignored) { }
        if ("pc".equals(mode) || "pc-collapsed".equals(mode)) awaitPcLayout(mode);
        View decor = activity.getWindow().getDecorView();
        // OnDraw runs before RenderThread submits the buffer. A posted main-thread
        // task alone can therefore still copy the previous frame.
        boolean frameCommit = Build.VERSION.SDK_INT >= 29 && decor.isHardwareAccelerated();
        for (int frame = 0; frame < 2; frame++) {
            if (frameCommit) awaitFrameCommit(activity, decor);
            else awaitDraw(activity, decor);
        }
        if (!frameCommit) SystemClock.sleep(100); // API 28/software best-effort fallback.
        Rect source = new Rect();
        instrumentation.runOnMainSync(() -> {
            WindowInsets insets = decor.getRootWindowInsets();
            int left = 0, top = 0, right = 0, bottom = 0;
            if (insets != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
                }
            }
            source.set(left, top, decor.getWidth() - right, decor.getHeight() - bottom);
        });
        require(!source.isEmpty(), "App content has no visible bounds");
        int width = Math.min(source.width(), 720);
        int height = Math.round(source.height() * (width / (float) source.width()));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        LinkedBlockingQueue<Integer> completion = new LinkedBlockingQueue<>(1);
        boolean callbackReceived = false;
        try {
            instrumentation.runOnMainSync(() -> PixelCopy.request(activity.getWindow(), source, bitmap,
                    code -> completion.offer(code), new Handler(Looper.getMainLooper())));
            Integer code = completion.poll(5, TimeUnit.SECONDS);
            callbackReceived = code != null;
            require(code != null && code == PixelCopy.SUCCESS,
                    "Window PixelCopy failed (result=" + (code == null ? "callback_timeout" : code) + ")");
            File folder = instrumentation.getTargetContext().getExternalFilesDir("screen-previews");
            require(folder != null && (folder.isDirectory() || folder.mkdirs()), "Preview directory unavailable");
            File target = new File(folder, "screen-" + mode + ".png");
            File temporary = new File(folder, "screen-" + mode + ".part");
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "PNG encoding failed");
                }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally { temporary.delete(); }
            result.putString("previewPath", target.getAbsolutePath());
            result.putString("previewSync", frameCommit ? "two_frame_commits" : "two_draws_fallback");
            result.putString("stream", "SCREEN_PREVIEW " + mode + ": " + bitmap.getWidth() + "x"
                    + bitmap.getHeight() + "; " + target.getAbsolutePath() + "\n");
        } finally {
            // A timed-out request may still own its destination; let that reference be
            // collected after its callback instead of recycling it during a GPU copy.
            if (callbackReceived) bitmap.recycle();
        }
    }

    private AccessibilityNodeInfo ownRoot() {
        AccessibilityNodeInfo active = automation.getRootInActiveWindow();
        if (isOwnNode(active)) return active;
        // PiP may own accessibility focus. Read only package identity from other
        // windows; every traversal and click is restricted to our own app tree.
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (isOwnNode(root)) return root;
        }
        return null;
    }

    private boolean isOwnNode(AccessibilityNodeInfo node) {
        return node != null && node.getPackageName() != null && packageName.contentEquals(node.getPackageName());
    }

    private static boolean hasLabel(AccessibilityNodeInfo node, String label) {
        return label.contentEquals(node.getText() == null ? "" : node.getText())
                || label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription());
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String[] labels) {
        if (node == null) return null;
        if (node.isVisibleToUser()) {
            for (String label : labels) if (hasLabel(node, label)) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), labels);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo awaitNode(long timeout, String... labels) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo node = find(ownRoot(), labels);
            if (node != null) return node;
            SystemClock.sleep(50);
        }
        throw new IllegalStateException("Requested Air Remote control unavailable");
    }

    private void click(AccessibilityNodeInfo node) {
        while (node != null && !node.isClickable()) node = node.getParent();
        require(isOwnNode(node) && node.isEnabled()
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Air Remote UI action rejected");
        instrumentation.waitForIdleSync();
    }

    private void awaitPcLayout(String mode) {
        String[][] controls = {
                {"Левая кнопка мыши"}, {"Правая кнопка мыши"}, {"Настройки ПК", "Подключить ПК"},
                {"Клавиатура Android"},
                {"Язык: русский", "Язык: английский"},
                {"Показать нумпад и дополнительные клавиши", "Показать буквенную клавиатуру"},
                {"pc-collapsed".equals(mode) ? "Показать клавиатуру" : "Скрыть клавиатуру"}
        };
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        long stableSince = 0;
        String previous = null;
        String missing = "changing layout";
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = ownRoot();
            StringBuilder geometry = new StringBuilder();
            boolean complete = true;
            for (String[] labels : controls) {
                AccessibilityNodeInfo node = find(root, labels);
                Rect bounds = new Rect();
                if (node != null) node.getBoundsInScreen(bounds);
                if (node == null || bounds.isEmpty()) { complete = false; missing = labels[0]; break; }
                geometry.append(bounds.toShortString()).append(node.isEnabled());
            }
            if (complete && "pc".equals(mode) && find(root, new String[] {"Ctrl"}) == null) {
                complete = false;
                missing = "Ctrl";
            }
            // Clipboard is optional (legacy/offline companion). Its appearance or
            // disappearance should restart settling without requiring a connection.
            geometry.append(find(root, new String[] {"Вставить на ПК"}) != null);
            String current = complete ? geometry.toString() : null;
            if (complete) missing = "changing layout";
            long now = SystemClock.elapsedRealtime();
            if (current != null && current.equals(previous)) {
                if (now - stableSince >= 500) return;
            } else {
                previous = current;
                stableSince = now;
            }
            SystemClock.sleep(50);
        }
        throw new IllegalStateException("PC toolbar did not reach a complete stable layout: " + missing);
    }

    private void awaitFrameCommit(Activity activity, View decor) throws InterruptedException {
        CountDownLatch committed = new CountDownLatch(1);
        Runnable callback = committed::countDown;
        ViewTreeObserver[] observer = new ViewTreeObserver[1];
        try {
            instrumentation.runOnMainSync(() -> {
                require(!activity.isDestroyed() && decor.isShown() && decor.getWidth() > 0 && decor.getHeight() > 0,
                        "App has no visible window surface");
                observer[0] = decor.getViewTreeObserver();
                observer[0].registerFrameCommitCallback(callback);
                decor.invalidate();
            });
            require(committed.await(5, TimeUnit.SECONDS), "App did not commit its preview frame");
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (observer[0] != null && observer[0].isAlive()) observer[0].unregisterFrameCommitCallback(callback);
            });
        }
    }

    private void awaitDraw(Activity activity, View decor) throws InterruptedException {
        CountDownLatch drawn = new CountDownLatch(1);
        AtomicBoolean firstDraw = new AtomicBoolean();
        ViewTreeObserver.OnDrawListener listener = new ViewTreeObserver.OnDrawListener() {
            @Override public void onDraw() {
                if (firstDraw.compareAndSet(false, true)) decor.post(() -> {
                    if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnDrawListener(this);
                    drawn.countDown();
                });
            }
        };
        try {
            instrumentation.runOnMainSync(() -> {
                require(!activity.isDestroyed() && decor.isShown() && decor.getWidth() > 0 && decor.getHeight() > 0,
                        "App has no visible window surface");
                decor.getViewTreeObserver().addOnDrawListener(listener);
                decor.invalidate();
            });
            require(drawn.await(5, TimeUnit.SECONDS), "App did not draw its preview");
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnDrawListener(listener);
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
