package dev.gti.gboard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Dev-only accessibility service that dumps the FULL window hierarchy,
 * including the InputMethod window that uiautomator cannot see. Enabled via
 * adb during REA to capture Gboard's real UI metrics. Safe to leave in the
 * app: it is disabled by default and only logs on events.
 */
public class UiDumpService extends AccessibilityService {
    private static final String TAG = "UiDump";

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        Log.i(TAG, "UiDumpService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only dump when something in an INPUT_METHOD window changed, to avoid noise.
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return;
            for (AccessibilityWindowInfo w : windows) {
                if (w == null) continue;
                try {
                    if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    Log.i(TAG, "=== INPUT_METHOD WINDOW ===");
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root != null) {
                        dump(root, 0);
                        root.recycle();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "window dump error: " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "event error: " + t);
        }
    }

    private void dump(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 20) return;
        try {
            CharSequence txt = n.getText();
            CharSequence desc = n.getContentDescription();
            CharSequence cls = n.getClassName();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < depth; i++) b.append("  ");
            b.append(cls == null ? "?" : cls);
            if (txt != null && txt.length() > 0) b.append(" txt=\"").append(txt).append("\"");
            if (desc != null && desc.length() > 0) b.append(" desc=\"").append(desc).append("\"");
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            b.append(" [").append(r.left).append(',').append(r.top)
             .append("][").append(r.right).append(',').append(r.bottom).append(']');
            Log.i(TAG, b.toString());
            int cc = n.getChildCount();
            for (int i = 0; i < cc; i++) {
                dump(n.getChild(i), depth + 1);
            }
        } catch (Throwable t) {
            Log.w(TAG, "node dump error: " + t);
        }
    }

    @Override public void onInterrupt() { }
}
