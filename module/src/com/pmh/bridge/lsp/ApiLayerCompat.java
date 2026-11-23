package com.pmh.bridge.lsp;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Replaces the missing PICO framework service "api_layer".
 *
 * <p>Implementing it in Java keeps the AIDL parcel layout and binder reference
 * handling in the platform's hands; the native stub could not do either safely.
 */
final class ApiLayerCompat {

    private static final String TAG = "PmhApiLayer";
    private static final String DESCRIPTOR = "com.pico.api.app.IApiLayer";

    private ApiLayerCompat() {
    }

    static void install(ClassLoader cl) {
        try {
            // ServiceManager is a hidden API, so it is resolved reflectively.
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            XposedBridge.hookAllMethods(serviceManager, "getService",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!"api_layer".equals(String.valueOf(param.args[0]))) {
                                return;
                            }
                            XposedBridge.log(TAG + ": getService(api_layer) -> "
                                    + param.getResult());
                            if (param.getResult() == null) {
                                param.setResult(new ApiLayerBinder());
                                XposedBridge.log(TAG + ": served api_layer");
                            }
                        }
                    });
            // The runtime resolves the service natively, so the manager itself is
            // neutralised: these entry points would otherwise dereference a null
            // IApiLayer and abort the process.
            Class<?> manager = Class.forName("com.pico.api.app.ApiLayerManager", true, cl);
            for (String name : new String[]{"addListeningFlag", "removeListeningFlag",
                    "registerAppSession", "unregisterAppSession"}) {
                try {
                    XposedBridge.hookAllMethods(manager, name, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    });
                    XposedBridge.log(TAG + ": neutralised " + name);
                } catch (Throwable inner) {
                    XposedBridge.log(TAG + ": skip " + name + ": " + inner);
                }
            }
            XposedBridge.log(TAG + ": hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed: " + t);
        }
    }

    private static final class ApiLayerBinder extends Binder {
        private IBinder session;

        @Override
        protected boolean onTransact(int code, Parcel in, Parcel out, int flags) {
            if (code == 0x5f4e5446) { // INTERFACE_TRANSACTION
                out.writeString(DESCRIPTOR);
                return true;
            }
            if (in != null) {
                in.enforceInterface(DESCRIPTOR);
            }
            switch (code) {
                case 9:   // registerAppSession(IBinder, int)
                    session = in.readStrongBinder();
                    in.readInt();
                    out.writeNoException();
                    return true;
                case 10:  // unregisterAppSession(IBinder, int)
                    in.readStrongBinder();
                    in.readInt();
                    out.writeNoException();
                    return true;
                case 21:  // registerSettingsObserverForNative(IBinder, int, String, int)
                case 22:  // unregisterSettingsObserverForNative(IBinder, int, String)
                case 25:  // registerBroadcastReceiverForNative(IBinder, String, String)
                case 26:  // unregisterBroadcastReceiverForNative(IBinder, String)
                    in.readStrongBinder();
                    in.readInt();
                    try {
                        in.readString();
                        in.readString();
                    } catch (Throwable ignored) {
                    }
                    out.writeNoException();
                    return true;
                case 1:   // getScreenState
                case 2:   // isInputMethodShowing
                    out.writeNoException();
                    out.writeInt(1);
                    return true;
                case 3:   // getSeethroughState
                case 5:   // getXrRuntimeDisplayState
                case 16:  // getPowerState
                case 17:  // getSettingsInt
                    out.writeNoException();
                    out.writeInt(0);
                    return true;
                case 4:   // getShowing3dApp
                case 6:   // getRunning2dAppData
                case 15:  // getTopAppOnDefaultDisplayForNative
                case 19:  // getSettingsString
                    out.writeNoException();
                    out.writeString(null);
                    return true;
                case 7:   // getTopAppOnDefaultDisplay
                    out.writeNoException();
                    out.writeInt(0);   // null ComponentName
                    return true;
                default:  // void methods
                    out.writeNoException();
                    return true;
            }
        }
    }
}
