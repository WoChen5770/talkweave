package io.github.wochen5770.talkweave.runtime;

import io.github.wochen5770.talkweave.persistence.SqliteStore;
import io.github.wochen5770.talkweave.persistence.ConversationRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@org.springframework.context.annotation.Profile("!managed")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssistantProperties.class)
public class RuntimeConfiguration {
    @Bean(destroyMethod = "close")
    SqliteStore sqliteStore(AssistantProperties properties) {
        // Validate everything before creating state or permitting any network activity.
        properties.validate();
        return SqliteStore.open(properties.storage());
    }
    @Bean
    ConversationRepository conversationRepository(SqliteStore store, AssistantProperties properties) {
        return new ConversationRepository(store,
                new ConversationRepository.Binding(properties.wechat().botId(), properties.wechat().ownerId()), properties.storage());
    }
}
