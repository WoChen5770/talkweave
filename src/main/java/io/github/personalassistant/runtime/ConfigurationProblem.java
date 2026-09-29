package io.github.personalassistant.runtime;

/** Messages contain property names and rules, never rejected values or nested exceptions. */
public final class ConfigurationProblem extends IllegalArgumentException {
    public ConfigurationProblem(String property, String rule) {
        super("Invalid configuration: " + property + " " + rule);
    }
}
