package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.AssistantApplication;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import io.github.wochen5770.talkweave.managed.binding.BindingCoordinator;
import java.net.URI;
import java.util.UUID;
import io.github.wochen5770.talkweave.managed.config.*;
import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.managed.cache.*;
import io.github.wochen5770.talkweave.managed.runtime.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Explicit test-classpath launch only. No outbound requests and no identity-verification bypass. */
@TestConfiguration(proxyBeanMethods = false)
@Profile("synthetic-browser-only")
public class ManagedBrowserFixture {
    public static void main(String[] args) throws Exception {
        if (Arrays.stream(args).anyMatch(arg -> !arg.equals("--synthetic.cache-fault=true")))
            throw new IllegalArgumentException("Only the synthetic cache fault option is accepted");
        boolean fault=Arrays.asList(args).contains("--synthetic.cache-fault=true");
        var target=ExternalIntegrationTarget.load();
        var fixture=new ExternalBusinessFixture();
        try { launch(target,fixture,fault); }
        catch (Throwable failure) { fixture.close(); throw failure; }
    }
    private static void launch(ExternalIntegrationTarget target,ExternalBusinessFixture fixture,boolean fault) throws Exception {
        var store=fixture.open(); // Same verified exclusive owner from empty preflight through browser shutdown.
        var materials=Files.createTempDirectory(Path.of("/private/tmp"),"talkweave-browser-");
        var source=target.config();
        var config=new ExternalServices(source.mysql(),source.redis(),source.historyCache(),source.conversation(),materials.toString());
        var clock=Clock.systemUTC(); var chat=new ManagedConversations(store,clock);
        var seed=AdminUsageFixture.seed(store);
        var redis=new RedisHistoryCache(config.redis(),config.historyCache());
        var port=new HistoryCachePort() {
            private String key(String key) { return target.redisKey("browser")+":"+UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            public String get(String key,long deadline) { if(fault) throw new IllegalStateException("Synthetic cache outage"); return redis.get(key(key),deadline); }
            public void put(String key,String value,long deadline) { if(fault) throw new IllegalStateException("Synthetic cache outage"); redis.put(key(key),value,deadline); }
            public void discard(String key,String value,long deadline) { redis.discard(key(key),value,deadline); }
            public void close() { redis.close(); }
        };
        var history=new HistoryService(chat,config.historyCache(),port,store.installationId(),store.cacheEpoch());
        var users=new ManagedUsers(store,clock); users.setEnabled(seed.a(),true); var scope=users.scope(seed.a());
        var event=chat.accept(scope,new WechatApiClient.Updates(List.of(new WechatApiClient.Incoming("browser-cache-diagnostic",scope.senderId(),"synthetic-context","synthetic",false,1,2)),"synthetic",null)).getFirst();
        history.load(scope,event.sequence(),new ManagedSettings(store,clock).currentModel().orElseThrow());
        users.setEnabled(seed.a(),false);
        var models=org.mockito.Mockito.mock(ManagedModelPool.class,invocation -> {
            if(invocation.getMethod().getName().equals("answer")) throw new IllegalStateException("Synthetic browser cannot call models");
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        org.springframework.context.ConfigurableApplicationContext context;
        try {
            context=new SpringApplicationBuilder(AssistantApplication.class,ManagedBrowserFixture.class)
                    .registerShutdownHook(false).profiles("managed","synthetic-browser-only")
                    .initializers(app -> {
                        var beans=app.getBeanFactory();
                        beans.registerSingleton("externalServices",config); beans.registerSingleton("managedStore",store);
                        beans.registerSingleton("historyService",history); beans.registerSingleton("managedModelPool",models);
                    }).run("--spring.config.import=", "--server.address=127.0.0.1", "--server.port=18680", "--managed.health-port=18081",
                            "--managed.bootstrap-username=synthetic-admin", "--managed.bootstrap-password=synthetic-browser-only-password",
                            "--managed.materials-directory="+materials);
        } catch(Throwable failure) { history.close(); fixture.close(); throw failure; }
        var shutdown=context.getBean(ManagedShutdown.class);
        shutdown.postProcessBeforeInitialization(store,"managedStore");
        shutdown.postProcessBeforeInitialization(history,"historyService");
        shutdown.postProcessBeforeInitialization(models,"managedModelPool");
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            try { context.close(); } finally { fixture.close(); }
        },"synthetic-browser-cleanup"));
        System.out.println("SYNTHETIC_BROWSER_READY loopback=18680 realProviders=DISABLED cache="+(fault?"FAULT":"EXTERNAL"));
    }
    @Bean @Primary io.github.wochen5770.talkweave.managed.runtime.ChannelRuntime.Ports syntheticChannelPorts() {
        return () -> new io.github.wochen5770.talkweave.managed.runtime.ChannelRuntime.Port() {
            public void validate(WechatApiClient.Credentials c) { }
            public WechatApiClient.Updates updates(WechatApiClient.Credentials c, String cursor) {
                return new WechatApiClient.Updates(java.util.List.of(), cursor, null);
            }
            public void lifecycle(WechatApiClient.Credentials c, boolean start) { }
            public String typingTicket(WechatApiClient.Credentials c, String recipient, String context) { return "synthetic-ticket"; }
            public void typing(WechatApiClient.Credentials c, String recipient, String ticket, boolean typing) { }
            public void send(WechatApiClient.Credentials c, String recipient, String context, String id, String text) {
                throw new IllegalStateException("Synthetic browser cannot send messages");
            }
            public void close() { }
        };
    }
    @Bean @Primary BindingCoordinator.Ports syntheticBrowserPorts() {
        return () -> new BindingCoordinator.Port() {
            final String id = UUID.randomUUID().toString();
            int polls;
            @Override public WechatApiClient.QrCode request() {
                return new WechatApiClient.QrCode("synthetic-" + id, "SYNTHETIC-ONLY-NOT-A-WECHAT-LOGIN:" + id);
            }
            @Override public WechatApiClient.LoginStatus poll(URI origin, WechatApiClient.QrCode qr, String code) {
                if (code != null) return new WechatApiClient.LoginStatus(WechatApiClient.LoginPhase.CONFIRMED, null,
                        new WechatApiClient.Credentials("synthetic-bot-" + id, "synthetic-token", WechatApiClient.LOGIN_ORIGIN, "synthetic-scanner-" + id));
                return new WechatApiClient.LoginStatus(++polls == 1 ? WechatApiClient.LoginPhase.SCANNED : WechatApiClient.LoginPhase.NEED_VERIFY_CODE, null, null);
            }
            @Override public void validate(WechatApiClient.Credentials credentials) { }
            @Override public void close() { }
        };
    }
}
