package com.esrc.biosignal.client;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.esrc.biosignal.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeviceListActivity extends Activity {

    private static final String TAG = "DeviceListActivity";

    // 결과 전달 키 (호출측이 BluetoothDevice.EXTRA_DEVICE로 읽음)
    private static final long SCAN_PERIOD = 10_000L;
    private static final int REQ_BT_ENABLE = 1001;
    private static final int REQ_PERMS = 1002;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mScanner;
    private ScanCallback mScanCb;

    private Handler mHandler;
    private boolean mScanning = false;

    private TextView mEmptyList;
    private DeviceAdapter deviceAdapter;
    private final List<BluetoothDevice> deviceList = new ArrayList<>();
    private final Map<String, Integer> devRssiValues = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 커스텀 타이틀 순서 주의: 1) feature 요청 → 2) setContentView → 3) setFeatureInt
        requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);
        setContentView(R.layout.device_list);
        getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE, R.layout.title_bar);

        // 타이틀 위치 그대로 유지
        android.view.WindowManager.LayoutParams layoutParams = this.getWindow().getAttributes();
        layoutParams.gravity = Gravity.TOP;
        layoutParams.y = 200;

        mHandler = new Handler();

        // BLE 지원 체크
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (mScanner == null && Build.VERSION.SDK_INT >= 21) {
            // 일부 기기에서 null이 반환될 수 있음: 일단 BT를 켠 후 다시 시도
            if (!mBluetoothAdapter.isEnabled()) {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ENABLE);
            }
        }

        deviceAdapter = new DeviceAdapter(this, deviceList);
        ListView newDevicesListView = findViewById(R.id.new_devices);
        newDevicesListView.setAdapter(deviceAdapter);
        newDevicesListView.setOnItemClickListener((parent, view, position, id) -> {
            BluetoothDevice device = deviceList.get(position);
            stopScanIfNeeded();

            Intent result = new Intent();
            result.putExtra(BluetoothDevice.EXTRA_DEVICE, device.getAddress());
            setResult(Activity.RESULT_OK, result);
            finish();
        });

        mEmptyList = findViewById(R.id.empty);
        Button cancelButton = findViewById(R.id.btn_cancel);
        cancelButton.setOnClickListener(v -> {
            if (!mScanning) startScan();
            else {
                stopScanIfNeeded();
                finish();
            }
        });

        // 권한/BT/위치(필요 시) 확인 후 스캔 시작
        startFlow();
    }

    private void startFlow() {
        if (!ensureBluetoothEnabled()) return;
        if (!ensurePermissions()) return;
        if (!ensureLocationIfNeeded()) return; // Android 6~11에서 필요

        startScan();
    }

    private boolean ensureBluetoothEnabled() {
        if (!mBluetoothAdapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ENABLE);
            return false;
        }
        return true;
    }

    private boolean ensurePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            // Android 6~11은 위치 권한 필요
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                            != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMS);
            return false;
        }
        return true;
    }

    private boolean ensureLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true; // 12+는 neverForLocation로 충분
        // 6~11: 위치 서비스 ON 여부 확인
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            boolean enabled = lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if (!enabled) {
                Toast.makeText(this, R.string.please_enable_location, Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                return false;
            }
        } catch (Exception ignored) {}
        return true;
    }

    private void startScan() {
        if (mScanning) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                        REQ_PERMS);
                return;
            }
        }

        if (mScanner == null) {
            mScanner = mBluetoothAdapter.getBluetoothLeScanner();
            if (mScanner == null) {
                Toast.makeText(this, R.string.bt_not_enabled, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 목록 초기화
        deviceList.clear();
        devRssiValues.clear();
        deviceAdapter.notifyDataSetChanged();
        mEmptyList.setText(R.string.scanning);

        if (mScanCb == null) {
            mScanCb = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    if (device == null || device.getAddress() == null) return;

                    String addr = device.getAddress();
                    if (!devRssiValues.containsKey(addr)) {
                        deviceList.add(device);
                    }
                    devRssiValues.put(addr, result.getRssi());
                    deviceAdapter.notifyDataSetChanged();
                    mEmptyList.setText("");
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    for (ScanResult r : results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e(TAG, "Scan failed: " + errorCode);
                    Toast.makeText(DeviceListActivity.this, "Scan failed: " + errorCode, Toast.LENGTH_SHORT).show();
                }
            };
        }

        // 필터 없이 전체 스캔 (Nordic 포함 주변 BLE 전부)
        List<ScanFilter> filters = null;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        mScanner.startScan(filters, settings, mScanCb);
        mScanning = true;

        // 일정 시간 후 자동 종료
        mHandler.postDelayed(() -> {
            stopScanIfNeeded();
            if (deviceList.isEmpty()) {
                mEmptyList.setText(R.string.no_devices_found);
            }
        }, SCAN_PERIOD);
    }

    private void stopScanIfNeeded() {
        if (mScanning && mScanner != null && mScanCb != null) {
            try { mScanner.stopScan(mScanCb); } catch (Exception ignored) {}
        }
        mScanning = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BT_ENABLE) {
            if (resultCode == RESULT_OK) {
                startFlow();
            } else {
                Toast.makeText(this, R.string.bt_not_enabled, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean granted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    granted = false; break;
                }
            }
            if (granted) {
                startFlow();
            } else {
                Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopScanIfNeeded();
    }

    // ---------------- Adapter ----------------

    class DeviceAdapter extends BaseAdapter {
        Context context;
        List<BluetoothDevice> devices;
        LayoutInflater inflater;

        DeviceAdapter(Context context, List<BluetoothDevice> devices) {
            this.context = context;
            inflater = LayoutInflater.from(context);
            this.devices = devices;
        }

        @Override
        public int getCount() { return devices.size(); }

        @Override
        public Object getItem(int position) { return devices.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewGroup vg;
            if (convertView != null) {
                vg = (ViewGroup) convertView;
            } else {
                vg = (ViewGroup) inflater.inflate(R.layout.device_element, parent, false);
            }

            BluetoothDevice device = devices.get(position);
            final TextView tvadd = vg.findViewById(R.id.address);
            final TextView tvname = vg.findViewById(R.id.name);
            final TextView tvpaired = vg.findViewById(R.id.paired);
            final TextView tvrssi = vg.findViewById(R.id.rssi);

            tvadd.setText(device.getAddress());
            String name = device.getName();
            tvname.setText(name != null ? name : "Unknown Device");

            int bond = device.getBondState();
            tvpaired.setVisibility(bond == BluetoothDevice.BOND_BONDED ? View.VISIBLE : View.GONE);

            Integer rssi = devRssiValues.get(device.getAddress());
            if (rssi != null) {
                tvrssi.setVisibility(View.VISIBLE);
                tvrssi.setText("Rssi = " + rssi);
            } else {
                tvrssi.setVisibility(View.GONE);
            }
            return vg;
        }
    }
}
