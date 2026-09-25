package dev.skillsgateway.server.ingestion;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.UpstreamCredential;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** STUB — RED phase. */
@Component
public class UpstreamCredentials {

    @Autowired
    public UpstreamCredentials(SkillsGatewayProperties properties) {
        this(properties.ingestion().upstreamCredentials());
    }

    UpstreamCredentials(List<UpstreamCredential> configured) {}

    public Optional<Selected> select(String url) {
        return Optional.empty();
    }

    public List<String> secrets() {
        return List.of();
    }

    public static final class Selected {

        public String urlPrefix() {
            throw new UnsupportedOperationException();
        }

        boolean covers(URL url) {
            throw new UnsupportedOperationException();
        }

        HttpConnectionFactory connectionFactory() {
            throw new UnsupportedOperationException();
        }
    }
}
