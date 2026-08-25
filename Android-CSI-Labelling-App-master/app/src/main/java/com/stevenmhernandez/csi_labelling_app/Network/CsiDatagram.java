package com.stevenmhernandez.csi_labelling_app.Network;

import java.util.ArrayList;
import java.util.List;

public final class CsiDatagram {

    /*
     * Firmware'den beklenen alanlar:
     *
     * type, esp_device_id, sequence, mac, rssi, channel,
     * esp_timestamp, length, first_word_invalid, csi_data
     */
    public static final int EXPECTED_FIELD_COUNT = 10;

    private final String csvLine;
    private final String deviceId;
    private final long sequence;

    private CsiDatagram(
            String csvLine,
            String deviceId,
            long sequence
    ) {
        this.csvLine = csvLine;
        this.deviceId = deviceId;
        this.sequence = sequence;
    }

    public static CsiDatagram parse(String message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "UDP mesajı null olamaz"
            );
        }

        String line = removeTrailingLineEndings(message).trim();

        if (line.isEmpty()) {
            throw new IllegalArgumentException(
                    "UDP mesajı boş olamaz"
            );
        }

        /*
         * Her UDP paketinde yalnızca bir CSI satırı bulunmalıdır.
         * Birden fazla satır tek datagramda gönderilirse metadata
         * kolonları bozulacağı için paket kabul edilmez.
         */
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "Bir UDP paketinde birden fazla satır bulundu"
            );
        }

        List<String> fields = parseCsvFields(line);

        if (fields.size() != EXPECTED_FIELD_COUNT) {
            throw new IllegalArgumentException(
                    "Beklenen kolon sayısı "
                            + EXPECTED_FIELD_COUNT
                            + ", gelen kolon sayısı "
                            + fields.size()
            );
        }

        String type = fields.get(0).trim();

        if (!"CSI_DATA".equals(type)) {
            throw new IllegalArgumentException(
                    "Geçersiz paket türü: " + type
            );
        }

        String deviceId = fields.get(1).trim();

        if (deviceId.isEmpty()) {
            throw new IllegalArgumentException(
                    "esp_device_id boş olamaz"
            );
        }

        long sequence;

        try {
            sequence = Long.parseLong(fields.get(2).trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "sequence sayısal değil: " + fields.get(2),
                    error
            );
        }

        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "sequence negatif olamaz"
            );
        }

        String csiData = fields.get(9).trim();

        if (csiData.isEmpty()) {
            throw new IllegalArgumentException(
                    "csi_data boş olamaz"
            );
        }

        return new CsiDatagram(
                line,
                deviceId,
                sequence
        );
    }

    private static String removeTrailingLineEndings(String value) {
        int endIndex = value.length();

        while (endIndex > 0) {
            char lastCharacter = value.charAt(endIndex - 1);

            if (lastCharacter == '\n' || lastCharacter == '\r') {
                endIndex--;
            } else {
                break;
            }
        }

        return value.substring(0, endIndex);
    }

    /*
     * csi_data alanındaki virgüllerin yeni kolon sanılmaması için
     * çift tırnakları dikkate alan küçük bir CSV ayrıştırıcısı.
     */
    private static List<String> parseCsvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean insideQuotes = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);

            if (character == '"') {
                if (insideQuotes
                        && index + 1 < line.length()
                        && line.charAt(index + 1) == '"') {

                    currentField.append('"');
                    index++;
                } else {
                    insideQuotes = !insideQuotes;
                }

            } else if (character == ',' && !insideQuotes) {
                fields.add(currentField.toString());
                currentField.setLength(0);

            } else {
                currentField.append(character);
            }
        }

        if (insideQuotes) {
            throw new IllegalArgumentException(
                    "CSV satırında kapanmamış çift tırnak bulundu"
            );
        }

        fields.add(currentField.toString());

        return fields;
    }

    public String getCsvLine() {
        return csvLine;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public long getSequence() {
        return sequence;
    }
}