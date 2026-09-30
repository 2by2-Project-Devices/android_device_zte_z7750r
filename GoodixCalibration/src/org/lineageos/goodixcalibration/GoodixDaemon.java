/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.goodixcalibration;

import android.os.HwBinder;
import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.NoSuchElementException;

/** Minimal client for vendor.goodix.hardware.biometrics.fingerprint@2.1::IGoodixFingerprintDaemon. */
class GoodixDaemon {
    private static final String TAG = "GoodixCalibration";

    private static final String DAEMON =
            "vendor.goodix.hardware.biometrics.fingerprint@2.1::IGoodixFingerprintDaemon";
    private static final String CALLBACK =
            "vendor.goodix.hardware.biometrics.fingerprint@2.1::IGoodixFingerprintDaemonCallback";
    private static final String IBASE = "android.hidl.base@1.0::IBase";

    private static final int TX_SET_NOTIFY = 1;
    private static final int TX_SEND_COMMAND = 2;

    private static final int TX_CB_ON_DAEMON_MESSAGE = 1;
    private static final int TX_INTERFACE_CHAIN = 256067662;
    private static final int TX_DEBUG = 256131655;
    private static final int TX_INTERFACE_DESCRIPTOR = 256136003;
    private static final int TX_GET_HASH_CHAIN = 256398152;
    private static final int TX_GET_DEBUG_INFO = 257049926;

    interface Listener {
        void onDaemonMessage(int msgId, int cmdId, byte[] data);
    }

    private final Callback mCallback;
    private IHwBinder mDaemon;

    GoodixDaemon(Listener listener) {
        mCallback = new Callback(listener);
    }

    boolean connect() {
        try {
            mDaemon = HwBinder.getService(DAEMON, "default");
            HwParcel request = new HwParcel();
            request.writeInterfaceToken(DAEMON);
            request.writeStrongBinder(mCallback);
            HwParcel reply = new HwParcel();
            try {
                mDaemon.transact(TX_SET_NOTIFY, request, reply, 0);
                reply.verifySuccess();
            } finally {
                reply.release();
            }
            return true;
        } catch (RemoteException | NoSuchElementException e) {
            Log.e(TAG, "Failed to connect to " + DAEMON, e);
            mDaemon = null;
            return false;
        }
    }

    /** Returns the synchronous result code of the command, or -1 on transport failure. */
    int sendCommand(int cmdId, byte[] param) {
        if (mDaemon == null) {
            return -1;
        }
        HwParcel request = new HwParcel();
        request.writeInterfaceToken(DAEMON);
        request.writeInt32(cmdId);
        request.writeInt8Vector(toList(param));
        HwParcel reply = new HwParcel();
        try {
            mDaemon.transact(TX_SEND_COMMAND, request, reply, 0);
            reply.verifySuccess();
            int result = reply.readInt32();
            reply.readInt8Vector();
            return result;
        } catch (RemoteException e) {
            Log.e(TAG, "sendCommand " + cmdId + " failed", e);
            return -1;
        } finally {
            reply.release();
        }
    }

    private static ArrayList<Byte> toList(byte[] data) {
        ArrayList<Byte> list = new ArrayList<>();
        if (data != null) {
            for (byte b : data) {
                list.add(b);
            }
        }
        return list;
    }

    private static final class Callback extends HwBinder implements IHwInterface {
        private final Listener mListener;

        Callback(Listener listener) {
            mListener = listener;
        }

        @Override
        public IHwBinder asBinder() {
            return this;
        }

        @Override
        public IHwInterface queryLocalInterface(String descriptor) {
            return CALLBACK.equals(descriptor) ? this : null;
        }

        @Override
        public boolean linkToDeath(DeathRecipient recipient, long cookie) {
            return true;
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient) {
            return true;
        }

        @Override
        public void onTransact(int code, HwParcel request, HwParcel reply, int flags) {
            switch (code) {
                case TX_CB_ON_DAEMON_MESSAGE: {
                    request.enforceInterface(CALLBACK);
                    request.readInt64();
                    int msgId = request.readInt32();
                    int cmdId = request.readInt32();
                    ArrayList<Byte> list = request.readInt8Vector();
                    byte[] data = new byte[list.size()];
                    for (int i = 0; i < data.length; i++) {
                        data[i] = list.get(i);
                    }
                    mListener.onDaemonMessage(msgId, cmdId, data);
                    break;
                }
                case TX_INTERFACE_CHAIN:
                    request.enforceInterface(IBASE);
                    reply.writeStatus(0);
                    reply.writeStringVector(new ArrayList<>(Arrays.asList(CALLBACK, IBASE)));
                    reply.send();
                    break;
                case TX_DEBUG:
                    request.enforceInterface(IBASE);
                    reply.writeStatus(0);
                    reply.send();
                    break;
                case TX_INTERFACE_DESCRIPTOR:
                    request.enforceInterface(IBASE);
                    reply.writeStatus(0);
                    reply.writeString(CALLBACK);
                    reply.send();
                    break;
                case TX_GET_HASH_CHAIN: {
                    request.enforceInterface(IBASE);
                    reply.writeStatus(0);
                    HwBlob blob = new HwBlob(16);
                    blob.putInt32(8, 0);
                    blob.putBool(12, false);
                    blob.putBlob(0, new HwBlob(0));
                    reply.writeBuffer(blob);
                    reply.send();
                    break;
                }
                case TX_GET_DEBUG_INFO: {
                    request.enforceInterface(IBASE);
                    reply.writeStatus(0);
                    HwBlob blob = new HwBlob(24);
                    blob.putInt32(0, android.os.Process.myPid());
                    blob.putInt64(8, 0);
                    blob.putInt32(16, 0);
                    reply.writeBuffer(blob);
                    reply.send();
                    break;
                }
                default:
                    break;
            }
        }
    }
}
