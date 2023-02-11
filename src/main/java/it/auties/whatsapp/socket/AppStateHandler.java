package it.auties.whatsapp.socket;

import static it.auties.whatsapp.api.ErrorHandler.Location.INITIAL_APP_STATE_SYNC;
import static it.auties.whatsapp.api.ErrorHandler.Location.PULL_APP_STATE;
import static it.auties.whatsapp.api.ErrorHandler.Location.PUSH_APP_STATE;
import static it.auties.whatsapp.model.request.Node.ofChildren;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Map.of;
import static java.util.concurrent.CompletableFuture.completedFuture;

import it.auties.bytes.Bytes;
import it.auties.whatsapp.binary.PatchType;
import it.auties.whatsapp.crypto.AesCbc;
import it.auties.whatsapp.crypto.Hmac;
import it.auties.whatsapp.crypto.LTHash;
import it.auties.whatsapp.model.action.ArchiveChatAction;
import it.auties.whatsapp.model.action.ClearChatAction;
import it.auties.whatsapp.model.action.ContactAction;
import it.auties.whatsapp.model.action.DeleteChatAction;
import it.auties.whatsapp.model.action.DeleteMessageForMeAction;
import it.auties.whatsapp.model.action.MarkChatAsReadAction;
import it.auties.whatsapp.model.action.MuteAction;
import it.auties.whatsapp.model.action.PinAction;
import it.auties.whatsapp.model.action.StarAction;
import it.auties.whatsapp.model.action.TimeFormatAction;
import it.auties.whatsapp.model.chat.Chat;
import it.auties.whatsapp.model.chat.ChatMute;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.info.MessageIndexInfo;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.request.Node;
import it.auties.whatsapp.model.setting.EphemeralSetting;
import it.auties.whatsapp.model.setting.LocaleSetting;
import it.auties.whatsapp.model.setting.PushNameSetting;
import it.auties.whatsapp.model.setting.UnarchiveChatsSetting;
import it.auties.whatsapp.model.sync.ActionDataSync;
import it.auties.whatsapp.model.sync.ActionMessageRangeSync;
import it.auties.whatsapp.model.sync.AppStateSyncKey;
import it.auties.whatsapp.model.sync.AppStateSyncKeyData;
import it.auties.whatsapp.model.sync.ExternalBlobReference;
import it.auties.whatsapp.model.sync.IndexSync;
import it.auties.whatsapp.model.sync.KeyId;
import it.auties.whatsapp.model.sync.LTHashState;
import it.auties.whatsapp.model.sync.MutationKeys;
import it.auties.whatsapp.model.sync.MutationSync;
import it.auties.whatsapp.model.sync.MutationsSync;
import it.auties.whatsapp.model.sync.PatchRequest;
import it.auties.whatsapp.model.sync.PatchSync;
import it.auties.whatsapp.model.sync.RecordSync;
import it.auties.whatsapp.model.sync.SnapshotSync;
import it.auties.whatsapp.model.sync.SyncActionMessage;
import it.auties.whatsapp.model.sync.Syncable;
import it.auties.whatsapp.model.sync.ValueSync;
import it.auties.whatsapp.model.sync.VersionSync;
import it.auties.whatsapp.util.BytesHelper;
import it.auties.whatsapp.util.HmacValidationException;
import it.auties.whatsapp.util.JacksonProvider;
import it.auties.whatsapp.util.Medias;
import it.auties.whatsapp.util.Specification;
import it.auties.whatsapp.util.Validate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.SneakyThrows;

class AppStateHandler extends Handler
    implements JacksonProvider {
  public static final int TIMEOUT = 120;
  private static final int PULL_ATTEMPTS = 3;
  private final SocketHandler socketHandler;
  private final Map<PatchType, Integer> attempts;

  protected AppStateHandler(SocketHandler socketHandler) {
    this.socketHandler = socketHandler;
    this.attempts = new HashMap<>();
  }

  protected CompletableFuture<Void> push(@NonNull PatchRequest patch) {
    return CompletableFuture.runAsync(() -> {
          awaitLatch();
          pullUninterruptedly(List.of(patch.type()))
              .thenCompose(ignored -> sendPush(createPushRequest(patch)))
              .join();
        }, getOrCreateService())
        .exceptionallyAsync(throwable -> socketHandler.errorHandler().handleFailure(PUSH_APP_STATE, throwable))
        .orTimeout(TIMEOUT, TimeUnit.SECONDS);
  }

  private PushRequest createPushRequest(PatchRequest patch) {
    try {
      var oldState = socketHandler.keys()
          .findHashStateByName(patch.type())
          .orElseGet(() -> new LTHashState(patch.type()));
      var newState = oldState.copy();
      var key = socketHandler.keys()
          .appKey();
      var index = patch.index()
          .getBytes(StandardCharsets.UTF_8);
      var actionData = ActionDataSync.builder()
          .index(index)
          .value(patch.sync())
          .padding(new byte[0])
          .version(patch.version())
          .build();
      var encoded = PROTOBUF.writeValueAsBytes(actionData);
      var mutationKeys = MutationKeys.of(key.keyData()
          .keyData());
      var encrypted = AesCbc.encryptAndPrefix(encoded, mutationKeys.encKey());
      var valueMac = generateMac(patch.operation(), encrypted, key.keyId()
          .keyId(), mutationKeys.macKey());
      var indexMac = Hmac.calculateSha256(index, mutationKeys.indexKey());
      var generator = new LTHash(newState);
      generator.mix(indexMac, valueMac, patch.operation());
      var result = generator.finish();
      newState.hash(result.hash());
      newState.indexValueMap(result.indexValueMap());
      newState.version(newState.version() + 1);
      var syncId = new KeyId(key.keyId()
          .keyId());
      var record = RecordSync.builder()
          .index(new IndexSync(indexMac))
          .value(new ValueSync(Bytes.of(encrypted, valueMac)
              .toByteArray()))
          .keyId(syncId)
          .build();
      var mutation = MutationSync.builder()
          .operation(patch.operation())
          .record(record)
          .build();
      var snapshotMac = generateSnapshotMac(newState.hash(), newState.version(), patch.type(),
          mutationKeys.snapshotMacKey());
      var patchMac = generatePatchMac(snapshotMac, valueMac, newState.version(), patch.type(),
          mutationKeys.patchMacKey());
      var sync = PatchSync.builder()
          .patchMac(patchMac)
          .snapshotMac(snapshotMac)
          .keyId(syncId)
          .mutations(List.of(mutation))
          .build();
      newState.indexValueMap()
          .put(Bytes.of(indexMac)
              .toBase64(), valueMac);
      return new PushRequest(patch, oldState, newState, sync);
    } catch (Throwable throwable) {
      throw new RuntimeException("Cannot create patch %s".formatted(patch), throwable);
    }
  }

  private CompletableFuture<Void> sendPush(PushRequest request) {
    try {
      var body = ofChildren("collection", of("name", request.patch()
              .type(), "version", request.newState()
              .version() - 1, "return_snapshot", false),
          Node.of("patch", PROTOBUF.writeValueAsBytes(request.sync())));
      return socketHandler.sendQuery("set", "w:sync:app:state", Node.ofChildren("sync", body))
          .thenAcceptAsync(this::parseSyncRequest)
          .thenRunAsync(
              () -> socketHandler.keys().putState(request.patch().type(), request.newState()))
          .thenRunAsync(
              () -> handleSyncRequest(request.patch().type(), request.sync(), request.oldState(),
                  request.newState().version()));
    } catch (Throwable throwable) {
      throw new RuntimeException("Cannot push patch", throwable);
    }
  }

  private void handleSyncRequest(PatchType patchType, PatchSync patch, LTHashState oldState,
      long newVersion) {
    var patches = List.of(patch.withVersion(new VersionSync(newVersion)));
    var results = decodePatches(patchType, patches, oldState);
    results.records().forEach(this::processActions);
  }

  @SuppressWarnings("CodeBlock2Expr")
  protected void pull(PatchType... patchTypes) {
    if (patchTypes == null || patchTypes.length == 0) {
      return;
    }
    CompletableFuture.runAsync(() -> {
          pullUninterruptedly(Arrays.asList(patchTypes))
              .thenAcceptAsync(success -> onPull(false, success))
              .join();
        }, getOrCreateService())
        .exceptionallyAsync(exception -> onPullError(false, exception));
  }

  protected CompletableFuture<Void> pullInitial() {
    return pullUninterruptedly(List.of(PatchType.CRITICAL_BLOCK, PatchType.CRITICAL_UNBLOCK_LOW))
        .thenComposeAsync(ignored -> pullUninterruptedly(List.of(PatchType.REGULAR, PatchType.REGULAR_LOW, PatchType.REGULAR_HIGH)))
        .thenComposeAsync(ignored -> pullUninterruptedly(List.of(PatchType.CRITICAL_BLOCK, PatchType.CRITICAL_UNBLOCK_LOW)))
        .thenAcceptAsync(success -> onPull(true, success))
        .exceptionallyAsync(exception -> onPullError(true, exception));
  }

  private void onPull(boolean initial, boolean success) {
    if(!socketHandler.store().initialSync()) {
      socketHandler.store().initialSync((initial && success) || isSyncComplete());
    }

    getOrCreateLatch().countDown();
    attempts.clear();
  }

  private boolean isSyncComplete() {
    return Arrays.stream(PatchType.values())
        .allMatch(this::isSyncComplete);
  }

  private boolean isSyncComplete(PatchType entry) {
    return socketHandler.keys()
        .findHashStateByName(entry)
        .filter(type -> type.version() > 0)
        .isPresent();
  }

  private Void onPullError(boolean initial, Throwable exception) {
    attempts.clear();
    if (initial) {
      getOrCreateLatch().countDown();
      return socketHandler.errorHandler()
          .handleFailure(INITIAL_APP_STATE_SYNC, exception);
    }
    return socketHandler.errorHandler()
        .handleFailure(PULL_APP_STATE, exception);
  }

  private CompletableFuture<Boolean> pullUninterruptedly(List<PatchType> patchTypes) {
    var tempStates = new HashMap<PatchType, LTHashState>();
    var nodes = getPullNodes(patchTypes, tempStates);
    return socketHandler.sendQuery("set", "w:sync:app:state", Node.ofChildren("sync", nodes))
        .thenApplyAsync(this::parseSyncRequest)
        .thenApplyAsync(records -> isPullSuccessful(records) ? records : null)
        .thenApplyAsync(records -> records == null ? null : decodeSyncs(tempStates, records))
        .thenComposeAsync(this::handlePullResult)
        .orTimeout(TIMEOUT, TimeUnit.SECONDS);
  }

  private CompletableFuture<Boolean> handlePullResult(List<PatchType> remaining) {
    if (remaining == null) {
      return completedFuture(false);
    }
    if (remaining.isEmpty()) {
      return completedFuture(true);
    }
    return pullUninterruptedly(remaining);
  }

  private boolean isPullSuccessful(List<SnapshotSyncRecord> records) {
    var count = records.stream()
        .map(SnapshotSyncRecord::patches)
        .mapToLong(Collection::size)
        .sum();
    return count != 0;
  }

  private List<Node> getPullNodes(List<PatchType> patchTypes, Map<PatchType, LTHashState> tempStates) {
    return patchTypes.stream()
        .map(this::createStateWithVersion)
        .peek(state -> tempStates.put(state.name(), state))
        .map(LTHashState::toNode)
        .toList();
  }

  private LTHashState createStateWithVersion(PatchType name) {
    return socketHandler.keys()
        .findHashStateByName(name)
        .orElseGet(() -> new LTHashState(name));
  }

  private List<PatchType> decodeSyncs(Map<PatchType, LTHashState> tempStates, List<SnapshotSyncRecord> records) {
    return records.stream()
        .map(record -> decodeSync(record, tempStates))
        .peek(chunk -> chunk.records()
            .forEach(this::processActions))
        .filter(PatchChunk::hasMore)
        .map(PatchChunk::patchType)
        .toList();
  }

  private PatchChunk decodeSync(SnapshotSyncRecord record, Map<PatchType, LTHashState> tempStates) {
    try {
      var results = new ArrayList<ActionDataSync>();
      if (record.hasSnapshot()) {
        var snapshot = decodeSnapshot(record.patchType(), record.snapshot());
        snapshot.ifPresent(decodedSnapshot -> {
          results.addAll(decodedSnapshot.records());
          tempStates.put(record.patchType(), decodedSnapshot.state());
          socketHandler.keys()
              .putState(record.patchType(), decodedSnapshot.state());
        });
      }
      if (record.hasPatches()) {
        var decodedPatches = decodePatches(record.patchType(), record.patches(), tempStates.get(record.patchType()));
        results.addAll(decodedPatches.records());
        socketHandler.keys()
            .putState(record.patchType(), decodedPatches.state());
      }
      return new PatchChunk(record.patchType(), results, record.hasMore());
    } catch (Throwable throwable) {
      var hashState = new LTHashState(record.patchType());
      socketHandler.keys()
          .putState(record.patchType(), hashState);
      attempts.put(record.patchType(), attempts.getOrDefault(record.patchType(), 0) + 1);
      if (attempts.get(record.patchType()) >= PULL_ATTEMPTS) {
        throw new RuntimeException("Cannot parse patch(%s tries)".formatted(PULL_ATTEMPTS),
            throwable);
      }
      return decodeSync(record, tempStates);
    }
  }

  private List<SnapshotSyncRecord> parseSyncRequest(Node node) {
    return Stream.ofNullable(node)
        .map(sync -> sync.findNodes("sync"))
        .flatMap(Collection::stream)
        .map(sync -> sync.findNodes("collection"))
        .flatMap(Collection::stream)
        .map(this::parseSync)
        .flatMap(Optional::stream)
        .toList();
  }

  private Optional<SnapshotSyncRecord> parseSync(Node sync) {
    var name = PatchType.of(sync.attributes()
        .getString("name"));
    var type = sync.attributes()
        .getString("type");
    if (Objects.equals(type, "error")) {
      return Optional.empty();
    }
    var more = sync.attributes()
        .getBoolean("has_more_patches");
    var snapshotSync = sync.findNode("snapshot")
        .flatMap(this::decodeSnapshot)
        .orElse(null);
    var versionCode = sync.attributes()
        .getInt("version");
    var patches = sync.findNode("patches")
        .orElse(sync)
        .findNodes("patch")
        .stream()
        .map(patch -> decodePatch(patch, versionCode))
        .flatMap(Optional::stream)
        .toList();
    return Optional.of(new SnapshotSyncRecord(name, snapshotSync, patches, more));
  }

  private Optional<SnapshotSync> decodeSnapshot(Node snapshot) {
    try {
      if (snapshot == null) {
        return Optional.empty();
      }
      var bytes = snapshot.contentAsBytes();
      if(bytes.isEmpty()){
        return Optional.empty();
      }
      var blob = PROTOBUF.readMessage(bytes.get(), ExternalBlobReference.class);
      var syncedData = Medias.download(blob).join();
      if(syncedData.isEmpty()){
        return Optional.empty();
      }
      return Optional.of(PROTOBUF.readMessage(syncedData.get(), SnapshotSync.class));
    }catch (IOException exception){
      return Optional.empty();
    }
  }

  private Optional<PatchSync> decodePatch(Node patch, long versionCode) {
    try {
      if (!patch.hasContent()) {
        return Optional.empty();
      }
      var patchSync = PROTOBUF.readMessage(patch.contentAsBytes()
          .orElseThrow(), PatchSync.class);
      if (!patchSync.hasVersion()) {
        var version = new VersionSync(versionCode + 1);
        patchSync.version(version);
      }
      return Optional.of(patchSync);
    }catch (IOException exception){
      return Optional.empty();
    }
  }

  private void processActions(ActionDataSync mutation) {
    var value = mutation.value();
    if (value == null) {
      return;
    }
    var action = value.action();
    if (action != null) {
      var messageIndex = mutation.messageIndex();
      var targetContact = messageIndex.chatJid()
          .flatMap(socketHandler.store()::findContactByJid);
      var targetChat = messageIndex.chatJid()
          .flatMap(socketHandler.store()::findChatByJid);
      var targetMessage = targetChat.flatMap(chat -> socketHandler.store()
          .findMessageById(chat, mutation.messageIndex().messageId().orElse(null)));
      switch (action) {
        case ClearChatAction clearChatAction ->
            clearMessages(targetChat.orElse(null), clearChatAction);
        case ContactAction contactAction ->
            updateName(
                targetContact.orElseGet(() -> createContact(messageIndex)),
                targetChat.orElseGet(() -> createChat(messageIndex)),
                contactAction
            );
        case DeleteChatAction ignored ->
            targetChat.ifPresent(Chat::removeMessages);
        case DeleteMessageForMeAction ignored ->
            targetMessage.ifPresent(message -> targetChat.ifPresent(chat -> deleteMessage(message, chat)));
        case MarkChatAsReadAction markAction ->
            targetChat.ifPresent(chat -> chat.unreadMessagesCount(markAction.read() ? 0 : -1));
        case MuteAction muteAction ->
            targetChat.ifPresent(chat -> chat.mute(ChatMute.muted(muteAction.muteEndTimestampInSeconds())));
        case PinAction pinAction ->
            targetChat.ifPresent(chat -> chat.pinnedTimestampInSeconds(pinAction.pinned() ? mutation.value().timestamp() : 0));
        case StarAction starAction ->
            targetMessage.ifPresent(message -> message.starred(starAction.starred()));
        case ArchiveChatAction archiveChatAction ->
            targetChat.ifPresent(chat -> chat.archived(archiveChatAction.archived()));
        case TimeFormatAction timeFormatAction ->
            socketHandler.store().twentyFourHourFormat(timeFormatAction.twentyFourHourFormatEnabled());
        default -> {}
      }
      socketHandler.onAction(action, messageIndex);
    }
    var setting = value.setting();
    if (setting != null) {
      switch (setting) {
        case EphemeralSetting ephemeralSetting ->
            showEphemeralMessageWarning(ephemeralSetting);
        case LocaleSetting localeSetting ->
            socketHandler.updateLocale(localeSetting.locale(), socketHandler.store().userLocale());
        case PushNameSetting pushNameSetting ->
            socketHandler.updateUserName(pushNameSetting.name(), socketHandler.store().userName());
        case UnarchiveChatsSetting unarchiveChatsSetting ->
            socketHandler.store().unarchiveChats(unarchiveChatsSetting.unarchiveChats());
        default -> {}
      }
      socketHandler.onSetting(setting);
    }
    var features = mutation.value().primaryFeature();
    if (features != null && !features.flags().isEmpty()) {
      socketHandler.onFeatures(features);
    }
  }

  private Chat createChat(MessageIndexInfo messageIndex) {
    var chat = messageIndex.chatJid()
        .orElseThrow();
    return socketHandler.store()
        .addChat(chat);
  }

  private Contact createContact(MessageIndexInfo messageIndex) {
    var chatJid = messageIndex.chatJid()
        .orElseThrow();
    var contact = socketHandler.store()
        .addContact(chatJid);
    socketHandler.onNewContact(contact);
    return contact;
  }

  private void showEphemeralMessageWarning(EphemeralSetting ephemeralSetting) {
    var logger = System.getLogger("AppStateHandler");
    logger.log(WARNING,
        "An ephemeral status update was received as a setting. " + "Data: %s".formatted(
            ephemeralSetting) + "This should not be possible." + " Open an issue on Github please");
  }

  private void clearMessages(Chat targetChat, ClearChatAction clearChatAction) {
    if (targetChat == null) {
      return;
    }

    if(clearChatAction.messageRange().isEmpty()){
      targetChat.removeMessages();
      return;
    }

    clearChatAction.messageRange()
        .stream()
        .map(ActionMessageRangeSync::messages)
        .flatMap(Collection::stream)
        .map(SyncActionMessage::key)
        .filter(Objects::nonNull)
        .forEach(key -> targetChat.removeMessage(entry -> Objects.equals(entry.id(), key.id())));
  }

  private void updateName(Contact contact, Chat chat, ContactAction contactAction) {
    contactAction.fullName()
        .ifPresent(contact::fullName);
    contactAction.firstName()
        .ifPresent(contact::shortName);
    chat.name(contactAction.name());
  }

  private void deleteMessage(MessageInfo message, Chat chat) {
    chat.removeMessage(message);
    socketHandler.onMessageDeleted(message, false);
  }

  private SyncRecord decodePatches(PatchType name, List<PatchSync> patches,
      LTHashState state) {
    var newState = state.copy();
    var results = patches.stream()
        .map(patch -> decodePatch(name, newState, patch))
        .map(MutationsRecord::records)
        .flatMap(Collection::stream)
        .toList();
    return new SyncRecord(newState, results);
  }

  private MutationsRecord decodePatch(PatchType patchType, LTHashState newState, PatchSync patch) {
    if (patch.hasExternalMutations()) {
      Medias.download(patch.externalMutations())
          .join()
          .ifPresent(blob -> handleExternalMutation(patch, blob));
    }

    newState.version(patch.version());
    var syncMac = calculateSyncMac(patch, patchType);
    Validate.isTrue(syncMac.isEmpty() || Arrays.equals(syncMac.get(), patch.patchMac()), "sync_mac",
        HmacValidationException.class);
    var mutations = decodeMutations(patch.mutations(), newState);
    newState.hash(mutations.result()
        .hash());
    newState.indexValueMap(mutations.result()
        .indexValueMap());
    var snapshotMac = generatePatchMac(patchType, newState, patch);
    Validate.isTrue(snapshotMac.isEmpty() || Arrays.equals(snapshotMac.get(), patch.snapshotMac()),
        "patch_mac",
        HmacValidationException.class);
    return mutations;
  }

  private void handleExternalMutation(PatchSync patch, byte[] blob) {
    try {
      var mutationsSync = PROTOBUF.readMessage(blob, MutationsSync.class);
      patch.mutations().addAll(mutationsSync.mutations());
    }catch (IOException exception){
      throw new UncheckedIOException("Cannot read mutation sync", exception);
    }
  }

  private Optional<byte[]> generatePatchMac(PatchType name, LTHashState newState, PatchSync patch) {
    return getMutationKeys(patch.keyId())
        .map(mutationKeys -> generateSnapshotMac(newState.hash(), newState.version(), name,
            mutationKeys.snapshotMacKey()));
  }

  private Optional<byte[]> calculateSyncMac(PatchSync patch, PatchType patchType) {
    return getMutationKeys(patch.keyId())
        .map(mutationKeys -> generatePatchMac(patch.snapshotMac(), getSyncMutationMac(patch),
            patch.version(), patchType, mutationKeys.patchMacKey()));
  }

  private byte[] getSyncMutationMac(PatchSync patch) {
    return patch.mutations()
        .stream()
        .map(mutation -> mutation.record()
            .value()
            .blob())
        .map(Bytes::of)
        .map(binary -> binary.slice(-Specification.Signal.KEY_LENGTH))
        .reduce(Bytes.newBuffer(), Bytes::append)
        .toByteArray();
  }

  private Optional<SyncRecord> decodeSnapshot(PatchType name, SnapshotSync snapshot) {
    var mutationKeys = getMutationKeys(snapshot.keyId());
    if (mutationKeys.isEmpty()) {
      return Optional.empty();
    }
    var newState = new LTHashState(name, snapshot.version()
        .version());
    var mutations = decodeMutations(snapshot.records(), newState);
    newState.hash(mutations.result()
        .hash());
    newState.indexValueMap(mutations.result()
        .indexValueMap());
    Validate.isTrue(
        Arrays.equals(snapshot.mac(), generateSnapshotMac(newState.hash(), newState.version(), name,
            mutationKeys.get().snapshotMacKey())),
        "decode_snapshot", HmacValidationException.class);
    return Optional.of(new SyncRecord(newState, mutations.records()));
  }

  private Optional<MutationKeys> getMutationKeys(KeyId snapshot) {
    return socketHandler.keys()
        .findAppKeyById(snapshot.id())
        .map(AppStateSyncKey::keyData)
        .map(AppStateSyncKeyData::keyData)
        .map(MutationKeys::of);
  }

  private MutationsRecord decodeMutations(List<? extends Syncable> syncs, LTHashState state) {
    var generator = new LTHash(state);
    var mutations = syncs.stream()
        .map(mutation -> decodeMutation(mutation.operation(), mutation.record(), generator))
        .flatMap(Optional::stream)
        .collect(Collectors.toList());
    return new MutationsRecord(generator.finish(), mutations);
  }

  @SneakyThrows
  private Optional<ActionDataSync> decodeMutation(RecordSync.Operation operation, RecordSync sync,
      LTHash generator) {
    var mutationKeys = getMutationKeys(sync.keyId());
    if (mutationKeys.isEmpty()) {
      return Optional.empty();
    }
    var blob = Bytes.of(sync.value()
        .blob());
    var encryptedBlob = blob.cut(-Specification.Signal.KEY_LENGTH)
        .toByteArray();
    var encryptedMac = blob.slice(-Specification.Signal.KEY_LENGTH)
        .toByteArray();
    Validate.isTrue(Arrays.equals(encryptedMac,
            generateMac(operation, encryptedBlob, sync.keyId().id(), mutationKeys.get().macKey())),
        "decode_mutation", HmacValidationException.class);
    var result = AesCbc.decrypt(encryptedBlob, mutationKeys.get().encKey());
    var actionSync = PROTOBUF.readMessage(result, ActionDataSync.class);
    Validate.isTrue(Arrays.equals(sync.index()
                .blob(),
            Hmac.calculateSha256(actionSync.index(), mutationKeys.get().indexKey())),
        "decode_mutation", HmacValidationException.class);
    generator.mix(sync.index()
        .blob(), encryptedMac, operation);
    return Optional.of(actionSync);
  }

  private byte[] generateMac(RecordSync.Operation operation, byte[] data, byte[] keyId,
      byte[] key) {
    var keyData = Bytes.of(operation.content())
        .append(keyId)
        .toByteArray();
    var last = Bytes.newBuffer(Specification.Signal.MAC_LENGTH - 1)
        .append(keyData.length)
        .toByteArray();
    var total = Bytes.of(keyData, data, last)
        .toByteArray();
    return Bytes.of(Hmac.calculateSha512(total, key))
        .cut(Specification.Signal.KEY_LENGTH)
        .toByteArray();
  }

  private byte[] generateSnapshotMac(byte[] ltHash, long version, PatchType patchType, byte[] key) {
    var total = Bytes.of(ltHash)
        .append(BytesHelper.longToBytes(version))
        .append(patchType.toString()
            .getBytes(StandardCharsets.UTF_8))
        .toByteArray();
    return Hmac.calculateSha256(total, key);
  }

  private byte[] generatePatchMac(byte[] snapshotMac, byte[] valueMac, long version,
      PatchType patchType,
      byte[] key) {
    var total = Bytes.of(snapshotMac)
        .append(valueMac)
        .append(BytesHelper.longToBytes(version))
        .append(patchType.toString()
            .getBytes(StandardCharsets.UTF_8))
        .toByteArray();
    return Hmac.calculateSha256(total, key);
  }

  @Override
  protected void awaitLatch() {
    if(socketHandler.store().initialSync()){
      return;
    }

    super.awaitLatch();
  }

  @Override
  protected void dispose() {
    super.dispose();
    attempts.clear();
    completeLatch();
  }

  private record SyncRecord(LTHashState state, List<ActionDataSync> records) {

  }

  private record SnapshotSyncRecord(PatchType patchType, SnapshotSync snapshot,
                                    List<PatchSync> patches,
                                    boolean hasMore) {

    public boolean hasSnapshot() {
      return snapshot != null;
    }

    public boolean hasPatches() {
      return patches != null && !patches.isEmpty();
    }
  }

  private record MutationsRecord(LTHash.Result result, List<ActionDataSync> records) {

  }

  private record PatchChunk(PatchType patchType, List<ActionDataSync> records, boolean hasMore) {

  }

  private record PushRequest(PatchRequest patch, LTHashState oldState, LTHashState newState,
                             PatchSync sync) {

  }
}
