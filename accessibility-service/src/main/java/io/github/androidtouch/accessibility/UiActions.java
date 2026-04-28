package io.github.androidtouch.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * High-level Phase 2 actions that operate on AccessibilityNodeInfo by reference,
 * not by pixel coordinate. All methods accept an UiNodeMatcher and return JSON
 * suitable for sending back over HTTP.
 *
 * IMPORTANT: callers MUST recycle returned AccessibilityNodeInfo themselves.
 */
public final class UiActions {

    private static final long DEFAULT_FIND_TIMEOUT_MS = 0L;
    private static final long FIND_POLL_INTERVAL_MS = 100L;
    private static final int  DEFAULT_MAX_SCROLLS    = 20;
    private static final long SCROLL_SETTLE_MS       = 350L;

    private final AccessibilityService service;

    public UiActions(AccessibilityService service) {
        this.service = service;
    }

    /**
     * Wait up to timeoutMs for at least one node matching `matcher` to exist,
     * then return all matches as a JSONArray of node-field objects.
     *
     * Returns an empty array if nothing was found in time.
     */
    public JSONArray find(UiNodeMatcher matcher, long timeoutMs, int maxDepth, boolean visibleOnly)
            throws JSONException {
        long deadline = timeoutMs > 0L ? SystemClock.uptimeMillis() + timeoutMs : -1L;
        UiTreeBuilder builder = new UiTreeBuilder(maxDepth, visibleOnly, null, matcher);
        while (true) {
            JSONArray matches = builder.findAll(service);
            if (matches.length() > 0) {
                return matches;
            }
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) {
                return matches;
            }
            try {
                Thread.sleep(Math.min(FIND_POLL_INTERVAL_MS, remaining));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return matches;
            }
        }
    }

    /**
     * Find the first matching node and return it (NOT recycled — caller owns it).
     * Returns null if not found in time.
     */
    public AccessibilityNodeInfo findFirstNode(UiNodeMatcher matcher, long timeoutMs, boolean visibleOnly) {
        long deadline = timeoutMs > 0L ? SystemClock.uptimeMillis() + timeoutMs : -1L;
        while (true) {
            AccessibilityNodeInfo hit = searchOnce(matcher, visibleOnly);
            if (hit != null) {
                return hit;
            }
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) {
                return null;
            }
            try {
                Thread.sleep(Math.min(FIND_POLL_INTERVAL_MS, remaining));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    public JSONObject clickNode(UiNodeMatcher matcher, long timeoutMs, boolean visibleOnly) throws JSONException {
        return performAction(matcher, timeoutMs, visibleOnly,
                AccessibilityNodeInfo.ACTION_CLICK, "ACTION_CLICK");
    }

    public JSONObject longClickNode(UiNodeMatcher matcher, long timeoutMs, boolean visibleOnly) throws JSONException {
        return performAction(matcher, timeoutMs, visibleOnly,
                AccessibilityNodeInfo.ACTION_LONG_CLICK, "ACTION_LONG_CLICK");
    }

    private JSONObject performAction(UiNodeMatcher matcher, long timeoutMs, boolean visibleOnly,
                                     int action, String actionName) throws JSONException {
        AccessibilityNodeInfo node = findFirstNode(matcher, timeoutMs, visibleOnly);
        if (node == null) {
            return error("element_not_found", "no node matched " + matcher);
        }
        try {
            AccessibilityNodeInfo target = node;
            // Climb to the nearest actionable ancestor if the matched node itself does not support the action
            if (action == AccessibilityNodeInfo.ACTION_CLICK && !node.isClickable()) {
                target = climbToClickable(node);
            } else if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && !node.isLongClickable()) {
                target = climbToLongClickable(node);
            }
            if (target == null) {
                return error("not_actionable", "matched node and ancestors are not " + actionName.toLowerCase() + "able");
            }
            boolean ok = target.performAction(action);
            JSONObject result = new JSONObject();
            result.put("status", ok ? "completed" : "failed");
            result.put("action", actionName);
            result.put("node", nodeSummary(target));
            if (target != node) {
                try {
                    target.recycle();
                } catch (Throwable ignored) {
                }
            }
            return result;
        } finally {
            try {
                node.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Scroll forward inside any scrollable ancestor of the search root until
     * a node matching `matcher` becomes visible. Returns the bounds.
     */
    public JSONObject scrollTo(UiNodeMatcher matcher, long timeoutMs, int maxScrolls, boolean visibleOnly)
            throws JSONException {
        if (maxScrolls <= 0) {
            maxScrolls = DEFAULT_MAX_SCROLLS;
        }
        long deadline = timeoutMs > 0L ? SystemClock.uptimeMillis() + timeoutMs : -1L;

        // First, see if it's already visible
        AccessibilityNodeInfo hit = searchOnce(matcher, true);
        if (hit != null) {
            try {
                JSONObject ok = new JSONObject();
                ok.put("status", "already_visible");
                ok.put("scrolls", 0);
                ok.put("node", nodeSummary(hit));
                return ok;
            } finally {
                try {
                    hit.recycle();
                } catch (Throwable ignored) {
                }
            }
        }

        int scrolls = 0;
        while (scrolls < maxScrolls) {
            AccessibilityNodeInfo scrollable = findScrollable();
            if (scrollable == null) {
                return error("no_scrollable", "no scrollable container available");
            }
            boolean ok;
            try {
                ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } finally {
                try {
                    scrollable.recycle();
                } catch (Throwable ignored) {
                }
            }
            if (!ok) {
                return error("scroll_failed", "scroll forward refused (end of list?)");
            }
            scrolls++;

            sleepCapped(SCROLL_SETTLE_MS, deadline);
            hit = searchOnce(matcher, true);
            if (hit != null) {
                try {
                    JSONObject result = new JSONObject();
                    result.put("status", "completed");
                    result.put("scrolls", scrolls);
                    result.put("node", nodeSummary(hit));
                    return result;
                } finally {
                    try {
                        hit.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (deadline > 0L && SystemClock.uptimeMillis() >= deadline) {
                break;
            }
        }
        return error("element_not_found", "scrolled " + scrolls + " times without finding " + matcher);
    }

    /** Returns a JSON description of the currently-input-focused node, or {found:false}. */
    public JSONObject focused() throws JSONException {
        AccessibilityNodeInfo focused = null;

        // 1) Try active window root → findFocus
        AccessibilityNodeInfo activeRoot = service.getRootInActiveWindow();
        if (activeRoot != null) {
            try {
                focused = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused == null) {
                    focused = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                }
            } finally {
                try {
                    activeRoot.recycle();
                } catch (Throwable ignored) {
                }
            }
        }

        // 2) Fall back to scanning all windows
        if (focused == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = service.getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo w : windows) {
                        if (w == null) {
                            continue;
                        }
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r == null) {
                            continue;
                        }
                        try {
                            AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                            if (f != null) {
                                focused = f;
                                break;
                            }
                        } finally {
                            try {
                                r.recycle();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        JSONObject result = new JSONObject();
        if (focused == null) {
            result.put("found", false);
            return result;
        }
        try {
            result.put("found", true);
            result.put("node", nodeSummary(focused));
        } finally {
            try {
                focused.recycle();
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    // ---- helpers ----------------------------------------------------------

    private AccessibilityNodeInfo searchOnce(UiNodeMatcher matcher, boolean visibleOnly) {
        List<AccessibilityNodeInfo> roots = collectRoots();
        try {
            for (AccessibilityNodeInfo r : roots) {
                AccessibilityNodeInfo match = depthFirstFind(r, matcher, visibleOnly);
                if (match != null) {
                    return match;
                }
            }
            return null;
        } finally {
            for (AccessibilityNodeInfo r : roots) {
                try {
                    r.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private AccessibilityNodeInfo depthFirstFind(AccessibilityNodeInfo node, UiNodeMatcher matcher, boolean visibleOnly) {
        if (node == null) {
            return null;
        }
        if (visibleOnly && !node.isVisibleToUser()) {
            // continue into children — invisible parents can have visible kids
        }
        if (matcher.matches(node) && (!visibleOnly || node.isVisibleToUser())) {
            // return a fresh copy; caller will recycle the originals
            return AccessibilityNodeInfo.obtain(node);
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                AccessibilityNodeInfo result = depthFirstFind(child, matcher, visibleOnly);
                if (result != null) {
                    return result;
                }
            } finally {
                try {
                    child.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollable() {
        List<AccessibilityNodeInfo> roots = collectRoots();
        try {
            for (AccessibilityNodeInfo r : roots) {
                AccessibilityNodeInfo s = depthFirstScrollable(r);
                if (s != null) {
                    return s;
                }
            }
            return null;
        } finally {
            for (AccessibilityNodeInfo r : roots) {
                try {
                    r.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private AccessibilityNodeInfo depthFirstScrollable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isScrollable() && node.isVisibleToUser()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                AccessibilityNodeInfo s = depthFirstScrollable(child);
                if (s != null) {
                    return s;
                }
            } finally {
                try {
                    child.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private List<AccessibilityNodeInfo> collectRoots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> ws = service.getWindows();
                if (ws != null) {
                    for (AccessibilityWindowInfo w : ws) {
                        if (w == null) {
                            continue;
                        }
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null) {
                            out.add(r);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = service.getRootInActiveWindow();
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private AccessibilityNodeInfo climbToClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < 32 && cur != null; i++) {
            if (cur.isClickable()) {
                return cur;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            try {
                cur.recycle();
            } catch (Throwable ignored) {
            }
            cur = parent;
        }
        if (cur != null) {
            try {
                cur.recycle();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private AccessibilityNodeInfo climbToLongClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < 32 && cur != null; i++) {
            if (cur.isLongClickable()) {
                return cur;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            try {
                cur.recycle();
            } catch (Throwable ignored) {
            }
            cur = parent;
        }
        if (cur != null) {
            try {
                cur.recycle();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static JSONObject nodeSummary(AccessibilityNodeInfo node) throws JSONException {
        JSONObject json = new JSONObject();
        if (node.getText() != null)               json.put("text", node.getText().toString());
        if (node.getContentDescription() != null) json.put("content_desc", node.getContentDescription().toString());
        if (node.getViewIdResourceName() != null) json.put("view_id", node.getViewIdResourceName());
        if (node.getClassName() != null)          json.put("class", node.getClassName().toString());
        if (node.getPackageName() != null)        json.put("package", node.getPackageName().toString());

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        JSONObject b = new JSONObject();
        b.put("left", r.left);
        b.put("top", r.top);
        b.put("right", r.right);
        b.put("bottom", r.bottom);
        b.put("center_x", r.centerX());
        b.put("center_y", r.centerY());
        b.put("width", r.width());
        b.put("height", r.height());
        json.put("bounds", b);

        json.put("clickable", node.isClickable());
        json.put("long_clickable", node.isLongClickable());
        json.put("focusable", node.isFocusable());
        json.put("focused", node.isFocused());
        json.put("scrollable", node.isScrollable());
        json.put("password", node.isPassword());
        json.put("editable", node.isEditable());
        json.put("enabled", node.isEnabled());
        json.put("visible", node.isVisibleToUser());
        return json;
    }

    private static JSONObject error(String code, String msg) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("status", "error");
        o.put("error", code);
        o.put("message", msg);
        return o;
    }

    private static void sleepCapped(long ms, long deadline) {
        long left = deadline > 0 ? Math.min(ms, deadline - SystemClock.uptimeMillis()) : ms;
        if (left <= 0) {
            return;
        }
        try {
            Thread.sleep(left);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
