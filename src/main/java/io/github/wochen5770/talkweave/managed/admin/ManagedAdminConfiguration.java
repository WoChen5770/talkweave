package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.managed.runtime.*;
import io.github.wochen5770.talkweave.managed.persistence.*;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("managed")
public class ManagedAdminConfiguration {
    @Bean org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer strictManagementJson() {
        return builder -> builder.featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .featuresToDisable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
    @Bean Clock managedClock() { return Clock.systemUTC(); }
    @Bean(destroyMethod = "close") ManagedStore managedStore(Environment env) {
        return ManagedStore.open(Path.of(env.getProperty("managed.directory", "./data-managed")));
    }
    @Bean RuntimeLimits runtimeLimits(Environment env) {
        var d = RuntimeLimits.defaults();
        return new RuntimeLimits(env.getProperty("managed.runtime.concurrency", Integer.class, d.concurrency()),
                env.getProperty("managed.runtime.per-user-backlog", Integer.class, d.perUserBacklog()),
                env.getProperty("managed.runtime.total-backlog", Integer.class, d.totalBacklog()),
                env.getProperty("managed.runtime.max-connections", Integer.class, d.maxConnections()),
                env.getProperty("managed.runtime.min-free-bytes", Long.class, d.minFreeBytes()),
                env.getProperty("managed.runtime.reply-bytes", Integer.class, d.replyBytes()));
    }
    @Bean ManagedConversations managedConversations(ManagedStore store, Clock clock, RuntimeLimits limits) {
        return new ManagedConversations(store, clock, limits.perUserBacklog(), limits.totalBacklog());
    }
    @Bean(destroyMethod = "close") ManagedModelPool managedModelPool() { return new ManagedModelPool(); }
    @Bean ChannelRuntime.Ports channelPorts(Environment env) {
        var hosts = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("assistant.wechat.trusted-hosts", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(java.util.List.of("ilinkai.weixin.qq.com"));
        return ChannelRuntime.wechatPorts(java.util.Set.copyOf(hosts));
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    RuntimeManager runtimeManager(ManagedUsers users, ManagedSettings settings, ManagedConversations conversations,
                                  ManagedUsage usage, RuntimeLimits limits, ChannelRuntime.Ports ports, ManagedModelPool models, Environment env) {
        return new RuntimeManager(users, settings, conversations, usage, limits, ports, models,
                RuntimeManager.diskSpace(Path.of(env.getProperty("managed.directory", "./data-managed"))));
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    io.github.wochen5770.talkweave.runtime.HealthServer managedHealth(RuntimeManager runtime, Environment env) throws java.io.IOException {
        return new io.github.wochen5770.talkweave.runtime.HealthServer(env.getProperty("managed.health-port", Integer.class, 8081), runtime::live,
                () -> { var status = runtime.status(); return java.util.Map.of("ready", status.health() == RuntimeManager.Health.RUNNING, "runtime", status.health().name()); });
    }
    @Bean ManagedSettings managedSettings(ManagedStore store, Clock clock) { return new ManagedSettings(store, clock); }
    @Bean ManagedUsers managedUsers(ManagedStore store, Clock clock) { return new ManagedUsers(store, clock); }
    @Bean ManagedUsage managedUsage(ManagedStore store, Clock clock) { return new ManagedUsage(store, clock); }
    @Bean ManagedAudit managedAudit(ManagedStore store, Clock clock) { return new ManagedAudit(store, clock); }
    @Bean io.github.wochen5770.talkweave.managed.binding.BindingMaterials bindingMaterials(ManagedStore store, Environment env) {
        // ManagedStore has validated the layout before any login-material directory is written.
        return new io.github.wochen5770.talkweave.managed.binding.BindingMaterials(Path.of(env.getProperty("managed.directory", "./data-managed")));
    }
    @Bean io.github.wochen5770.talkweave.managed.binding.BindingCoordinator.Ports bindingPorts(Environment env) {
        var hosts = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("assistant.wechat.trusted-hosts", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(java.util.List.of("ilinkai.weixin.qq.com"));
        return io.github.wochen5770.talkweave.managed.binding.BindingCoordinator.wechatPorts(java.util.Set.copyOf(hosts));
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    io.github.wochen5770.talkweave.managed.binding.BindingCoordinator bindingCoordinator(ManagedUsers users,
            io.github.wochen5770.talkweave.managed.binding.BindingMaterials materials,
            io.github.wochen5770.talkweave.managed.binding.BindingCoordinator.Ports ports, Clock clock, RuntimeManager runtime) {
        return new io.github.wochen5770.talkweave.managed.binding.BindingCoordinator(users, materials,
                io.github.wochen5770.talkweave.channel.wechat.ScannerIdentityResolver.production(), ports, clock,
                scope -> runtime.reconcile(), 16);
    }
    @Bean PasswordEncoder administratorPasswords() { return new BCryptPasswordEncoder(12); }
    @Bean AdminIdentity adminIdentity(ManagedSettings settings, PasswordEncoder passwords, Environment env) {
        return new AdminIdentity(settings, passwords, env.getProperty("managed.bootstrap-username", "admin"),
                env.getProperty("managed.bootstrap-password", ""));
    }
}
