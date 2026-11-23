package com.pmh.surfaceprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.FileDescriptor;

public class SurfaceProbeActivity extends Activity
        implements TextureView.SurfaceTextureListener {

    private static final String TAG = "PmhSurfaceProbe";
    private static final String CONNECT_ACTION = "com.pico.picoopenxr.ipc.CONNECT";
    private static final String DESCRIPTOR = "com.pico.picoopenxr.ipc.IPicoOpenXR";

    private static final int TX_GET_TEX_ID_OES = 1;
    private static final int TX_CREATE_ANDROID_SURFACE = 2;
    private static final int TX_CONNECT = 3;

    private TextureView view;
    private IBinder runtime;
    private ParcelFileDescriptor pipeRead;
    private ParcelFileDescriptor pipeWrite;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            runtime = service;
            Log.i(TAG, "connected to " + name + " binder=" + service);
            // The runtime service only accepts connect() once its IPC server is
            // initialised, which happens when a title drives it. Probing the
            // texture id first shows whether the service is usable at all.
            requestSurface();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            runtime = null;
            Log.w(TAG, "runtime disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new TextureView(this);
        view.setSurfaceTextureListener(this);
        setContentView(view);

        Intent intent = new Intent(CONNECT_ACTION);
        intent.setPackage("com.pico.xr.openxr_runtime");
        boolean bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "bindService -> " + bound);
    }

    @Override
    protected void onDestroy() {
        try {
            unbindService(connection);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /** connect(fd) establishes the client channel the runtime expects. */
    private void handshake() {
        if (runtime == null) {
            return;
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            pipeRead = pipe[0];
            pipeWrite = pipe[1];
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(DESCRIPTOR);
            // The service reads a presence int before the descriptor itself.
            data.writeInt(1);
            pipeRead.writeToParcel(data, 0);
            runtime.transact(TX_CONNECT, data, reply, 0);
            reply.readException();
            data.recycle();
            reply.recycle();
            Log.i(TAG, "connect(fd) ok fd=" + pipeRead.getFd());
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
        }
        requestSurface();
    }

    private void requestSurface() {
        if (runtime == null || view == null || view.getWidth() == 0) {
            return;
        }
        int texId = -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            runtime.transact(TX_GET_TEX_ID_OES, data, reply, 0);
            reply.readException();
            texId = reply.readInt();
            data.recycle();
            reply.recycle();
            Log.i(TAG, "getTexIdOes -> " + texId);

            data = Parcel.obtain();
            reply = Parcel.obtain();
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(view.getWidth());
            data.writeInt(view.getHeight());
            data.writeInt(texId);
            data.writeInt(0);
            boolean ok = runtime.transact(TX_CREATE_ANDROID_SURFACE, data, reply, 0);
            reply.readException();
            Surface surface = reply.readInt() != 0
                    ? Surface.CREATOR.createFromParcel(reply) : null;
            Log.i(TAG, "createAndroidSurface ok=" + ok + " surface=" + surface
                    + " size=" + view.getWidth() + "x" + view.getHeight() + " tex=" + texId);
            if (surface != null) {
                surface.release();
            }
        } catch (Throwable t) {
            Log.e(TAG, "surface request failed (texId=" + texId + ")", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        requestSurface();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }
}
