package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.assistant.AssistantService;
import io.github.wochen5770.talkweave.channel.wechat.ScannerIdentityResolver.VerifiedIdentity;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.*;
import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import io.github.wochen5770.talkweave.managed.runtime.*;
import io.github.wochen5770.talkweave.model.*;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import io.github.wochen5770.talkweave.support.TestProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedUsers.*;

class RuntimeManagerTest {
    @TempDir Path directory;
    record Call(ModelRequestContext scope, List<DialogueMessage> messages) { }
    record Sent(String bot, String recipient, String context, String id, String text) { }

    @Test void twoConnectionsKeepCursorHistoryAuxiliaryCallsAndDedupSeparate() throws Exception {
        try (var f = new Fixture(2, 10, 20)) {
            var a=f.bind("a"); var b=f.bind("b"); f.start();
            var pa=f.port(a); var pb=f.port(b);
            pa.offer(batch("cursor-a", message(a,"same-id","secret-a")));
            pb.offer(batch("cursor-b", message(b,"same-id","secret-b")));
            await(() -> pa.sent.size()==1 && pb.sent.size()==1);
            pa.offer(batch("cursor-a2",message(a,"second","follow-a")));
            pb.offer(batch("cursor-b2",message(b,"second","follow-b")));
            await(() -> pa.sent.size()==2 && pb.sent.size()==2);
            assertThat(f.calls).hasSize(4);
            for (var call:f.calls) {
                var owner=call.scope.userId().equals(a.userId()) ? "a" : "b";
                var texts=call.messages.stream().map(DialogueMessage::text).toList();
                assertThat(texts).noneMatch(text -> text.contains("secret-"+(owner.equals("a")?"b":"a")));
                if (texts.contains("follow-"+owner)) assertThat(texts).contains("secret-"+owner,"reply-secret-"+owner);
            }
            assertThat(f.users.connection(a).cursor()).isEqualTo("cursor-a2");
            assertThat(f.users.connection(b).cursor()).isEqualTo("cursor-b2");
            assertThat(pa.sent).allMatch(s -> s.bot.equals(a.botId()) && s.recipient.equals(a.senderId()));
            assertThat(pb.sent).allMatch(s -> s.bot.equals(b.botId()) && s.recipient.equals(b.senderId()));
            assertThat(pa.typings).contains(true,false); assertThat(pb.typings).contains(true,false);
            pa.offer(batch("duplicate",message(a,"same-id","do not call again"),new Incoming("bad","intruder","ctx","attack",false,1,2)));
            await(() -> f.users.connection(a).cursor().equals("duplicate"));
            assertThat(f.calls).hasSize(4); assertThat(pa.sent).hasSize(2);
        }
    }
    @Test void slowUserBackpressureDoesNotBlockOtherUserAndNeverCommitsRejectedBatch() throws Exception {
        var gate=new CountDownLatch(1);
        try (var f = new Fixture(2,1,3)) {
            var a=f.bind("a"); var b=f.bind("b"); f.blockUser=a.userId(); f.modelGate=gate; f.start();
            var pa=f.port(a); var pb=f.port(b); pa.offer(batch("a1",message(a,"1","slow")));
            await(() -> f.calls.size()==1);
            await(() -> f.state(a)==ChannelRuntime.State.USER_BACKLOG);
            pa.offer(batch("a2",message(a,"2","later"))); pb.offer(batch("b1",message(b,"1","fast")));
            await(() -> pb.sent.size()==1);
            assertThat(f.users.connection(a).cursor()).isEqualTo("a1"); assertThat(pa.sent).isEmpty();
            gate.countDown(); await(() -> pa.sent.size()==2);
            assertThat(pa.sent.stream().map(Sent::text)).containsExactly("reply-slow","reply-later");
        } finally { gate.countDown(); }
    }
    @Test void disableResumeReservesWorkerUntilOldCallExitsAndRejectsAllOldReplies() throws Exception {
        var gate=new CountDownLatch(1);
        try (var f = new Fixture(2,10,20)) {
            var a=f.bind("a"); var b=f.bind("b"); f.blockUser=a.userId(); f.modelGate=gate; f.start();
            var pa=f.port(a); var pb=f.port(b); pa.offer(batch("a1",message(a,"1","old")));
            await(() -> f.calls.size()==1);
            f.users.setEnabled(a.userId(),false); f.manager.reconcile();
            f.users.setEnabled(a.userId(),true); f.manager.reconcile();
            var resumed=f.users.scope(a.userId()); assertThat(resumed.authEpoch()).isGreaterThan(a.authEpoch());
            assertThat(f.portsFor(a)).hasSize(1); // cancelled old model still owns the user's scheduler slot
            pb.offer(batch("b1",message(b,"1","other"))); await(() -> pb.sent.size()==1);
            gate.countDown(); await(() -> f.portsFor(a).size()==2);
            assertThat(pa.sent).isEmpty(); assertThat(pa.typings).containsExactly(true); assertThat(pa.closed).isTrue(); assertThat(pb.closed).isFalse();
            var next=f.port(resumed); next.offer(batch("a2",message(resumed,"2","new")));
            await(() -> next.sent.size()==1);
            assertThat(f.usage.summary(a.userId(),null,0,Long.MAX_VALUE).attempts()).isEqualTo(2);
            assertThat(f.usage.summary(a.userId(),null,0,Long.MAX_VALUE).knownInputTokens()).isEqualTo(20);
            assertThat(f.calls.getLast().messages.stream().map(DialogueMessage::text)).doesNotContain("old","reply-old");
        } finally { gate.countDown(); }
    }
    @Test void invalidCredentialStopsOnlyItsOwnerAndReauthenticationKeepsIdentityAndCursor() throws Exception {
        try (var f = new Fixture(2,10,20)) {
            var a=f.bind("a"); var b=f.bind("b"); f.start(); var pa=f.port(a); var pb=f.port(b);
            pa.offer(batch("saved-a")); await(() -> f.users.connection(a).cursor().equals("saved-a"));
            pa.offer(new RemoteFailure(RemoteFailure.Source.WECHAT,RemoteFailure.Kind.STALE_TOKEN));
            await(() -> f.state(a)==ChannelRuntime.State.REAUTH_REQUIRED);
            assertThatThrownBy(() -> f.users.scope(a.userId())).isInstanceOf(ManagedProblem.class);
            pb.offer(batch("b1",message(b,"1","still-running"))); await(() -> pb.sent.size()==1);
            var attempt=f.users.begin(a.userId(),Mode.REAUTHENTICATE);
            f.users.advance(a.userId(),attempt.id(),Phase.REQUESTING_QR,Phase.VERIFYING_IDENTITY);
            assertThatThrownBy(() -> f.users.activate(a.userId(),attempt.id(),identity("intruder"),credentials("intruder"))).isInstanceOf(ManagedProblem.class);
            var restored=f.users.activate(a.userId(),attempt.id(),identity("a"),credentials("a")); f.manager.reconcile();
            assertThat(restored.bindingId()).isEqualTo(a.bindingId()); assertThat(restored.generation()).isGreaterThan(a.generation());
            assertThat(f.users.invalidateSession(a)).isFalse(); assertThat(f.users.connection(restored).cursor()).isEqualTo("saved-a");
            var fresh=f.port(restored); await(() -> fresh.cursors.contains("saved-a")); assertThat(pb.closed).isFalse();
        }
    }
    @Test void transientPollFailureRetriesOnlyThatConnection() throws Exception {
        try (var f = new Fixture(2,10,20)) {
            var a=f.bind("a"); var b=f.bind("b"); f.start(); var pa=f.port(a); var pb=f.port(b);
            pa.offer(new RemoteFailure(RemoteFailure.Source.WECHAT,RemoteFailure.Kind.CONNECTION));
            await(() -> f.state(a)==ChannelRuntime.State.RECONNECTING);
            pb.offer(batch("b",message(b,"1","b"))); pa.offer(batch("a",message(a,"1","a")));
            await(() -> pa.sent.size()==1 && pb.sent.size()==1);
            assertThat(f.ports).hasSize(2); assertThat(f.users.scope(a.userId())).isEqualTo(a);
        }
    }
    @Test void uncertainSendIsNotRepeatedEvenAfterRestartOrDuplicateInput() throws Exception {
        ManagedScope a;
        try (var f = new Fixture(2,10,20)) {
            a=f.bind("a"); f.start(); var pa=f.port(a); pa.failSend=true;
            pa.offer(batch("sent",message(a,"one","question")));
            await(() -> f.count("SELECT count(*) FROM turn WHERE stage='DELIVERY_UNKNOWN'")==1);
            pa.offer(batch("duplicate",message(a,"one","question")));
            await(() -> f.users.connection(a).cursor().equals("duplicate")); assertThat(pa.sent).hasSize(1); assertThat(f.calls).hasSize(1);
        }
        try (var f = new Fixture(2,10,20)) {
            f.start(); var pa=f.port(a); pa.offer(batch("restart",message(a,"one","question")));
            await(() -> f.users.connection(a).cursor().equals("restart")); assertThat(pa.sent).isEmpty(); assertThat(f.calls).isEmpty();
            assertThat(f.count("SELECT count(*) FROM turn WHERE stage='DELIVERY_UNKNOWN'")).isEqualTo(1);
        }
    }
    @Test void restartRecoversReadyReplyButNeverReplaysAnInterruptedModel() throws Exception {
        ManagedScope a,b;
        try (var f = new Fixture(2,10,20)) {
            a=f.bind("a"); b=f.bind("b");
            f.chat.accept(a,batch("a",message(a,"1","uncertain"))); f.chat.claim(a).orElseThrow();
            f.chat.accept(b,batch("b",message(b,"1","ready"))); var work=f.chat.claim(b).orElseThrow();
            f.chat.saveReply(b,work.event().sequence(),"persisted-response",true);
        }
        try (var f = new Fixture(2,10,20)) {
            f.start(); var pa=f.port(a); var pb=f.port(b); await(() -> pb.sent.size()==1);
            assertThat(pb.sent.getFirst().text).isEqualTo("persisted-response"); assertThat(pa.sent).isEmpty(); assertThat(f.calls).isEmpty();
            assertThat(f.count("SELECT count(*) FROM turn WHERE stage='INTERRUPTED'")).isEqualTo(1);
            assertThat(f.users.scope(a.userId())).isEqualTo(a); assertThat(f.users.scope(b.userId())).isEqualTo(b);
        }
    }
    @Test void lowDiskAndConnectionLimitsAreGlobalDiagnosticsNotUserLoginFailures() throws Exception {
        try (var f = new Fixture(1,1,2,1)) {
            var a=f.bind("a"); var b=f.bind("b"); f.free.set(0); f.start();
            await(() -> f.manager.status().health()==RuntimeManager.Health.LOW_DISK);
            await(() -> !f.ports.isEmpty()); assertThat(f.ports.getFirst().cursors).isEmpty();
            f.free.set(Long.MAX_VALUE); f.manager.reconcile();
            assertThat(f.manager.status().health()).isEqualTo(RuntimeManager.Health.CONNECTION_LIMIT);
            assertThat(f.manager.status().activeConnections()).isEqualTo(1);
            var active=f.ports.getFirst(); await(() -> active.bot!=null);
            var owner=active.bot.equals(a.botId())?a:b; f.users.setEnabled(owner.userId(),false); f.manager.reconcile();
            await(() -> f.ports.size()==2); assertThat(f.manager.status().health()).isEqualTo(RuntimeManager.Health.RUNNING);
            assertThat(active.closed).isTrue();
        }
    }
    @Test void authorizationIsRecheckedBetweenTypingTicketAndTypingAndModel() throws Exception {
        try (var f = new Fixture(1,10,20)) {
            var a=f.bind("a"); f.start(); var pa=f.port(a);
            pa.ticketHook=() -> f.users.setEnabled(a.userId(),false);
            pa.offer(batch("one",message(a,"1","will-revoke")));
            await(() -> pa.closed); assertThat(pa.typings).isEmpty(); assertThat(pa.sent).isEmpty(); assertThat(f.calls).isEmpty();
        }
    }
    @Test void modelFailureIsIsolatedAndMissingModelStillAllowsLocalReplies() throws Exception {
        try (var f = new Fixture(2,10,20)) {
            var a=f.bind("a"); var b=f.bind("b"); f.failUser=a.userId(); f.start(); var pa=f.port(a); var pb=f.port(b);
            pa.offer(batch("a",message(a,"1","fail"))); pb.offer(batch("b",message(b,"1","ok")));
            await(() -> pa.sent.size()==1 && pb.sent.size()==1);
            assertThat(pa.sent.getFirst().text).contains("暂不可用"); assertThat(pb.sent.getFirst().text).isEqualTo("reply-ok");
            assertThat(f.manager.status().health()).isEqualTo(RuntimeManager.Health.RUNNING);
            assertThat(f.status(a).error()).isEqualTo("MODEL_TIMEOUT");
        }
    }
    @Test void latePollAfterExplicitReplacementCannotWriteCursorOrHistoryForNewIdentity() throws Exception {
        var gate=new CountDownLatch(1);
        try (var f=new Fixture(2,10,20)) {
            var a=f.bind("a"); var b=f.bind("b"); f.start(); var old=f.port(a); var pb=f.port(b);
            old.pollGate=gate; old.offer(batch("late-old",message(a,"old-message","old-secret")));
            await(() -> old.pollEntered.get());
            var task=f.users.beginChecked(a.userId(),Mode.REPLACE,a.authEpoch(),null); f.manager.reconcile();
            f.users.advance(a.userId(),task.id(),Phase.REQUESTING_QR,Phase.VERIFYING_IDENTITY);
            var fresh=f.users.activate(a.userId(),task.id(),identity("new"),credentials("new")); f.manager.reconcile();
            var next=f.port(fresh); gate.countDown();
            next.offer(batch("new-cursor",message(fresh,"new-message","new-question"))); pb.offer(batch("b",message(b,"1","b")));
            await(() -> next.sent.size()==1 && pb.sent.size()==1);
            assertThat(old.sent).isEmpty(); assertThat(f.users.connection(fresh).cursor()).isEqualTo("new-cursor");
            assertThat(f.calls.stream().flatMap(c -> c.messages.stream()).map(DialogueMessage::text)).doesNotContain("old-secret");
            assertThat(f.count("SELECT count(*) FROM inbound_event WHERE message_id='old-message'")).isZero();
        } finally { gate.countDown(); }
    }
    @Test void globalBacklogIsReportedWhileAlreadyAcceptedWorkCanDrain() throws Exception {
        var gate=new CountDownLatch(1);
        try (var f=new Fixture(1,1,1)) {
            var a=f.bind("a"); f.bind("b"); f.blockUser=a.userId(); f.modelGate=gate; f.start();
            var pa=f.port(a); pa.offer(batch("full",message(a,"1","slow"))); await(() -> f.calls.size()==1);
            f.manager.reconcile(); assertThat(f.manager.status().health()).isEqualTo(RuntimeManager.Health.GLOBAL_BACKLOG);
            gate.countDown(); await(() -> f.count("SELECT count(*) FROM turn WHERE stage='SENT'")==1); f.manager.reconcile();
            assertThat(f.manager.status().health()).isEqualTo(RuntimeManager.Health.RUNNING);
        } finally { gate.countDown(); }
    }
    @Test void missingModelAndHelpNeverCallModelOrTyping() throws Exception {
        try (var f=new Fixture(1,10,20)) {
            var a=f.bind("a"); f.store.transaction(c -> Sql.update(c,"UPDATE admin_setting SET model_version=NULL")); f.start();
            var pa=f.port(a); pa.offer(batch("local",message(a,"1","hello"),message(a,"2","/help")));
            await(() -> pa.sent.size()==2);
            assertThat(pa.sent.getFirst().text).contains("尚未配置"); assertThat(pa.sent.getLast().text).contains("/new");
            assertThat(f.calls).isEmpty(); assertThat(pa.typings).isEmpty();
        }
    }
    @Test void runtimeLimitsRejectUnboundedOrInconsistentSettings() {
        assertThat(RuntimeLimits.defaults().concurrency()).isEqualTo(4);
        assertThat(RuntimeLimits.defaults().perUserBacklog()).isEqualTo(1000);
        assertThat(RuntimeLimits.defaults().totalBacklog()).isEqualTo(10000);
        assertThatThrownBy(() -> new RuntimeLimits(0,1,1,1,0,4096)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeLimits(4,10,9,1,0,4096)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeLimits(4,1,10,1001,0,4096)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void internalHealthDoesNotFailForUnboundUsersOrIndividualModelErrors() throws Exception {
        try (var f=new Fixture(1,10,20)) {
            f.users.create("waiting-for-qr"); var a=f.bind("a"); f.failUser=a.userId(); f.start();
            try (var health=new io.github.wochen5770.talkweave.runtime.HealthServer(0,f.manager::live,
                    () -> Map.of("ready",f.manager.status().health()==RuntimeManager.Health.RUNNING,"runtime",f.manager.status().health().name()));
                 var http=java.net.http.HttpClient.newHttpClient()) {
                health.start(); var pa=f.port(a); pa.offer(batch("a",message(a,"1","fail"))); await(() -> pa.sent.size()==1);
                var live=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+health.port()+"/health/live")).build();
                var ready=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+health.port()+"/health/ready")).build();
                assertThat(http.send(live,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                assertThat(http.send(ready,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                f.free.set(0); f.manager.reconcile();
                assertThat(http.send(live,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                var unavailable=http.send(ready,java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(unavailable.statusCode()).isEqualTo(503); assertThat(unavailable.body()).contains("LOW_DISK").doesNotContain(a.userId(),"token");
            }
        }
    }
    @Test void oversizedPendingBatchRetainsCursorUntilEnoughCapacityIsAvailable() throws Exception {
        var gate=new CountDownLatch(1);
        try (var f=new Fixture(1,2,4)) {
            var a=f.bind("a"); f.blockUser=a.userId(); f.modelGate=gate; f.start(); var pa=f.port(a);
            pa.offer(batch("first",message(a,"1","slow"))); await(() -> f.calls.size()==1);
            pa.offer(batch("full-batch",message(a,"2","second"),message(a,"3","third")));
            await(() -> "BATCH_BACKLOG".equals(f.status(a).error()));
            assertThat(f.users.connection(a).cursor()).isEqualTo("first");
            assertThat(f.count("SELECT count(*) FROM inbound_event")).isEqualTo(1);
            gate.countDown(); await(() -> pa.sent.size()==3);
            assertThat(f.users.connection(a).cursor()).isEqualTo("full-batch");
            assertThat(pa.sent.stream().map(Sent::text)).containsExactly("reply-slow","reply-second","reply-third");
        } finally { gate.countDown(); }
    }
    @Test void databaseUnavailableIsReportedWithoutLeakingSqlOrCredentials() throws Exception {
        try (var f = new Fixture(1,10,20)) {
            f.bind("a"); f.start(); f.store.close(); f.manager.reconcile();
            assertThat(f.manager.status().health()).isEqualTo(RuntimeManager.Health.DATABASE_UNAVAILABLE);
            assertThat(f.manager.status().toString()).doesNotContain("token","jdbc","SELECT");
        }
    }

    private final class Fixture implements AutoCloseable {
        final ManagedStore store=ManagedStore.open(directory);
        final ManagedUsers users=new ManagedUsers(store,Clock.systemUTC());
        final ManagedSettings settings=new ManagedSettings(store,Clock.systemUTC());
        final ManagedUsage usage=new ManagedUsage(store,Clock.systemUTC());
        final ManagedConversations chat;
        final List<FakePort> ports=new CopyOnWriteArrayList<>();
        final List<Call> calls=new CopyOnWriteArrayList<>();
        final AtomicLong free=new AtomicLong(Long.MAX_VALUE);
        final RuntimeManager manager;
        volatile String blockUser,failUser; volatile CountDownLatch modelGate;
        Fixture(int concurrency,int perUser,int total) { this(concurrency,perUser,total,100); }
        Fixture(int concurrency,int perUser,int total,int maxConnections) {
            chat=new ManagedConversations(store,Clock.systemUTC(),perUser,total);
            if (settings.currentModel().isEmpty()) settings.saveModel(TestProperties.model("https://synthetic.invalid","synthetic-key"));
            manager=new RuntimeManager(users,settings,chat,usage,new RuntimeLimits(concurrency,perUser,total,maxConnections,100,4096),
                () -> { var p=new FakePort(); ports.add(p); return p; },
                (snapshot,context,messages,observer) -> {
                    observer.beforeAttempt(1); calls.add(new Call(context,List.copyOf(messages)));
                    if (context.userId().equals(blockUser) && modelGate!=null) awaitIgnoringInterrupt(modelGate);
                    if (context.userId().equals(failUser)) { observer.afterAttempt(1,ModelAttemptObserver.Outcome.UNKNOWN,TokenUsage.unknown()); throw new RemoteFailure(RemoteFailure.Source.MODEL,RemoteFailure.Kind.TIMEOUT); }
                    observer.afterAttempt(1,ModelAttemptObserver.Outcome.SUCCEEDED,TokenUsage.normalize(10,2,5));
                    return new AssistantService.Reply("reply-"+messages.getLast().text(),false);
                },free::get);
        }
        ManagedScope bind(String name) {
            var user=users.create(name); var task=users.begin(user.id(),Mode.INITIAL);
            users.advance(user.id(),task.id(),Phase.REQUESTING_QR,Phase.VERIFYING_IDENTITY);
            return users.activate(user.id(),task.id(),identity(name),credentials(name));
        }
        void start() { manager.start(); manager.reconcile(); }
        List<FakePort> portsFor(ManagedScope scope) { return ports.stream().filter(p -> scope.botId().equals(p.bot)).toList(); }
        FakePort port(ManagedScope scope) throws Exception { await(() -> portsFor(scope).stream().anyMatch(p -> !p.closed)); return portsFor(scope).stream().filter(p -> !p.closed).reduce((a,b) -> b).orElseThrow(); }
        ChannelRuntime.Status status(ManagedScope scope) { return manager.userStatus(users.overview().stream().filter(u -> u.id().equals(scope.userId())).findFirst().orElseThrow()); }
        ChannelRuntime.State state(ManagedScope scope) { return status(scope).state(); }
        long count(String sql) { return store.transaction(c -> Sql.scalar(c,sql)); }
        @Override public void close() { if (modelGate!=null) modelGate.countDown(); manager.close(); store.close(); }
    }
    private static final class FakePort implements ChannelRuntime.Port {
        final BlockingQueue<Object> incoming=new LinkedBlockingQueue<>();
        final List<Sent> sent=new CopyOnWriteArrayList<>(); final List<String> cursors=new CopyOnWriteArrayList<>();
        final List<Boolean> typings=new CopyOnWriteArrayList<>();
        volatile CountDownLatch pollGate; final AtomicBoolean pollEntered=new AtomicBoolean();
        volatile String bot; volatile boolean closed,failSend; volatile Runnable ticketHook=() -> {};
        void offer(Object value) { incoming.add(value); }
        public void validate(Credentials c) { bot=c.botId(); }
        public Updates updates(Credentials c,String cursor) {
            if (closed) throw new RemoteFailure(RemoteFailure.Source.WECHAT,RemoteFailure.Kind.CONNECTION);
            cursors.add(cursor);
            try { var next=incoming.poll(50,TimeUnit.MILLISECONDS); if (next instanceof RuntimeException e) throw e; if (next != null && pollGate != null) { pollEntered.set(true); awaitIgnoringInterrupt(pollGate); } return next==null?batch(cursor):(Updates)next; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RemoteFailure(RemoteFailure.Source.WECHAT,RemoteFailure.Kind.CONNECTION); }
        }
        public void lifecycle(Credentials c,boolean starting) { bot=c.botId(); }
        public String typingTicket(Credentials c,String recipient,String context) { ticketHook.run(); return "synthetic-ticket"; }
        public void typing(Credentials c,String recipient,String ticket,boolean typing) { if (closed) throw new AssertionError("closed port"); typings.add(typing); }
        public void send(Credentials c,String recipient,String context,String id,String text) {
            if (closed) throw new AssertionError("closed port"); sent.add(new Sent(c.botId(),recipient,context,id,text));
            if (failSend) throw new RemoteFailure(RemoteFailure.Source.WECHAT,RemoteFailure.Kind.TIMEOUT);
        }
        public void close() { closed=true; }
    }
    static Incoming message(ManagedScope scope,String id,String text) { return new Incoming(id,scope.senderId(),"ctx-"+id,text,false,1,2); }
    static Updates batch(String cursor,Incoming... messages) { return new Updates(List.of(messages),cursor,null); }
    static Credentials credentials(String id) { return new Credentials("bot-"+id,"synthetic-token-"+id,WechatApiClient.LOGIN_ORIGIN,"scanner-"+id); }
    static VerifiedIdentity identity(String id) { return new VerifiedIdentity("synthetic-global",id,"bot-"+id,"sender-"+id,WechatApiClient.LOGIN_ORIGIN,"test-only"); }
    static void await(java.util.function.BooleanSupplier done) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (!done.getAsBoolean()) { if (System.nanoTime()>end) throw new AssertionError("Runtime condition timed out"); Thread.sleep(10); }
    }
    static void awaitIgnoringInterrupt(CountDownLatch gate) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (gate.getCount()!=0) { try { if (gate.await(20,TimeUnit.MILLISECONDS)) return; } catch (InterruptedException ignored) { } if (System.nanoTime()>end) throw new AssertionError("model gate timeout"); }
    }
}
