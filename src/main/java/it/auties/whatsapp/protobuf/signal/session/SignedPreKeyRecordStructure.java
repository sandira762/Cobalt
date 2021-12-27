package it.auties.whatsapp.protobuf.signal.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import it.auties.whatsapp.protobuf.signal.keypair.SignalKeyPair;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Accessors(fluent = true)
public class SignedPreKeyRecordStructure {
  @JsonProperty("1")
  @JsonPropertyDescription("uint32")
  private int id;

  @JsonProperty("2")
  @JsonPropertyDescription("bytes")
  private byte[] publicKey;

  @JsonProperty("3")
  @JsonPropertyDescription("bytes")
  private byte[] privateKey;

  @JsonProperty("4")
  @JsonPropertyDescription("bytes")
  private byte[] signature;

  @JsonProperty("5")
  @JsonPropertyDescription("fixed64")
  private long timestamp;

  public SignalKeyPair keyPair(){
    return new SignalKeyPair(publicKey, privateKey);
  }
}
