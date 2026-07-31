package com.stevenmhernandez.csi_labelling_app.Experiments;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.os.CountDownTimer;

import androidx.appcompat.app.AppCompatActivity;

import com.stevenmhernandez.csi_labelling_app.Network.UdpCsiReceiver;
import com.stevenmhernandez.csi_labelling_app.R;
import com.stevenmhernandez.csi_labelling_app.Services.BaseDataCollectorService;
import com.stevenmhernandez.csi_labelling_app.Services.FileDataCollectorService;
import com.stevenmhernandez.esp32csiserial.CSIDataInterface;
import com.stevenmhernandez.esp32csiserial.ESP32CSISerial;

import java.io.IOException;

public class TimerMainActivity extends AppCompatActivity implements CSIDataInterface {

    private ESP32CSISerial csiSerial = new ESP32CSISerial();

    private static final int UDP_PORT = 5005;
    private static final long START_COUNTDOWN_MILLIS = 3_000L;
    private static final long RECORDING_DURATION_MILLIS = 30_000L;
    private static final long ONE_SECOND_MILLIS = 1_000L;

    private boolean isCountingDown = false;
    private CountDownTimer startCountdownTimer;
    private CountDownTimer automaticPauseTimer;

    private UdpCsiReceiver udpReceiver;
    private long udpPacketCounter = 0;

    private TextView frameRateTextView;
    private Button startStopButton;
    private Button locationButton;
    private Switch objectSwitch;

    /*
     * Deney metadata'sı.
     * START sırasında bu değerler kilitlenir.
     */
    private volatile boolean isRecording = false;
    private volatile String locationName = "";
    private volatile int objectPresent = 0;

    /*
     * Uygulama oturumu boyunca aynı CSV dosyası kullanılır.
     */
    private final BaseDataCollectorService dataCollectorService =
            new FileDataCollectorService();

    private long csiCounter = 0;
    private long csiPerSecondCounter = 0;
    private long counterStart = System.currentTimeMillis();
    private long csiPerSecondCounterFinal = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stand_walk_run);

        frameRateTextView = findViewById(R.id.frameRateTextView);

        startStopButton = findViewById(R.id.startStopButton);
        locationButton = findViewById(R.id.locationButton);
        objectSwitch = findViewById(R.id.objectSwitch);

        startStopButton.setOnClickListener(v -> toggleRecording());
        locationButton.setOnClickListener(v -> showLocationDialog());

        objectSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            objectPresent = isChecked ? 1 : 0;
        });

        /*
         * CSV sadece burada oluşturulur.
         * START/STOP yeni dosya oluşturmaz.
         */
        dataCollectorService.setup(this);

        /*
         * Artık bütün CSI satırları aynı kolon yapısına sahip.
         */
        dataCollectorService.handle(
                "type,esp_device_id,sequence,mac,rssi,channel,esp_timestamp,length,"
                        + "first_word_invalid,csi_data,object_present,location\n"
        );

        udpReceiver = new UdpCsiReceiver(
                UDP_PORT,
                new UdpCsiReceiver.Listener() {
                    @Override
                    public void onPacket(String message, String sourceIp) {

                        udpPacketCounter++;

                        /*
                         * UDP paketleri STOP durumunda da telefona gelebilir.
                         * Ancak CSV'ye yalnızca isRecording=true iken yazılır.
                         *
                         * STOP sırasında ekrandaki kayıt sayacı da artık
                         * UDP paketleri nedeniyle değişmez.
                         */
                        if (message.startsWith("CSI_DATA")) {
                            addCsi(message);
                        }
                    }

                    @Override
                    public void onError(Exception error) {
                        runOnUiThread(() ->
                                Toast.makeText(
                                        TimerMainActivity.this,
                                        "UDP hatası: " + error.getMessage(),
                                        Toast.LENGTH_LONG
                                ).show()
                        );
                    }
                }
        );

        frameRateTextView.setText("KAYIT DURDU");
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (udpReceiver != null) {
            udpReceiver.start();
        }
    }

    @Override
    protected void onPause() {
        if (udpReceiver != null) {
            udpReceiver.stop();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (startCountdownTimer != null) {
            startCountdownTimer.cancel();
        }

        if (automaticPauseTimer != null) {
            automaticPauseTimer.cancel();
        }

        super.onDestroy();
    }

    public void shareOverBluetooth(View view) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND);

        try {
            intent.setType("text/plain");
            intent.putExtra(
                    Intent.EXTRA_STREAM,
                    dataCollectorService.getFileUri()
            );
            startActivity(intent);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void addCsi(String csiString) {

        /*
         * EN ÖNEMLİ KONTROL:
         * STOP durumunda hiçbir CSI satırı CSV'ye yazılmaz.
         */
        if (!isRecording) {
            return;
        }

        /*
         * Her CSI satırına o anda geçerli olan:
         * object_present ve location bilgileri eklenir.
         */
        String csvLine = appendMetadata(csiString);

        /*
         * Aynı CSI paketi yalnızca bir kez yazılır.
         */
        dataCollectorService.handle(csvLine);

        if (counterStart + 1000 < System.currentTimeMillis()) {
            counterStart = System.currentTimeMillis();
            csiPerSecondCounterFinal = csiPerSecondCounter;
            csiPerSecondCounter = 0;
        }

        csiCounter++;
        csiPerSecondCounter++;

        String counterText =
                csiCounter + " | " + csiPerSecondCounterFinal;

        runOnUiThread(() ->
                frameRateTextView.setText(counterText)
        );
    }

    private void toggleRecording() {

        // Geri sayım devam ederken tekrar START çalıştırılmaz.
        if (isCountingDown) {
            return;
        }

        // Manuel PAUSE
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

        startCountdown();
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
                        (millisUntilFinished + ONE_SECOND_MILLIS - 1L)
                                / ONE_SECOND_MILLIS
                );

                updateCountdownUi(secondsRemaining);
            }

            @Override
            public void onFinish() {
                startCountdownTimer = null;
                isCountingDown = false;
                startStopButton.setEnabled(true);

                startRecording();
            }
        }.start();
    }

    private void updateCountdownUi(long secondsRemaining) {
        String seconds = String.valueOf(secondsRemaining);

        startStopButton.setText(seconds);
        frameRateTextView.setText(
                "KAYIT " + seconds + " SANİYE SONRA BAŞLAYACAK"
        );
    }

    private void startRecording() {
        isRecording = true;

        startStopButton.setText("PAUSE");
        locationButton.setEnabled(false);
        objectSwitch.setEnabled(false);

        csiCounter = 0;
        csiPerSecondCounter = 0;
        csiPerSecondCounterFinal = 0;
        counterStart = System.currentTimeMillis();

        frameRateTextView.setText("0 | 0");

        Toast.makeText(
                this,
                "CSI kaydı başladı: "
                        + locationName
                        + " | Eşya: "
                        + objectPresent,
                Toast.LENGTH_SHORT
        ).show();

        automaticPauseTimer = new CountDownTimer(
                RECORDING_DURATION_MILLIS,
                RECORDING_DURATION_MILLIS
        ) {
            @Override
            public void onTick(long millisUntilFinished) {
                // Her saniye ekranda süre göstermek istemiyorsan boş kalabilir.
            }

            @Override
            public void onFinish() {
                automaticPauseTimer = null;

                if (isRecording) {
                    pauseRecording(true);
                }
            }
        }.start();
    }

    private void pauseRecording(boolean automatic) {
        if (!isRecording) {
            return;
        }

        isRecording = false;

        if (automaticPauseTimer != null) {
            automaticPauseTimer.cancel();
            automaticPauseTimer = null;
        }

        startStopButton.setText("START");
        locationButton.setEnabled(true);
        objectSwitch.setEnabled(true);

        frameRateTextView.setText(
                automatic
                        ? "30 SANİYELİK KAYIT TAMAMLANDI"
                        : "KAYIT DURDU"
        );

        Toast.makeText(
                this,
                automatic
                        ? "30 saniyelik CSI kaydı tamamlandı ve duraklatıldı"
                        : "CSI kaydı duraklatıldı",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void showLocationDialog() {

        EditText input = new EditText(this);
        input.setHint("Örn: Lab2");
        input.setSingleLine(true);

        /*
         * Daha önce girilen lokasyon korunur.
         * Kullanıcı değiştirmediği sürece bütün sonraki CSI
         * satırlarına aynı değer yazılır.
         */
        input.setText(locationName);

        new AlertDialog.Builder(this)
                .setTitle("Veri toplama lokasyonu")
                .setView(input)
                .setPositiveButton("Kaydet", (dialog, which) -> {

                    String enteredLocation =
                            input.getText().toString().trim();

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
                            "Lokasyon: " + locationName
                    );
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    /*
     * CSI_DATA firmware satırının sonuna sabit sırayla:
     *
     * object_present, location
     *
     * eklenir.
     */
    private String appendMetadata(String originalLine) {

        String line = originalLine;

        while (line.endsWith("\n") || line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }

        String safeLocation =
                locationName.replace("\"", "\"\"");

        return line
                + "," + objectPresent
                + ",\"" + safeLocation + "\"\n";
    }
}