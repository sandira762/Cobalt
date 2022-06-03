package it.auties.whatsapp.model.sync;

import it.auties.protobuf.api.model.ProtobufMessage;
import it.auties.protobuf.api.model.ProtobufProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.ArrayList;
import java.util.List;

import static it.auties.protobuf.api.model.ProtobufProperty.Type.*;

@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public class DeviceListMetadata implements ProtobufMessage {
    @ProtobufProperty(index = 1, type = BYTES)
    private byte[] senderKeyHash;

    @ProtobufProperty(index = 2, type = UINT64)
    private Long senderTimestamp;

    @ProtobufProperty(index = 3, type = UINT32, repeated = true, packed = true)
    private List<Integer> senderKeyIndexes;

    @ProtobufProperty(index = 8, type = BYTES)
    private byte[] recipientKeyHash;

    @ProtobufProperty(index = 9, type = UINT64)
    private Long recipientTimestamp;

    @ProtobufProperty(index = 10, type = UINT32, repeated = true, packed = true)
    private List<Integer> recipientKeyIndexes;

    public static class DeviceListMetadataBuilder {
        public DeviceListMetadataBuilder senderKeyIndexes(List<Integer> senderKeyIndexes) {
            if (this.senderKeyIndexes == null) this.senderKeyIndexes = new ArrayList<>();
            this.senderKeyIndexes.addAll(senderKeyIndexes);
            return this;
        }

        public DeviceListMetadataBuilder recipientKeyIndexes(List<Integer> recipientKeyIndexes) {
            if (this.recipientKeyIndexes == null) this.recipientKeyIndexes = new ArrayList<>();
            this.recipientKeyIndexes.addAll(recipientKeyIndexes);
            return this;
        }
    }
}
