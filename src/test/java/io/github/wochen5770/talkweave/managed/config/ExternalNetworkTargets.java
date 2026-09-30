package io.github.wochen5770.talkweave.managed.config;

/** CI-only firewall input. Never outputs authentication, URLs or config contents. */
public final class ExternalNetworkTargets {
    public static void main(String[] args) {
        var config=ExternalIntegrationTarget.load().config();
        print(config.mysql().host(),config.mysql().port()); print(config.redis().host(),config.redis().port());
    }
    private static void print(String host,int port) {
        // Explicit numeric targets avoid opening DNS egress during the restricted test phase.
        if(!host.matches("[0-9.]+") || !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))
            throw new IllegalArgumentException("Isolated CI requires explicit IPv4 targets");
        for(var octet:host.split("\\.")) if(Integer.parseInt(octet)>255) throw new IllegalArgumentException("Invalid CI target");
        System.out.println(host+" "+port);
    }
}
