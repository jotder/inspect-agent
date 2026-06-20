package com.eoiagent.host;

import com.eoiagent.core.AppId;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AgentService.open: binds a session or fails closed on a profile mismatch (T-114; spec AC1). */
class DefaultAgentServiceTest {

    private static DefaultAgentService service(DeploymentProfile configured) {
        return new DefaultAgentService(new AppId("app"), new FakeConfig(configured),
                new StubOrchestrator(), StubGuardrail.pass(), new RecordingAuditSink());
    }

    private static SessionRequest request(DeploymentProfile profile) {
        return new SessionRequest(new UserId("u"), Role.USER, profile,
                new PageContext("dashboard", Map.of(), Map.of()), Map.of());
    }

    @Test
    void openReturnsALiveSession() {
        AgentSession session = service(DeploymentProfile.OFFLINE).open(request(DeploymentProfile.OFFLINE));
        assertThat(session).isNotNull();
    }

    @Test
    void openRejectsProfileMismatch() {
        assertThatThrownBy(() -> service(DeploymentProfile.OFFLINE).open(request(DeploymentProfile.CLOUD)))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void openRejectsNullProfile() {
        assertThatThrownBy(() -> service(DeploymentProfile.OFFLINE).open(request(null)))
                .isInstanceOf(ConfigException.class);
    }
}
