package com.stevenmhernandez.csi_labelling_app.Experiments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.stevenmhernandez.csi_labelling_app.Network.UdpCsiReceiver;
import com.stevenmhernandez.csi_labelling_app.R;
import com.stevenmhernandez.csi_labelling_app.Services.BaseDataCollectorService;
import com.stevenmhernandez.csi_labelling_app.Services.FileDataCollectorService;
import com.stevenmhernandez.esp32csiserial.CSIDataInterface;
import com.stevenmhernandez.esp32csiserial.ESP32CSISerial;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public class TimerMainActivity extends AppCompatActivity implements CSIDataInterface {

    double timer_pause_seconds = 3.0;

    String[] actions = new String[]{
            "sit",
            "*transition*",
            "stand",
            "*transition*",
    };

    private ESP32CSISerial csiSerial = new ESP32CSISerial();

    private static final int UDP_PORT = 5005;

    private UdpCsiReceiver udpReceiver;
    private long udpPacketCounter = 0;

    private TextView textView;
    private ConstraintLayout background;
    private Timer timer;

    private TextView frameRateTextView;
    private TextView repetitionsTextView;

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
    private volatile String currentAction = "";

    /*
     * Uygulama oturumu boyunca aynı CSV dosyası kullanılır.
     */
    private final BaseDataCollectorService dataCollectorService =
            new FileDataCollectorService();

    private int actionIndex = 0;
    private int actionsRepetitions = 0;

    private long csiCounter = 0;
    private long csiPerSecondCounter = 0;
    private long counterStart = System.currentTimeMillis();
    private long csiPerSecondCounterFinal = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stand_walk_run);

        textView = findViewById(R.id.textView);
        background = findViewById(R.id.background);
        frameRateTextView = findViewById(R.id.frameRateTextView);
        repetitionsTextView = findViewById(R.id.repetitionsTextView);

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
                        + "first_word_invalid,csi_data,current_action,object_present,location\n"
        );

        TimerMainActivity activity = this;

        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {

                /*
                 * Hareket etiketi bellekte tutulur.
                 * Artık ayrıca CURRENT_ACTION satırı CSV'ye yazılmaz.
                 */
                currentAction = actions[actionIndex];

                final String actionForUi = currentAction;

                activity.runOnUiThread(() -> {
                    textView.setText(actionForUi);
                    textView.setTextColor(Color.BLACK);
                    background.setBackgroundColor(Color.WHITE);

                    repetitionsTextView.setText(
                            "Total Reps: "
                                    + actionsRepetitions
                                    + " + "
                                    + actionIndex
                                    + "/"
                                    + actions.length
                    );
                });

                actionIndex = (actionIndex + 1) % actions.length;

                if (actionIndex == 0) {
                    actionsRepetitions++;
                }
            }
        }, 0, (long) (timer_pause_seconds * 1000));

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

    /*
     * Eski arayüz/uyumluluk için bırakıldı.
     * Yeni CSV yapısında ayrı CURRENT_ACTION satırı yazılmıyor.
     */
    public String updateCsiString(Activity activity, String action) {
        String deviceId = Settings.Secure.getString(
                activity.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );

        return String.format(
                "'CURRENT_ACTION','%s',%d,'%s'\n",
                deviceId,
                System.currentTimeMillis(),
                action
        );
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
         * current_action,
         * object_present,
         * location
         * bilgileri eklenir.
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

        /*
         * STOP
         */
        if (isRecording) {
            isRecording = false;

            startStopButton.setText("START");

            /*
             * STOP'tan sonra deney bilgileri tekrar değiştirilebilir.
             */
            locationButton.setEnabled(true);
            objectSwitch.setEnabled(true);

            /*
             * UDP paketleri gelmeye devam etse bile kullanıcı artık
             * kayıt sayacının ilerlediğini görmez.
             */
            frameRateTextView.setText("KAYIT DURDU");

            Toast.makeText(
                    this,
                    "CSI kaydı durduruldu",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        /*
         * Lokasyon girilmeden START yapılmasın.
         */
        if (locationName.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "Önce lokasyon adını girin",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        /*
         * START
         */
        isRecording = true;

        startStopButton.setText("STOP");

        /*
         * Kayıt sırasında metadata değiştirilemez.
         * Böylece tek kayıt bloğunun ortasında lab1 -> lab2
         * veya 0 -> 1 değişimi olmaz.
         */
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
     * current_action, object_present, location
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

        String safeAction =
                currentAction.replace("\"", "\"\"");

        return line
                + ",\"" + safeAction + "\""
                + "," + objectPresent
                + ",\"" + safeLocation + "\"\n";
    }
}