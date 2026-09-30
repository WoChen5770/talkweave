package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.channel.wechat.*;
import java.time.Clock;
import java.util.*;

/** Test-only bulk data, called only within the exclusive, initially empty ExternalBusinessFixture. */
final class SyntheticHistory {
    private SyntheticHistory() { }
    static ManagedScope user(ManagedStore store, Clock clock) {
        var users = new ManagedUsers(store, clock);
        var user = users.create("synthetic-history");
        var task = users.begin(user.id(), ManagedUsers.Mode.INITIAL);
        users.advance(user.id(), task.id(), ManagedUsers.Phase.REQUESTING_QR, ManagedUsers.Phase.VERIFYING_IDENTITY);
        String id = UUID.randomUUID().toString();
        return users.activate(user.id(), task.id(), new ScannerIdentityResolver.VerifiedIdentity("synthetic-only", id, id, id,
                WechatApiClient.LOGIN_ORIGIN, "synthetic"), new WechatApiClient.Credentials(id, "synthetic-token", WechatApiClient.LOGIN_ORIGIN, id));
    }
    static ManagedConversations.Event accept(ManagedConversations chat, ManagedScope scope, String text) {
        return chat.accept(scope, new WechatApiClient.Updates(List.of(new WechatApiClient.Incoming(UUID.randomUUID().toString(),
                scope.senderId(), "synthetic-context", text, false, 1, 2)), "synthetic-cursor", null)).getFirst();
    }
    static ManagedConversations.Event seed(ManagedStore store, ManagedConversations chat, ManagedScope scope, int rounds) {
        if (rounds < 1 || rounds > 10000) throw new IllegalArgumentException();
        var first = accept(chat, scope, "synthetic-first");
        String digits = "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        store.transaction(c -> {
            ManagedUsers.authorize(c, scope);
            Sql.update(c, "UPDATE turn SET stage='INTERRUPTED' WHERE event_sequence=?", first.sequence());
            Sql.update(c, "INSERT INTO inbound_event(user_id,binding_id,conversation_id,bot_id,sender_id,generation,auth_epoch,message_id,received_at,text,context_token,kind) "
                    + "SELECT ?,?,?,?,?,?,?,CONCAT(?,n),?,CONCAT('synthetic question 😀 ',n),'synthetic-context','CHAT' FROM "
                    + "(SELECT a.n+10*b.n+100*c.n+1000*d.n n FROM " + digits + " a CROSS JOIN " + digits + " b CROSS JOIN " + digits + " c CROSS JOIN " + digits + " d) numbers WHERE n<? ORDER BY n",
                    scope.userId(), scope.bindingId(), first.conversationId(), scope.botId(), scope.senderId(), scope.generation(), scope.authEpoch(),
                    UUID.randomUUID().toString(), first.receivedAt(), rounds);
            Sql.update(c, "INSERT INTO turn(event_sequence,user_id,binding_id,conversation_id,runtime_epoch,stage,reply_text,model_succeeded,updated_at) "
                    + "SELECT sequence,user_id,binding_id,conversation_id,?,'SENT',CONCAT('synthetic answer ',text),1,received_at FROM inbound_event WHERE conversation_id=? AND user_id=? AND sequence>?",
                    store.epoch(), first.conversationId(), scope.userId(), first.sequence());
            Sql.update(c, "UPDATE conversation SET history_revision=?,confirmed_turn_count=?,last_confirmed_sequence="
                    + "(SELECT MAX(sequence) FROM inbound_event WHERE conversation_id=? AND user_id=?) WHERE id=? AND user_id=?",
                    rounds, rounds, first.conversationId(), scope.userId(), first.conversationId(), scope.userId());
            return null;
        });
        return accept(chat, scope, "synthetic-current");
    }
}
