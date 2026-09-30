package io.github.wochen5770.talkweave.managed.runtime;

import io.github.wochen5770.talkweave.managed.binding.*;
import io.github.wochen5770.talkweave.managed.cache.HistoryService;
import io.github.wochen5770.talkweave.managed.persistence.ManagedStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

/** One six-second application budget, reserving two seconds for the web server/framework.
 * Registered before init callbacks, so partially started contexts use the same cleanup path.
 * Only fixed component labels are reported; no exceptions, endpoints or identities escape.
 */
public final class ManagedShutdown implements BeanPostProcessor, ApplicationListener<ContextClosedEvent>, DisposableBean, Ordered {
    private record Resource(String name, Runnable fence, AutoCloseable close, boolean materials) { }
    public record Result(boolean complete, List<String> unfinished, List<String> failed) { }
    private final List<Resource> resources = new ArrayList<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Duration budget;
    private volatile Result result = new Result(false,List.of(),List.of());
    public ManagedShutdown() { this(Duration.ofSeconds(6)); }
    ManagedShutdown(Duration budget) { this.budget = budget; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public Object postProcessBeforeInitialization(Object bean, String name) {
        if (bean instanceof ManagedStore store) register("mysql",store::fence,store,false);
        else if (bean instanceof RuntimeManager runtime) register("runtime",runtime::quiesce,runtime,false);
        else if (bean instanceof BindingCoordinator binding) register("qr",binding::quiesce,binding,false);
        else if (bean instanceof ManagedModelPool model) register("model",()->{},model,false);
        else if (bean instanceof HistoryService history) register("history",()->{},history,false);
        else if (bean instanceof ManagedMaterials materials) register("materials",()->{},materials,true);
        return bean;
    }
    synchronized void register(String name, Runnable fence, AutoCloseable close, boolean materials) {
        resources.add(new Resource(name,fence,close,materials));
    }
    @Override public void onApplicationEvent(ContextClosedEvent event) { destroy(); }
    @Override public void destroy() {
        if (!closing.compareAndSet(false,true)) return;
        long deadline=System.nanoTime()+budget.toNanos();
        List<Resource> snapshot;
        synchronized(this) { snapshot=List.copyOf(resources); }
        var failures=new CopyOnWriteArrayList<String>();
        // All fences are local/nonblocking, and all run before any resource release.
        for(var resource:snapshot) try { resource.fence().run(); } catch(RuntimeException failure) { failures.add(resource.name()); }
        var executor=Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("managed-close-",0).factory());
        var pending=new LinkedHashMap<String,Future<?>>();
        try {
            for(var resource:snapshot) if(!resource.materials()) pending.put(resource.name(),executor.submit(()->close(resource,failures)));
            for(var future:pending.values()) {
                try { future.get(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                catch(ExecutionException | TimeoutException ignored) { }
            }
            // Keep the material lock until QR writers have stopped; an abnormal process exit releases it.
            boolean remoteDone=pending.values().stream().allMatch(Future::isDone) && failures.isEmpty();
            for(var resource:snapshot) if(resource.materials() && remoteDone) {
                var future=executor.submit(()->close(resource,failures)); pending.put(resource.name(),future);
                try { future.get(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch(ExecutionException | TimeoutException ignored) { }
            }
            var unfinished=new ArrayList<String>();
            pending.forEach((name,future)-> { if(!future.isDone()) { unfinished.add(name); future.cancel(true); } });
            if(!remoteDone && snapshot.stream().anyMatch(Resource::materials)) unfinished.add("materials");
            result=new Result(unfinished.isEmpty() && failures.isEmpty(),List.copyOf(unfinished),List.copyOf(failures));
            var log=org.slf4j.LoggerFactory.getLogger(ManagedShutdown.class);
            if(result.complete()) log.info("Managed shutdown complete within shared budget");
            else log.warn("Managed shutdown incomplete: unfinished={} failed={}; uncertain work remains unreplayed",result.unfinished(),result.failed());
        } finally { executor.shutdownNow(); }
    }
    private static void close(Resource resource,List<String> failures) {
        boolean complete=false;
        try { resource.close().close(); complete=true; }
        catch(Exception ignored) { }
        finally { if(!complete) failures.add(resource.name()); }
    }
    public Result result() { return result; }
}
