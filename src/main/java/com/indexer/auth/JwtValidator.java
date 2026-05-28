package com.indexer.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.List;
import java.util.Set;

public class JwtValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtValidator.class);

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final String groupsClaim;

    /** Production constructor — fetches keys from JWKS URL. */
    public JwtValidator(String jwksUrl, String issuer, String audience, String groupsClaim) {
        try {
            JWKSource<SecurityContext> keySource = JWKSourceBuilder
                    .create(new URL(jwksUrl))
                    .retrying(true)
                    .build();
            this.processor = buildProcessor(keySource, issuer, audience);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JWT validator with JWKS URL: " + jwksUrl, e);
        }
        this.groupsClaim = groupsClaim;
        log.info("JWT validator initialized (issuer: {}, audience: {})", issuer, audience);
    }

    /** Test constructor — uses a provided JWKSource (e.g., ImmutableJWKSet). */
    public JwtValidator(JWKSource<SecurityContext> keySource, String issuer, String audience, String groupsClaim) {
        this.processor = buildProcessor(keySource, issuer, audience);
        this.groupsClaim = groupsClaim;
    }

    public CallerIdentity validate(String jwt, String sourceIp) {
        try {
            JWTClaimsSet claims = processor.process(jwt, null);

            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new JwtValidationException("JWT missing required 'sub' claim value");
            }
            String name = claims.getStringClaim("name");
            if (name == null) name = sub;

            List<String> groups = claims.getStringListClaim(groupsClaim);
            if (groups == null) groups = List.of();

            return CallerIdentity.fromOAuth(sub, name, groups, sourceIp);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null) message = e.getClass().getSimpleName();
            throw new JwtValidationException("JWT validation failed: " + message, e);
        }
    }

    private static ConfigurableJWTProcessor<SecurityContext> buildProcessor(
            JWKSource<SecurityContext> keySource, String issuer, String audience) {
        var keySelector = new JWSVerificationKeySelector<>(
                Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256), keySource);

        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(keySelector);
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                audience,
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of(
                        JWTClaimNames.SUBJECT,
                        JWTClaimNames.EXPIRATION_TIME,
                        JWTClaimNames.ISSUED_AT
                )
        ));
        return processor;
    }
}
