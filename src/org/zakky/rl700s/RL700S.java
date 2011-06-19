
package org.zakky.rl700s;

import android.hardware.usb.UsbDevice;

/**
 * RL-700S 用のユーティリティクラス。
 */
public final class RL700S {
    public static final String NAME = "RL-700S";
    
    public static final int VENDOR_ID = 0x04f9;
    public static final int PRODUCT_ID = 0x2021;

    /**
     * 渡された {@link UsbDevice} が、 RL-700S であるかどうかを判定します。
     * 
     * @param device チェック対象のデバイス。 {@code null} を渡すことは推奨しませんが、渡された場合は RL-700S
     *            ではないと判定します。
     * @return RL-700Sである場合は {@code true}、そうでない場合は {@code false} を返します。
     */
    public static boolean isRl700s(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (device.getVendorId() != VENDOR_ID) {
            return false;
        }
        if (device.getProductId() != PRODUCT_ID) {
            return false;
        }
        return true;
    }
}
