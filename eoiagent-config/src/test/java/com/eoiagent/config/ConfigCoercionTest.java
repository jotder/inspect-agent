package com.eoiagent.config;

import com.eoiagent.core.ConfigException;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Typed coercion (AC5) and fail-fast on non-coercible / invalid values (AC4). */
class ConfigCoercionTest {

    private static final ConfigKey<Integer> INT_KEY =
            ConfigKey.of("eoiagent.test.int", Integer.class);
    private static final ConfigKey<Long> LONG_KEY =
            ConfigKey.of("eoiagent.test.long", Long.class);

    @Test
    void coercesStringBooleanIntegerLongAndEnum() { // AC5
        ConfigProvider cfg = new ProgrammaticConfigProvider(Map.of(
                "eoiagent.profile", "CLOUD",
                "eoiagent.audit.sink", "file",
                "eoiagent.approval.required", "FALSE",
                "eoiagent.test.int", "42",
                "eoiagent.test.long", "9000000000"));
        assertThat(cfg.get(ConfigKeys.PROFILE)).isEqualTo(DeploymentProfile.CLOUD);
        assertThat(cfg.get(ConfigKeys.AUDIT_SINK)).isEqualTo("file");
        assertThat(cfg.get(ConfigKeys.APPROVAL_REQUIRED)).isFalse();
        assertThat(cfg.get(INT_KEY)).isEqualTo(42);
        assertThat(cfg.get(LONG_KEY)).isEqualTo(9_000_000_000L);
    }

    @Test
    void enumCoercionIsCaseInsensitive() {
        ConfigProvider cfg = new ProgrammaticConfigProvider(Map.of("eoiagent.profile", "cloud"));
        assertThat(cfg.profile()).isEqualTo(DeploymentProfile.CLOUD);
    }

    @Test
    void invalidProfileThrowsAtConstruction() { // AC4
        assertThatThrownBy(() -> new ProgrammaticConfigProvider(Map.of("eoiagent.profile", "MARS")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("DeploymentProfile");
    }

    @Test
    void nonCoercibleBooleanThrowsAtConstruction() { // AC4
        assertThatThrownBy(() -> new ProgrammaticConfigProvider(Map.of(
                "eoiagent.approval.required", "yes")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("eoiagent.approval.required");
    }

    @Test
    void malformedBooleanOnGetThrows() {
        // A test-only key is not validated at construction, so coercion surfaces on get().
        ConfigKey<Boolean> adHoc = ConfigKey.of("eoiagent.unregistered.flag", Boolean.class);
        ConfigProvider cfg = new ProgrammaticConfigProvider(Map.of(
                "eoiagent.unregistered.flag", "maybe"));
        assertThatThrownBy(() -> cfg.get(adHoc)).isInstanceOf(ConfigException.class);
    }
}
