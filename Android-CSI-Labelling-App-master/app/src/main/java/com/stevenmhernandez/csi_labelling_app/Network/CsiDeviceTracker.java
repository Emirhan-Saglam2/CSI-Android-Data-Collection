package com.stevenmhernandez.csi_labelling_app.Network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CsiDeviceTracker {

    /*
     * Her esp_device_id için ayrı sayaç ve bağlantı durumu tutulur.
     */
    private final ConcurrentHashMap<String, DeviceState> devices =
            new ConcurrentHashMap<>();

    public void record(
            CsiDatagram datagram,
            String sourceIp,
            long receivedAtElapsedNanos
    ) {
        String deviceId = datagram.getDeviceId();

        DeviceState state = devices.get(deviceId);

        if (state == null) {
            DeviceState newState = new DeviceState();

            DeviceState existingState =
                    devices.putIfAbsent(deviceId, newState);

            state = existingState == null
                    ? newState
                    : existingState;
        }

        state.record(
                datagram.getSequence(),
                sourceIp,
                receivedAtElapsedNanos
        );
    }

    public int getTrackedDeviceCount() {
        return devices.size();
    }

    /*
     * Belirtilen süre içerisinde paket gönderen cihazlar aktif sayılır.
     */
    public int getActiveDeviceCount(
            long nowElapsedNanos,
            long activeTimeoutNanos
    ) {
        int activeDeviceCount = 0;

        for (DeviceState state : devices.values()) {
            if (state.isActive(
                    nowElapsedNanos,
                    activeTimeoutNanos
            )) {
                activeDeviceCount++;
            }
        }

        return activeDeviceCount;
    }

    public List<String> getActiveDeviceIds(
            long nowElapsedNanos,
            long activeTimeoutNanos
    ) {
        List<String> activeDeviceIds = new ArrayList<>();

        for (Map.Entry<String, DeviceState> entry
                : devices.entrySet()) {

            if (entry.getValue().isActive(
                    nowElapsedNanos,
                    activeTimeoutNanos
            )) {
                activeDeviceIds.add(entry.getKey());
            }
        }

        Collections.sort(activeDeviceIds);

        return activeDeviceIds;
    }

    public long getTotalReceivedPackets() {
        long total = 0;

        for (DeviceState state : devices.values()) {
            total += state.getReceivedPackets();
        }

        return total;
    }

    /*
     * sequence değerleri arasındaki boşlukların toplamıdır.
     * Örneğin 100, 101, 104 geldiyse üç satır arasında
     * 102 ve 103 eksik olduğu için iki boşluk kaydedilir.
     */
    public long getTotalSequenceGaps() {
        long total = 0;

        for (DeviceState state : devices.values()) {
            total += state.getSequenceGaps();
        }

        return total;
    }

    public long getTotalDuplicatePackets() {
        long total = 0;

        for (DeviceState state : devices.values()) {
            total += state.getDuplicatePackets();
        }

        return total;
    }

    public long getTotalOutOfOrderPackets() {
        long total = 0;

        for (DeviceState state : devices.values()) {
            total += state.getOutOfOrderPackets();
        }

        return total;
    }

    /*
     * Aynı esp_device_id farklı bir IP adresinden görülürse artırılır.
     * Bu durum DHCP değişimi veya iki ESP'nin yanlışlıkla aynı
     * esp_device_id değerini kullanması nedeniyle oluşabilir.
     */
    public long getTotalSourceIpChanges() {
        long total = 0;

        for (DeviceState state : devices.values()) {
            total += state.getSourceIpChanges();
        }

        return total;
    }

    public void clear() {
        devices.clear();
    }

    private static final class DeviceState {

        private boolean hasLastSequence = false;
        private long lastSequence = 0;

        private long receivedPackets = 0;
        private long sequenceGaps = 0;
        private long duplicatePackets = 0;
        private long outOfOrderPackets = 0;
        private long sourceIpChanges = 0;

        private long lastSeenElapsedNanos = 0;
        private String lastSourceIp = "";

        private synchronized void record(
                long sequence,
                String sourceIp,
                long receivedAtElapsedNanos
        ) {
            receivedPackets++;

            if (!hasLastSequence) {
                hasLastSequence = true;
                lastSequence = sequence;

            } else if (sequence == lastSequence) {
                duplicatePackets++;

            } else if (sequence > lastSequence) {
                long difference = sequence - lastSequence;

                if (difference > 1) {
                    sequenceGaps += difference - 1;
                }

                lastSequence = sequence;

            } else {
                /*
                 * Daha küçük sequence gelmesi, paketin sırasız
                 * ulaşması veya ESP32'nin yeniden başlaması olabilir.
                 */
                outOfOrderPackets++;
            }

            String normalizedSourceIp =
                    sourceIp == null ? "" : sourceIp.trim();

            if (!lastSourceIp.isEmpty()
                    && !normalizedSourceIp.isEmpty()
                    && !lastSourceIp.equals(normalizedSourceIp)) {

                sourceIpChanges++;
            }

            if (!normalizedSourceIp.isEmpty()) {
                lastSourceIp = normalizedSourceIp;
            }

            lastSeenElapsedNanos = receivedAtElapsedNanos;
        }

        private synchronized boolean isActive(
                long nowElapsedNanos,
                long activeTimeoutNanos
        ) {
            if (lastSeenElapsedNanos == 0) {
                return false;
            }

            long elapsed = nowElapsedNanos
                    - lastSeenElapsedNanos;

            return elapsed >= 0
                    && elapsed <= activeTimeoutNanos;
        }

        private synchronized long getReceivedPackets() {
            return receivedPackets;
        }

        private synchronized long getSequenceGaps() {
            return sequenceGaps;
        }

        private synchronized long getDuplicatePackets() {
            return duplicatePackets;
        }

        private synchronized long getOutOfOrderPackets() {
            return outOfOrderPackets;
        }

        private synchronized long getSourceIpChanges() {
            return sourceIpChanges;
        }
    }
}