package com.pmh.bridge.lsp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/**
 * Feeds the tracker list of the PICO 体感追踪器 app (com.pvr.swift).
 *
 * The list fragment observes {@code SwiftViewModel.getTrackerLiveData()} and
 * {@code getConnectableTrackerLiveData()}. Those are only ever populated from
 * devices Swift bonded itself, which cannot happen on Horizon OS, so the list
 * stays empty. We build the {@code PeripheralDevice} entries ourselves and push
 * them into both LiveData objects.
 */
public final class SwiftTrackerList {

    private static final String TAG = "PmhTrackerList";

    private SwiftTrackerList() {
    }

    public static void install(final ClassLoader cl) {
        try {
            final Class<?> vm = Class.forName("com.pvr.swift.viewmodel.SwiftViewModel", false, cl);
            XposedBridge.hookAllConstructors(vm, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    startFeeding(param.thisObject, cl);
                }
            });
            // The fragment turns devices into rows itself; supply them directly.
            Class<?> frag = Class.forName("com.pvr.swift.fragment.TrackerListFragment", false, cl);
            XposedBridge.hookAllMethods(frag, "handleListChange",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            java.util.List<Object> rows = buildTrackers(cl);
                            log("handleListChange -> " + rows.size());
                            return rows;
                        }
                    });
            // The RecyclerView adapter is the only reliable sink for the rows.
            Class<?> adapter = Class.forName("com.pvr.swift.adapter.ConnectableTrackerAdapter",
                    false, cl);
            XposedBridge.hookAllConstructors(adapter, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sAdapter = param.thisObject;
                }
            });
            // isChangingTrackerListEmpty() reads this one, and it gates the empty view.
            Class<?> source = Class.forName("com.pvr.swift.datasource.SwiftDataSource", false, cl);
            XposedBridge.hookAllConstructors(source, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    startFeedingSource(param.thisObject, cl);
                }
            });
            Class<?> frag2 = Class.forName("com.pvr.swift.fragment.TrackerListFragment", false, cl);
            XposedBridge.hookAllConstructors(frag2, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sFragment = param.thisObject;
                }
            });
            // Diagnostic: which fragment is actually on screen.
            Class<?> fragBase = Class.forName("androidx.fragment.app.Fragment", false, cl);
            XposedBridge.hookAllMethods(fragBase, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    log("fragment resumed: " + param.thisObject.getClass().getSimpleName());
                }
            });
            startSubmitting(cl);
            log("installed");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    private static volatile Object sAdapter;
    private static volatile Object sFragment;

    private static void startFeedingSource(final Object source, final ClassLoader cl) {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Object live = source.getClass().getMethod("getMChangingTrackerLiveData")
                            .invoke(source);
                    java.util.List<Object> rows = buildTrackers(cl);
                    if (live != null && !rows.isEmpty() && pushValue(live, rows)) {
                        log("source rows " + rows.size());
                    }
                } catch (Throwable t) {
                    log("source feed failed: " + t);
                }
                handler.postDelayed(this, 2000L);
            }
        }, 1500L);
    }

    /** Keeps re-submitting the rows so the fragment's own empty updates lose. */
    private static void startSubmitting(final ClassLoader cl) {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Object adapter = sAdapter;
                Object fragment = sFragment;
                if (adapter != null && fragment != null) {
                    try {
                        // Let the app build its own rows: it fills name/battery/state
                        // through the peripheral stub.
                        java.util.List<String> macs = PeripheralServiceStub.deviceMacs();
                        Class<?> deviceCls = Class.forName(
                                "com.pvr.btperipheral.api.PeripheralDevice", false, cl);
                        java.util.concurrent.CopyOnWriteArrayList<Object> devices =
                                new java.util.concurrent.CopyOnWriteArrayList<>();
                        for (String mac : macs) {
                            devices.add(deviceCls.getConstructor(String.class).newInstance(mac));
                        }
                        java.lang.reflect.Method update = findRefresh(fragment, adapter);
                        if (update == null) {
                            throw new NoSuchMethodException("refresh method");
                        }
                        update.invoke(null, fragment, adapter, devices);
                        int children = -1;
                        try {
                            java.lang.reflect.Field rv = fragment.getClass()
                                    .getDeclaredField("mRecyclerView");
                            rv.setAccessible(true);
                            Object view = rv.get(fragment);
                            // A RecyclerView with no layout manager never lays out
                            // its rows, which is why the list looked empty.
                            Object lm = view.getClass().getMethod("getLayoutManager").invoke(view);
                            if (lm == null) {
                                Class<?> linear = Class.forName(
                                        "androidx.recyclerview.widget.LinearLayoutManager", false, cl);
                                Object context = view.getClass().getMethod("getContext").invoke(view);
                                Object manager = linear.getConstructor(android.content.Context.class)
                                        .newInstance(context);
                                view.getClass().getMethod("setLayoutManager",
                                        Class.forName("androidx.recyclerview.widget.RecyclerView$LayoutManager",
                                                false, cl)).invoke(view, manager);
                                log("layout manager installed");
                            }
                            view.getClass().getMethod("setVisibility", int.class)
                                    .invoke(view, android.view.View.VISIBLE);
                            java.lang.reflect.Method gc = view.getClass()
                                    .getMethod("getChildCount");
                            children = (Integer) gc.invoke(view);
                        } catch (Throwable ignored) {
                        }
                        log("refresh rows " + devices.size()
                                + " frag=" + fragment.getClass().getSimpleName()
                                + " rvChildren=" + children
                                + " adapterItems=" + ((java.util.List<?>) adapter.getClass()
                                        .getMethod("getCurrentList").invoke(adapter)).size());
                    } catch (Throwable t) {
                        log("refresh failed: " + t);
                        try {
                            java.util.List<Object> rows = buildTrackers(cl);
                            adapter.getClass().getMethod("submitList", java.util.List.class)
                                    .invoke(adapter, rows);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                handler.postDelayed(this, 2000L);
            }
        }, 3000L);
    }

    /** Builds the row models the list adapter expects. */
    private static java.util.List<Object> buildTrackers(ClassLoader cl) {
        java.util.concurrent.CopyOnWriteArrayList<Object> rows =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            Class<?> trackerCls = Class.forName("com.pvr.swift.model.Tracker", false, cl);
            Class<?> deviceCls = Class.forName("com.pvr.btperipheral.api.PeripheralDevice", false, cl);
            // itemType 5 is the section header, 4 is a device row; itemStatus 2
            // renders the "connected" style, matching ConnectFragment's mapping.
            rows.add(trackerCls.getConstructor(int.class, int.class, deviceCls)
                    .newInstance(0, 5, null));
            String linked = PeripheralServiceStub.linkedMac();
            for (String mac : PeripheralServiceStub.deviceMacs()) {
                Object device = deviceCls.getConstructor(String.class).newInstance(mac);
                // 2 = connected, 0 = waiting, matching ConnectFragment's mapping.
                int status = mac.equalsIgnoreCase(linked) ? 2 : 0;
                rows.add(trackerCls.getConstructor(int.class, int.class, deviceCls)
                        .newInstance(status, 4, device));
            }
        } catch (Throwable t) {
            log("build failed: " + t);
        }
        return rows;
    }

    private static void startFeeding(final Object viewModel, final ClassLoader cl) {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                feed(viewModel, cl);
                handler.postDelayed(this, 2000L);
            }
        }, 2000L);
    }

    private static void feed(Object viewModel, ClassLoader cl) {
        try {
            java.util.List<String> macs = PeripheralServiceStub.deviceMacs();
            if (macs.isEmpty()) {
                return;
            }
            Class<?> deviceCls = Class.forName("com.pvr.btperipheral.api.PeripheralDevice", false, cl);
            java.util.concurrent.CopyOnWriteArrayList<Object> devices =
                    new java.util.concurrent.CopyOnWriteArrayList<>();
            for (String mac : macs) {
                devices.add(deviceCls.getConstructor(String.class).newInstance(mac));
            }
            boolean ok = push(viewModel, "getTrackerLiveData", devices);
            ok |= push(viewModel, "getConnectableTrackerLiveData", devices);
            if (ok) {
                log("posted " + devices.size() + " device(s)");
            }
        } catch (Throwable t) {
            log("feed failed: " + t);
        }
    }

    private static volatile java.lang.reflect.Method sRefresh;

    /**
     * Finds the fragment's static (fragment, adapter, CopyOnWriteArrayList) updater.
     * R8 renames lambdas, so match on the shape instead of the name.
     */
    private static java.lang.reflect.Method findRefresh(Object fragment, Object adapter) {
        java.lang.reflect.Method cached = sRefresh;
        if (cached != null) {
            return cached;
        }
        Class<?> fragCls = fragment.getClass();
        for (java.lang.reflect.Method m : fragCls.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 3) {
                continue;
            }
            if (!ps[0].isAssignableFrom(fragCls) || !ps[1].isAssignableFrom(adapter.getClass())) {
                continue;
            }
            String third = ps[2].getName();
            if (!third.contains("CopyOnWriteArrayList") && !third.equals("java.util.List")) {
                continue;
            }
            m.setAccessible(true);
            sRefresh = m;
            log("refresh method " + m.getName());
            return m;
        }
        return null;
    }

    private static boolean push(Object viewModel, String getter, Object value) {
        try {
            Object live = viewModel.getClass().getMethod(getter).invoke(viewModel);
            return live != null && pushValue(live, value);
        } catch (Throwable t) {
            log(getter + " failed: " + t);
            return false;
        }
    }

    /**
     * Swift is obfuscated, so the LiveData setter name is not stable. Walk the
     * class hierarchy and call the first single-argument void method that
     * accepts our list.
     */
    private static boolean pushValue(Object live, Object value) {
        for (Class<?> c = live.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 1 || m.getReturnType() != void.class) {
                    continue;
                }
                if (!m.getParameterTypes()[0].isAssignableFrom(value.getClass())
                        && m.getParameterTypes()[0] != Object.class) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    m.invoke(live, value);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
