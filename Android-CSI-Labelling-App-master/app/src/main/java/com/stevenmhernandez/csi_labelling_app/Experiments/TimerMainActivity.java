package com.stevenmhernandez.csi_labelling_app.Experiments;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.stevenmhernandez.csi_labelling_app.Network.CsiDatagram;
import com.stevenmhernandez.csi_labelling_app.Network.CsiDeviceTracker;
import com.stevenmhernandez.csi_labelling_app.Network.UdpCsiReceiver;
import com.stevenmhernandez.csi_labelling_app.R;
import com.stevenmhernandez.csi_labelling_app.Services.FileDataCollectorService;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class TimerMainActivity extends AppCompatActivity {

    private static final int UDP_PORT = 5005;
    private static final int EXPECTED_DEVICE_COUNT = 4;
    private static final int ANDROID_16_API_LEVEL = 36;

    private static final long START_COUNTDOWN_MILLIS = 3_000L;
    private static final long ONE_SECOND_MILLIS = 1_000L;

    private static final int DEFAULT_RECORDING_DURATION_SECONDS = 30;
    private static final int MIN_RECORDING_DURATION_SECONDS = 1;
    private static final int MAX_RECORDING_DURATION_SECONDS = 3_600;

    /*
     * Son üç saniyede paket gönderen ESP32 aktif kabul edilir.
     */
    private static final long ACTIVE_DEVICE_TIMEOUT_NANOS =
            3_000_000_000L;

    private final Object recordingStateLock = new Object();

    private final FileDataCollectorService dataCollectorService =
            new FileDataCollectorService();

    /*
     * Uygulama açıkken görülen bütün cihazları takip eder.
     */
    private final CsiDeviceTracker liveDeviceTracker =
            new CsiDeviceTracker();

    /*
     * Her kayıt oturumunda yeniden oluşturulur.
     */
    private volatile CsiDeviceTracker recordingDeviceTracker =
            new CsiDeviceTracker();

    private final AtomicLong receivedDatagramCount =
            new AtomicLong(0);

    private final AtomicLong validPacketCount =
            new AtomicLong(0);

    private final AtomicLong malformedPacketCount =
            new AtomicLong(0);

    private final AtomicLong ignoredDatagramCount =
            new AtomicLong(0);

    private final AtomicLong recordingAttemptCount =
            new AtomicLong(0);

    private final Handler uiHandler =
            new Handler(Looper.getMainLooper());

    private UdpCsiReceiver udpReceiver;
    private ToneGenerator toneGenerator;

    private TextView frameRateTextView;
    private Button startStopButton;
    private Button locationButton;
    private Switch objectSwitch;

    private volatile boolean isRecording = false;
    private volatile boolean isCountingDown = false;

    private volatile String locationName = "";
    private volatile int objectPresent = 0;
    private volatile String recordingSessionId = "";

    private volatile int recordingDurationSeconds =
            DEFAULT_RECORDING_DURATION_SECONDS;

    private volatile long remainingRecordingSeconds = 0;
    private volatile long sessionStartDroppedWriteCount = 0;

    private volatile String lastPacketError = "";
    private volatile String recordingStatusText = "KAYIT DURDU";

    private CountDownTimer startCountdownTimer;
    private CountDownTimer automaticPauseTimer;

    private boolean localNetworkPermissionRequestStarted = false;

    private final ActivityResultLauncher<String>
            localNetworkPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (isGranted) {
                            localNetworkPermissionRequestStarted = false;
                            startUdpReceiver();
                        } else {
                            recordingStatusText =
                                    "YEREL AĞ İZNİ GEREKLİ";

                            if (frameRateTextView != null) {
                                frameRateTextView.setText(
                                        recordingStatusText
                                );
                            }

                            Toast.makeText(
                                    this,
                                    "Android 16'da ESP32 UDP paketlerini "
                                            + "alabilmek için Yakındaki cihazlar "
                                            + "izni gereklidir.",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    }
            );

    /*
     * Yalnızca ana arayüz thread'i kullanır.
     */
    private long previousUiValidPacketCount = 0;

    private final Runnable statisticsUiUpdater = new Runnable() {
        @Override
        public void run() {
            updateStatisticsUi();

            uiHandler.postDelayed(
                    this,
                    ONE_SECOND_MILLIS
            );
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_stand_walk_run);
        applySystemBarInsets();

        try {
            toneGenerator = new ToneGenerator(
                    AudioManager.STREAM_NOTIFICATION,
                    90
            );
        } catch (RuntimeException error) {
            toneGenerator = null;
        }

        frameRateTextView =
                findViewById(R.id.frameRateTextView);

        startStopButton =
                findViewById(R.id.startStopButton);

        locationButton =
                findViewById(R.id.locationButton);

        objectSwitch =
                findViewById(R.id.objectSwitch);

        startStopButton.setOnClickListener(
                view -> toggleRecording()
        );

        locationButton.setOnClickListener(
                view -> showLocationDialog()
        );

        objectSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) ->
                        objectPresent = isChecked ? 1 : 0
        );

        dataCollectorService.setup(this);

        dataCollectorService.handle(
                "type,esp_device_id,sequence,mac,rssi,channel,"
                        + "esp_timestamp,length,first_word_invalid,csi_data,"
                        + "object_present,location,source_ip,"
                        + "phone_receive_timestamp_ms,"
                        + "phone_receive_elapsed_ns,"
                        + "recording_session_id\n"
        );

        if (!dataCollectorService.isRunning()) {
            Toast.makeText(
                    this,
                    "CSI dosyası oluşturulamadı: "
                            + dataCollectorService.getLastErrorMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }

        udpReceiver = new UdpCsiReceiver(
                UDP_PORT,
                new UdpCsiReceiver.Listener() {
                    @Override
                    public void onPacket(
                            String message,
                            String sourceIp
                    ) {
                        handleUdpPacket(message, sourceIp);
                    }

                    @Override
                    public void onError(Exception error) {
                        String errorMessage = error.getMessage();

                        if (errorMessage == null
                                || errorMessage.trim().isEmpty()) {
                            errorMessage =
                                    error.getClass().getSimpleName();
                        }

                        String finalErrorMessage = errorMessage;

                        runOnUiThread(() ->
                                Toast.makeText(
                                        TimerMainActivity.this,
                                        "UDP hatası: "
                                                + finalErrorMessage,
                                        Toast.LENGTH_LONG
                                ).show()
                        );
                    }
                }
        );

        frameRateTextView.setText(recordingStatusText);
    }

    @Override
    protected void onResume() {
        super.onResume();

        startUdpReceiverWhenPermitted();

        uiHandler.removeCallbacks(statisticsUiUpdater);
        uiHandler.post(statisticsUiUpdater);
    }

    private void startUdpReceiverWhenPermitted() {
        /*
         * Android 14 ve daha eski sürümlerde INTERNET izni
         * yerel UDP alımı için yeterlidir.
         */
        if (Build.VERSION.SDK_INT < ANDROID_16_API_LEVEL) {
            startUdpReceiver();
            return;
        }

        boolean permissionGranted =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED;

        if (permissionGranted) {
            startUdpReceiver();
            return;
        }

        if (!localNetworkPermissionRequestStarted) {
            localNetworkPermissionRequestStarted = true;

            localNetworkPermissionLauncher.launch(
                    Manifest.permission.NEARBY_WIFI_DEVICES
            );
        }
    }

    private void startUdpReceiver() {
        if (udpReceiver != null && !udpReceiver.isRunning()) {
            udpReceiver.start();
        }
    }

    private void applySystemBarInsets() {
        View rootView = findViewById(R.id.background);

        if (rootView == null) {
            return;
        }

        final int originalLeft = rootView.getPaddingLeft();
        final int originalTop = rootView.getPaddingTop();
        final int originalRight = rootView.getPaddingRight();
        final int originalBottom = rootView.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(
                rootView,
                (view, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                                    | WindowInsetsCompat.Type.displayCutout()
                    );

                    view.setPadding(
                            originalLeft + insets.left,
                            originalTop + insets.top,
                            originalRight + insets.right,
                            originalBottom + insets.bottom
                    );

                    return windowInsets;
                }
        );

        ViewCompat.requestApplyInsets(rootView);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(statisticsUiUpdater);

        if (isCountingDown) {
            cancelStartCountdown();
        }

        if (isRecording) {
            pauseRecording(false);
        }

        if (udpReceiver != null) {
            udpReceiver.stop();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(statisticsUiUpdater);

        if (startCountdownTimer != null) {
            startCountdownTimer.cancel();
            startCountdownTimer = null;
        }

        if (automaticPauseTimer != null) {
            automaticPauseTimer.cancel();
            automaticPauseTimer = null;
        }

        if (udpReceiver != null) {
            udpReceiver.stop();
        }

        dataCollectorService.close();

        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }

        super.onDestroy();
    }

    private void handleUdpPacket(
            String message,
            String sourceIp
    ) {
        long phoneReceiveTimestampMillis =
                System.currentTimeMillis();

        long phoneReceiveElapsedNanos =
                SystemClock.elapsedRealtimeNanos();

        receivedDatagramCount.incrementAndGet();

        if (message == null) {
            malformedPacketCount.incrementAndGet();
            lastPacketError = "Null UDP mesajı";
            return;
        }

        String trimmedMessage = message.trim();

        if (!trimmedMessage.startsWith("CSI_DATA")) {
            ignoredDatagramCount.incrementAndGet();
            return;
        }

        final CsiDatagram datagram;

        try {
            datagram = CsiDatagram.parse(message);

        } catch (IllegalArgumentException error) {
            malformedPacketCount.incrementAndGet();

            String errorMessage = error.getMessage();

            lastPacketError = errorMessage == null
                    ? error.getClass().getSimpleName()
                    : shortenText(errorMessage, 100);

            return;
        }

        validPacketCount.incrementAndGet();

        liveDeviceTracker.record(
                datagram,
                sourceIp,
                phoneReceiveElapsedNanos
        );

        CsiDeviceTracker currentRecordingTracker;
        String currentSessionId;
        String currentLocation;
        int currentObjectPresent;

        synchronized (recordingStateLock) {
            if (!isRecording) {
                return;
            }

            currentRecordingTracker =
                    recordingDeviceTracker;

            currentSessionId =
                    recordingSessionId;

            currentLocation =
                    locationName;

            currentObjectPresent =
                    objectPresent;
        }

        currentRecordingTracker.record(
                datagram,
                sourceIp,
                phoneReceiveElapsedNanos
        );

        String csvLine = appendMetadata(
                datagram.getCsvLine(),
                currentObjectPresent,
                currentLocation,
                sourceIp,
                phoneReceiveTimestampMillis,
                phoneReceiveElapsedNanos,
                currentSessionId
        );

        dataCollectorService.handle(csvLine);
        recordingAttemptCount.incrementAndGet();
    }

    private void toggleRecording() {
        if (isCountingDown) {
            return;
        }

        if (isRecording) {
            pauseRecording(false);
            return;
        }

        if (locationName.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "Önce lokasyon adını girin",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        showRecordingDurationDialog();
    }

    private void showRecordingDurationDialog() {
        EditText input = new EditText(this);

        input.setHint("Süreyi saniye olarak girin");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(
                String.valueOf(recordingDurationSeconds)
        );
        input.selectAll();

        new AlertDialog.Builder(this)
                .setTitle("CSI kayıt süresi")
                .setMessage(
                        MIN_RECORDING_DURATION_SECONDS
                                + "–"
                                + MAX_RECORDING_DURATION_SECONDS
                                + " saniye arasında bir süre girin."
                )
                .setView(input)
                .setPositiveButton(
                        "Devam",
                        (dialog, which) -> {
                            String enteredValue =
                                    input.getText()
                                            .toString()
                                            .trim();

                            int enteredSeconds;

                            try {
                                enteredSeconds =
                                        Integer.parseInt(
                                                enteredValue
                                        );

                            } catch (NumberFormatException error) {
                                Toast.makeText(
                                        this,
                                        "Geçerli bir tam sayı girin",
                                        Toast.LENGTH_SHORT
                                ).show();

                                return;
                            }

                            if (enteredSeconds
                                    < MIN_RECORDING_DURATION_SECONDS
                                    || enteredSeconds
                                    > MAX_RECORDING_DURATION_SECONDS) {

                                Toast.makeText(
                                        this,
                                        "Süre "
                                                + MIN_RECORDING_DURATION_SECONDS
                                                + "–"
                                                + MAX_RECORDING_DURATION_SECONDS
                                                + " saniye arasında olmalı",
                                        Toast.LENGTH_SHORT
                                ).show();

                                return;
                            }

                            recordingDurationSeconds =
                                    enteredSeconds;

                            checkDevicesAndStartCountdown();
                        }
                )
                .setNegativeButton("İptal", null)
                .show();
    }

    private void checkDevicesAndStartCountdown() {
        long nowElapsedNanos =
                SystemClock.elapsedRealtimeNanos();

        int activeDeviceCount =
                liveDeviceTracker.getActiveDeviceCount(
                        nowElapsedNanos,
                        ACTIVE_DEVICE_TIMEOUT_NANOS
                );

        List<String> activeDeviceIds =
                liveDeviceTracker.getActiveDeviceIds(
                        nowElapsedNanos,
                        ACTIVE_DEVICE_TIMEOUT_NANOS
                );

        if (activeDeviceCount != EXPECTED_DEVICE_COUNT) {
            showDeviceCountWarning(
                    activeDeviceCount,
                    activeDeviceIds
            );

            return;
        }

        startCountdown();
    }

    private void showDeviceCountWarning(
            int activeDeviceCount,
            List<String> activeDeviceIds
    ) {
        String message =
                "Beklenen aktif ESP32 sayısı: "
                        + EXPECTED_DEVICE_COUNT
                        + "\nAlgılanan aktif ESP32 sayısı: "
                        + activeDeviceCount
                        + "\nCihaz kimlikleri: "
                        + formatDeviceIds(activeDeviceIds)
                        + "\n\nYine de kayıt başlatılsın mı?";

        new AlertDialog.Builder(this)
                .setTitle("ESP32 sayısı uyuşmuyor")
                .setMessage(message)
                .setPositiveButton(
                        "Yine de başlat",
                        (dialog, which) -> startCountdown()
                )
                .setNegativeButton("İptal", null)
                .show();
    }

    private void startCountdown() {
        isCountingDown = true;

        locationButton.setEnabled(false);
        objectSwitch.setEnabled(false);
        startStopButton.setEnabled(false);

        updateCountdownUi(3);

        startCountdownTimer = new CountDownTimer(
                START_COUNTDOWN_MILLIS,
                ONE_SECOND_MILLIS
        ) {
            @Override
            public void onTick(long millisUntilFinished) {
                long secondsRemaining = Math.max(
                        1L,
                        (
                                millisUntilFinished
                                        + ONE_SECOND_MILLIS
                                        - 1L
                        ) / ONE_SECOND_MILLIS
                );

                updateCountdownUi(secondsRemaining);
            }

            @Override
            public void onFinish() {
                startCountdownTimer = null;
                isCountingDown = false;

                startStopButton.setEnabled(true);

                playRecordingStartedSound();
                startRecording();
            }
        }.start();
    }

    private void cancelStartCountdown() {
        if (startCountdownTimer != null) {
            startCountdownTimer.cancel();
            startCountdownTimer = null;
        }

        isCountingDown = false;

        startStopButton.setEnabled(true);
        startStopButton.setText("START");

        locationButton.setEnabled(true);
        objectSwitch.setEnabled(true);

        recordingStatusText = "GERİ SAYIM İPTAL EDİLDİ";
        frameRateTextView.setText(recordingStatusText);
    }

    private void updateCountdownUi(long secondsRemaining) {
        String seconds = String.valueOf(secondsRemaining);

        startStopButton.setText(seconds);

        frameRateTextView.setText(
                "KAYIT "
                        + seconds
                        + " SANİYE SONRA BAŞLAYACAK"
        );
    }

    private void startRecording() {
        CsiDeviceTracker newRecordingTracker =
                new CsiDeviceTracker();

        String newSessionId =
                UUID.randomUUID().toString();

        synchronized (recordingStateLock) {
            recordingDeviceTracker =
                    newRecordingTracker;

            recordingSessionId =
                    newSessionId;

            recordingAttemptCount.set(0);

            sessionStartDroppedWriteCount =
                    dataCollectorService.getDroppedWriteCount();

            remainingRecordingSeconds =
                    recordingDurationSeconds;

            isRecording = true;
        }

        recordingStatusText = "KAYIT DEVAM EDİYOR";

        startStopButton.setText("PAUSE");
        locationButton.setEnabled(false);
        objectSwitch.setEnabled(false);

        Toast.makeText(
                this,
                "CSI kaydı başladı: "
                        + locationName
                        + " | Eşya: "
                        + objectPresent
                        + " | Süre: "
                        + recordingDurationSeconds
                        + " sn"
                        + " | Oturum: "
                        + newSessionId.substring(0, 8),
                Toast.LENGTH_SHORT
        ).show();

        long recordingDurationMillis =
                recordingDurationSeconds
                        * ONE_SECOND_MILLIS;

        automaticPauseTimer = new CountDownTimer(
                recordingDurationMillis,
                ONE_SECOND_MILLIS
        ) {
            @Override
            public void onTick(long millisUntilFinished) {
                remainingRecordingSeconds = Math.max(
                        1L,
                        (
                                millisUntilFinished
                                        + ONE_SECOND_MILLIS
                                        - 1L
                        ) / ONE_SECOND_MILLIS
                );
            }

            @Override
            public void onFinish() {
                automaticPauseTimer = null;
                remainingRecordingSeconds = 0;

                if (isRecording) {
                    pauseRecording(true);
                }
            }
        }.start();
    }

    private void pauseRecording(boolean automatic) {
        synchronized (recordingStateLock) {
            if (!isRecording) {
                return;
            }

            isRecording = false;
        }

        if (automaticPauseTimer != null) {
            automaticPauseTimer.cancel();
            automaticPauseTimer = null;
        }

        remainingRecordingSeconds = 0;

        startStopButton.setText("START");
        locationButton.setEnabled(true);
        objectSwitch.setEnabled(true);

        recordingStatusText = automatic
                ? recordingDurationSeconds
                  + " SANİYELİK KAYIT TAMAMLANDI"
                : "KAYIT DURDU";

        frameRateTextView.setText(recordingStatusText);

        if (automatic) {
            playRecordingFinishedSound();
        }

        Toast.makeText(
                this,
                automatic
                        ? recordingDurationSeconds
                          + " saniyelik CSI kaydı tamamlandı"
                        : "CSI kaydı duraklatıldı",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void updateStatisticsUi() {
        if (isCountingDown) {
            return;
        }

        long nowElapsedNanos =
                SystemClock.elapsedRealtimeNanos();

        int activeDeviceCount =
                liveDeviceTracker.getActiveDeviceCount(
                        nowElapsedNanos,
                        ACTIVE_DEVICE_TIMEOUT_NANOS
                );

        List<String> activeDeviceIds =
                liveDeviceTracker.getActiveDeviceIds(
                        nowElapsedNanos,
                        ACTIVE_DEVICE_TIMEOUT_NANOS
                );

        long currentValidPacketCount =
                validPacketCount.get();

        long packetsPerSecond = Math.max(
                0,
                currentValidPacketCount
                        - previousUiValidPacketCount
        );

        previousUiValidPacketCount =
                currentValidPacketCount;

        StringBuilder status = new StringBuilder();

        status.append(recordingStatusText);

        status.append("\nAktif ESP32: ")
                .append(activeDeviceCount)
                .append("/")
                .append(EXPECTED_DEVICE_COUNT)
                .append(" | CSI/s: ")
                .append(packetsPerSecond);

        status.append("\nID: ")
                .append(formatDeviceIds(activeDeviceIds));

        if (isRecording) {
            CsiDeviceTracker sessionTracker =
                    recordingDeviceTracker;

            long sessionDroppedWrites = Math.max(
                    0,
                    dataCollectorService.getDroppedWriteCount()
                            - sessionStartDroppedWriteCount
            );

            status.append("\nKalan süre: ")
                    .append(remainingRecordingSeconds)
                    .append(" sn");

            status.append(" | Paket: ")
                    .append(recordingAttemptCount.get());

            status.append("\nSequence boşluğu: ")
                    .append(
                            sessionTracker
                                    .getTotalSequenceGaps()
                    );

            status.append(" | Tekrar: ")
                    .append(
                            sessionTracker
                                    .getTotalDuplicatePackets()
                    );

            status.append(" | Sırasız: ")
                    .append(
                            sessionTracker
                                    .getTotalOutOfOrderPackets()
                    );

            status.append("\nYazma kuyruğu: ")
                    .append(
                            dataCollectorService
                                    .getPendingWriteCount()
                    )
                    .append(" | Yazma düşen: ")
                    .append(sessionDroppedWrites);
        }

        status.append("\nUDP: ")
                .append(receivedDatagramCount.get())
                .append(" | Geçerli: ")
                .append(validPacketCount.get())
                .append(" | Bozuk: ")
                .append(malformedPacketCount.get())
                .append(" | Yok sayılan: ")
                .append(ignoredDatagramCount.get());

        long sourceIpChanges =
                liveDeviceTracker.getTotalSourceIpChanges();

        if (sourceIpChanges > 0) {
            status.append("\nIP değişimi/ID çakışması: ")
                    .append(sourceIpChanges);
        }

        String writeError =
                dataCollectorService.getLastErrorMessage();

        if (writeError != null) {
            status.append("\nDOSYA HATASI: ")
                    .append(shortenText(writeError, 100));
        }

        if (!lastPacketError.isEmpty()) {
            status.append("\nSon bozuk paket: ")
                    .append(lastPacketError);
        }

        frameRateTextView.setText(status.toString());
    }

    private void showLocationDialog() {
        EditText input = new EditText(this);

        input.setHint("Örn: Lab2");
        input.setSingleLine(true);
        input.setText(locationName);

        new AlertDialog.Builder(this)
                .setTitle("Veri toplama lokasyonu")
                .setView(input)
                .setPositiveButton(
                        "Kaydet",
                        (dialog, which) -> {
                            String enteredLocation =
                                    input.getText()
                                            .toString()
                                            .trim();

                            if (enteredLocation.isEmpty()) {
                                Toast.makeText(
                                        this,
                                        "Lokasyon boş bırakılamaz",
                                        Toast.LENGTH_SHORT
                                ).show();

                                return;
                            }

                            locationName = enteredLocation;

                            locationButton.setText(
                                    "Lokasyon: "
                                            + locationName
                            );
                        }
                )
                .setNegativeButton("İptal", null)
                .show();
    }

    private String appendMetadata(
            String originalLine,
            int currentObjectPresent,
            String currentLocation,
            String sourceIp,
            long phoneReceiveTimestampMillis,
            long phoneReceiveElapsedNanos,
            String currentSessionId
    ) {
        return originalLine
                + "," + currentObjectPresent
                + "," + quoteCsv(currentLocation)
                + "," + quoteCsv(sourceIp)
                + "," + phoneReceiveTimestampMillis
                + "," + phoneReceiveElapsedNanos
                + "," + quoteCsv(currentSessionId)
                + "\n";
    }

    private String quoteCsv(String value) {
        String safeValue =
                value == null ? "" : value;

        return "\""
                + safeValue.replace("\"", "\"\"")
                + "\"";
    }

    private String formatDeviceIds(
            List<String> deviceIds
    ) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return "-";
        }

        StringBuilder result = new StringBuilder();

        for (int index = 0;
             index < deviceIds.size();
             index++) {

            if (index > 0) {
                result.append(", ");
            }

            result.append(deviceIds.get(index));
        }

        return result.toString();
    }

    private String shortenText(
            String value,
            int maximumLength
    ) {
        if (value == null) {
            return "";
        }

        if (value.length() <= maximumLength) {
            return value;
        }

        return value.substring(0, maximumLength)
                + "...";
    }

    private void playRecordingStartedSound() {
        ToneGenerator generator = toneGenerator;

        if (generator != null) {
            generator.startTone(
                    ToneGenerator.TONE_PROP_ACK,
                    400
            );
        }
    }

    private void playRecordingFinishedSound() {
        ToneGenerator generator = toneGenerator;

        if (generator != null) {
            generator.startTone(
                    ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
                    1_000
            );
        }
    }

    public void shareOverBluetooth(View view) {
        Intent intent = new Intent();

        intent.setAction(Intent.ACTION_SEND);
        intent.setType("text/csv");

        try {
            intent.putExtra(
                    Intent.EXTRA_STREAM,
                    dataCollectorService.getFileUri()
            );

            startActivity(intent);

        } catch (IOException error) {
            Toast.makeText(
                    this,
                    "Dosya paylaşılamadı: "
                            + error.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}