package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * Browser sessions live in the gateway's database through Spring Session JDBC, so that a sign-in
 * and the session after it work whichever replica answers each request (GW_AUTH_0053). The tables
 * are in {@code V1__init.sql}.
 */
@Configuration(proxyBeanMethods = false)
class SessionStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreConfiguration.class);

    /**
     * Session attributes are JDK-serialized, and Spring Security changes its serial version on every
     * minor release, so an upgrade can leave rows the new version cannot read. Such an attribute
     * reads as absent: its holder signs in again instead of every request failing until it expires.
     */
    @Bean
    @Requirements({"GW_AUTH_0053"})
    ConversionService springSessionConversionService() {
        DeserializingConverter deserializer =
                new DeserializingConverter(getClass().getClassLoader());
        GenericConversionService conversion = new GenericConversionService();
        conversion.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversion.addConverter(byte[].class, Object.class, bytes -> {
            try {
                return deserializer.convert(bytes);
            } catch (RuntimeException e) {
                log.debug("a stored session attribute could not be read and is treated as absent", e);
                return null;
            }
        });
        return conversion;
    }
}
