package io.github.wochen5770.talkweave.runtime;

/** Messages contain property names and rules, never rejected values or nested exceptions. */
public final class ConfigurationProblem extends IllegalArgumentException {
    private final String property;
    public String property() { return property; }
    public ConfigurationProblem(String property, String rule) {
        super("Invalid configuration: " + property + " " + rule);
        this.property = property;
    }
}
