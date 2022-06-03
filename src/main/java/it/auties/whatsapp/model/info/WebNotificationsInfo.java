package it.auties.whatsapp.model.info;

import it.auties.protobuf.api.model.ProtobufProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

import static it.auties.protobuf.api.model.ProtobufProperty.Type.*;

@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public final class WebNotificationsInfo implements Info {
  @ProtobufProperty(index = 2, type = UINT64)
  private Long timestamp;

  @ProtobufProperty(index = 3, type = UINT32)
  private Integer unreadChats;

  @ProtobufProperty(index = 4, type = UINT32)
  private Integer notifyMessageCount;

  @ProtobufProperty(index = 5, type = MESSAGE,
          concreteType = MessageInfo.class, repeated = true)
  private List<MessageInfo> notifyMessages;
}
