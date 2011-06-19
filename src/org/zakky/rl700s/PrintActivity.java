
package org.zakky.rl700s;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * 印刷を行うためのアクティビティです。
 */
public class PrintActivity extends Activity {
    public static final String TAG = "RL700S";

    private static final String ACTION_USB_PERMISSION = PrintActivity.class.getPackage().getName()
            + ".USB_PERMISSION";

    private UsbManager mManager;

    private UsbDevice mTargetDevice = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        final HashMap<String, UsbDevice> devices = mManager.getDeviceList();
        showDeviceCountAsToast(devices.size());
        final UsbDevice target = findTargetDevice(devices.values());
        if (target == null) {
            final String message = getString(R.string.target_not_found, RL700S.NAME);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }
        mTargetDevice = target;

        registerReceiver(mUsbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
    }

    private void showDeviceCountAsToast(int count) {
        final String message;
        if (count <= 0) {
            message = getString(R.string.msg_no_device_found);
        } else {
            message = getString(R.string.msg_device_found, count);
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        if (mTargetDevice != null) {
            return mTargetDevice;
        }
        return super.onRetainNonConfigurationInstance();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mTargetDevice != null) {
            if (requestPermission(mTargetDevice)) {
                startPrint();
            }
        }
    }

    private static UsbDevice findTargetDevice(Iterable<UsbDevice> devices) {
        for (UsbDevice device : devices) {
            if (!RL700S.isRl700s(device)) {
                continue;
            }
            // found
            return device;
        }
        return null;
    }

    private boolean requestPermission(UsbDevice device) {
        if (mManager.hasPermission(mTargetDevice)) {
            return true;
        }

        final PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(
                ACTION_USB_PERMISSION),
                0);
        mManager.requestPermission(device, pi);
        return false;
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    final UsbDevice device = (UsbDevice) intent
                            .getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(TAG, "USB Permission denied");
                        PrintActivity.this.setResult(Activity.RESULT_CANCELED);
                        PrintActivity.this.finish();
                        return;
                    }

                    if (device == null) {
                        Log.i(TAG, "USB device is null in BroadcastReceiver");
                        PrintActivity.this.setResult(Activity.RESULT_CANCELED);
                        PrintActivity.this.finish();
                        return;
                    }
                    mTargetDevice = device;
                    startPrint();
                }
            }
        }
    };

    private static final int ENDPOINT_NUMBER_FOR_INBULK = 1;
    private static final int ENDPOINT_NUMBER_FOR_OUTBULK = 2;

    /**
     * 印刷処理を開始します。 {@link #mTargetDevice} が示すデバイスに対して印刷を行うので、{@code null} でない
     * 値をセットしてから呼びだしてください。
     */
    private void startPrint() {
        final UsbInterface iface = mTargetDevice.getInterface(0);
        final UsbEndpoint in = iface.getEndpoint(0);
        if (!checkEndpoint(in, ENDPOINT_NUMBER_FOR_INBULK, UsbConstants.USB_ENDPOINT_XFER_BULK,
                UsbConstants.USB_DIR_IN)) {
            // TODO エラー処理
            return;
        }
        final UsbEndpoint out = iface.getEndpoint(1);
        if (!checkEndpoint(out, ENDPOINT_NUMBER_FOR_OUTBULK, UsbConstants.USB_ENDPOINT_XFER_BULK,
                UsbConstants.USB_DIR_OUT)) {
            // TODO エラー処理
            return;
        }

        final UsbDeviceConnection conn = mManager.openDevice(mTargetDevice);
        if (!conn.claimInterface(iface, false)) {
            // TODO エラー処理
            return;
        }

    }

    private static boolean checkEndpoint(UsbEndpoint endpoint, int number, int type, int direction) {
        if (endpoint == null) {
            return false;
        }
        if (endpoint.getEndpointNumber() != number) {
            return false;
        }
        if (endpoint.getType() != type) {
            return false;
        }
        if (endpoint.getDirection() != direction) {
            return false;
        }
        return true;
    }

    private static boolean send(UsbDeviceConnection conn, UsbEndpoint endpoint, ByteBuffer buffer,
            int timeoutMillis) {
        if (endpoint.getDirection() != UsbConstants.USB_DIR_OUT) {
            throw new RuntimeException("endpoint " + endpoint.getEndpointNumber()
                     + " is not for send.");
        }
        if (endpoint.getMaxPacketSize() < buffer.remaining()) {
            throw new RuntimeException("buffer is too big.");
        }
        final byte[] rawBuffer;
        if (buffer.position() == 0 && buffer.arrayOffset() == 0) {
            rawBuffer = buffer.array();
        } else {
            rawBuffer = new byte[buffer.remaining()];
            System.arraycopy(buffer.array(), buffer.arrayOffset() + buffer.position(), rawBuffer,
                    0, buffer.remaining());
        }
        final int sent = conn.bulkTransfer(endpoint, rawBuffer, buffer.remaining(), timeoutMillis);
        if (sent != buffer.remaining()) {
            return false;
        }
        buffer.position(buffer.position() + buffer.remaining());
        return true;
    }

    private static boolean recv(UsbDeviceConnection conn, UsbEndpoint endpoint, ByteBuffer buffer,
            int timeoutMillis) {
        if (endpoint.getDirection() != UsbConstants.USB_DIR_IN) {
            throw new RuntimeException("endpoint " + endpoint.getEndpointNumber()
                     + " is not for send.");
        }
        if (buffer.remaining() < endpoint.getMaxPacketSize()) {
            throw new RuntimeException("buffer is too small.");
        }
        final byte[] rawBuffer;
        if (buffer.arrayOffset() == 0) {
            rawBuffer = buffer.array();
        } else {
            rawBuffer = new byte[buffer.remaining()];
        }
        final int recv = conn.bulkTransfer(endpoint, rawBuffer, endpoint.getMaxPacketSize(),
                timeoutMillis);
        if (recv < 0) {
            return false;
        }
        if (buffer.array() != rawBuffer) {
            System.arraycopy(rawBuffer, 0, buffer.array(),
                    buffer.arrayOffset() + buffer.position(), recv);
        }
        buffer.position(buffer.position() + recv);
        buffer.flip();

        return true;
    }

}
