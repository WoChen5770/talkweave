package io.github.personalassistant.runtime;

import io.github.personalassistant.channel.wechat.IdentityVerifier;
import io.github.personalassistant.channel.wechat.InboxReceiver;
import io.github.personalassistant.channel.wechat.LoginCoordinator;
import io.github.personalassistant.channel.wechat.WechatApiClient;
import io.github.personalassistant.channel.wechat.WechatOutbound;
import io.github.personalassistant.model.CompatibleChatClient;
import io.github.personalassistant.persistence.ConversationRepository;
import io.github.personalassistant.persistence.StorageProblem;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true", matchIfMissing = true)
public class AssistantRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    WechatApiClient wechatApiClient(AssistantProperties properties, Environment environment) {
        var hosts = Binder.get(environment).bind("assistant.wechat.trusted-hosts", Bindable.listOf(String.class))
                .orElse(List.of("ilinkai.weixin.qq.com"));
        return new WechatApiClient(properties.wechat().requestTimeout(), properties.wechat().pollTimeout(), Set.copyOf(hosts));
    }
    @Bean(destroyMethod = "close")
    CompatibleChatClient compatibleChatClient(AssistantProperties properties) { return new CompatibleChatClient(properties.model()); }

    @Bean
    AssistantRuntime assistantRuntime(AssistantProperties properties, ConversationRepository repository,
                                      WechatApiClient wechat, CompatibleChatClient model, Environment environment) throws Exception {
        var files = new PrivateStateFiles(properties.storage().dataPath().resolve("login"), false);
        var login = new LoginCoordinator(repository, files, LoginCoordinator.port(wechat), Clock.systemUTC(), properties.wechat().loginTimeout());
        var identity = new IdentityVerifier(repository, files);
        var receiver = InboxReceiver.forWechat(repository, wechat, (session, messages) -> {
            try { identity.observe(session, messages); }
            catch (java.io.IOException failure) { throw new StorageProblem(StorageProblem.Reason.INVALID_DIRECTORY); }
        });
        var outbound = new WechatOutbound(repository, wechat);
        return new AssistantRuntime(repository, login, identity, receiver, model, outbound, properties, files,
                () -> { wechat.close(); model.close(); }, environment.getProperty("assistant.health-port", Integer.class, 8081),
                outbound, outbound::connected, outbound::stopping);
    }
}