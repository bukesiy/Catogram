/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.SparseArray;

import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

public class ChatObject {

    public static final int CHAT_TYPE_CHAT = 0;
    public static final int CHAT_TYPE_CHANNEL = 2;
    public static final int CHAT_TYPE_USER = 3;
    public static final int CHAT_TYPE_MEGAGROUP = 4;

    public static final int ACTION_PIN = 0;
    public static final int ACTION_CHANGE_INFO = 1;
    public static final int ACTION_BLOCK_USERS = 2;
    public static final int ACTION_INVITE = 3;
    public static final int ACTION_ADD_ADMINS = 4;
    public static final int ACTION_POST = 5;
    public static final int ACTION_SEND = 6;
    public static final int ACTION_SEND_MEDIA = 7;
    public static final int ACTION_SEND_STICKERS = 8;
    public static final int ACTION_EMBED_LINKS = 9;
    public static final int ACTION_SEND_POLLS = 10;
    public static final int ACTION_VIEW = 11;
    public static final int ACTION_EDIT_MESSAGES = 12;
    public static final int ACTION_DELETE_MESSAGES = 13;
    public static final int ACTION_MANAGE_CALLS = 14;

    private static final int MAX_PARTICIPANTS_COUNT = 5000;

    public static class Call {
        public TLRPC.GroupCall call;
        public int chatId;
        public SparseArray<TLRPC.TL_groupCallParticipant> participants = new SparseArray<>();
        public ArrayList<TLRPC.TL_groupCallParticipant> sortedParticipants = new ArrayList<>();
        public ArrayList<Integer> invitedUsers = new ArrayList<>();
        public HashSet<Integer> invitedUsersMap = new HashSet<>();
        public SparseArray<TLRPC.TL_groupCallParticipant> participantsBySources = new SparseArray<>();
        private String nextLoadOffset;
        public boolean membersLoadEndReached;
        public boolean loadingMembers;
        public boolean reloadingMembers;
        public boolean recording;
        public AccountInstance currentAccount;
        public int speakingMembersCount;
        private Runnable typingUpdateRunnable = () -> {
            typingUpdateRunnableScheduled = false;
            checkOnlineParticipants();
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallTypingsUpdated);
        };
        private boolean typingUpdateRunnableScheduled;
        private int lastLoadGuid;
        private HashSet<Integer> loadingGuids = new HashSet<>();
        private ArrayList<TLRPC.TL_updateGroupCallParticipants> updatesQueue = new ArrayList<>();
        private long updatesStartWaitTime;

        public TLRPC.Peer selfPeer;

        private HashSet<Integer> loadingUids = new HashSet<>();
        private HashSet<Integer> loadingSsrcs = new HashSet<>();

        private Runnable checkQueueRunnable;

        private long lastGroupCallReloadTime;
        private boolean loadingGroupCall;

        public void setCall(AccountInstance account, int chatId, TLRPC.TL_phone_groupCall groupCall) {
            this.chatId = chatId;
            currentAccount = account;
            call = groupCall.call;
            recording = call.record_start_date != 0;
            int date = Integer.MAX_VALUE;
            for (int a = 0, N = groupCall.participants.size(); a < N; a++) {
                TLRPC.TL_groupCallParticipant participant = groupCall.participants.get(a);
                participants.put(MessageObject.getPeerId(participant.peer), participant);
                sortedParticipants.add(participant);
                if (participant.source != 0) {
                    participantsBySources.put(participant.source, participant);
                }
                date = Math.min(date, participant.date);
            }
            sortParticipants();
            nextLoadOffset = groupCall.participants_next_offset;
            loadMembers(true);
        }

        public void addSelfDummyParticipant(boolean notify) {
            int selfId = getSelfId();
            if (participants.indexOfKey(selfId) >= 0) {
                return;
            }
            TLRPC.TL_groupCallParticipant selfDummyParticipant = new TLRPC.TL_groupCallParticipant();
            selfDummyParticipant.peer = selfPeer;
            selfDummyParticipant.muted = true;
            selfDummyParticipant.self = true;
            TLRPC.Chat chat = currentAccount.getMessagesController().getChat(chatId);
            selfDummyParticipant.can_self_unmute = !call.join_muted || ChatObject.canManageCalls(chat);
            selfDummyParticipant.date = currentAccount.getConnectionsManager().getCurrentTime();
            if (ChatObject.canManageCalls(chat) || !ChatObject.isChannel(chat) || chat.megagroup || selfDummyParticipant.can_self_unmute) {
                selfDummyParticipant.active_date = currentAccount.getConnectionsManager().getCurrentTime();
            }
            if (selfId > 0) {
                TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount.getCurrentAccount()).getUserFull(selfId);
                if (userFull != null) {
                    selfDummyParticipant.about = userFull.about;
                }
            } else {
                TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount.getCurrentAccount()).getChatFull(-selfId);
                if (chatFull != null) {
                    selfDummyParticipant.about = chatFull.about;
                }
            }
            participants.put(selfId, selfDummyParticipant);
            sortedParticipants.add(selfDummyParticipant);
            sortParticipants();
            if (notify) {
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
            }
        }

        public void migrateToChat(TLRPC.Chat chat) {
            chatId = chat.id;
            VoIPService voIPService = VoIPService.getSharedInstance();
            if (voIPService != null && voIPService.getAccount() == currentAccount.getCurrentAccount() && voIPService.getChat() != null && voIPService.getChat().id == -chatId) {
                voIPService.migrateToChat(chat);
            }
        }

        public boolean shouldShowPanel() {
            return call.participants_count > 0 || isScheduled();
        }

        public boolean isScheduled() {
            return (call.flags & 128) != 0;
        }

        private int getSelfId() {
            int selfId;
            if (selfPeer != null) {
                return MessageObject.getPeerId(selfPeer);
            } else {
                return currentAccount.getUserConfig().getClientUserId();
            }
        }

        public void loadMembers(boolean fromBegin) {
            if (fromBegin) {
                if (reloadingMembers) {
                    return;
                }
                membersLoadEndReached = false;
                nextLoadOffset = null;
            }
            if (membersLoadEndReached || sortedParticipants.size() > MAX_PARTICIPANTS_COUNT) {
                return;
            }
            if (fromBegin) {
                reloadingMembers = true;
            }
            loadingMembers = true;
            TLRPC.TL_phone_getGroupParticipants req = new TLRPC.TL_phone_getGroupParticipants();
            req.call = getInputGroupCall();
            req.offset = nextLoadOffset != null ? nextLoadOffset : "";
            req.limit = 20;
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                loadingMembers = false;
                if (fromBegin) {
                    reloadingMembers = false;
                }
                if (response != null) {
                    TLRPC.TL_phone_groupParticipants groupParticipants = (TLRPC.TL_phone_groupParticipants) response;
                    currentAccount.getMessagesController().putUsers(groupParticipants.users, false);
                    currentAccount.getMessagesController().putChats(groupParticipants.chats, false);
                    SparseArray<TLRPC.TL_groupCallParticipant> old = null;
                    int selfId = getSelfId();
                    TLRPC.TL_groupCallParticipant oldSelf = participants.get(selfId);
                    if (TextUtils.isEmpty(req.offset)) {
                        if (participants.size() != 0) {
                            old = participants;
                            participants = new SparseArray<>();
                        } else {
                            participants.clear();
                        }
                        sortedParticipants.clear();
                        participantsBySources.clear();
                        loadingGuids.clear();
                    }
                    nextLoadOffset = groupParticipants.next_offset;
                    if (groupParticipants.participants.isEmpty() || TextUtils.isEmpty(nextLoadOffset)) {
                        membersLoadEndReached = true;
                    }
                    if (TextUtils.isEmpty(req.offset)) {
                        call.version = groupParticipants.version;
                        call.participants_count = groupParticipants.count;
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("new participants count " + call.participants_count);
                        }
                    }
                    long time = SystemClock.elapsedRealtime();
                    currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.applyGroupCallVisibleParticipants, time);
                    boolean hasSelf = false;
                    for (int a = 0, N = groupParticipants.participants.size(); a <= N; a++) {
                        TLRPC.TL_groupCallParticipant participant;
                        if (a == N) {
                            if (fromBegin && oldSelf != null && !hasSelf) {
                                participant = oldSelf;
                            } else {
                                continue;
                            }
                        } else {
                            participant = groupParticipants.participants.get(a);
                            if (participant.self) {
                                hasSelf = true;
                            }
                        }
                        TLRPC.TL_groupCallParticipant oldParticipant = participants.get(MessageObject.getPeerId(participant.peer));
                        if (oldParticipant != null) {
                            sortedParticipants.remove(oldParticipant);
                            if (oldParticipant.source != 0) {
                                participantsBySources.remove(oldParticipant.source);
                            }
                            if (oldParticipant.self) {
                                participant.lastTypingDate = oldParticipant.active_date;
                            } else {
                                participant.lastTypingDate = Math.max(participant.active_date, oldParticipant.active_date);
                            }
                            if (time != participant.lastVisibleDate) {
                                participant.active_date = participant.lastTypingDate;
                            }
                        } else if (old != null) {
                            oldParticipant = old.get(MessageObject.getPeerId(participant.peer));
                            if (oldParticipant != null) {
                                if (oldParticipant.self) {
                                    participant.lastTypingDate = oldParticipant.active_date;
                                } else {
                                    participant.lastTypingDate = Math.max(participant.active_date, oldParticipant.active_date);
                                }
                                if (time != participant.lastVisibleDate) {
                                    participant.active_date = participant.lastTypingDate;
                                } else {
                                    participant.active_date = oldParticipant.active_date;
                                }
                            }
                        }
                        participants.put(MessageObject.getPeerId(participant.peer), participant);
                        sortedParticipants.add(participant);
                        if (participant.source != 0) {
                            participantsBySources.put(participant.source, participant);
                        }
                    }
                    if (call.participants_count < participants.size()) {
                        call.participants_count = participants.size();
                    }
                    sortParticipants();
                    currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
                    setParticiapantsVolume();
                }
            }));
        }

        private void setParticiapantsVolume() {
            VoIPService voIPService = VoIPService.getSharedInstance();
            if (voIPService != null && voIPService.getAccount() == currentAccount.getCurrentAccount() && voIPService.getChat() != null && voIPService.getChat().id == -chatId) {
                voIPService.setParticipantsVolume();
            }
        }

        public void setTitle(String title) {
            TLRPC.TL_phone_editGroupCallTitle req = new TLRPC.TL_phone_editGroupCallTitle();
            req.call = getInputGroupCall();
            req.title = title;
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> {
                if (response != null) {
                    final TLRPC.Updates res = (TLRPC.Updates) response;
                    currentAccount.getMessagesController().processUpdates(res, false);
                }
            });
        }

        public void addInvitedUser(int uid) {
            if (participants.get(uid) != null || invitedUsersMap.contains(uid)) {
                return;
            }
            invitedUsersMap.add(uid);
            invitedUsers.add(uid);
        }

        public void processTypingsUpdate(AccountInstance accountInstance, ArrayList<Integer> uids, int date) {
            boolean updated = false;
            ArrayList<Integer> participantsToLoad = null;
            long time = SystemClock.elapsedRealtime();
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.applyGroupCallVisibleParticipants, time);
            for (int a = 0, N = uids.size(); a < N; a++) {
                Integer id = uids.get(a);
                TLRPC.TL_groupCallParticipant participant = participants.get(id);
                if (participant != null) {
                    if (date - participant.lastTypingDate > 10) {
                        if (participant.lastVisibleDate != date) {
                            participant.active_date = date;
                        }
                        participant.lastTypingDate = date;
                        updated = true;
                    }
                } else {
                    if (participantsToLoad == null) {
                        participantsToLoad = new ArrayList<>();
                    }
                    participantsToLoad.add(id);
                }
            }
            if (participantsToLoad != null) {
                loadUnknownParticipants(participantsToLoad, true, null);
            }
            if (updated) {
                sortParticipants();
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
            }
        }

        private void loadUnknownParticipants(ArrayList<Integer> participantsToLoad, boolean isIds, OnParticipantsLoad onLoad) {
            HashSet<Integer> set = isIds ? loadingUids : loadingSsrcs;
            for (int a = 0, N = participantsToLoad.size(); a < N; a++) {
                if (set.contains(participantsToLoad.get(a))) {
                    participantsToLoad.remove(a);
                    a--;
                    N--;
                }
            }
            if (participantsToLoad.isEmpty()) {
                return;
            }
            int guid = ++lastLoadGuid;
            loadingGuids.add(guid);
            set.addAll(participantsToLoad);
            TLRPC.TL_phone_getGroupParticipants req = new TLRPC.TL_phone_getGroupParticipants();
            req.call = getInputGroupCall();
            if (isIds) {
                for (int a = 0, N = participantsToLoad.size(); a < N; a++) {
                    Integer uid = participantsToLoad.get(a);
                    if (uid > 0) {
                        TLRPC.TL_inputPeerUser peerUser = new TLRPC.TL_inputPeerUser();
                        peerUser.user_id = uid;
                        req.ids.add(peerUser);
                    } else {
                        TLRPC.Chat chat = currentAccount.getMessagesController().getChat(-uid);
                        TLRPC.InputPeer inputPeer;
                        if (chat == null || ChatObject.isChannel(chat)) {
                            inputPeer = new TLRPC.TL_inputPeerChannel();
                            inputPeer.channel_id = -uid;
                        } else {
                            inputPeer = new TLRPC.TL_inputPeerChat();
                            inputPeer.chat_id = -uid;
                        }
                        req.ids.add(inputPeer);
                    }
                }
            } else {
                req.sources = participantsToLoad;
            }
            req.offset = "";
            req.limit = 100;
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!loadingGuids.remove(guid)) {
                    return;
                }
                if (response != null) {
                    TLRPC.TL_phone_groupParticipants groupParticipants = (TLRPC.TL_phone_groupParticipants) response;
                    currentAccount.getMessagesController().putUsers(groupParticipants.users, false);
                    currentAccount.getMessagesController().putChats(groupParticipants.chats, false);
                    for (int a = 0, N = groupParticipants.participants.size(); a < N; a++) {
                        TLRPC.TL_groupCallParticipant participant = groupParticipants.participants.get(a);
                        int pid = MessageObject.getPeerId(participant.peer);
                        TLRPC.TL_groupCallParticipant oldParticipant = participants.get(pid);
                        if (oldParticipant != null) {
                            sortedParticipants.remove(oldParticipant);
                            if (oldParticipant.source != 0) {
                                participantsBySources.remove(oldParticipant.source);
                            }
                        }
                        participants.put(pid, participant);
                        sortedParticipants.add(participant);
                        if (participant.source != 0) {
                            participantsBySources.put(participant.source, participant);
                        }
                        if (invitedUsersMap.contains(pid)) {
                            Integer id = pid;
                            invitedUsersMap.remove(id);
                            invitedUsers.remove(id);
                        }
                    }
                    if (call.participants_count < participants.size()) {
                        call.participants_count = participants.size();
                    }
                    sortParticipants();
                    currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
                    if (onLoad != null) {
                        onLoad.onLoad(participantsToLoad);
                    } else {
                        setParticiapantsVolume();
                    }
                }
                set.removeAll(participantsToLoad);
            }));
        }

        public void processVoiceLevelsUpdate(int[] ssrc, float[] levels, boolean[] voice) {
            boolean updated = false;
            int currentTime = currentAccount.getConnectionsManager().getCurrentTime();
            ArrayList<Integer> participantsToLoad = null;
            long time = SystemClock.elapsedRealtime();
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.applyGroupCallVisibleParticipants, time);
            for (int a = 0; a < ssrc.length; a++) {
                TLRPC.TL_groupCallParticipant participant;
                if (ssrc[a] == 0) {
                    participant = participants.get(getSelfId());
                } else {
                    participant = participantsBySources.get(ssrc[a]);
                }
                if (participant != null) {
                    participant.hasVoice = voice[a];
                    if (voice[a] || time - participant.lastVoiceUpdateTime > 500) {
                        participant.hasVoiceDelayed = voice[a];
                        participant.lastVoiceUpdateTime = time;
                    }
                    if (levels[a] > 0.1f) {
                        if (voice[a] && participant.lastTypingDate + 1 < currentTime) {
                            if (time != participant.lastVisibleDate) {
                                participant.active_date = currentTime;
                            }
                            participant.lastTypingDate = currentTime;
                            updated = true;
                        }
                        participant.lastSpeakTime = SystemClock.uptimeMillis();
                        participant.amplitude = levels[a];
                    } else {
                        participant.amplitude = 0;
                    }
                } else if (ssrc[a] != 0) {
                    if (participantsToLoad == null) {
                        participantsToLoad = new ArrayList<>();
                    }
                    participantsToLoad.add(ssrc[a]);
                }
            }
            if (participantsToLoad != null) {
                loadUnknownParticipants(participantsToLoad, false, null);
            }
            if (updated) {
                sortParticipants();
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
            }
        }

        public interface OnParticipantsLoad {
            void onLoad(ArrayList<Integer> ssrcs);
        }

        public void processUnknownVideoParticipants(int[] ssrc, OnParticipantsLoad onLoad) {
            ArrayList<Integer> participantsToLoad = null;
            for (int a = 0; a < ssrc.length; a++) {
                TLRPC.TL_groupCallParticipant participant = participantsBySources.get(ssrc[a]);
                if (participant == null) {
                    if (participantsToLoad == null) {
                        participantsToLoad = new ArrayList<>();
                    }
                    participantsToLoad.add(ssrc[a]);
                }
            }
            if (participantsToLoad != null) {
                loadUnknownParticipants(participantsToLoad, false, onLoad);
            }
        }

        private int isValidUpdate(TLRPC.TL_updateGroupCallParticipants update) {
            if (call.version + 1 == update.version || call.version == update.version) {
                return 0;
            } else if (call.version < update.version) {
                return 1;
            } else {
                return 2;
            }
        }

        public void setSelfPeer(TLRPC.InputPeer peer) {
            if (peer == null) {
                selfPeer = null;
            } else {
                if (peer instanceof TLRPC.TL_inputPeerUser) {
                    selfPeer = new TLRPC.TL_peerUser();
                    selfPeer.user_id = peer.user_id;
                } else if (peer instanceof TLRPC.TL_inputPeerChat) {
                    selfPeer = new TLRPC.TL_peerChat();
                    selfPeer.chat_id = peer.chat_id;
                } else {
                    selfPeer = new TLRPC.TL_peerChannel();
                    selfPeer.channel_id = peer.channel_id;
                }
            }
        }

        private void processUpdatesQueue() {
            Collections.sort(updatesQueue, (updates, updates2) -> AndroidUtilities.compare(updates.version, updates2.version));
            if (updatesQueue != null && !updatesQueue.isEmpty()) {
                boolean anyProceed = false;
                for (int a = 0; a < updatesQueue.size(); a++) {
                    TLRPC.TL_updateGroupCallParticipants update = updatesQueue.get(a);
                    int updateState = isValidUpdate(update);
                    if (updateState == 0) {
                        processParticipantsUpdate(update, true);
                        anyProceed = true;
                        updatesQueue.remove(a);
                        a--;
                    } else if (updateState == 1) {
                        if (updatesStartWaitTime != 0 && (anyProceed || Math.abs(System.currentTimeMillis() - updatesStartWaitTime) <= 1500)) {
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("HOLE IN GROUP CALL UPDATES QUEUE - will wait more time");
                            }
                            if (anyProceed) {
                                updatesStartWaitTime = System.currentTimeMillis();
                            }
                        } else {
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("HOLE IN GROUP CALL UPDATES QUEUE - reload participants");
                            }
                            updatesStartWaitTime = 0;
                            updatesQueue.clear();
                            nextLoadOffset = null;
                            loadMembers(true);
                        }
                        return;
                    } else {
                        updatesQueue.remove(a);
                        a--;
                    }
                }
                updatesQueue.clear();
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("GROUP CALL UPDATES QUEUE PROCEED - OK");
                }
            }
            updatesStartWaitTime = 0;
        }

        private void checkQueue() {
            checkQueueRunnable = null;
            if (updatesStartWaitTime != 0 && (System.currentTimeMillis() - updatesStartWaitTime) >= 1500) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("QUEUE GROUP CALL UPDATES WAIT TIMEOUT - CHECK QUEUE");
                }
                processUpdatesQueue();
            }
            if (!updatesQueue.isEmpty()) {
                AndroidUtilities.runOnUIThread(checkQueueRunnable = this::checkQueue, 1000);
            }
        }

        private void loadGroupCall() {
            if (loadingGroupCall || SystemClock.elapsedRealtime() - lastGroupCallReloadTime < 30000) {
                return;
            }
            loadingGroupCall = true;
            TLRPC.TL_phone_getGroupParticipants req = new TLRPC.TL_phone_getGroupParticipants();
            req.call = getInputGroupCall();
            req.offset = "";
            req.limit = 1;
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                lastGroupCallReloadTime = SystemClock.elapsedRealtime();
                loadingGroupCall = false;
                if (response != null) {
                    TLRPC.TL_phone_groupParticipants res = (TLRPC.TL_phone_groupParticipants) response;
                    currentAccount.getMessagesController().putUsers(res.users, false);
                    currentAccount.getMessagesController().putChats(res.chats, false);
                    if (call.participants_count != res.count) {
                        call.participants_count = res.count;
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("new participants reload count " + call.participants_count);
                        }
                        currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
                    }
                }
            }));
        }

        public void processParticipantsUpdate(TLRPC.TL_updateGroupCallParticipants update, boolean fromQueue) {
            if (!fromQueue) {
                boolean versioned = false;
                for (int a = 0, N = update.participants.size(); a < N; a++) {
                    TLRPC.TL_groupCallParticipant participant = update.participants.get(a);
                    if (participant.versioned) {
                        versioned = true;
                        break;
                    }
                }
                if (versioned && call.version + 1 < update.version) {
                    if (reloadingMembers || updatesStartWaitTime == 0 || Math.abs(System.currentTimeMillis() - updatesStartWaitTime) <= 1500) {
                        if (updatesStartWaitTime == 0) {
                            updatesStartWaitTime = System.currentTimeMillis();
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("add TL_updateGroupCallParticipants to queue " + update.version);
                        }
                        updatesQueue.add(update);
                        if (checkQueueRunnable == null) {
                            AndroidUtilities.runOnUIThread(checkQueueRunnable = this::checkQueue, 1500);
                        }
                    } else {
                        nextLoadOffset = null;
                        loadMembers(true);
                    }
                    return;
                }
                if (versioned && update.version < call.version) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("ignore processParticipantsUpdate because of version");
                    }
                    return;
                }
            }
            boolean reloadCall = false;
            boolean updated = false;
            boolean selfUpdated = false;
            boolean changedOrAdded = false;
            int selfId = getSelfId();
            long time = SystemClock.elapsedRealtime();
            int lastParticipantDate;
            if (!sortedParticipants.isEmpty()) {
                lastParticipantDate = sortedParticipants.get(sortedParticipants.size() - 1).date;
            } else {
                lastParticipantDate = 0;
            }
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.applyGroupCallVisibleParticipants, time);
            for (int a = 0, N = update.participants.size(); a < N; a++) {
                TLRPC.TL_groupCallParticipant participant = update.participants.get(a);
                int pid = MessageObject.getPeerId(participant.peer);
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("process participant " + pid + " left = " + participant.left + " versioned " + participant.versioned + " flags = " + participant.flags + " self = " + selfId + " volume = " + participant.volume);
                }
                TLRPC.TL_groupCallParticipant oldParticipant = participants.get(pid);
                if (participant.left) {
                    if (oldParticipant == null && update.version == call.version) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("unknowd participant left, reload call");
                        }
                        reloadCall = true;
                    }
                    if (oldParticipant != null) {
                        participants.remove(pid);
                        if (participant.source != 0) {
                            participantsBySources.remove(participant.source);
                        }
                        sortedParticipants.remove(oldParticipant);
                    }
                    call.participants_count--;
                    if (call.participants_count < 0) {
                        call.participants_count = 0;
                    }
                    updated = true;
                } else {
                    if (invitedUsersMap.contains(pid)) {
                        Integer id = pid;
                        invitedUsersMap.remove(id);
                        invitedUsers.remove(id);
                    }
                    if (oldParticipant != null) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("new participant, update old");
                        }
                        oldParticipant.muted = participant.muted;
                        if (!participant.min) {
                            oldParticipant.volume = participant.volume;
                            oldParticipant.muted_by_you = participant.muted_by_you;
                        } else {
                            if ((participant.flags & 128) != 0 && (oldParticipant.flags & 128) == 0) {
                                participant.flags &=~ 128;
                            }
                            if (participant.volume_by_admin && oldParticipant.volume_by_admin) {
                                oldParticipant.volume = participant.volume;
                            }
                        }
                        oldParticipant.flags = participant.flags;
                        oldParticipant.can_self_unmute = participant.can_self_unmute;
                        if (oldParticipant.raise_hand_rating == 0 && participant.raise_hand_rating != 0) {
                            oldParticipant.lastRaiseHandDate = SystemClock.elapsedRealtime();
                        }
                        oldParticipant.raise_hand_rating = participant.raise_hand_rating;
                        oldParticipant.date = participant.date;
                        oldParticipant.lastTypingDate = Math.max(oldParticipant.active_date, participant.active_date);
                        if (time != oldParticipant.lastVisibleDate) {
                            oldParticipant.active_date = oldParticipant.lastTypingDate;
                        }
                        if (oldParticipant.source != participant.source) {
                            if (oldParticipant.source != 0) {
                                participantsBySources.remove(oldParticipant.source);
                            }
                            oldParticipant.source = participant.source;
                            if (oldParticipant.source != 0) {
                                participantsBySources.put(oldParticipant.source, oldParticipant);
                            }
                        }
                    } else {
                        if (participant.just_joined) {
                            call.participants_count++;
                            if (update.version == call.version) {
                                reloadCall = true;
                                if (BuildVars.LOGS_ENABLED) {
                                    FileLog.d("new participant, just joned, reload call");
                                }
                            } else {
                                if (BuildVars.LOGS_ENABLED) {
                                    FileLog.d("new participant, just joned");
                                }
                            }
                        }
                        if (participant.raise_hand_rating != 0) {
                            participant.lastRaiseHandDate = SystemClock.elapsedRealtime();
                        }
                        if (pid == selfId || sortedParticipants.size() < 20 || participant.date <= lastParticipantDate || participant.active_date != 0 || participant.can_self_unmute || !participant.muted || !participant.min || membersLoadEndReached) {
                            sortedParticipants.add(participant);
                        }
                        participants.put(pid, participant);
                        if (participant.source != 0) {
                            participantsBySources.put(participant.source, participant);
                        }
                    }
                    if (pid == selfId && participant.active_date == 0 && (participant.can_self_unmute || !participant.muted)) {
                        participant.active_date = currentAccount.getConnectionsManager().getCurrentTime();
                    }
                    changedOrAdded = true;
                    updated = true;
                }
                if (pid == selfId) {
                    selfUpdated = true;
                }
            }
            if (update.version > call.version) {
                call.version = update.version;
                if (!fromQueue) {
                    processUpdatesQueue();
                }
            }
            if (call.participants_count < participants.size()) {
                call.participants_count = participants.size();
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("new participants count after update " + call.participants_count);
            }
            if (reloadCall) {
                loadGroupCall();
            }
            if (updated) {
                if (changedOrAdded) {
                    sortParticipants();
                }
                currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, selfUpdated);
            }
        }

        public void processGroupCallUpdate(AccountInstance accountInstance, TLRPC.TL_updateGroupCall update) {
            if (call.version < update.call.version) {
                nextLoadOffset = null;
                loadMembers(true);
            }
            call = update.call;
            recording = call.record_start_date != 0;
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
        }

        public TLRPC.TL_inputGroupCall getInputGroupCall() {
            TLRPC.TL_inputGroupCall inputGroupCall = new TLRPC.TL_inputGroupCall();
            inputGroupCall.id = call.id;
            inputGroupCall.access_hash = call.access_hash;
            return inputGroupCall;
        }

        private void sortParticipants() {
            TLRPC.Chat chat = currentAccount.getMessagesController().getChat(chatId);
            boolean isAdmin = ChatObject.canManageCalls(chat);
            int selfId = getSelfId();
            Collections.sort(sortedParticipants, (o1, o2) -> {
                if (o1.active_date != 0 && o2.active_date != 0) {
                    return Integer.compare(o2.active_date, o1.active_date);
                } else if (o1.active_date != 0) {
                    return -1;
                } else if (o2.active_date != 0) {
                    return 1;
                }
                if (MessageObject.getPeerId(o1.peer) == selfId) {
                    return -1;
                } else if (MessageObject.getPeerId(o2.peer) == selfId) {
                    return 1;
                }
                if (isAdmin) {
                    if (o1.raise_hand_rating != 0 && o2.raise_hand_rating != 0) {
                        return Long.compare(o2.raise_hand_rating, o1.raise_hand_rating);
                    } else if (o1.raise_hand_rating != 0) {
                        return -1;
                    } else if (o2.raise_hand_rating != 0) {
                        return 1;
                    }
                }
                if (call.join_date_asc) {
                    return Integer.compare(o1.date, o2.date);
                } else {
                    return Integer.compare(o2.date, o1.date);
                }
            });
            if (sortedParticipants.size() > MAX_PARTICIPANTS_COUNT && (!ChatObject.canManageCalls(chat) || sortedParticipants.get(sortedParticipants.size() - 1).raise_hand_rating == 0)) {
                for (int a = MAX_PARTICIPANTS_COUNT, N = sortedParticipants.size(); a < N; a++) {
                    TLRPC.TL_groupCallParticipant p = sortedParticipants.get(MAX_PARTICIPANTS_COUNT);
                    if (p.raise_hand_rating != 0) {
                        continue;
                    }
                    participantsBySources.remove(p.source);
                    participants.remove(MessageObject.getPeerId(p.peer));
                    sortedParticipants.remove(MAX_PARTICIPANTS_COUNT);
                }
            }
            checkOnlineParticipants();
        }

        public void saveActiveDates() {
            for (int a = 0, N = sortedParticipants.size(); a < N; a++) {
                TLRPC.TL_groupCallParticipant p = sortedParticipants.get(a);
                p.lastActiveDate = p.active_date;
            }
        }

        private void checkOnlineParticipants() {
            if (typingUpdateRunnableScheduled) {
                AndroidUtilities.cancelRunOnUIThread(typingUpdateRunnable);
                typingUpdateRunnableScheduled = false;
            }
            speakingMembersCount = 0;
            int currentTime = currentAccount.getConnectionsManager().getCurrentTime();
            int minDiff = Integer.MAX_VALUE;
            for (int a = 0, N = sortedParticipants.size(); a < N; a++) {
                TLRPC.TL_groupCallParticipant participant = sortedParticipants.get(a);
                int diff = currentTime - participant.active_date;
                if (diff < 5) {
                    speakingMembersCount++;
                    minDiff = Math.min(diff, minDiff);
                }
                if (Math.max(participant.date, participant.active_date) <= currentTime - 5) {
                    break;
                }
            }
            if (minDiff != Integer.MAX_VALUE) {
                AndroidUtilities.runOnUIThread(typingUpdateRunnable, minDiff * 1000);
                typingUpdateRunnableScheduled = true;
            }
        }

        public void toggleRecord(String title) {
            recording = !recording;
            TLRPC.TL_phone_toggleGroupCallRecord req = new TLRPC.TL_phone_toggleGroupCallRecord();
            req.call = getInputGroupCall();
            req.start = recording;
            if (title != null) {
                req.title = title;
                req.flags |= 2;
            }
            currentAccount.getConnectionsManager().sendRequest(req, (response, error) -> {
                if (response != null) {
                    final TLRPC.Updates res = (TLRPC.Updates) response;
                    currentAccount.getMessagesController().processUpdates(res, false);
                }
            });
            currentAccount.getNotificationCenter().postNotificationName(NotificationCenter.groupCallUpdated, chatId, call.id, false);
        }
    }

    public static int getParticipantVolume(TLRPC.TL_groupCallParticipant participant) {
        return ((participant.flags & 128) != 0 ? participant.volume : 10000);
    }

    private static boolean isBannableAction(int action) {
        switch (action) {
            case ACTION_PIN:
            case ACTION_CHANGE_INFO:
            case ACTION_INVITE:
            case ACTION_SEND:
            case ACTION_SEND_MEDIA:
            case ACTION_SEND_STICKERS:
            case ACTION_EMBED_LINKS:
            case ACTION_SEND_POLLS:
            case ACTION_VIEW:
                return true;
        }
        return false;
    }

    private static boolean isAdminAction(int action) {
        switch (action) {
            case ACTION_PIN:
            case ACTION_CHANGE_INFO:
            case ACTION_INVITE:
            case ACTION_ADD_ADMINS:
            case ACTION_POST:
            case ACTION_EDIT_MESSAGES:
            case ACTION_DELETE_MESSAGES:
            case ACTION_BLOCK_USERS:
                return true;
        }
        return false;
    }

    private static boolean getBannedRight(TLRPC.TL_chatBannedRights rights, int action) {
        if (rights == null) {
            return false;
        }
        boolean value;
        switch (action) {
            case ACTION_PIN:
                return rights.pin_messages;
            case ACTION_CHANGE_INFO:
                return rights.change_info;
            case ACTION_INVITE:
                return rights.invite_users;
            case ACTION_SEND:
                return rights.send_messages;
            case ACTION_SEND_MEDIA:
                return rights.send_media;
            case ACTION_SEND_STICKERS:
                return rights.send_stickers;
            case ACTION_EMBED_LINKS:
                return rights.embed_links;
            case ACTION_SEND_POLLS:
                return rights.send_polls;
            case ACTION_VIEW:
                return rights.view_messages;
        }
        return false;
    }

    public static boolean isActionBannedByDefault(TLRPC.Chat chat, int action) {
        if (getBannedRight(chat.banned_rights, action)) {
            return false;
        }
        return getBannedRight(chat.default_banned_rights, action);
    }

    public static boolean isActionBanned(TLRPC.Chat chat, int action) {
        return chat != null && (getBannedRight(chat.banned_rights, action) || getBannedRight(chat.default_banned_rights, action));
    }

    public static boolean canUserDoAdminAction(TLRPC.Chat chat, int action) {
        if (chat == null) {
            return false;
        }
        if (chat.creator) {
            return true;
        }
        if (chat.admin_rights != null) {
            boolean value;
            switch (action) {
                case ACTION_PIN:
                    value = chat.admin_rights.pin_messages;
                    break;
                case ACTION_CHANGE_INFO:
                    value = chat.admin_rights.change_info;
                    break;
                case ACTION_INVITE:
                    value = chat.admin_rights.invite_users;
                    break;
                case ACTION_ADD_ADMINS:
                    value = chat.admin_rights.add_admins;
                    break;
                case ACTION_POST:
                    value = chat.admin_rights.post_messages;
                    break;
                case ACTION_EDIT_MESSAGES:
                    value = chat.admin_rights.edit_messages;
                    break;
                case ACTION_DELETE_MESSAGES:
                    value = chat.admin_rights.delete_messages;
                    break;
                case ACTION_BLOCK_USERS:
                    value = chat.admin_rights.ban_users;
                    break;
                case ACTION_MANAGE_CALLS:
                    value = chat.admin_rights.manage_call;
                    break;
                default:
                    value = false;
                    break;
            }
            if (value) {
                return true;
            }
        }
        return false;
    }

    public static boolean canUserDoAction(TLRPC.Chat chat, int action) {
        if (chat == null) {
            return true;
        }
        if (canUserDoAdminAction(chat, action)) {
            return true;
        }
        if (getBannedRight(chat.banned_rights, action)) {
            return false;
        }
        if (isBannableAction(action)) {
            if (chat.admin_rights != null && !isAdminAction(action)) {
                return true;
            }
            if (chat.default_banned_rights == null && (
                    chat instanceof TLRPC.TL_chat_layer92 ||
                    chat instanceof TLRPC.TL_chat_old ||
                    chat instanceof TLRPC.TL_chat_old2 ||
                    chat instanceof TLRPC.TL_channel_layer92 ||
                    chat instanceof TLRPC.TL_channel_layer77 ||
                    chat instanceof TLRPC.TL_channel_layer72 ||
                    chat instanceof TLRPC.TL_channel_layer67 ||
                    chat instanceof TLRPC.TL_channel_layer48 ||
                    chat instanceof TLRPC.TL_channel_old)) {
                return true;
            }
            return chat.default_banned_rights != null && !getBannedRight(chat.default_banned_rights, action);
        }
        return false;
    }

    public static boolean isLeftFromChat(TLRPC.Chat chat) {
        return chat == null || chat instanceof TLRPC.TL_chatEmpty || chat instanceof TLRPC.TL_chatForbidden || chat instanceof TLRPC.TL_channelForbidden || chat.left || chat.deactivated;
    }

    public static boolean isKickedFromChat(TLRPC.Chat chat) {
        return chat == null || chat instanceof TLRPC.TL_chatEmpty || chat instanceof TLRPC.TL_chatForbidden || chat instanceof TLRPC.TL_channelForbidden || chat.kicked || chat.deactivated || chat.banned_rights != null && chat.banned_rights.view_messages;
    }

    public static boolean isNotInChat(TLRPC.Chat chat) {
        return chat == null || chat instanceof TLRPC.TL_chatEmpty || chat instanceof TLRPC.TL_chatForbidden || chat instanceof TLRPC.TL_channelForbidden || chat.left || chat.kicked || chat.deactivated;
    }

    public static boolean isChannel(TLRPC.Chat chat) {
        return chat instanceof TLRPC.TL_channel || chat instanceof TLRPC.TL_channelForbidden;
    }

    public static boolean isMegagroup(TLRPC.Chat chat) {
        return (chat instanceof TLRPC.TL_channel || chat instanceof TLRPC.TL_channelForbidden) && chat.megagroup;
    }

    public static boolean isMegagroup(int currentAccount, int chatId) {
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
        return ChatObject.isChannel(chat) && chat.megagroup;
    }

    public static boolean hasAdminRights(TLRPC.Chat chat) {
        return chat != null && (chat.creator || chat.admin_rights != null && chat.admin_rights.flags != 0);
    }

    public static boolean canChangeChatInfo(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_CHANGE_INFO);
    }

    public static boolean canAddAdmins(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_ADD_ADMINS);
    }

    public static boolean canBlockUsers(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_BLOCK_USERS);
    }

    public static boolean canManageCalls(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_MANAGE_CALLS);
    }

    public static boolean canSendStickers(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_SEND_STICKERS);
    }

    public static boolean canSendEmbed(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_EMBED_LINKS);
    }

    public static boolean canSendMedia(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_SEND_MEDIA);
    }

    public static boolean canSendPolls(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_SEND_POLLS);
    }

    public static boolean canSendMessages(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_SEND);
    }

    public static boolean canPost(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_POST);
    }

    public static boolean canAddUsers(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_INVITE);
    }

    public static boolean shouldSendAnonymously(TLRPC.Chat chat) {
        return chat != null && chat.admin_rights != null && chat.admin_rights.anonymous;
    }

    public static boolean canAddBotsToChat(TLRPC.Chat chat) {
        if (isChannel(chat)) {
            return chat.megagroup && (chat.admin_rights != null && (chat.admin_rights.post_messages || chat.admin_rights.add_admins) || chat.creator);
        } else {
            return chat.migrated_to == null;
        }
    }

    public static boolean canPinMessages(TLRPC.Chat chat) {
        return canUserDoAction(chat, ACTION_PIN) || ChatObject.isChannel(chat) && !chat.megagroup && chat.admin_rights != null && chat.admin_rights.edit_messages;
    }

    public static boolean isChannel(int chatId, int currentAccount) {
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
        return chat instanceof TLRPC.TL_channel || chat instanceof TLRPC.TL_channelForbidden;
    }

    public static boolean isCanWriteToChannel(int chatId, int currentAccount) {
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
        return ChatObject.canSendMessages(chat) || chat.megagroup;
    }

    public static boolean canWriteToChat(TLRPC.Chat chat) {
        return !isChannel(chat) || chat.creator || chat.admin_rights != null && chat.admin_rights.post_messages || !chat.broadcast && !chat.gigagroup || chat.gigagroup && ChatObject.hasAdminRights(chat);
    }

    public static String getBannedRightsString(TLRPC.TL_chatBannedRights bannedRights) {
        String currentBannedRights = "";
        currentBannedRights += bannedRights.view_messages ? 1 : 0;
        currentBannedRights += bannedRights.send_messages ? 1 : 0;
        currentBannedRights += bannedRights.send_media ? 1 : 0;
        currentBannedRights += bannedRights.send_stickers ? 1 : 0;
        currentBannedRights += bannedRights.send_gifs ? 1 : 0;
        currentBannedRights += bannedRights.send_games ? 1 : 0;
        currentBannedRights += bannedRights.send_inline ? 1 : 0;
        currentBannedRights += bannedRights.embed_links ? 1 : 0;
        currentBannedRights += bannedRights.send_polls ? 1 : 0;
        currentBannedRights += bannedRights.invite_users ? 1 : 0;
        currentBannedRights += bannedRights.change_info ? 1 : 0;
        currentBannedRights += bannedRights.pin_messages ? 1 : 0;
        currentBannedRights += bannedRights.until_date;
        return currentBannedRights;
    }

    public static TLRPC.Chat getChatByDialog(long did, int currentAccount) {
        int lower_id = (int) did;
        int high_id = (int) (did >> 32);
        if (lower_id < 0) {
            return MessagesController.getInstance(currentAccount).getChat(-lower_id);
        }
        return null;
    }
}
