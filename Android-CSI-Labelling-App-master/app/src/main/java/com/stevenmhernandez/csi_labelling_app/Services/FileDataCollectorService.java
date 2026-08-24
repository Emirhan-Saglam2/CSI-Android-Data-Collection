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

    private final BlockingQueue<String> writeQueue =
            new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);

    private final AtomicLong writtenLineCount =
            new AtomicLong(0);

    private final AtomicLong droppedWriteCount =
            new AtomicLong(0);

    private final AtomicReference<String> lastErrorMessage =
            new AtomicReference<>(null);

    private volatile File outputFile;
    private volatile BufferedOutputStream outputStream;
    private volatile boolean running = false;
    private volatile Thread writerThread;

    @Override
    public synchronized void setup(Context context) {
        if (running) {
            return;
        }

        outputFile = new File(
                context.getFilesDir(),
                "backup" + System.currentTimeMillis() + ".csv"
        );

        try {
            outputStream = new BufferedOutputStream(
                    new FileOutputStream(outputFile, true),
                    STREAM_BUFFER_BYTES
            );

        } catch (IOException error) {
            setWriteError(error);
            return;
        }

        writeQueue.clear();
        running = true;

        writerThread = new Thread(
                this::runWriterLoop,
                "CsiFileWriter"
        );

        writerThread.start();
    }

    /*
     * Bu metot artık dosyaya doğrudan yazmaz.
     * Satırı beklemeden yazma kuyruğuna ekler.
     */
    @Override
    public void handle(String csi) {
        if (csi == null || csi.isEmpty()) {
            return;
        }

        if (!running) {
            droppedWriteCount.incrementAndGet();
            return;
        }

        boolean accepted = writeQueue.offer(csi);

        if (!accepted) {
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
                            250,
                            TimeUnit.MILLISECONDS
                    );
                } catch (InterruptedException ignored) {
                    /*
                     * close() çağrıldığında thread uyandırılır.
                     * Kuyrukta veri varsa yazmaya devam edilir.
                     */
                }

                if (line != null) {
                    writeLine(stream, line);
                }

                int batchCount = 0;

                while (batchCount < MAX_BATCH_SIZE) {
                    String queuedLine = writeQueue.poll();

                    if (queuedLine == null) {
                        break;
                    }

                    writeLine(stream, queuedLine);
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

            running = false;

            if (outputStream == stream) {
                outputStream = null;
            }

            if (Thread.currentThread() == writerThread) {
                writerThread = null;
            }
        }
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

        if (file == null) {
            throw new IOException(
                    "CSI çıktı dosyası henüz oluşturulmadı"
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

    public String getLastErrorMessage() {
        return lastErrorMessage.get();
    }
}