package com.stevenmhernandez.csi_labelling_app.Services;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class FileDataCollectorService
        extends BaseDataCollectorService {

    private static final String LOG_TAG =
            "FileDataCollectorService";

    /*
     * Kuyruk dolarsa UDP alıcı iş parçacığı bekletilmez.
     * Paket düşürülür ve droppedWriteCount artırılır.
     */
    private static final int WRITE_QUEUE_CAPACITY = 8_192;
    private static final int STREAM_BUFFER_BYTES = 65_536;
    private static final int MAX_BATCH_SIZE = 256;
    private static final long FLUSH_INTERVAL_MILLIS = 500L;
    private static final long QUEUE_POLL_MILLIS = 250L;

    private final BlockingQueue<String> writeQueue =
            new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);

    private final AtomicLong writtenLineCount =
            new AtomicLong(0);

    private final AtomicLong droppedWriteCount =
            new AtomicLong(0);

    private final AtomicLong recreatedFileCount =
            new AtomicLong(0);

    private final AtomicReference<String> lastErrorMessage =
            new AtomicReference<>(null);

    private final AtomicReference<String> csvHeader =
            new AtomicReference<>(null);

    private volatile File outputDirectory;
    private volatile File outputFile;
    private volatile BufferedOutputStream outputStream;
    private volatile boolean running = false;
    private volatile Thread writerThread;

    /* Yalnızca CsiFileWriter iş parçacığı tarafından değiştirilir. */
    private boolean headerWrittenForCurrentFile = false;

    @Override
    public synchronized void setup(Context context) {
        if (running) {
            return;
        }

        outputDirectory = context.getFilesDir();
        writeQueue.clear();
        writtenLineCount.set(0);
        droppedWriteCount.set(0);
        recreatedFileCount.set(0);
        lastErrorMessage.set(null);
        csvHeader.set(null);
        headerWrittenForCurrentFile = false;

        try {
            outputStream = openNewOutputStream();
        } catch (IOException error) {
            setWriteError(error);
            return;
        }

        running = true;

        writerThread = new Thread(
                this::runWriterLoop,
                "CsiFileWriter"
        );

        writerThread.start();
    }

    /*
     * Bu metot dosyaya doğrudan yazmaz.
     * Satırı beklemeden yazma kuyruğuna ekler.
     */
    @Override
    public void handle(String csi) {
        if (csi == null || csi.isEmpty()) {
            return;
        }

        if (looksLikeCsvHeader(csi)) {
            csvHeader.compareAndSet(null, csi);
        }

        if (!running) {
            droppedWriteCount.incrementAndGet();
            return;
        }

        if (!writeQueue.offer(csi)) {
            droppedWriteCount.incrementAndGet();
        }
    }

    private void runWriterLoop() {
        BufferedOutputStream stream = outputStream;
        long lastFlushTime = System.currentTimeMillis();

        if (stream == null) {
            running = false;
            return;
        }

        try {
            while (running || !writeQueue.isEmpty()) {
                String line = null;

                try {
                    line = writeQueue.poll(
                            QUEUE_POLL_MILLIS,
                            TimeUnit.MILLISECONDS
                    );
                } catch (InterruptedException ignored) {
                    /*
                     * close() çağrıldığında thread uyandırılır.
                     * Kuyrukta veri varsa yazmaya devam edilir.
                     */
                }

                stream = recreateStreamIfFileWasDeleted(stream);

                if (line != null) {
                    writeQueuedLine(stream, line);
                }

                int batchCount = 0;

                while (batchCount < MAX_BATCH_SIZE) {
                    String queuedLine = writeQueue.poll();

                    if (queuedLine == null) {
                        break;
                    }

                    writeQueuedLine(stream, queuedLine);
                    batchCount++;
                }

                long currentTime = System.currentTimeMillis();

                if (currentTime - lastFlushTime
                        >= FLUSH_INTERVAL_MILLIS) {

                    stream.flush();
                    lastFlushTime = currentTime;
                }
            }

            stream.flush();

        } catch (IOException error) {
            droppedWriteCount.incrementAndGet();
            droppedWriteCount.addAndGet(writeQueue.size());
            writeQueue.clear();

            setWriteError(error);

        } finally {
            closeStreamQuietly(stream);
            running = false;

            if (outputStream == stream) {
                outputStream = null;
            }

            if (Thread.currentThread() == writerThread) {
                writerThread = null;
            }
        }
    }

    /*
     * Android/Linux'ta açık bir dosya silindiğinde akış eski dosya tanıtıcısına
     * yazmaya devam edebilir. Bu nedenle dosya yolu düzenli olarak kontrol edilir.
     * Yol silinmişse eski akış kapatılır ve yeni bir CSV dosyası açılır.
     */
    private BufferedOutputStream recreateStreamIfFileWasDeleted(
            BufferedOutputStream currentStream
    ) throws IOException {

        File currentFile = outputFile;

        if (currentFile != null && currentFile.isFile()) {
            return currentStream;
        }

        closeStreamQuietly(currentStream);

        BufferedOutputStream newStream = openNewOutputStream();
        headerWrittenForCurrentFile = false;

        String header = csvHeader.get();

        if (header != null && !header.isEmpty()) {
            writeLine(newStream, header);
            headerWrittenForCurrentFile = true;
            newStream.flush();
        }

        recreatedFileCount.incrementAndGet();
        lastErrorMessage.set(null);

        Log.i(
                LOG_TAG,
                "Silinen CSI dosyasının yerine yeni CSV oluşturuldu: "
                        + outputFile.getAbsolutePath()
        );

        return newStream;
    }

    private BufferedOutputStream openNewOutputStream()
            throws IOException {

        File directory = outputDirectory;

        if (directory == null) {
            throw new IOException(
                    "CSI çıktı klasörü ayarlanmadı"
            );
        }

        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException(
                    "CSI çıktı klasörü oluşturulamadı: "
                            + directory.getAbsolutePath()
            );
        }

        long timestamp = System.currentTimeMillis();
        File candidate = new File(
                directory,
                "backup" + timestamp + ".csv"
        );

        int suffix = 1;

        while (candidate.exists()) {
            candidate = new File(
                    directory,
                    "backup" + timestamp + "_" + suffix + ".csv"
            );
            suffix++;
        }

        BufferedOutputStream newStream =
                new BufferedOutputStream(
                        new FileOutputStream(candidate, false),
                        STREAM_BUFFER_BYTES
                );

        outputFile = candidate;
        outputStream = newStream;

        return newStream;
    }

    private void writeQueuedLine(
            BufferedOutputStream stream,
            String line
    ) throws IOException {

        if (looksLikeCsvHeader(line)) {
            if (headerWrittenForCurrentFile) {
                return;
            }

            headerWrittenForCurrentFile = true;
        }

        writeLine(stream, line);
    }

    private boolean looksLikeCsvHeader(String line) {
        return line.startsWith("type,esp_device_id,");
    }

    private void writeLine(
            BufferedOutputStream stream,
            String line
    ) throws IOException {

        stream.write(
                line.getBytes(StandardCharsets.UTF_8)
        );

        writtenLineCount.incrementAndGet();
    }

    private void closeStreamQuietly(
            BufferedOutputStream stream
    ) {
        if (stream == null) {
            return;
        }

        try {
            stream.flush();
        } catch (IOException error) {
            setWriteError(error);
        }

        try {
            stream.close();
        } catch (IOException error) {
            setWriteError(error);
        }
    }

    private void setWriteError(Exception error) {
        String message = error.getMessage();

        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }

        lastErrorMessage.set(message);

        Log.e(
                LOG_TAG,
                "CSI dosya yazma hatası",
                error
        );
    }

    /*
     * Activity kapanırken kuyruktaki bütün satırların yazılması
     * ve dosyanın kapatılması için çağrılır.
     */
    public void close() {
        Thread threadToJoin;

        synchronized (this) {
            running = false;
            threadToJoin = writerThread;
        }

        if (threadToJoin == null
                || Thread.currentThread() == threadToJoin) {
            return;
        }

        threadToJoin.interrupt();

        try {
            threadToJoin.join(3_000L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }

        if (threadToJoin.isAlive()) {
            Log.w(
                    LOG_TAG,
                    "Dosya yazma thread'i kapanmayı hâlâ bekliyor"
            );
        }
    }

    @Override
    public Uri getFileUri() throws IOException {
        File file = outputFile;

        if (file == null || !file.isFile()) {
            throw new IOException(
                    "CSI çıktı dosyası bulunamadı; yeniden oluşturulması bekleniyor"
            );
        }

        return Uri.fromFile(file);
    }

    public boolean isRunning() {
        return running;
    }

    public int getPendingWriteCount() {
        return writeQueue.size();
    }

    public long getWrittenLineCount() {
        return writtenLineCount.get();
    }

    public long getDroppedWriteCount() {
        return droppedWriteCount.get();
    }

    public long getRecreatedFileCount() {
        return recreatedFileCount.get();
    }

    public String getLastErrorMessage() {
        return lastErrorMessage.get();
    }
}