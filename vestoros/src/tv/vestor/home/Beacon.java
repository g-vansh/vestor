package tv.vestor.home;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;

import java.util.Arrays;

/**
 * The AirPlay beacon over Bluetooth LE (see {@link AirPlay}), through Android's advertiser. It
 * broadcasts one message, never connectable, with no name, at Android's balanced pace (about four
 * times a second), the way an Apple TV does. Whether a Fire TV's Bluetooth can advertise at all is
 * up to its chip, so {@link #status} says plainly when it cannot.
 */
final class Beacon implements AirPlay.Radio {
    private final Context context;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback live;
    private byte[] showing;
    private volatile String status = "off";

    Beacon(Context c) {
        context = c.getApplicationContext();
    }

    @Override
    public synchronized void show(byte[] data) {
        if (live != null && Arrays.equals(showing, data) && ("on".equals(status) || "starting".equals(status))) return;
        hide();
        BluetoothManager m = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = m == null ? null : m.getAdapter();
        if (a == null) {
            status = "unsupported";
            return;
        }
        if (!a.isEnabled()) {
            status = "bluetooth";
            return;
        }
        BluetoothLeAdvertiser adv = a.getBluetoothLeAdvertiser();
        if (adv == null) {
            status = "unsupported";
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(false).setTimeout(0).build();
        AdvertiseData message = new AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                .addManufacturerData(AirPlay.APPLE, data).build();
        final AdvertiseCallback cb = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings in) {
                synchronized (Beacon.this) {
                    if (live == this) status = "on";
                }
            }

            @Override
            public void onStartFailure(int code) {
                synchronized (Beacon.this) {
                    if (live != this) return;
                    live = null;
                    showing = null;
                    status = code == ADVERTISE_FAILED_FEATURE_UNSUPPORTED ? "unsupported" : "failed";
                }
            }
        };
        try {
            live = cb;
            showing = data.clone();
            advertiser = adv;
            status = "starting";
            adv.startAdvertising(settings, message, cb);
        } catch (SecurityException e) {
            live = null;
            showing = null;
            status = "refused";
        } catch (RuntimeException e) {
            live = null;
            showing = null;
            status = "failed";
        }
    }

    @Override
    public synchronized void hide() {
        if (live != null && advertiser != null) {
            try {
                advertiser.stopAdvertising(live);
            } catch (RuntimeException ignored) {
                // Bluetooth went off, and the advertising with it
            }
        }
        live = null;
        showing = null;
        if ("on".equals(status) || "starting".equals(status)) status = "off";
    }

    @Override
    public String status() {
        return status;
    }
}
