package it.auties.whatsapp.model.button;

import it.auties.protobuf.api.model.ProtobufMessage;
import it.auties.protobuf.api.model.ProtobufProperty;
import it.auties.whatsapp.model.message.button.ButtonStructureMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import static it.auties.protobuf.api.model.ProtobufProperty.Type.STRING;

@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public class QuickReplyButton implements ProtobufMessage {
  @ProtobufProperty(index = 1, type = STRING)
  private ButtonStructureMessage displayText;

  @ProtobufProperty(index = 2, type = STRING)
  private String id;
}
