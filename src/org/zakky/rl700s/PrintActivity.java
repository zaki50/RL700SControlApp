
package org.zakky.rl700s;

import org.zakky.rl700s.comm.RL700SCommands;
import org.zakky.rl700s.comm.RL700SCommands.CommandMode;
import org.zakky.rl700s.comm.RL700SCommands.CompressionMode;
import org.zakky.rl700s.comm.RL700SCommands.EnhancedMode;
import org.zakky.rl700s.comm.RL700SCommands.Paper;
import org.zakky.rl700s.comm.RL700SStatus;
import org.zakky.rl700s.comm.RL700SStatus.ErrorInfo;

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
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * 印刷を行うためのアクティビティです。
 */
public class PrintActivity extends Activity {
    public static final String TAG = "RL700S";

    private static final String ACTION_USB_PERMISSION = PrintActivity.class.getPackage().getName()
            + ".USB_PERMISSION";

    private UsbManager mManager;

    private UsbDevice mTargetDevice = null;

    private Handler mHandler = new Handler();

    private TextView mStatusView;

    private TextView mTapeTypeView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mStatusView = (TextView) findViewById(R.id.printer_status);
        mTapeTypeView = (TextView) findViewById(R.id.tape_type);

        mManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        Object ci = getLastNonConfigurationInstance();
        if (ci instanceof UsbDevice) {
            mTargetDevice = (UsbDevice) ci;
        } else {
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
        }

        registerReceiver(mUsbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbReceiver);
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
    protected void onStart() {
        super.onResume();

        if (mTargetDevice != null) {
            if (requestPermission(mTargetDevice)) {
                final PrinterDevice printer = openPrinter();
                if (printer == null) {
                    Toast.makeText(this, R.string.msg_failed_to_open_printer, //
                            Toast.LENGTH_LONG).show();
                    return;
                }
                print(printer);
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
                ACTION_USB_PERMISSION), 0);
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
                    final PrinterDevice printer = openPrinter();
                    if (printer == null) {
                        Toast.makeText(PrintActivity.this, R.string.msg_failed_to_open_printer, //
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    print(printer);
                }
            }
        }
    };

    private static final int ENDPOINT_NUMBER_FOR_INBULK = 1;

    private static final int ENDPOINT_NUMBER_FOR_OUTBULK = 2;

    private final class PrinterDevice {
        private final UsbDeviceConnection mConnection;

        private final UsbInterface mInterface;

        private final UsbEndpoint mIn;

        private final UsbEndpoint mOut;

        public PrinterDevice(UsbDeviceConnection connection, UsbInterface interface1,
                UsbEndpoint in, UsbEndpoint out) {
            super();
            mConnection = connection;
            mInterface = interface1;
            mIn = in;
            mOut = out;
        }

        public UsbDeviceConnection getConnection() {
            return mConnection;
        }

        public UsbInterface getInterface() {
            return mInterface;
        }

        public UsbEndpoint in() {
            return mIn;
        }

        public UsbEndpoint out() {
            return mOut;
        }
    }

    /**
     * 印刷処理を開始します。 {@link #mTargetDevice} が示すデバイスに対して印刷を行うので、{@code null} でない
     * 値をセットしてから呼びだしてください。
     */
    private PrinterDevice openPrinter() {
        final UsbInterface iface = mTargetDevice.getInterface(0);
        final UsbEndpoint in = iface.getEndpoint(0);
        if (!checkEndpoint(in, ENDPOINT_NUMBER_FOR_INBULK, UsbConstants.USB_ENDPOINT_XFER_BULK,
                UsbConstants.USB_DIR_IN)) {
            return null;
        }
        final UsbEndpoint out = iface.getEndpoint(1);
        if (!checkEndpoint(out, ENDPOINT_NUMBER_FOR_OUTBULK, UsbConstants.USB_ENDPOINT_XFER_BULK,
                UsbConstants.USB_DIR_OUT)) {
            return null;
        }

        final UsbDeviceConnection conn = mManager.openDevice(mTargetDevice);
        if (!conn.claimInterface(iface, true)) {
            return null;
        }
        final PrinterDevice printer = new PrinterDevice(conn, iface, in, out);
        return printer;
    }

    private void print(PrinterDevice printer) {
        final UsbDeviceConnection conn = printer.getConnection();

        @SuppressWarnings("unused")
        final UsbInterface iface = printer.getInterface();

        //final Paper paperType = Paper.SZ;
        final Paper paperType = Paper.LAMINATE;

        final StatusReceiver receiver = new StatusReceiver(conn, printer.in());
        new Thread(receiver).start();

        final ByteBuffer outBuff = RL700SCommands.allocateOutBuffer();

        RL700SCommands.getInit(outBuff);
        send(conn, printer.out(), outBuff, 1000);

        RL700SCommands.getStatus(outBuff);
        send(conn, printer.out(), outBuff, 1000);

        RL700SCommands.getSwitchCommandMode(outBuff, CommandMode.RASTER);
        send(conn, printer.out(), outBuff, 1000);

        RL700SCommands.getSetPrintInformation(outBuff, paperType, null, null, true, false);
        send(conn, printer.out(), outBuff, 1000);

        RL700SCommands.getSetMergin(outBuff, 20);
        send(conn, printer.out(), outBuff, 1000);

        //        RL700SCommands.getSetMode(outBuff, EnumSet.noneOf(Mode.class));
        //        send(conn, printer.out(), outBuff, 1000);
        //
        RL700SCommands.getSetEnhancedMode(outBuff,
                EnumSet.of(EnhancedMode.HALF_CUT, EnhancedMode.CUT_ON_CHAIN_PRINT));
        send(conn, printer.out(), outBuff, 1000);

        final CompressionMode cmode = CompressionMode.TIFF;
        RL700SCommands.getSelectCompressionMode(outBuff, cmode);
        send(conn, printer.out(), outBuff, 1000);

        final Object[] rasterData = (Object[]) getIntent().getSerializableExtra("data");
        for (int i = 0; i < rasterData.length; i++) {
            final byte[] line = (byte[]) rasterData[i];
            RL700SCommands.getSendRasterLine(outBuff, line, cmode);
            send(conn, printer.out(), outBuff, 1000);
        }
        if (paperType != Paper.SZ) {
            // SZ 以外では、余計にデータを送らないとなぜか短く切られてしまう。
            for (int i = 0; i < 300; i++) {
                RL700SCommands.getSendZeroRasterLine(outBuff);
                send(conn, printer.out(), outBuff, 1000);
            }
        }

        RL700SCommands.getStartPrintWithEvacuation(outBuff);
        send(conn, printer.out(), outBuff, 1000);
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
        if (buffer.arrayOffset() == 0 && buffer.position() == 0) {
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

        return true;
    }

    private final class StatusReceiver implements Runnable {
        private final UsbDeviceConnection mConnection;

        private final UsbEndpoint mInEndpoint;

        private final ByteBuffer mInBuf = RL700SStatus.allocateInBuffer();

        private StatusReceiver(UsbDeviceConnection mConnection, UsbEndpoint mInEndpoint) {
            super();
            this.mConnection = mConnection;
            this.mInEndpoint = mInEndpoint;
        }

        @Override
        public void run() {
            int statusCount = 0;
            while (true) {
                do {
                    final int remaining = mInBuf.remaining();
                    if (!recv(mConnection, mInEndpoint, mInBuf, 5000)) {
                        return;
                    }

                    if (remaining == mInBuf.remaining()) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(1000L);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                } while (mInBuf.hasRemaining());
                mInBuf.flip();

                try {
                    final RL700SStatus status = RL700SStatus.parse(mInBuf);
                    statusCount++;
                    mInBuf.clear();

                    final int a = statusCount;
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {

                            switch (status.getStatusType()) {
                                case 0: // ステータスリクエストへの応答
                                    handleStatusUpdate(status.getMediaType(),
                                            status.getMediaWidth(), status.getMediaLength());
                                    break;
                                case 1: // 印刷終了
                                    handleFinish();
                                    break;
                                case 2: // エラー発生
                                    handleError(status.getErrorInfoSet());
                                    break;
                                case 5: // 通知
                                    handleNotification();
                                    break;
                                case 6: // フェーズ変更
                                    handlePhaseChange();
                                    break;
                                default:
                            }

                            Toast.makeText(PrintActivity.this, "" + a, Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (ParseException e) {
                    Log.e(TAG, "failed to parse status.", e);
                }
            }
        }

        private void handleStatusUpdate(int mediaType, int mediaWidth, int mediaLength) {
            mStatusView.setText("ステータス取得完了");
            Paper p = null;
            for (Paper candidate : Paper.values()) {
                if (candidate.rawValue() == mediaType) {
                    p = candidate;
                    break;
                }
            }
            mTapeTypeView.setText(p == null ? "不明なテープ(" + mediaType + ")" : p.name());
        }

        private void handleFinish() {
            mStatusView.setText("印刷完了");

        }

        private void handleError(EnumSet<ErrorInfo> errorInfo) {
            mStatusView.setText("エラー" + errorInfo.toString());

        }

        private void handleNotification() {
            mStatusView.setText("通知");

        }

        private void handlePhaseChange() {
            mStatusView.setText("フェーズ変更");

        }

    }
}
